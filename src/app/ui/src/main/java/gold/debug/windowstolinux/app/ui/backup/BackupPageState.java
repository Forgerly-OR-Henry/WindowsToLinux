package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;

import java.util.Objects;

/**
 * Preserves managed input, archive, result, and candidate lifecycle across shell rebuilds. / 在外壳重建时保留受管输入、归档、结果和候选生命周期。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param targetServerId target server id / 目标服务器标识
 * @param archivePath archive path / 归档路径
 * @param destinationPath destination path / 目的地路径
 * @param output destination receiving the produced content / 接收所生成内容的目标
 * @param preparedCandidate prepared candidate / 已准备候选
 * @param task task / 任务
 * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
 * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
 */
public record BackupPageState(
        String applicationId,
        String targetServerId,
        String archivePath,
        String destinationPath,
        String output,
        PreparedBackupCandidate preparedCandidate,
        int task,
        char[] backupPassword,
        char[] masterPassword
) implements AutoCloseable {
    /**
     * Constructs a default backup task with retained prior page values. / 保留页面先前输入，构造默认备份任务。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetServerId target server id / 目标服务器标识
     * @param archivePath archive path / 归档路径
     * @param destinationPath destination path / 目的地路径
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparedCandidate prepared candidate / 已准备候选
     */
    public BackupPageState(String applicationId, String targetServerId, String archivePath, String destinationPath,
                           String output, PreparedBackupCandidate preparedCandidate) {
        this(applicationId, targetServerId, archivePath, destinationPath, output, preparedCandidate, 0, new char[0], new char[0]);
    }

    /**
     * Validates immutable page state. / 校验不可变页面状态。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetServerId target server id / 目标服务器标识
     * @param archivePath archive path / 归档路径
     * @param destinationPath destination path / 目的地路径
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparedCandidate prepared candidate / 已准备候选
     * @param task task / 任务
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupPageState {
        if (task < 0 || task > 2) throw new IllegalArgumentException("invalid backup task");
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        targetServerId = Objects.requireNonNull(targetServerId, "targetServerId");
        archivePath = Objects.requireNonNull(archivePath, "archivePath");
        destinationPath = Objects.requireNonNull(destinationPath, "destinationPath");
        output = Objects.requireNonNull(output, "output");
        backupPassword = Objects.requireNonNull(backupPassword).clone();
        masterPassword = Objects.requireNonNull(masterPassword).clone();
    }

    /**
     * Restores state captured before remote backup creation was exposed. / 恢复远端备份创建入口出现前捕获的状态。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param archivePath archive path / 归档路径
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparedCandidate prepared candidate / 已准备候选
     */
    public BackupPageState(String applicationId, String archivePath, String output,
                           PreparedBackupCandidate preparedCandidate) {
        this(applicationId, "", archivePath, "", output, preparedCandidate);
    }

    /**
     * Restores state captured before target restore and migration were exposed. / 恢复目标恢复及迁移入口出现前捕获的状态。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param archivePath archive path / 归档路径
     * @param destinationPath destination path / 目的地路径
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param preparedCandidate prepared candidate / 已准备候选
     */
    public BackupPageState(String applicationId, String archivePath, String destinationPath, String output,
                           PreparedBackupCandidate preparedCandidate) {
        this(applicationId, "", archivePath, destinationPath, output, preparedCandidate);
    }

    /**
     * Returns independent password for backup secret encryption or decryption.
     * <p>返回备份秘密加密或解密使用的独立密码。
     *
     * @return independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     */
    @Override public char[] backupPassword() { return backupPassword.clone(); }
    /**
     * Returns master-password buffer used to unlock protected credentials.
     * <p>返回用于解锁受保护凭据的主密码缓冲区。
     *
     * @return master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     */
    @Override public char[] masterPassword() { return masterPassword.clone(); }
    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     */
    @Override public void close() {
        java.util.Arrays.fill(backupPassword, '\0'); java.util.Arrays.fill(masterPassword, '\0');
    }
}
