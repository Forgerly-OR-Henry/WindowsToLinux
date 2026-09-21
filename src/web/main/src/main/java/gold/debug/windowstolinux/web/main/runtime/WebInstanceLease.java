package gold.debug.windowstolinux.web.main.runtime;

import gold.debug.windowstolinux.web.file.workspace.WebWorkspace;
import gold.debug.windowstolinux.web.db.runtime.WebStorageLocation;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

/**
 * Owns an exclusive data-root lease and marker; unrelated nonempty directories are never adopted.
 * <p>持有数据根目录独占租约及标记；绝不接管无关非空目录。
 */
public final class WebInstanceLease implements AutoCloseable {
    /**
     * Channel.
     * <p>通道。
     */
    private final FileChannel channel;
    /**
     * Lock.
     * <p>锁。
     */
    private final FileLock lock;
    /**
     * Database existed.
     * <p>数据库Existed。
     */
    private final boolean databaseExisted;
    /**
     * Binds the supplied dependencies and state for web instance lease.
     * <p>为Web实例租约绑定传入的依赖及状态。
     *
     * @param channel channel / 通道
     * @param lock lock / 锁
     * @param databaseExisted database existed / 数据库Existed
     */
    private WebInstanceLease(FileChannel channel, FileLock lock, boolean databaseExisted) {
        this.channel = channel; this.lock = lock; this.databaseExisted = databaseExisted;
    }

    /**
     * Acquires an exclusive data-root lock only after validating ownership and rejecting unrelated existing content.
     * <p>仅在验证归属并拒绝无关既有内容后获取数据根目录独占锁。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     * @return constructed or resolved web instance lease / 构造或解析得到的Web实例租约
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static WebInstanceLease acquire(Path root, long minimumFreeBytes) throws IOException {
        WebWorkspace.safeAncestors(root);
        if (Files.exists(root.resolve("windowstolinux.db"))) throw new IOException("Desktop data cannot be shared");
        Files.createDirectories(root);
        Path marker = root.resolve(".web-runtime");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 64 || !Files.readString(marker).equals("windowstolinux-internal-web-v1"))
                throw new IOException("Invalid Web runtime ownership");
        } else {
            try (var entries = Files.list(root)) {
                if (entries.anyMatch(path -> !path.getFileName().toString().equals(".instance-lock"))) throw new IOException("Web runtime directory is not empty");
            }
        }
        Path file = root.resolve(".instance-lock"); WebWorkspace.safeAncestors(file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Another Web instance owns the directory");
            if (!Files.exists(marker)) Files.writeString(marker, "windowstolinux-internal-web-v1", StandardOpenOption.CREATE_NEW);
            if (Files.getFileStore(root).getUsableSpace() < minimumFreeBytes) throw new IOException("Web data disk space is insufficient");
            return new WebInstanceLease(channel, lock, Files.exists(root.resolve(WebStorageLocation.DATABASE_NAME), LinkOption.NOFOLLOW_LINKS));
        } catch (Exception failure) {
            channel.close(); throw new IOException("Web data lease could not be acquired", failure);
        }
    }
    /**
     * Returns database existed.
     * <p>返回数据库Existed。
     *
     * @return true when returns database existed, false otherwise / 返回数据库Existed时为 true，否则为 false
     */
    public boolean databaseExisted() { return databaseExisted; }
    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     *
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
