package gold.debug.windowstolinux.app.source.boundary;

import java.io.*;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

import gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.source.SourceDirectoryEntry;
import gold.debug.windowstolinux.appapi.source.SourceFile;
import gold.debug.windowstolinux.appapi.task.CancellationToken;

/** Reads only explicitly named regular files within one confirmed directory. / 仅读取一个已确认目录中明确命名的普通文件。 */
public final class SelectedDirectory {
    /** Canonical selected boundary. / 规范的选定边界。 */
    private final Path root;

    /** Selected directory identity retained across accesses. / 跨访问保留的所选目录身份。 */
    private final String identity;

    /** Captures the actual directory selected by the user, resolving its initial aliases. / 捕获用户选择的实际目录，解析其初始别名。
     * @param selected user-selected directory / 用户选定目录
     * @throws IOException if the directory identity cannot be established / 无法确立目录身份时 */
    public SelectedDirectory(Path selected) throws IOException {
        root = selected.toAbsolutePath().normalize().toRealPath();
        var attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
            throw new IOException("Source directory identity is unavailable");
        identity = WindowsFileIdentity.read(root);
    }

    /** Returns the resolved user boundary to trusted host composition. / 向可信宿主装配返回解析后的用户边界。
     * @return canonical directory / 规范目录 */
    public Path root() {
        return root;
    }

    /** Validates portable slash-separated relative names without rewriting them. / 校验可移植的斜线分隔相对名称，不改写它们。
     * @param value explicit author-selected name / 作者明确选择的名称
     * @return unchanged validated name / 原样的已校验名称 */
    public static String relative(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.contains("\\")
                || value.chars().anyMatch(c -> c < 32 || c == 127 || ":*?\"<>|".indexOf(c) >= 0))
            throw new IllegalArgumentException("Invalid relative source path");
        try {
            StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid source path Unicode");
        }
        for (String segment : value.split("/", -1)) {
            String stem = segment.split("\\.", 2)[0];
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.endsWith(".")
                    || segment.endsWith(" ") || stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])"))
                throw new IllegalArgumentException("Invalid relative source segment");
        }
        return value;
    }

    /** Captures the complete resolved file boundary and identity. / 捕获完整解析文件边界及身份。
     * @param name exact relative file / 精确相对文件
     * @return observed regular file / 已观察普通文件
     * @throws IOException if a link escapes the selected boundary or the file is unavailable / 链接越过所选边界或文件不可用时 */
    public Entry inspect(String name) throws IOException {
        relative(name);
        checkRoot();
        Path resolved = root.resolve(name).toRealPath();
        if (!resolved.startsWith(root) || resolved.equals(root))
            throw new IOException("Source path escapes its selection");
        var attributes = Files.readAttributes(resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
            throw new IOException("Source path is not a stable regular file");
        return new Entry(name, resolved, attributes, WindowsFileIdentity.read(resolved));
    }

    /** Lists immediate observations without traversing links or returning a truncated success. / 列举直接观测，不遍历链接或把截断结果当成功。
     * @param relativeDirectory exact directory or empty selected root / 精确目录或空的所选根
     * @param maximum maximum immediate entries / 最大直接子项数
     * @param cancellation original task cancellation / 原任务取消
     * @return immutable complete sorted observations / 不可变完整有序观测
     * @throws IOException if identity, containment, capacity or enumeration fails / 身份、包含边界、容量或枚举失败时 */
    public List<SourceDirectoryEntry> list(String relativeDirectory, int maximum, CancellationToken cancellation)
            throws IOException {
        if (relativeDirectory == null || maximum < 1 || maximum > 100_000)
            throw new IllegalArgumentException("Invalid source directory listing request");
        checkRoot();
        cancelled(cancellation);
        Path directory = relativeDirectory.isEmpty() ? root : root.resolve(relative(relativeDirectory)).toRealPath();
        if (!directory.startsWith(root))
            throw new IOException("Source directory escapes its selection");
        var before = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isDirectory() || before.isOther() || before.isSymbolicLink())
            throw new IOException("Source directory is not ordinary");
        String directoryIdentity = WindowsFileIdentity.read(directory);
        var entries = new ArrayList<SourceDirectoryEntry>();
        try (var children = Files.newDirectoryStream(directory)) {
            for (var child : children) {
                cancelled(cancellation);
                if (entries.size() == maximum)
                    throw new IOException("Source directory exceeds its entry budget");
                String name = relative(relativeDirectory.isEmpty()
                        ? child.getFileName().toString()
                        : relativeDirectory + "/" + child.getFileName());
                var attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                var type = attributes.isSymbolicLink() || attributes.isOther()
                        ? SourceDirectoryEntry.EntryType.OTHER
                        : attributes.isDirectory()
                                ? SourceDirectoryEntry.EntryType.DIRECTORY
                                : attributes.isRegularFile()
                                        ? SourceDirectoryEntry.EntryType.FILE
                                        : SourceDirectoryEntry.EntryType.OTHER;
                entries.add(new SourceDirectoryEntry(name, type,
                        type == SourceDirectoryEntry.EntryType.FILE ? attributes.size() : 0));
            }
        }
        checkRoot();
        cancelled(cancellation);
        var after = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path resolved = relativeDirectory.isEmpty() ? root : root.resolve(relativeDirectory).toRealPath();
        if (!directory.equals(resolved) || !directoryIdentity.equals(WindowsFileIdentity.read(directory))
                || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Source directory changed while listing");
        return entries.stream().sorted(Comparator.comparing(SourceDirectoryEntry::relativePath)).toList();
    }

    /** Verifies the exact originally selected directory before and after access. / 在访问前后核验精确原选定目录。
     * @throws IOException if the selected root was replaced / 所选根已被替换时 */
    private void checkRoot() throws IOException {
        var now = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!now.isDirectory() || now.isOther() || now.isSymbolicLink()
                || !identity.equals(WindowsFileIdentity.read(root)) || !root.toRealPath().equals(root))
            throw new IOException("Selected source directory changed");
    }

    /** Reads a copied payload after confirming that the source stayed unchanged. / 确认源未变化后读取独立载荷。
     * @param name exact selected file / 精确选定文件
     * @param maximum requested byte budget / 请求的字节预算
     * @param cancellation original task cancellation / 原任务取消
     * @return copied bytes and actual digest / 复制字节及实际摘要
     * @throws IOException if the source changes or exceeds its budget / 源变化或超预算时 */
    public SourceFile read(String name, long maximum, CancellationToken cancellation) throws IOException {
        if (maximum < 0 || maximum > 8L * 1024 * 1024)
            throw new IllegalArgumentException("Source read limit exceeds eight MiB");
        var entry = inspect(name);
        try (var output = new ByteArrayOutputStream((int) Math.min(entry.attributes().size(), maximum))) {
            var copied = transfer(entry, output, maximum, cancellation);
            var verified = transfer(entry, OutputStream.nullOutputStream(), maximum, cancellation);
            if (!copied.equals(verified))
                throw new IOException("Source content changed while reading");
            byte[] bytes = output.toByteArray();
            try {
                return new SourceFile(name, bytes, copied.sha256());
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    /** Copies and hashes one already observed file, checking identity on both sides. / 复制并散列一个已观察文件，在前后检查身份。
     * @param entry observed source / 已观察源
     * @param output destination or digest-only sink / 目的地或仅摘要接收器
     * @param maximum requested remaining bytes / 请求的剩余字节
     * @param cancellation original cancellation / 原取消信号
     * @return measured length and digest / 实测长度及摘要
     * @throws IOException if boundaries, content or budgets change / 边界、内容或预算变化时 */
    public Content transfer(Entry entry, OutputStream output, long maximum, CancellationToken cancellation)
            throws IOException {
        check(entry);
        if (maximum < 0 || entry.attributes().size() > maximum)
            throw new IOException("Source exceeds its requested budget");
        var digest = digest();
        long size = 0;
        byte[] buffer = new byte[65536];
        try (var input = Files.newInputStream(entry.path(), LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                cancelled(cancellation);
                if (count > maximum - size)
                    throw new IOException("Source grew beyond its requested budget");
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                size += count;
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
        cancelled(cancellation);
        check(entry);
        if (size != entry.attributes().size())
            throw new IOException("Source size changed while copying");
        return new Content(size, HexFormat.of().formatHex(digest.digest()));
    }

    /** Verifies that the selected spelling still resolves to the same file observation. / 核验所选拼写仍解析到同一文件观测。
     * @param before original observation / 原观测
     * @throws IOException if identity, size or timestamp changed / 身份、大小或时间戳变化时 */
    public void check(Entry before) throws IOException {
        var after = inspect(before.relativePath());
        if (!before.path().equals(after.path()) || !before.identity().equals(after.identity())
                || before.attributes().size() != after.attributes().size()
                || !before.attributes().lastModifiedTime().equals(after.attributes().lastModifiedTime())
                || !before.attributes().creationTime().equals(after.attributes().creationTime()))
            throw new IOException("Selected source file changed");
    }

    /** Stops before handing off incomplete or cancelled bytes. / 在交付不完整或已取消字节前停止。
     * @param cancellation original signal / 原信号 */
    public static void cancelled(CancellationToken cancellation) {
        if (cancellation.requested() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Source operation cancelled");
    }

    /** Creates the required JDK digest. / 创建所需 JDK 摘要。
     * @return SHA-256 instance / SHA-256 实例 */
    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** Immutable source observation. / 不可变来源观测。
     * @param relativePath exact selected name / 精确选定名称
     * @param path resolved contained file / 已解析受限文件
     * @param attributes original file attributes / 原文件属性
     * @param identity native file identity / 原生文件身份 */
    public record Entry(String relativePath, Path path, BasicFileAttributes attributes, String identity) {
    }

    /** Actual copied content identity. / 实际复制内容身份。
     * @param size measured bytes / 实测字节
     * @param sha256 content digest / 内容摘要 */
    public record Content(long size, String sha256) {
    }
}
