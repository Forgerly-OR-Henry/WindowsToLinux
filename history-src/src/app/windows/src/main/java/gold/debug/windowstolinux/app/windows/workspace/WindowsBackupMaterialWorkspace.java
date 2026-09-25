package gold.debug.windowstolinux.app.windows.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Objects;

/**
 * Owns disposable local material only below the configured desktop work directory. / 仅持有桌面已配置工作目录下的可丢弃本地取材。
 */
public final class WindowsBackupMaterialWorkspace {
    /**
     * MINIMUM FREE BYTES.
     * <p>最小剩余字节。
     */
    private static final long MINIMUM_FREE_BYTES = 16L * 1024 * 1024;

    /**
     * Root directory defining the filesystem boundary.
     * <p>定义文件系统边界的根目录。
     */
    private final Path root;

    /**
     * Creates the boundary under the sole run-mode-derived work directory. / 在唯一由运行模式派生的工作目录下创建边界。
     *
     * @param workDirectory work directory / 工作目录
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public WindowsBackupMaterialWorkspace(Path workDirectory) {
        root = Objects.requireNonNull(workDirectory, "workDirectory").toAbsolutePath().normalize()
                .resolve("backup-materials");
    }

    /**
     * Creates one private attempt directory. / 创建一个私有尝试目录。
     *
     * @return one private attempt directory / 一个私有尝试目录
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     */
    public WindowsBackupMaterialAttempt createAttempt() throws WindowsWorkspaceException {
        try {
            prepareRoot();
            Path directory = Files.createTempDirectory(root, "attempt-").toAbsolutePath().normalize();
            Object key = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
            return new WindowsBackupMaterialAttempt(directory, key);
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.BACKUP_MATERIAL_WORKSPACE_FAILED,
                    "A private backup material directory could not be created", exception);
        }
    }

    /**
     * Resolves one canonical member path without creating it. / 解析一个规范成员路径且不创建文件。
     *
     * @param attempt attempt / 尝试
     * @param memberPath member path / 成员路径
     * @return one canonical member path without creating it / 一个规范成员路径且不创建文件
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public Path member(WindowsBackupMaterialAttempt attempt, String memberPath) throws WindowsWorkspaceException {
        verify(attempt);
        memberPath = Objects.requireNonNull(memberPath, "memberPath").replace('\\', '/');
        if (memberPath.startsWith("/") || memberPath.contains("..") || memberPath.contains("//")
                || !memberPath.matches("[A-Za-z0-9._/-]{1,512}")) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.BACKUP_MATERIAL_WORKSPACE_FAILED,
                    "Backup material member path is invalid", null);
        }
        Path member = attempt.directory().resolve(memberPath).normalize();
        if (!member.startsWith(attempt.directory())) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.BACKUP_MATERIAL_WORKSPACE_FAILED,
                    "Backup material member escaped its attempt", null);
        }
        try {
            Files.createDirectories(member.getParent());
        } catch (IOException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.BACKUP_MATERIAL_WORKSPACE_FAILED,
                    "Backup material parent could not be created", exception);
        }
        return member;
    }

    /**
     * Deletes only the unchanged exact attempt directory. / 仅删除身份未改变的精确尝试目录。
     *
     * @param attempt attempt / 尝试
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void discard(WindowsBackupMaterialAttempt attempt) throws WindowsWorkspaceException {
        Objects.requireNonNull(attempt, "attempt");
        if (!Files.exists(attempt.directory(), LinkOption.NOFOLLOW_LINKS))
            return;
        verify(attempt);
        try (var paths = Files.walk(attempt.directory())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.startsWith(attempt.directory()) || Files.isSymbolicLink(path)) {
                    throw new IOException("backup material cleanup encountered an external link");
                }
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.BACKUP_MATERIAL_WORKSPACE_FAILED,
                    "Backup material attempt could not be cleaned", exception);
        }
    }

    /**
     * Verifies windows backup material workspace.
     * <p>验证Windows备份素材工作区。
     *
     * @param attempt attempt / 尝试
     * @throws WindowsWorkspaceException if the windows workspace boundary rejects the operation / Windows工作区边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private void verify(WindowsBackupMaterialAttempt attempt) throws WindowsWorkspaceException {
        Path directory = Objects.requireNonNull(attempt, "attempt").directory();
        if (!directory.getParent().equals(root)) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.BACKUP_MATERIAL_WORKSPACE_FAILED,
                    "Backup material attempt is outside the managed root", null);
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || Files.isSymbolicLink(directory)
                    || attempt.fileKey() != null && !attempt.fileKey().equals(attributes.fileKey())) {
                throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.BACKUP_MATERIAL_WORKSPACE_FAILED,
                        "Backup material attempt changed externally and was preserved", null);
            }
        } catch (WindowsWorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.BACKUP_MATERIAL_WORKSPACE_FAILED,
                    "Backup material attempt identity could not be verified", exception);
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
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(root)) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.DIRECTORY_UNAVAILABLE,
                    "Backup material root is not one writable regular directory", null);
        }
        if (Files.getFileStore(root).getUsableSpace() < MINIMUM_FREE_BYTES) {
            throw WindowsWorkspaceException.create(WindowsWorkspaceFailureType.CAPACITY_INSUFFICIENT,
                    "Backup material root lacks minimum free capacity", null);
        }
    }
}
