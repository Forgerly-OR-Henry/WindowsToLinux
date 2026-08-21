package gold.debug.windowstolinux.app.windows.workspace;

import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchivePreparer;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;
import gold.debug.windowstolinux.shared.source.archive.SourceArchiveException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Windows desktop entry point for preparing a platform-neutral source archive.
 *
 * <p>准备平台无关源码归档的 Windows 桌面入口。
 */
public final class WindowsSourcePreparer {
    private static final long MINIMUM_FREE_BYTES = 1024L * 1024L;
    private final SafeSourceArchivePreparer archiver;
    private final Path workDirectory;
    private final Path archiveDirectory;

    /**
     * Creates a {@code WindowsSourcePreparer} instance.
     *
     * <p>创建 {@code WindowsSourcePreparer} 实例。
     *
     * @param workDirectory the {@code workDirectory} value / {@code workDirectory} 值
     */
    public WindowsSourcePreparer(Path workDirectory) {
        this(new SafeSourceArchivePreparer(), workDirectory);
    }

    WindowsSourcePreparer(SafeSourceArchivePreparer archiver, Path workDirectory) {
        this.archiver = Objects.requireNonNull(archiver, "archiver");
        this.workDirectory = Objects.requireNonNull(workDirectory, "workDirectory").toAbsolutePath().normalize();
        this.archiveDirectory = this.workDirectory.resolve("archives");
    }

    /** Returns the platform-owned root shared by archive and Git snapshot operations. / 返回归档和 Git 快照操作共用的平台拥有根目录。 */
    public Path workDirectory() {
        return workDirectory;
    }

    /**
     * Performs the {@code prepare} operation.
     *
     * <p>执行 {@code prepare} 操作。
     *
     * @param sourceDirectory the {@code sourceDirectory} value / {@code sourceDirectory} 值
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @return the operation result / 操作结果
     * @throws IOException if the operation cannot be completed / 无法完成操作时
     */
    public PreparedSourceArchive prepare(Path sourceDirectory, String applicationId) throws WindowsWorkspaceException {
        if (applicationId == null || !applicationId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.APPLICATION_ID_INVALID,
                    "The application identifier cannot be used for a controlled archive name", null);
        }
        try {
            Files.createDirectories(archiveDirectory);
            if (Files.isSymbolicLink(archiveDirectory) || !Files.isDirectory(archiveDirectory)) {
                throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.DIRECTORY_UNAVAILABLE,
                        "The platform archive workspace is not a regular directory", null);
            }
            if (!Files.isWritable(archiveDirectory)) {
                throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.DIRECTORY_NOT_WRITABLE,
                        "The platform archive workspace is not writable", null);
            }
            if (Files.getFileStore(archiveDirectory).getUsableSpace() < MINIMUM_FREE_BYTES) {
                throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.CAPACITY_INSUFFICIENT,
                        "The platform archive workspace lacks minimum free capacity", null);
            }
            SourceArchive archive = archiver.archive(sourceDirectory,
                    archiveDirectory.resolve(applicationId + "-" + UUID.randomUUID() + ".tar.gz"));
            return new PreparedSourceArchive(new SourceArchiveDescriptor(
                    archive.archivePath(), archive.contentSha256(), archive.byteCount(), archive.uncompressedByteCount()
            ), archive.excludedEntries());
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (SourceArchiveException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.ARCHIVE_FAILED,
                    "The selected source could not be archived in the controlled Windows workspace", exception);
        } catch (IOException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.DIRECTORY_UNAVAILABLE,
                    "The controlled Windows workspace could not be prepared", exception);
        }
    }
}
