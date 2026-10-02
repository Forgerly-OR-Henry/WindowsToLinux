package gold.debug.windowstolinux.app.mod.install;

import gold.debug.windowstolinux.appapi.file.FileEntry;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.mod.load.ModCodeInspection;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.config.ModManifest;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

/** Reads and stages original packages without loading author classes or changing installed state. / 读取并暂存原包，不加载作者类或改变已安装状态。 */
public final class ModPackageReader {
    /** Original archive and total outer payload limit. / 原归档及外层载荷总量上限。 */
    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    /** Outer file and directory count limit. / 外层文件及目录计数上限。 */
    public static final int MAX_ENTRIES = 10000;

    /** Total nested metadata entry limit, without expanding resources. / 不展开资源时的嵌套元数据条目总量上限。 */
    private static final int MAX_JAR_ENTRIES = 100000;

    /** Ordinary files permitted at the archive root. / 归档根允许的普通文件。 */
    private static final Set<String> ROOT_FILES = Set.of("mod.json", "README.md", "LICENSE", "CHANGELOG.md");

    /** Immutable original outer-file evidence. / 不可变原外层文件依据。
     * @param path original relative spelling / 原相对路径拼写
     * @param size actual byte length / 实际字节长度
     * @param sha256 original file digest / 原文件摘要 */
    public record FileEntry(String path, long size, String sha256) {
    }

    /** Read-only archive facts, not an installation or authorization receipt. / 只读归档事实，不是安装或授权凭据。
     * @param archive exact original path / 精确原路径
     * @param manifest strict declaration / 严格声明
     * @param archiveSha256 original archive bytes / 原归档字节摘要
     * @param contentSha256 format-one content digest / 格式一内容摘要
     * @param files verified ordinary files / 已验证普通文件
     * @param jars main, companions and libraries in explicit loading order / 主包、从包及库的明确加载顺序
     * @param classes logical class names mapped to their owning JAR / 逻辑类名到所属 JAR 的映射 */
    public record Inspection(Path archive, ModManifest manifest, String archiveSha256, String contentSha256,
                             List<FileEntry> files, List<String> jars, Map<String, String> classes) {
        /** Copies collections without granting authority from caller-constructed evidence. / 复制集合，不因调用方构造依据而授予权限。
         * @param archive original path / 原路径
         * @param manifest author metadata / 作者元数据
         * @param archiveSha256 archive digest / 归档摘要
         * @param contentSha256 content digest / 内容摘要
         * @param files ordinary files / 普通文件
         * @param jars ordered sources / 有序来源
         * @param classes class ownership / 类归属 */
        public Inspection {
            files = List.copyOf(files);
            jars = List.copyOf(jars);
            classes = Map.copyOf(classes);
        }
    }

    /** Prevents construction of the stateless reader. / 禁止构造无状态读取器。 */
    private ModPackageReader() {
    }

    /** Verifies original bytes, topology, hashes and nested class ownership without executing code. / 不执行代码地核验原字节、布局、摘要及嵌套类归属。
     * @param archive explicit original package / 明示原包
     * @return immutable archive facts / 不可变归档事实
     * @throws IOException if incomplete, redirected, invalid or changed during reading / 不完整、重定向、无效或读取时变化 */
    public static Inspection read(Path archive) throws IOException {
        Path path = ordinary(archive);
        if (!path.getFileName().toString().endsWith(".wtlmod"))
            throw new IOException("Expected .wtlmod extension");
        var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() > MAX_BYTES)
            throw new IOException("Mod package exceeds 2 GiB");
        try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            String archiveHash = hash(file);
            Inspection result;
            try (var outer = new ModZip(file, 0, file.size(), MAX_ENTRIES, true)) {
                var entries = new LinkedHashMap<String, ZipArchiveEntry>();
                var files = new ArrayList<FileEntry>();
                long total = 0;
                byte[] manifestBytes = null;
                for (var entry : outer.entries) {
                    String name = ModZip.name(entry, true);
                    if (entry.getMethod() != ZipEntry.STORED || entry.getSize() != entry.getCompressedSize())
                        throw new IOException("Outer Mod entries must use STORED");
                    if (entry.isDirectory()) {
                        if (!Set.of("main", "lib").contains(name) || entry.getSize() != 0 || entry.getCrc() != 0)
                            throw new IOException("Unexpected outer directory");
                        continue;
                    }
                    if (!ROOT_FILES.contains(name) && !name.matches("(?:main|lib)/[^/]+\\.jar"))
                        throw new IOException("Unexpected outer file layout");
                    total = Math.addExact(total, entry.getSize());
                    if (total > MAX_BYTES || (entry.getSize() == 0 && !name.equals("CHANGELOG.md")))
                        throw new IOException("Empty required file or excessive package payload");
                    if (name.equals("mod.json") && entry.getSize() > ManifestReader.MAX_BYTES)
                        throw new IOException("Mod manifest exceeds size limit");
                    var captured = name.equals("mod.json") ? new ByteArrayOutputStream() : null;
                    var digest = copy(outer, entry, captured == null ? OutputStream.nullOutputStream() : captured);
                    files.add(new FileEntry(name, entry.getSize(), digest));
                    entries.put(name, entry);
                    if (captured != null)
                        manifestBytes = captured.toByteArray();
                }
                if (!entries.keySet().containsAll(Set.of("mod.json", "README.md", "LICENSE")))
                    throw new IOException("Missing required package files");
                var manifest = ManifestReader.read(manifestBytes);
                if (!entries.containsKey(manifest.mainJar()))
                    throw new IOException("Declared main JAR is missing");
                var jars = new ArrayList<String>();
                jars.add(manifest.mainJar());
                entries.keySet().stream().filter(n -> n.startsWith("main/") && !n.equals(manifest.mainJar()))
                    .sorted(ModPackageReader::comparePaths).forEach(jars::add);
                entries.keySet().stream().filter(n -> n.startsWith("lib/")).sorted(ModPackageReader::comparePaths)
                    .forEach(jars::add);
                var classes = index(file, entries, jars, manifest);
                files.sort((a, b) -> comparePaths(a.path(), b.path()));
                result = new Inspection(path, manifest, archiveHash, content(files), files, jars, classes);
            }
            var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || !after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()) || !ordinary(path).equals(path)
                || !archiveHash.equals(hash(file)))
                throw new IOException("Original package changed during inspection");
            return result;
        }
    }

    /** Expands a newly verified package only into a new explicit staging directory. / 仅向新明示暂存目录展开重新核验过的包。
     * Partial output remains owned by the caller's operation on failure. / 失败时部分输出仍归调用方操作持有。
     * @param expected previous read-only facts, rechecked before writing / 写入前复核的先前只读事实
     * @param destination absent directory under an existing staging parent / 现有暂存父目录下尚不存在的目录
     * @return current reverified archive facts / 当前重新验证的归档事实
     * @throws IOException if evidence changed, destination exists or copying is unconfirmed / 依据变化、目标已存在或复制未确认时 */
    public static Inspection extract(Inspection expected, Path destination) throws IOException {
        return extract(expected, destination, false);
    }

    /** Expands into an empty directory whose native identity the host already persisted. / 展开到宿主已持久保存原生身份的空目录。
     * @param expected original verified package / 原已验证包
     * @param destination existing empty owned staging directory / 已有空的自有暂存目录
     * @return current reverified archive facts / 当前重新验证的归档事实
     * @throws IOException if the directory is not empty or extraction is unconfirmed / 目录非空或展开未确认时 */
    public static Inspection extractIntoEmpty(Inspection expected, Path destination) throws IOException {
        return extract(expected, destination, true);
    }

    /** Shares byte verification while allowing ownership to be persisted before the first output file. / 共用字节验证，允许在首个输出文件前持久保存归属。
     * @param expected original facts / 原事实
     * @param destination exact staging root / 精确暂存根
     * @param ownedEmpty whether the host already created and claimed an empty root / 宿主是否已创建并取得空根
     * @return verified current facts / 已验证当前事实
     * @throws IOException if ownership or bytes are unconfirmed / 归属或字节未确认时 */
    private static Inspection extract(Inspection expected, Path destination, boolean ownedEmpty) throws IOException {
        var current = read(expected.archive());
        if (!current.archiveSha256().equals(expected.archiveSha256())
            || !current.contentSha256().equals(expected.contentSha256()))
            throw new IOException("Package changed since review");
        if (!destination.isAbsolute() || !destination.equals(destination.normalize()))
            throw new IOException("Staging destination must be an absolute canonical path");
        var parent = destination.getParent();
        unredirected(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Missing staging parent");
        try (var file = FileChannel.open(current.archive(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var outer = new ModZip(file, 0, file.size(), MAX_ENTRIES, true)) {
            if (!hash(file).equals(current.archiveSha256()))
                throw new IOException("Package changed before staging");
            if (ownedEmpty) {
                unredirected(destination);
                try (var children = Files.newDirectoryStream(destination)) {
                    if (children.iterator().hasNext())
                        throw new IOException("Owned extraction directory is not empty");
                }
            } else
                Files.createDirectory(destination);
            for (var entry : current.files()) {
                Path target = destination.resolve(entry.path());
                if (!target.getParent().equals(destination)
                    && !Files.exists(target.getParent(), LinkOption.NOFOLLOW_LINKS))
                    Files.createDirectory(target.getParent());
                unredirected(target.getParent());
                var source = outer.zip.getEntry(entry.path());
                if (source == null || source.getSize() != entry.size())
                    throw new IOException("Package entry changed");
                try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                    if (!copy(outer, source, output).equals(entry.sha256()))
                        throw new IOException("Staged bytes differ from review");
                }
            }
            if (!hash(file).equals(current.archiveSha256()))
                throw new IOException("Package changed while staging");
        }
        // No publication, deletion or database registration is implied by staging. / 暂存不代表发布、删除或数据库登记。
        return current;
    }

    /** Verifies installed bytes against the registered digest without requiring the original archive. / 无需原包，按登记摘要核验安装字节。
     * @param directory exact registered program root / 精确登记程序根
     * @param manifest original registered declaration / 原登记声明
     * @param digest original complete content digest / 原完整内容摘要
     * @return verified JAR paths in loading order / 按加载顺序验证的 JAR 路径
     * @throws IOException if paths, bytes or code admission changed / 路径、字节或代码准入变化时 */
    public static List<Path> installedSources(Path directory, ModManifest manifest, String digest) throws IOException {
        if (!directory.isAbsolute() || !directory.equals(directory.normalize()))
            throw new IOException("Explicit installed directory required");
        unredirected(directory);
        var files = new ArrayList<FileEntry>();
        long[] total = {0};
        int[] count = {0};
        int[] directories = {0};
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            /** Rejects extra containers before inspecting their descendants. / 检验后代前拒绝额外容器。
             * @param path observed directory / 已观察目录
             * @param attrs original attributes / 原属性
             * @return continued traversal / 继续遍历
             * @throws IOException if redirected or unexpected / 重定向或意外目录时 */
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                unredirected(path);
                String name = directory.relativize(path).toString();
                if ((!name.isEmpty() && !Set.of("main", "lib").contains(name)) || ++directories[0] > 3)
                    throw new IOException("Unexpected installed directory");
                return FileVisitResult.CONTINUE;
            }

            /** Captures bounded ordinary bytes for comparison with the original registration. / 捕获有界普通字节以比较原登记。
             * @param path observed file / 已观察文件
             * @param before original attributes / 原属性
             * @return continued traversal / 继续遍历
             * @throws IOException if unexpected, changed or excessive / 意外、变化或超限时 */
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes before) throws IOException {
                String name = directory.relativize(path).toString().replace('\\', '/');
                if ((!ROOT_FILES.contains(name) && !name.matches("(?:main|lib)/[^/]+\\.jar")) || !before.isRegularFile()
                    || before.size() > MAX_BYTES || ++count[0] > MAX_ENTRIES
                    || (total[0] += before.size()) > MAX_BYTES)
                    throw new IOException("Unexpected installed file or excessive payload");
                ordinary(path);
                String hash;
                try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    hash = hash(file);
                }
                var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                    throw new IOException("Installed file changed during inspection");
                ordinary(path);
                files.add(new FileEntry(name, before.size(), hash));
                return FileVisitResult.CONTINUE;
            }
        });
        return verifyDirectory(manifest, digest, files, directory);
    }

    /** Verifies the entire extracted tree against retained file evidence without needing the archive. / 无需原包，按保留文件依据核验整个展开目录树。
     * This proves bytes, not ownership or permission to publish/delete the directory. / 此方法证明字节，不证明目录归属或发布、删除权限。
     * @param expected original inspected file facts / 原检验文件事实
     * @param directory explicit extracted root / 明示展开根目录
     * @throws IOException if evidence, paths or bytes differ / 依据、路径或字节不一致时 */
    public static void verifyDirectory(Inspection expected, Path directory) throws IOException {
        verifyDirectory(expected.manifest(), expected.contentSha256(), expected.files(), directory);
    }

    /** Shares full byte and code checks for staged and registered programs. / 暂存和登记程序共用完整字节及代码检查。
     * @param manifest original declaration / 原声明
     * @param digest original content hash / 原内容摘要
     * @param files observed or retained file evidence / 已观察或保留文件依据
     * @param directory exact program directory / 精确程序目录
     * @return verified ordered sources / 已验证有序来源
     * @throws IOException if the original program cannot be proven / 无法证明原程序时 */
    private static List<Path> verifyDirectory(ModManifest manifest, String digest, List<FileEntry> files,
                                              Path directory) throws IOException {
        if (!directory.isAbsolute() || !directory.equals(directory.normalize()))
            throw new IOException("Explicit extracted directory required");
        unredirected(directory);
        var remaining = new HashMap<String, FileEntry>();
        if (files.isEmpty() || files.size() > MAX_ENTRIES)
            throw new IOException("Invalid retained file count");
        long total = 0;
        for (var entry : files) {
            if (entry.path() == null || entry.size() < 0 || entry.size() > MAX_BYTES || entry.sha256() == null
                || !entry.sha256().matches("[0-9a-f]{64}")
                || (!ROOT_FILES.contains(entry.path()) && !entry.path().matches("(?:main|lib)/[^/\\\\:]+\\.jar"))
                || remaining.putIfAbsent(entry.path(), entry) != null)
                throw new IOException("Invalid retained file evidence");
            total += entry.size();
            if (total > MAX_BYTES)
                throw new IOException("Retained payload exceeds limit");
        }
        var sorted = new ArrayList<>(files);
        sorted.sort((a, b) -> comparePaths(a.path(), b.path()));
        if (!content(sorted).equals(digest)
            || !remaining.keySet().containsAll(Set.of("mod.json", "README.md", "LICENSE")))
            throw new IOException("Retained content evidence is inconsistent");
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            /** Permits only ordinary archive containers. / 仅允许普通归档容器。
             * @param path current directory / 当前目录
             * @param attrs observed attributes / 已观察属性
             * @return continued traversal / 继续遍历
             * @throws IOException if redirected or unexpected / 重定向或意外目录时 */
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                unredirected(path);
                String name = directory.relativize(path).toString();
                if (!name.isEmpty() && !Set.of("main", "lib").contains(name))
                    throw new IOException("Unexpected extracted directory");
                return FileVisitResult.CONTINUE;
            }

            /** Hashes every original file and rejects additions or changes. / 摘要每个原文件并拒绝增加或变化。
             * @param path current file / 当前文件
             * @param before observed attributes / 已观察属性
             * @return continued traversal / 继续遍历
             * @throws IOException if changed, redirected or unexpected / 变化、重定向或意外文件时 */
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes before) throws IOException {
                var entry = remaining.remove(directory.relativize(path).toString().replace('\\', '/'));
                if (entry == null || !before.isRegularFile() || before.size() != entry.size())
                    throw new IOException("Extracted files differ from retained evidence");
                ordinary(path);
                try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    if (!hash(file).equals(entry.sha256()))
                        throw new IOException("Extracted file digest changed");
                }
                var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime()))
                    throw new IOException("Extracted file changed during verification");
                ordinary(path);
                return FileVisitResult.CONTINUE;
            }
        });
        if (!remaining.isEmpty()
            || !Files.readString(directory.resolve("mod.json"), StandardCharsets.UTF_8).equals(manifest.json()))
            throw new IOException("Extracted files or manifest do not match original evidence");
        var jars = new ArrayList<String>();
        jars.add(manifest.mainJar());
        sorted.stream().map(FileEntry::path)
            .filter(name -> name.startsWith("main/") && !name.equals(manifest.mainJar())).forEach(jars::add);
        sorted.stream().map(FileEntry::path).filter(name -> name.startsWith("lib/")).forEach(jars::add);
        var sources = jars.stream().map(directory::resolve).toList();
        ModCodeInspection.read(manifest, sources).validate(false, Map.of());
        return sources;
    }

    /** Indexes nested class names without expanding resources or linking author types. / 不展开资源或链接作者类型地索引嵌套类名。
     * @param file original held handle / 原持有句柄
     * @param entries outer entries / 外层条目
     * @param jars explicit loading order / 明确加载顺序
     * @param manifest strict metadata / 严格元数据
     * @return exact logical class owners / 精确逻辑类归属
     * @throws IOException if a JAR is invalid, duplicates types or carries reserved code / JAR 无效、重复类型或携带保留代码时 */
    private static Map<String, String> index(FileChannel file, Map<String, ZipArchiveEntry> entries, List<String> jars,
                                             ModManifest manifest) throws IOException {
        var classes = new HashMap<String, String>();
        int count = 0;
        boolean mainEntry = false;
        var code = new ModCodeInspection(manifest);
        for (String path : jars) {
            var source = entries.get(path);
            try (var jar = new ModZip(file, source.getDataOffset(), source.getSize(), MAX_JAR_ENTRIES - count, false)) {
                count += jar.entries.size();
                boolean multiRelease = false;
                for (var entry : jar.entries) {
                    if (!entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF"))
                        continue;
                    if (entry.getSize() > ManifestReader.MAX_BYTES)
                        throw new IOException("JAR manifest exceeds size limit");
                    var metadata = new ByteArrayOutputStream();
                    copy(jar, entry, metadata);
                    var attributes = new java.util.jar.Manifest(new ByteArrayInputStream(metadata.toByteArray()));
                    ModClassLoader.rejectManifestClasspath(attributes);
                    multiRelease = "true".equalsIgnoreCase(attributes.getMainAttributes().getValue("Multi-Release"));
                }
                for (var entry : jar.entries) {
                    if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED)
                        throw new IOException("Unsupported JAR compression method");
                    if (entry.isDirectory())
                        continue;
                    String name = ModZip.name(entry, false);
                    int release = ModCodeInspection.release(name, multiRelease);
                    if (name.startsWith("META-INF/versions/")) {
                        String[] parts = name.split("/", 4);
                        if (parts.length != 4 || !parts[2].matches("[1-9][0-9]*"))
                            throw new IOException("Malformed multi-release resource");
                        name = parts[3];
                    }
                    if (!name.endsWith(".class") || name.equals("module-info.class"))
                        continue;
                    String type = name.substring(0, name.length() - 6).replace('/', '.');
                    if (type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.")
                        || type.startsWith("org.h2.") || type.startsWith("gold.debug.windowstolinux.app.")
                        || type.startsWith("gold.debug.windowstolinux.appapi.")
                        || ClassLoader.getPlatformClassLoader().getResource(name) != null)
                        throw new IOException("Mod contains a reserved host class");
                    String owner = classes.putIfAbsent(type, path);
                    if (owner != null && !owner.equals(path))
                        throw new IOException("Duplicate class across Mod JARs");
                    if (release >= 0) {
                        var crc = new CRC32();
                        try (var input = new java.util.zip.CheckedInputStream(jar.zip.getInputStream(entry), crc)) {
                            code.add(path, path.equals(manifest.mainJar()), name, release, input, entry.getSize());
                        }
                        if (crc.getValue() != entry.getCrc())
                            throw new IOException("Class CRC mismatch");
                    }
                    if (type.equals(manifest.entryClass()) && path.equals(manifest.mainJar())) {
                        if (entry.getName().equals(name))
                            mainEntry = true;
                    }
                }
            }
        }
        if (!mainEntry)
            throw new IOException("Main JAR must contain the base entry class");
        code.validate(false, Map.of());
        return Map.copyOf(classes);
    }

    /** Streams actual bytes while enforcing the original length and CRC. / 流式读取实际字节并强制原长度及 CRC。
     * @param archive held archive / 持有归档
     * @param entry original entry / 原条目
     * @param output destination or null sink / 目标或丢弃流
     * @return exact file digest / 精确文件摘要
     * @throws IOException if data or output is incomplete / 数据或输出不完整时 */
    private static String copy(ModZip archive, ZipArchiveEntry entry, OutputStream output) throws IOException {
        var digest = sha256();
        var crc = new CRC32();
        long count = 0;
        byte[] buffer = new byte[65536];
        try (var input = archive.zip.getInputStream(entry)) {
            for (int read; (read = input.read(buffer)) != -1; ) {
                count += read;
                if (count > entry.getSize() || count > MAX_BYTES)
                    throw new IOException("ZIP payload exceeds declaration");
                digest.update(buffer, 0, read);
                crc.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (count != entry.getSize() || crc.getValue() != entry.getCrc())
            throw new IOException("ZIP length or CRC mismatch");
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Computes the documented v1 content digest over original file spellings and bytes. / 按原文件拼写及字节计算文档规定的 v1 内容摘要。
     * @param files sorted verified files / 已排序已验证文件
     * @return content digest / 内容摘要 */
    private static String content(List<FileEntry> files) {
        var digest = sha256();
        digest.update("WTLMOD-CONTENT-1\n".getBytes(StandardCharsets.US_ASCII));
        for (var file : files) {
            byte[] name = file.path().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(name.length).array());
            digest.update(name);
            digest.update(ByteBuffer.allocate(8).putLong(file.size()).array());
            digest.update(HexFormat.of().parseHex(file.sha256()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Orders paths by unsigned UTF-8 bytes. / 按无符号 UTF-8 字节排序路径。
     * @param first first path / 第一路径
     * @param second second path / 第二路径
     * @return lexical comparison / 字典序比较 */
    private static int comparePaths(String first, String second) {
        return Arrays.compareUnsigned(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    /** Hashes the original open file without changing its shared cursor. / 不改变共用游标地摘要原打开文件。
     * @param file original handle / 原句柄
     * @return original SHA-256 digest / 原始 SHA-256 摘要
     * @throws IOException if bytes are unavailable / 字节不可用时 */
    private static String hash(FileChannel file) throws IOException {
        var digest = sha256();
        var bytes = ByteBuffer.allocate(65536);
        long position = 0;
        for (int read; (read = file.read(bytes, position)) != -1; ) {
            bytes.flip();
            digest.update(bytes);
            position += read;
            bytes.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Creates the JDK-required digest engine. / 创建 JDK 必需的摘要引擎。
     * @return fresh digest / 新摘要实例 */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Resolves only an unredirected ordinary file. / 仅解析无重定向普通文件。
     * @param file explicit file / 明示文件
     * @return canonical file / 规范文件
     * @throws IOException if absent or redirected / 缺失或重定向时 */
    private static Path ordinary(Path file) throws IOException {
        if (file == null || !file.isAbsolute() || !file.equals(file.normalize()))
            throw new IOException("Explicit canonical archive path required");
        unredirected(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Package is not a regular file");
        return file;
    }

    /** Rejects symbolic or reparse redirection in every existing path component. / 拒绝每个现有路径段中的符号或重解析重定向。
     * @param path existing canonical path / 现有规范路径
     * @throws IOException if redirected or absent / 重定向或缺失时 */
    private static void unredirected(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            var attributes = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || !part.toRealPath().equals(part))
                throw new IOException("Package or staging path is redirected");
        }
    }
}
