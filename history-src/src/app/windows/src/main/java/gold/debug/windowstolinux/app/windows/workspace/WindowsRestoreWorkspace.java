package gold.debug.windowstolinux.app.windows.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/**
 * Owns only local digest-bound restore candidate attempt directories. / 仅持有本地摘要绑定恢复候选尝试目录。
 */
public final class WindowsRestoreWorkspace {
    /**
     * MINIMUM FREE BYTES.
     * <p>最小剩余字节。
     */
    private static final long MINIMUM_FREE_BYTES = 1024L * 1024L;

    /**
     * Restore directory.
     * <p>恢复目录。
     */
    private final Path restoreDirectory;

    /**
     * Creates a restore workspace under the existing platform work directory. / 在现有平台工作目录下创建恢复工作区边界。
     *
     * @param workDirectory work directory / 工作目录
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public WindowsRestoreWorkspace(Path workDirectory) {
        restoreDirectory = Objects.requireNonNull(workDirectory, "workDirectory").toAbsolutePath().normalize()
                .resolve("restore-candidates");
    }

    /**
     * Creates one private attempt parent while leaving the candidate root absent for safe extraction. / 创建一个私有尝试父目录，并保持候选根不存在以便安全提取。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param archiveSha256 SHA-256 identity of the reviewed archive / 已审阅归档的 SHA-256 身份
     * @return one private attempt parent while leaving the candidate root absent for safe extraction / 一个私有尝试父目录，并保持候选根不存在以便安全提取
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public WindowsRestoreAttempt createAttempt(String applicationId, String archiveSha256)
            throws WindowsWorkspaceException {
        applicationId = Objects.requireNonNull(applicationId, "applicationId").trim();
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256").trim().toLowerCase(Locale.ROOT);
        if (!applicationId.matches("[a-z0-9][a-z0-9-]{0,62}") || !archiveSha256.matches("[0-9a-f]{64}")) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.APPLICATION_ID_INVALID,
                    "The restore candidate identity is invalid", null);
        }
        try {
            prepareRoot();
            Path parent = Files.createTempDirectory(restoreDirectory, "attempt-").toAbsolutePath().normalize();
            String candidateId = applicationId + "-" + archiveSha256.substring(0, 16);
            Object parentFileKey = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
            return new WindowsRestoreAttempt(parent, parent.resolve(candidateId), candidateId, parentFileKey);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.RESTORE_WORKSPACE_FAILED,
                    "A local restore attempt directory could not be created", exception);
        }
    }

    /**
     * Deletes only one exact attempt previously created under this workspace. / 仅删除此工作区下先前创建的一个精确尝试。
     *
     * @param attempt attempt / 尝试
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void discardAttempt(WindowsRestoreAttempt attempt) throws WindowsWorkspaceException {
        Objects.requireNonNull(attempt, "attempt");
        Path parent = attempt.parent().toAbsolutePath().normalize();
        if (!parent.getParent().equals(restoreDirectory) || !attempt.candidateRoot().getParent().equals(parent)) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.RESTORE_WORKSPACE_FAILED,
                    "The restore attempt is outside the platform workspace", null);
        }
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS))
            return;
        try {
            BasicFileAttributes attributes = Files.readAttributes(parent, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || Files.isSymbolicLink(parent)
                    || attempt.parentFileKey() != null && !attempt.parentFileKey().equals(attributes.fileKey())) {
                throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.RESTORE_WORKSPACE_FAILED,
                        "The restore attempt parent changed externally and was preserved", null);
            }
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.RESTORE_WORKSPACE_FAILED,
                    "The restore attempt parent identity could not be verified", exception);
        }
        try (var paths = Files.walk(parent)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.startsWith(parent))
                    throw new IOException("restore cleanup escaped its attempt parent");
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.RESTORE_WORKSPACE_FAILED,
                    "The failed local restore attempt could not be cleaned", exception);
        }
    }

    /**
     * Prepares root directory defining the filesystem boundary.
     * <p>准备定义文件系统边界的根目录。
     *
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     */
    private void prepareRoot() throws IOException, WindowsWorkspaceException {
        Files.createDirectories(restoreDirectory);
        if (Files.isSymbolicLink(restoreDirectory) || !Files.isDirectory(restoreDirectory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(restoreDirectory)) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.DIRECTORY_UNAVAILABLE,
                    "The restore workspace is not one writable regular directory", null);
        }
        if (Files.getFileStore(restoreDirectory).getUsableSpace() < MINIMUM_FREE_BYTES) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.CAPACITY_INSUFFICIENT,
                    "The restore workspace lacks minimum free capacity", null);
        }
    }
}
