package gold.debug.windowstolinux.web.main.runtime;

import gold.debug.windowstolinux.web.file.workspace.WebWorkspace;
import gold.debug.windowstolinux.web.db.runtime.WebStorageLocation;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

/** Exclusive data-root lease and ownership marker; never adopts a nonempty unrelated directory. */
public final class WebInstanceLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final boolean databaseExisted;
    private WebInstanceLease(FileChannel channel, FileLock lock, boolean databaseExisted) {
        this.channel = channel; this.lock = lock; this.databaseExisted = databaseExisted;
    }

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
    public boolean databaseExisted() { return databaseExisted; }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
