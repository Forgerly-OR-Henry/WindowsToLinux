package gold.debug.windowstolinux.shared.backup.crypto;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by independent backup-secret cryptography. / 独立备份秘密密码学持有的失败类型。
 */
public enum BackupSecretFailureType implements FailureDefinition {
    /**
     * PASSWORD INVALID classification within backup secret failure type.
     * <p>备份秘密失败类型中的密码无效分类。
     */
    PASSWORD_INVALID("secret.backup.password-invalid", "backup", "secret.error.backupPasswordInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ENCRYPT FAILED classification within backup secret failure type.
     * <p>备份秘密失败类型中的加密失败分类。
     */
    ENCRYPT_FAILED("secret.backup.encrypt-failed", "backup", "secret.error.backupEncryptFailed", FailureRecoveryAction.RETRY),
    /**
     * DECRYPT FAILED classification within backup secret failure type.
     * <p>备份秘密失败类型中的解密失败分类。
     */
    DECRYPT_FAILED("secret.backup.decrypt-failed", "backup", "secret.error.backupDecryptFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * PAYLOAD INVALID classification within backup secret failure type.
     * <p>备份秘密失败类型中的载荷无效分类。
     */
    PAYLOAD_INVALID("secret.backup.payload-invalid", "backup", "secret.error.backupPayloadInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION);

    /**
     * Stable machine-readable classification code.
     * <p>稳定的机器可读分类码。
     */
    private final String code;
    /**
     * Stage associated with the result or failure.
     * <p>结果或失败所属阶段。
     */
    private final String phase;
    /**
     * Stable localization key for user-facing text.
     * <p>用户可见文本的稳定本地化键。
     */
    private final String messageKey;
    /**
     * Action required to recover from the classified failure.
     * <p>从已分类失败中恢复所需的动作。
     */
    private final FailureRecoveryAction recoveryAction;

    /**
     * Binds the supplied dependencies and state for backup secret failure type.
     * <p>为备份秘密失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    BackupSecretFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    /**
     * Returns stable machine-readable classification code.
     * <p>返回稳定的机器可读分类码。
     *
     * @return stable machine-readable classification code / 稳定的机器可读分类码
     */
    @Override public String code() { return code; }
    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    @Override public String domain() { return "secret"; }
    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    @Override public String phase() { return phase; }
    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    @Override public String messageKey() { return messageKey; }
    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}
