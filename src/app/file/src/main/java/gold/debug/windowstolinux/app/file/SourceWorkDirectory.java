package gold.debug.windowstolinux.app.file;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

import gold.debug.windowstolinux.app.windows.file.WindowsFileIdentity;

/** Owns a task-local file container and its persisted physical identity. / 持有任务本地文件容器及其持久物理身份。 */
public final class SourceWorkDirectory implements AutoCloseable {
    /** Validated host work root. / 已校验宿主工作根。 */
    private final Path workRoot;

    /** Exact original task identity. / 精确原任务身份。 */
    private final UUID task;

    /** Unique file container identity. / 唯一文件容器身份。 */
    private final UUID id;

    /** Exact Mod owner. / 精确 Mod 所有者。 */
    private final String owner;

    /** Private container outside the returned payload directory. / 返回载荷目录之外的私有容器。 */
    private final Path directory;

    /** Original physical directory identity. / 原物理目录身份。 */
    private String identity;

    /** Whether this instance created or recovered the exact directory. / 当前实例是否创建或恢复了精确目录。 */
    private boolean owned;

    /** Whether actual cleanup completed. / 实际清理是否完成。 */
    private boolean closed;

    /** Binds deterministic cleanup coordinates without creating any files. / 绑定确定性清理坐标，不创建任何文件。
     * @param workRoot platform-selected source work root / 平台选定来源工作根
     * @param task original task identity / 原任务身份
     * @param id unique container identity / 唯一容器身份
     * @param owner exact Mod identity / 精确 Mod 身份 */
    public SourceWorkDirectory(Path workRoot, UUID task, UUID id, String owner) {
        this.workRoot = workRoot.toAbsolutePath().normalize();
        this.task = Objects.requireNonNull(task);
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        directory = this.workRoot.resolve(task.toString()).resolve(id.toString());
    }

    /** Creates only a new task container and records its physical ownership. / 仅创建新的任务容器并记录物理归属。
     * @throws IOException if creation or ownership recording fails / 创建或归属记录失败时 */
    public void prepare() throws IOException {
        if (owned || closed)
            throw new IllegalStateException("Source work was already consumed");
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
            throw new IllegalStateException("Source work was already consumed");
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

    /** Verifies ownership before returning the writable payload directory. / 返回可写载荷目录前核验归属。
     * @return exact owned payload directory / 精确所属载荷目录
     * @throws IOException if ownership or payload identity is unavailable / 归属或载荷身份不可用时 */
    public Path directory() throws IOException {
        requireOwned();
        attributes(directory.resolve("files"));
        return directory.resolve("files");
    }

    /** Refuses cleanup or publication after path substitution. / 路径被替换后拒绝清理或发布。
     * @throws IOException if the owned physical directory changed / 所属物理目录变化时 */
    public void requireOwned() throws IOException {
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
            /* Other snapshots may retain or remove their shared parent. / 其他快照可能保留或移除共享父目录。 */
        }
    }

    /** Creates missing parents only while all existing components remain ordinary directories. / 仅在已有组件均为普通目录时创建缺失父目录。
     * @param path required directory / 所需目录
     * @throws IOException if a component is unsafe / 组件不安全时 */
    static void directories(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException exists) {
                /* Validate below. / 下方核验。 */
            }
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
    static BasicFileAttributes attributes(Path path) throws IOException {
        var value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!value.isDirectory() || value.isSymbolicLink() || value.isOther())
            throw new IOException("Unsafe source work directory");
        return value;
    }
}
