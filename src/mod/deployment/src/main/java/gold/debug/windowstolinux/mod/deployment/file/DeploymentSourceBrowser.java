package gold.debug.windowstolinux.mod.deployment.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only paginated access to an owned frozen source, shared by assistance and agent. / 供辅助与自主部署共用的所属冻结源码只读分页访问。 */
public final class DeploymentSourceBrowser {
    /** Maximum single text file. / 单个文本文件上限。 */
    private static final int MAX_FILE = 262144;

    /** Canonical immutable snapshot root. / 规范化不可变快照根。 */
    private final Path root;

    /** Original regular-file digests, never writable paths supplied by a model. / 原普通文件摘要，不使用模型提供的可写路径。 */
    private final Map<String, String> files;

    /** Platform snapshot content identity. / 平台快照内容身份。 */
    private final String revision;

    /** Indexes a real platform-owned snapshot and rejects links. / 索引真实平台所属快照并拒绝链接。
     * @param root snapshot directory / 快照目录
     * @param revision platform-reported content digest / 平台报告的内容摘要
     * @throws Exception if the snapshot is unsafe or exceeds limits / 快照不安全或超限时 */
    public DeploymentSourceBrowser(Path root, String revision) throws Exception {
        this.root = Objects.requireNonNull(root).toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(this.root))
            throw new SecurityException("Invalid frozen source root");
        if (!Objects.requireNonNull(revision).matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid frozen source revision");
        this.revision = revision;
        this.files = inventory();
    }

    /** Returns original platform content identity. / 返回原平台内容身份。
     * @return frozen source digest / 冻结来源摘要 */
    public String revision() {
        return revision;
    }

    /** Rechecks exact file inventory and content before model use. / 模型使用前复核精确文件清单与内容。
     * @throws Exception if a file changed or disappeared / 文件更改或消失时 */
    public void verify() throws Exception {
        if (!files.equals(inventory()))
            throw new SecurityException("Frozen source changed");
    }

    /** Rechecks source boundaries without hashing up to two GiB on every model turn. / 每轮模型调用只复核来源边界，不重复散列最多二GiB内容。
     * @throws Exception if files or link boundaries changed / 文件或链接边界变化时 */
    public void verifyBoundaries() throws Exception {
        var observed = new HashSet<String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root))
                    continue;
                if (Files.isSymbolicLink(path))
                    throw new SecurityException("Source link rejected");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new SecurityException("Nonregular source rejected");
                String name = root.relativize(path).toString().replace('\\', '/');
                if (!files.containsKey(name) || !observed.add(name))
                    throw new SecurityException("Frozen source inventory changed");
            }
        }
        if (!observed.equals(files.keySet()))
            throw new SecurityException("Frozen source inventory changed");
    }

    /** Lists immediate members using a bounded stable cursor. / 使用有界稳定游标列举直属成员。
     * @param directory relative directory, empty for root / 相对目录，空串为根
     * @param offset zero-based cursor / 从零开始的游标
     * @param limit bounded page size / 有界页大小
     * @return sorted relative members / 已排序相对成员
     * @throws Exception on unsafe or changed source / 来源不安全或改变时 */
    public List<String> list(String directory, int offset, int limit) throws Exception {
        page(offset, limit);
        verifyBoundaries();
        String prefix = directory.isEmpty() ? "" : relative(directory) + "/";
        var children = new TreeSet<String>();
        for (String name : files.keySet())
            if (name.startsWith(prefix)) {
                String tail = name.substring(prefix.length());
                int slash = tail.indexOf('/');
                children.add(prefix + (slash < 0 ? tail : tail.substring(0, slash + 1)));
            }
        return children.stream().skip(offset).limit(limit).toList();
    }

    /** Reads a bounded page of numbered and redacted UTF-8 lines. / 读取有界的编号脱敏 UTF-8 行。
     * @param path admitted relative file / 已准入相对文件
     * @param offset zero-based line cursor / 从零开始的行游标
     * @param limit line count / 行数
     * @return copied text lines / 复制的文本行
     * @throws Exception on unsafe or changed source / 来源不安全或改变时 */
    public List<String> read(String path, int offset, int limit) throws Exception {
        page(offset, limit);
        verifyBoundaries();
        var lines = text(relative(path));
        var result = new ArrayList<String>();
        int characters = 0;
        for (int index = offset; index < lines.size() && result.size() < limit; index++) {
            String line = (index + 1) + ": " + safe(lines.get(index));
            if (characters + line.length() > 12000)
                break;
            result.add(line);
            characters += line.length();
        }
        return List.copyOf(result);
    }

    /** Searches a bounded page of admitted files for literal text. / 在已准入文件的有界页中查找字面文本。
     * @param query literal query / 字面查询
     * @param offset file cursor / 文件游标
     * @param limit file count / 文件数
     * @return copied location-bearing matches / 含位置的复制匹配
     * @throws Exception on unsafe or changed source / 来源不安全或改变时 */
    public List<String> search(String query, int offset, int limit) throws Exception {
        page(offset, limit);
        verifyBoundaries();
        if (query == null || query.isBlank() || query.length() > 256)
            throw new IllegalArgumentException("Invalid source query");
        var result = new ArrayList<String>();
        int characters = 0;
        for (String path : files.keySet().stream().skip(offset).limit(limit).toList()) {
            List<String> lines;
            try {
                lines = text(path);
            } catch (java.nio.charset.CharacterCodingException binary) {
                continue;
            } catch (IOException large) {
                continue;
            }
            for (int index = 0; index < lines.size(); index++) {
                String line = safe(lines.get(index));
                if (!line.contains(query))
                    continue;
                String match = path + ":" + (index + 1) + ": " + line;
                if (characters + match.length() > 12000 || result.size() == 100)
                    return List.copyOf(result);
                result.add(match);
                characters += match.length();
            }
        }
        return List.copyOf(result);
    }

    /** Collects immutable file identities with no symbolic links. / 收集不可变文件身份并拒绝符号链接。
     * @return sorted path-to-content map / 已排序路径至内容映射
     * @throws Exception on unsafe or oversized inventory / 清单不安全或超限时 */
    private Map<String, String> inventory() throws Exception {
        var indexed = new TreeMap<String, String>();
        long total = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root))
                    continue;
                if (Files.isSymbolicLink(path))
                    throw new SecurityException("Source link rejected");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new SecurityException("Nonregular source rejected");
                String name = root.relativize(path).toString().replace('\\', '/');
                relative(name);
                long size = Files.size(path);
                total += size;
                if (indexed.size() >= 20000 || total > 2L * 1024 * 1024 * 1024)
                    throw new IOException("Source browsing budget exhausted");
                indexed.put(name, digest(path));
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    /** Computes one regular file's SHA-256 without following a link. / 不跟随链接计算一个普通文件的 SHA-256。
     * @param path admitted file / 已准入文件
     * @return lowercase digest / 小写摘要
     * @throws Exception on unavailable input / 输入不可用时 */
    private static String digest(Path path) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0; )
                hash.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    /** Reads strict UTF-8 from one indexed member. / 从一个已索引成员读取严格 UTF-8。
     * @param name relative member / 相对成员
     * @return copied lines / 复制的行
     * @throws IOException on changed or invalid content / 内容改变或无效时 */
    private List<String> text(String name) throws IOException {
        if (!files.containsKey(name))
            throw new IOException("Source member was not indexed");
        Path path = root.resolve(name);
        if (!path.normalize().startsWith(root) || Files.isSymbolicLink(path) || Files.size(path) > MAX_FILE)
            throw new IOException("Source member is unsafe or too large");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE + 1);
        }
        if (bytes.length > MAX_FILE)
            throw new IOException("Source file exceeds text budget");
        try {
            String observed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!observed.equals(files.get(name)))
                throw new SecurityException("Frozen source changed while reading");
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
        String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (value.indexOf('\0') >= 0)
            throw new java.nio.charset.CharacterCodingException();
        var lines = new ArrayList<String>();
        boolean privateBlock = false;
        for (String line : value.lines().toList()) {
            if (line.matches(".*-----BEGIN .*PRIVATE KEY-----.*"))
                privateBlock = true;
            lines.add(privateBlock ? "[credential content excluded]" : line);
            if (line.matches(".*-----END .*PRIVATE KEY-----.*"))
                privateBlock = false;
        }
        return List.copyOf(lines);
    }

    /** Rejects traversal and platform aliases. / 拒绝越界与平台别名。
     * @param value model-provided member / 模型提供的成员
     * @return checked unchanged member / 经检查的原成员 */
    private static String relative(String value) {
        if (value == null || value.isEmpty() || value.length() > 500 || value.startsWith("/") || value.contains("\\")
            || value.contains(":") || value.chars().anyMatch(Character::isISOControl))
            throw new SecurityException("Invalid source path");
        for (String part : value.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new SecurityException("Source traversal rejected");
        return value;
    }

    /** Enforces current bounded pagination. / 执行当前有界分页限制。
     * @param offset first item / 首项
     * @param limit page size / 页大小 */
    private static void page(int offset, int limit) {
        if (offset < 0 || offset > 1000000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid source page");
    }

    /** Redacts likely credentials before a model receives text. / 模型接收文本前脱敏疑似凭据。
     * @param line one source line / 一行源码
     * @return bounded redacted line / 有界脱敏行 */
    private static String safe(String line) {
        if (line.matches("(?i).*(password|passwd|secret|token|authorization|api[_-]?key|private[_-]?key).*[:=].*")
            || line.matches("(?i).*\\bbearer\\s+\\S+.*") || line.contains("PRIVATE KEY")
            || line.matches(".*https?://[^ /]+:[^ /]+@.*"))
            return "[credential content excluded]";
        return line.length() <= 4000 ? line : line.substring(0, 4000) + " [line truncated]";
    }
}
