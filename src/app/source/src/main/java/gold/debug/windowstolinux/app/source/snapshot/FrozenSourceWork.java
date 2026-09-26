package gold.debug.windowstolinux.app.source.snapshot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

import gold.debug.windowstolinux.app.source.boundary.SelectedDirectory;
import gold.debug.windowstolinux.app.windows.filesystem.WindowsFileIdentity;
import gold.debug.windowstolinux.appapi.source.SnapshotRequest;
import gold.debug.windowstolinux.appapi.task.CancellationToken;

/** Owns one task-local snapshot and its verifiable cleanup marker. / 持有一个任务本地快照及可核验清理标记。 */
public final class FrozenSourceWork implements AutoCloseable {
    /** Maximum declared snapshot capacity. / 最大声明快照容量。 */
    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    /** Maximum explicit file count. / 最大明确文件数量。 */
    public static final int MAX_FILES = 100000;

    /** Validated host work root. / 已校验宿主工作根。 */
    private final Path workRoot;

    /** Exact original task identity. / 精确原任务身份。 */
    private final UUID task;

    /** Unique snapshot identity. / 唯一快照身份。 */
    private final UUID id;

    /** Exact Mod owner. / 精确 Mod 所有者。 */
    private final String owner;

    /** Private snapshot container outside the returned payload directory. / 返回载荷目录之外的私有快照容器。 */
    private final Path directory;

    /** Original physical directory identity. / 原物理目录身份。 */
    private String identity;

    /** Whether this instance created or recovered the exact directory. / 当前实例是否创建或恢复了精确目录。 */
    private boolean owned;

    /** Whether actual cleanup completed. / 实际清理是否完成。 */
    private boolean closed;

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
        this.workRoot = workRoot.toAbsolutePath().normalize();
        this.task = Objects.requireNonNull(task);
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        directory = this.workRoot.resolve(task.toString()).resolve(id.toString());
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

    /** Creates only a new task container and records its physical ownership. / 仅创建新的任务容器并记录物理归属。
     * @throws IOException if creation or ownership recording fails / 创建或归属记录失败时 */
    public void prepare() throws IOException {
        if (owned || closed)
            throw new IllegalStateException("Snapshot work was already consumed");
        directories(directory.getParent());
        Files.createDirectory(directory);
        owned = true;
        attributes(directory);
        identity = WindowsFileIdentity.read(directory);
        try (var output = new DataOutputStream(
                Files.newOutputStream(directory.resolve("owner.bin"), StandardOpenOption.CREATE_NEW))) {
            output.writeUTF("WTL-SOURCE-WORK-1");
            output.writeUTF(task.toString());
            output.writeUTF(id.toString());
            output.writeUTF(owner);
            output.writeUTF(identity);
        }
        Files.createDirectory(directory.resolve("files"));
    }

    /** Recovers only a matching persisted task-owned container, never an unmarked directory. / 仅恢复匹配的持久任务容器，不接管无标记目录。
     * @throws IOException if the marker or physical identity cannot be proved / 无法证明标记或物理身份时 */
    public void recover() throws IOException {
        if (owned || closed)
            throw new IllegalStateException("Snapshot work was already consumed");
        if (!exists(directory)) {
            closed = true;
            return;
        }
        attributes(directory);
        var marker = directory.resolve("owner.bin");
        var meta = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!meta.isRegularFile() || meta.isOther() || meta.isSymbolicLink() || meta.size() > 16384)
            throw new IOException("Source ownership marker is unavailable");
        try (var input = new DataInputStream(Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS))) {
            if (!input.readUTF().equals("WTL-SOURCE-WORK-1") || !input.readUTF().equals(task.toString())
                    || !input.readUTF().equals(id.toString()) || !input.readUTF().equals(owner))
                throw new IOException("Source ownership marker does not match");
            String recorded = input.readUTF();
            if (input.read() != -1 || !recorded.equals(WindowsFileIdentity.read(directory)))
                throw new IOException("Source work identity changed");
            identity = recorded;
        }
        owned = true;
    }

    /** Copies exactly the chosen files, verifies the source again, then freezes the payload. / 精确复制选中文件，再次核对来源，随后冻结载荷。
     * @param source original selected boundary / 原选定边界
     * @param request author-selected files and budget / 作者选定文件及预算
     * @param cancellation original task cancellation / 原任务取消
     * @throws IOException if source, output or identity validation fails / 来源、输出或身份校验失败时 */
    public void capture(SelectedDirectory source, SnapshotRequest request, CancellationToken cancellation)
            throws IOException {
        List<String> names = validate(request);
        requireOwned();
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
            requireOwned();
            Path target = directory.resolve("files").resolve(entry.relativePath());
            directories(target.getParent());
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
            freeze(directory.resolve("files").resolve(name));
        manifest = Collections.unmodifiableSortedMap(copied);
        sha256 = manifestDigest(copied);
        verify(cancellation);
    }

    /** Revalidates copied payload before exposing its location or digest. / 暴露位置或摘要前重新核验复制载荷。
     * @param cancellation current task cancellation / 当前任务取消
     * @throws IOException if the frozen payload or its ownership changed / 冻结载荷或归属变化时 */
    public void verify(CancellationToken cancellation) throws IOException {
        requireOwned();
        if (manifest == null)
            throw new IOException("Snapshot capture did not complete");
        attributes(directory.resolve("files"));
        var payload = new SelectedDirectory(directory.resolve("files"));
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
        return directory.resolve("files");
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

    /** Refuses cleanup or publication after path substitution. / 路径被替换后拒绝清理或发布。
     * @throws IOException if the owned physical directory changed / 所属物理目录变化时 */
    private void requireOwned() throws IOException {
        if (!owned || closed)
            throw new IOException("Source work is not open");
        parents(directory.getParent());
        attributes(directory);
        if (!WindowsFileIdentity.read(directory).equals(identity))
            throw new IOException("Source work was replaced");
    }

    /** Deletes only the owned tree without following links, retaining identity on failure. / 仅删除所属树且不跟随链接，失败时保留身份。
     * @throws IOException if physical cleanup cannot complete / 无法完成物理清理时 */
    @Override
    public void close() throws IOException {
        if (closed)
            return;
        if (!owned) {
            closed = true;
            return;
        }
        if (!exists(directory)) {
            closed = true;
            return;
        }
        requireOwned();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            /** Checks each traversed directory before descending. / 下降前检查每个遍历目录。
             * @param path candidate directory / 候选目录
             * @param attrs non-following attributes / 不跟随链接的属性
             * @return traversal decision / 遍历决定
             * @throws IOException if a directory is an unsafe link / 目录是不安全链接时 */
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                requireOwned();
                attributes(path);
                return FileVisitResult.CONTINUE;
            }

            /** Deletes a file or link itself, never its target. / 删除文件或链接本身，不删除链接目标。
             * @param file task-owned entry / 任务所属项
             * @param attrs non-following attributes / 不跟随链接属性
             * @return continued traversal / 继续遍历
             * @throws IOException if deletion fails / 删除失败时 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                requireOwned();
                if (file.equals(directory.resolve("owner.bin")))
                    return FileVisitResult.CONTINUE;
                if (attrs.isRegularFile() && !attrs.isSymbolicLink() && !attrs.isOther()) {
                    var dos = Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    if (dos != null)
                        dos.setReadOnly(false);
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            /** Removes an emptied owned directory after successful traversal. / 成功遍历后移除已空的所属目录。
             * @param path traversed directory / 已遍历目录
             * @param failure traversal failure / 遍历失败
             * @return continued traversal / 继续遍历
             * @throws IOException if traversal or deletion fails / 遍历或删除失败时 */
            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null)
                    throw failure;
                requireOwned();
                if (path.equals(directory))
                    Files.deleteIfExists(directory.resolve("owner.bin"));
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
        closed = true;
        try {
            Files.delete(directory.getParent());
        } catch (DirectoryNotEmptyException | NoSuchFileException occupied) {
            /* Other snapshots may retain or remove their shared parent. / 其他快照可能保留或移除共享父目录。 */ }
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

    /** Creates missing parents only while all existing components remain ordinary directories. / 仅在已有组件均为普通目录时创建缺失父目录。
     * @param path required directory / 所需目录
     * @throws IOException if a component is unsafe / 组件不安全时 */
    private static void directories(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException exists) {
                /* Validate below. / 下方核验。 */ }
            attributes(current);
        }
    }

    /** Checks every existing parent without recreating missing ownership. / 检查每个已有父目录，不重建缺失归属。
     * @param path existing directory / 已有目录
     * @throws IOException if a parent is missing or linked / 父目录缺失或为链接时 */
    private static void parents(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            attributes(current);
        }
    }

    /** Confirms absence only after checking every preceding existing directory. / 仅在检查此前每个已有目录后确认缺失。
     * @param path expected owned directory / 预期所属目录
     * @return whether every component exists / 是否所有组件均存在
     * @throws IOException if an existing component is unsafe or unreadable / 已有组件不安全或不可读时 */
    private static boolean exists(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            try {
                attributes(current);
            } catch (NoSuchFileException absent) {
                return false;
            }
        }
        return true;
    }

    /** Requires stable non-link directory attributes. / 要求稳定且非链接的目录属性。
     * @param path exact directory / 精确目录
     * @return actual attributes / 实际属性
     * @throws IOException if identity is unavailable / 身份不可用时 */
    private static BasicFileAttributes attributes(Path path) throws IOException {
        var value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!value.isDirectory() || value.isSymbolicLink() || value.isOther())
            throw new IOException("Unsafe source work directory");
        return value;
    }
}
