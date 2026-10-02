package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

import gold.debug.windowstolinux.app.file.SelectedDirectory;
import gold.debug.windowstolinux.appapi.file.SnapshotRequest;
import gold.debug.windowstolinux.appapi.task.CancelToken;

/** Owns one task-local snapshot and its verifiable cleanup marker. / 持有一个任务本地快照及可核验清理标记。 */
public final class FrozenSourceWork implements AutoCloseable {
    /** Maximum declared snapshot capacity. / 最大声明快照容量。 */
    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    /** Maximum explicit file count. / 最大明确文件数量。 */
    public static final int MAX_FILES = 100000;

    /** Shared physical ownership and cleanup. / 共用物理归属及清理。 */
    private final SourceWorkDirectory work;

    /** Original payload coordinates, exposed only after caller verification. / 仅供调用方核验后暴露的原载荷坐标。 */
    private final Path payload;

    /** Complete frozen file manifest after successful capture. / 成功捕获后的完整冻结文件清单。 */
    private SortedMap<String, SelectedDirectory.Content> manifest;

    /** Digest of the frozen manifest. / 冻结清单摘要。 */
    private String sha256;

    /** Binds deterministic cleanup coordinates without creating any files. / 绑定确定性清理坐标，不创建任何文件。
     * @param workRoot platform-selected source work root / 平台选定来源工作根
     * @param task original task identity / 原任务身份
     * @param id unique snapshot identity / 唯一快照身份
     * @param owner exact Mod identity / 精确 Mod 身份 */
    public FrozenSourceWork(Path workRoot, UUID task, UUID id, String owner) {
        work = new SourceWorkDirectory(workRoot, task, id, owner);
        payload = workRoot.toAbsolutePath().normalize().resolve(task.toString()).resolve(id.toString())
            .resolve("files");
    }

    /** Rejects oversized or ambiguous requests before creating task files. / 创建任务文件前拒绝超限或歧义请求。
     * @param request explicit files and byte limit / 明确文件及字节上限
     * @return immutable sorted exact names / 不可变排序精确名称 */
    public static List<String> validate(SnapshotRequest request) {
        if (request.maxTotalBytes() > MAX_BYTES || request.relativePaths().size() > MAX_FILES)
            throw new IllegalArgumentException("Source snapshot budget exceeds platform limits");
        var names = new TreeSet<String>();
        for (String name : request.relativePaths())
            if (!names.add(SelectedDirectory.relative(name)))
                throw new IllegalArgumentException("Duplicate source snapshot path");
        return List.copyOf(names);
    }

    /** Creates a new owned container. / 创建新的所属容器。
     * @throws IOException if ownership cannot be recorded / 无法记录归属时 */
    public void prepare() throws IOException {
        work.prepare();
    }

    /** Recovers the original physical container for cleanup only. / 仅为清理恢复原物理容器。
     * @throws IOException if original ownership cannot be proved / 无法证明原归属时 */
    public void recover() throws IOException {
        work.recover();
    }

    /** Copies exactly the chosen files, verifies the source again, then freezes the payload. / 精确复制选中文件，再次核对来源，随后冻结载荷。
     * @param source original selected boundary / 原选定边界
     * @param request author-selected files and budget / 作者选定文件及预算
     * @param cancellation original task cancellation / 原任务取消
     * @throws IOException if source, output or identity validation fails / 来源、输出或身份校验失败时 */
    public void capture(SelectedDirectory source, SnapshotRequest request, CancelToken cancellation)
        throws IOException {
        List<String> names = validate(request);
        work.requireOwned();
        if (manifest != null)
            throw new IllegalStateException("Snapshot is already frozen");
        var observations = new TreeMap<String, SelectedDirectory.Entry>();
        long declared = 0;
        for (String name : names) {
            SelectedDirectory.cancelled(cancellation);
            var entry = source.inspect(name);
            if (entry.attributes().size() > request.maxTotalBytes() - declared)
                throw new IOException("Source snapshot exceeds its budget");
            declared += entry.attributes().size();
            observations.put(name, entry);
        }
        var copied = new TreeMap<String, SelectedDirectory.Content>();
        long written = 0;
        for (var entry : observations.values()) {
            SelectedDirectory.cancelled(cancellation);
            work.requireOwned();
            Path target = work.directory().resolve(entry.relativePath());
            SourceWorkDirectory.directories(target.getParent());
            SelectedDirectory.Content content;
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                content = source.transfer(entry, output, request.maxTotalBytes() - written, cancellation);
            }
            written += content.size();
            copied.put(entry.relativePath(), content);
        }
        for (var entry : observations.values()) {
            var actual = source.transfer(entry, OutputStream.nullOutputStream(), request.maxTotalBytes(), cancellation);
            if (!actual.equals(copied.get(entry.relativePath())))
                throw new IOException("Source changed during snapshot capture");
        }
        for (String name : names)
            freeze(work.directory().resolve(name));
        manifest = Collections.unmodifiableSortedMap(copied);
        sha256 = manifestDigest(copied);
        verify(cancellation);
    }

    /** Revalidates copied payload before exposing its location or digest. / 暴露位置或摘要前重新核验复制载荷。
     * @param cancellation current task cancellation / 当前任务取消
     * @throws IOException if the frozen payload or its ownership changed / 冻结载荷或归属变化时 */
    public void verify(CancelToken cancellation) throws IOException {
        work.requireOwned();
        if (manifest == null)
            throw new IOException("Snapshot capture did not complete");
        var payload = new SelectedDirectory(work.directory());
        var actual = new TreeMap<String, SelectedDirectory.Content>();
        try (var stream = Files.walk(payload.root())) {
            var files = stream.iterator();
            while (files.hasNext()) {
                SelectedDirectory.cancelled(cancellation);
                var file = files.next();
                var attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther())
                    throw new IOException("Snapshot payload contains a link");
                if (attrs.isDirectory())
                    continue;
                String name = payload.root().relativize(file).toString().replace('\\', '/');
                if (!manifest.containsKey(name))
                    throw new IOException("Unexpected snapshot payload entry");
                var entry = payload.inspect(name);
                if (!entry.path().equals(file))
                    throw new IOException("Snapshot payload contains an alias");
                actual.put(name, payload.transfer(entry, OutputStream.nullOutputStream(), MAX_BYTES, cancellation));
            }
        }
        if (!actual.equals(manifest))
            throw new IOException("Frozen snapshot content changed");
    }

    /** Returns the already verified payload directory. / 返回已核验的载荷目录。
     * @return exact payload location / 精确载荷位置 */
    public Path directory() {
        return payload;
    }

    /** Returns the frozen aggregate digest. / 返回冻结的聚合摘要。
     * @return deterministic manifest digest / 确定性清单摘要 */
    public String sha256() {
        return Objects.requireNonNull(sha256, "Snapshot is not complete");
    }

    /** Hashes sorted paths, measured lengths and binary file digests with unambiguous framing. / 使用无歧义帧散列排序路径、实测长度及二进制文件摘要。
     * @param entries sorted frozen files / 排序冻结文件
     * @return aggregate SHA-256 / 聚合 SHA-256
     * @throws IOException if digest framing fails / 摘要帧编码失败时 */
    private static String manifestDigest(SortedMap<String, SelectedDirectory.Content> entries) throws IOException {
        var digest = SelectedDirectory.digest();
        try (var output = new DataOutputStream(
            new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            output.write("WTL-SOURCE-1\0".getBytes(StandardCharsets.US_ASCII));
            for (var entry : entries.entrySet()) {
                byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
                output.writeInt(path.length);
                output.write(path);
                output.writeLong(entry.getValue().size());
                output.write(HexFormat.of().parseHex(entry.getValue().sha256()));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Removes only the originally owned physical container. / 仅移除原来所属的物理容器。
     * @throws IOException if cleanup remains uncertain / 清理仍不确定时 */
    @Override
    public void close() throws IOException {
        work.close();
    }

    /** Makes copied files read-only using the native supported attribute view. / 使用原生支持的属性视图将复制文件设为只读。
     * @param file owned ordinary file / 所属普通文件
     * @throws IOException if no supported freeze mechanism exists / 无支持的冻结机制时 */
    private static void freeze(Path file) throws IOException {
        var dos = Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) {
            dos.setReadOnly(true);
            return;
        }
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix == null)
            throw new IOException("Read-only snapshot attributes are unavailable");
        var permissions = new HashSet<>(posix.readAttributes().permissions());
        permissions.removeAll(Set.of(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE));
        posix.setPermissions(permissions);
    }

}
