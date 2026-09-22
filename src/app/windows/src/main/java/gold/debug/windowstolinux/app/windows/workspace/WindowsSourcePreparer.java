package gold.debug.windowstolinux.app.windows.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchivePreparer;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;
import gold.debug.windowstolinux.shared.source.archive.SourceArchiveException;

/**
 * Windows desktop entry point for preparing a platform-neutral source archive.
 *
 *  <p>准备平台无关源码归档的 Windows 桌面入口。
 */
public final class WindowsSourcePreparer {
    /**
     * MINIMUM FREE BYTES.
     * <p>最小剩余字节。
     */
    private static final long MINIMUM_FREE_BYTES = 1024L * 1024L;

    /**
     * Bound safe source archive preparer collaborator for archiver.
     * <p>处理归档生成器的安全源码归档准备器协作对象。
     */
    private final SafeSourceArchivePreparer archiver;

    /**
     * Work directory.
     * <p>工作目录。
     */
    private final Path workDirectory;

    /**
     * Archive directory.
     * <p>归档目录。
     */
    private final Path archiveDirectory;

    /**
     * Initializes windows source preparer through its shared constructor contract.
     * <p>通过共享构造契约初始化Windows源码准备器。
     *
     * @param workDirectory work directory / 工作目录
     */
    public WindowsSourcePreparer(Path workDirectory) {
        this(new SafeSourceArchivePreparer(), workDirectory);
    }

    /**
     * Validates and binds the inputs required by windows source preparer.
     * <p>校验并绑定Windows源码准备器所需输入。
     *
     * @param archiver archiver / 归档生成器
     * @param workDirectory work directory / 工作目录
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    WindowsSourcePreparer(SafeSourceArchivePreparer archiver, Path workDirectory) {
        this.archiver = Objects.requireNonNull(archiver, "archiver");
        this.workDirectory = Objects.requireNonNull(workDirectory, "workDirectory").toAbsolutePath().normalize();
        this.archiveDirectory = this.workDirectory.resolve("archives");
    }

    /**
     * Returns the platform-owned root shared by archive and Git snapshot operations. / 返回归档和 Git 快照操作共用的平台拥有根目录。
     *
     * @return the platform-owned root shared by archive and Git snapshot operations / 归档和 Git 快照操作共用的平台拥有根目录
     */
    public Path workDirectory() {
        return workDirectory;
    }

    /**
     * Prepares prepared source archive.
     * <p>准备已准备源码归档。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @param applicationId managed application identifier / 受管应用标识
     * @return the operation result / 操作结果
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
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
            return new PreparedSourceArchive(new SourceArchiveDescriptor(archive.archivePath(), archive.contentSha256(),
                    archive.byteCount(), archive.uncompressedByteCount()), archive.excludedEntries());
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
