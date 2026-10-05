package gold.debug.windowstolinux.app.file.source;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.util.*;

import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Persists bounded canonical source facts outside the author payload without copying the raw tree. / 在作者载荷外持久保存有界规范来源事实，不复制原始树。 */
public final class SourceMaterialManifest {
    /** Canonical existing snapshot framing. / 快照既有规范帧。 */
    private static final byte[] PREFIX = "WTL-SOURCE-1\0".getBytes(StandardCharsets.US_ASCII);

    /** Raw-tree manifest budget independent of filtered snapshot bytes. / 独立于筛选快照字节的原始树清单预算。 */
    public static final long MAX_MANIFEST_BYTES = 32L * 1024 * 1024;

    /** Raw-tree item budget, preserving existing Git tree-output capacity. / 原始树项目预算，保留既有 Git 树输出容量。 */
    public static final int MAX_FILES = 1_000_000;

    /** Prevents construction of the host-only framing utility. / 防止构造仅宿主使用的帧工具。 */
    private SourceMaterialManifest() {
    }

    /** Captures, freezes and durably seals exactly named stable files in the original container. / 捕获、冻结并持久封存原容器中精确命名的稳定文件。
     * @param work original verified workspace / 原已核验工作目录
     * @param paths complete original ordinary names / 完整原普通名称
     * @param cancel original cancellation / 原取消
     * @return canonical manifest facts / 规范清单事实
     * @throws IOException if content, physical identity or persistence fails / 内容、物理身份或持久保存失败时 */
    public static Manifest capture(SourceWorkDirectory work, List<String> paths, CancelToken cancel) throws IOException {
        var names = validate(paths);
        var source = new SelectedDirectory(work.directory());
        var entries = new TreeMap<String, SelectedDirectory.Content>();
        var identities = new HashSet<String>();
        long size = 0;
        for (String path : names) {
            SelectedDirectory.cancelled(cancel);
            var entry = source.inspectStrict(path);
            if (!identities.add(entry.identity()))
                throw new IOException("Material names refer to a duplicate physical file");
            var content = source.transfer(entry, OutputStream.nullOutputStream(), Long.MAX_VALUE, cancel);
            size = Math.addExact(size, content.size());
            freeze(entry.path());
            entries.put(path, content);
        }
        String digest = write(work.metadata("manifest.bin"), entries);
        var result = new Manifest(digest, size, entries);
        verify(work, result, cancel);
        return result;
    }

    /** Rejects invalid or oversized raw manifest requests before consuming a workspace. / 消费工作目录前拒绝无效或过大原始清单请求。
     * @param paths complete original ordinary names / 完整原普通名称
     * @return exact sorted unique names / 精确排序唯一名称
     * @throws IOException if count, duplication or framing exceeds the original budget / 数量、重复或帧超出原预算时 */
    public static List<String> validate(List<String> paths) throws IOException {
        if (paths.size() > MAX_FILES)
            throw new IOException("Material file count exceeds its budget");
        var names = new TreeSet<String>();
        long framing = PREFIX.length;
        for (String path : paths) {
            if (!names.add(SelectedDirectory.relative(path)))
                throw new IOException("Duplicate material path");
            framing = Math.addExact(framing, 44L + path.getBytes(StandardCharsets.UTF_8).length);
            if (framing > MAX_MANIFEST_BYTES)
                throw new IOException("Material manifest exceeds its byte budget");
        }
        return List.copyOf(names);
    }

    /** Reads only bounded canonical frames and checks complete header facts. / 仅读取有界规范帧并检查完整头部事实。
     * @param work original physical container / 原物理容器
     * @param expectedDigest trusted manifest digest / 可信清单摘要
     * @param expectedBytes trusted file byte count / 可信文件字节数
     * @param expectedFiles trusted item count / 可信项目数
     * @return immutable validated manifest / 不可变已校验清单
     * @throws IOException if persisted framing or trusted counts differ / 持久帧或可信计数不同时 */
    public static Manifest read(SourceWorkDirectory work, String expectedDigest, long expectedBytes, int expectedFiles)
        throws IOException {
        Path file = work.metadata("manifest.bin");
        var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
            || attributes.size() < PREFIX.length || attributes.size() > MAX_MANIFEST_BYTES
            || expectedFiles < 0 || expectedFiles > MAX_FILES || expectedBytes < 0)
            throw new IOException("Material manifest is unavailable");
        var digest = SelectedDirectory.digest();
        var entries = new TreeMap<String, SelectedDirectory.Content>();
        long remaining = attributes.size() - PREFIX.length;
        long size = 0;
        String prior = null;
        try (var input = new DataInputStream(new DigestInputStream(
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest))) {
            if (!Arrays.equals(input.readNBytes(PREFIX.length), PREFIX))
                throw new IOException("Unknown material manifest framing");
            while (remaining != 0) {
                if (remaining < 44 || entries.size() == expectedFiles)
                    throw new IOException("Material manifest count or framing differs");
                int length = input.readInt();
                if (length < 1 || length > remaining - 44)
                    throw new IOException("Invalid material path frame");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length)
                    throw new EOFException("Material path frame is incomplete");
                String path = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                SelectedDirectory.relative(path);
                long count = input.readLong();
                byte[] hash = input.readNBytes(32);
                if (count < 0 || hash.length != 32 || prior != null && prior.compareTo(path) >= 0)
                    throw new IOException("Noncanonical material frame");
                entries.put(path, new SelectedDirectory.Content(count, HexFormat.of().formatHex(hash)));
                size = Math.addExact(size, count);
                remaining -= 44L + length;
                prior = path;
            }
            if (input.read() != -1)
                throw new IOException("Material manifest grew while reading");
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("Invalid material manifest", failure);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(expectedDigest) || size != expectedBytes || entries.size() != expectedFiles)
            throw new IOException("Material manifest differs from original facts");
        work.requireOwned();
        return new Manifest(actual, size, entries);
    }

    /** Rechecks every whitelisted file without following links or applying a raw-tree byte ceiling. / 不跟随链接或套用原始树字节上限，复核每个白名单文件。
     * @param work original verified container / 原已核验容器
     * @param original immutable original manifest / 不可变原清单
     * @param cancel original cancellation / 原取消
     * @throws IOException if any original content differs / 任一原内容不同时 */
    public static void verify(SourceWorkDirectory work, Manifest original, CancelToken cancel) throws IOException {
        Manifest persisted = read(work, original.sha256(), original.byteCount(), original.entries().size());
        if (!persisted.entries().equals(original.entries()))
            throw new IOException("Material manifest changed");
        var source = new SelectedDirectory(work.directory());
        var identities = new HashSet<String>();
        for (var item : original.entries().entrySet()) {
            SelectedDirectory.cancelled(cancel);
            var entry = source.inspectStrict(item.getKey());
            if (!identities.add(entry.identity()) || !item.getValue().equals(
                source.transfer(entry, OutputStream.nullOutputStream(), Long.MAX_VALUE, cancel)))
                throw new IOException("Original material content changed");
        }
        work.requireOwned();
    }

    /** Writes the existing canonical framing and forces the exact file to stable storage. / 写入既有规范帧并强制精确文件持久化。
     * @param path host metadata position / 宿主元数据位置
     * @param entries sorted immutable facts / 排序不可变事实
     * @return canonical SHA-256 / 规范 SHA-256
     * @throws IOException if metadata already exists or write fails / 元数据已存在或写入失败时 */
    private static String write(Path path, SortedMap<String, SelectedDirectory.Content> entries) throws IOException {
        var digest = SelectedDirectory.digest();
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             var output = new DataOutputStream(new DigestOutputStream(java.nio.channels.Channels.newOutputStream(channel), digest))) {
            output.write(PREFIX);
            for (var item : entries.entrySet()) {
                byte[] name = item.getKey().getBytes(StandardCharsets.UTF_8);
                output.writeInt(name.length);
                output.write(name);
                output.writeLong(item.getValue().size());
                output.write(HexFormat.of().parseHex(item.getValue().sha256()));
            }
            output.flush();
            channel.force(true);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Hashes a filtered subset using the unchanged canonical snapshot framing. / 使用不变的规范快照帧散列筛选子集。
     * @param entries sorted exact subset / 排序精确子集
     * @return canonical subset digest / 规范子集摘要
     * @throws IOException if canonical framing fails / 规范帧编码失败时 */
    public static String digest(SortedMap<String, SelectedDirectory.Content> entries) throws IOException {
        var digest = SelectedDirectory.digest();
        try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            output.write(PREFIX);
            for (var item : entries.entrySet()) {
                byte[] name = item.getKey().getBytes(StandardCharsets.UTF_8);
                output.writeInt(name.length);
                output.write(name);
                output.writeLong(item.getValue().size());
                output.write(HexFormat.of().parseHex(item.getValue().sha256()));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Freezes ordinary material files using the actual supported native attribute view. / 使用实际支持的原生属性视图冻结普通素材文件。
     * @param path exact ordinary file / 精确普通文件
     * @throws IOException if native readonly attributes are unavailable / 原生只读属性不可用时 */
    private static void freeze(Path path) throws IOException {
        var dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            dos.setReadOnly(true);
            return;
        }
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null)
            throw new IOException("Material readonly attributes are unavailable");
        var permissions = new HashSet<>(posix.readAttributes().permissions());
        permissions.removeAll(Set.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE));
        posix.setPermissions(permissions);
    }

    /** Immutable bounded file facts, independent of directory authorization. / 独立于目录授权的不可变有界文件事实。
     * @param sha256 canonical framing digest / 规范帧摘要
     * @param byteCount total raw file bytes / 原文件总字节数
     * @param entries exact sorted names and content identities / 精确有序名称及内容身份 */
    public record Manifest(String sha256, long byteCount, SortedMap<String, SelectedDirectory.Content> entries) {
        /** Copies immutable facts without acquiring filesystem authority. / 复制不可变事实，不取得文件系统授权。
         * @param sha256 canonical digest / 规范摘要
         * @param byteCount exact bytes / 精确字节数
         * @param entries original content facts / 原内容事实 */
        public Manifest {
            entries = Collections.unmodifiableSortedMap(new TreeMap<>(entries));
        }
    }
}
