package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by secret persistence adapters. / 秘密持久化适配器持有的失败类型。
 */
public enum SecretStoreFailureType implements FailureDefinition {
    /**
     * ENCRYPT FAILED classification within secret store failure type.
     * <p>秘密存储失败类型中的加密失败分类。
     */
    ENCRYPT_FAILED("secret.persistence.encrypt-failed", "persistence", "secret.error.encryptFailed", FailureRecoveryAction.RETRY),
    /**
     * DELETE FAILED classification within secret store failure type.
     * <p>秘密存储失败类型中的删除失败分类。
     */
    DELETE_FAILED("secret.persistence.delete-failed", "persistence", "secret.error.deleteFailed", FailureRecoveryAction.RETRY),
    /**
     * VERSION UNSUPPORTED classification within secret store failure type.
     * <p>秘密存储失败类型中的版本不支持分类。
     */
    VERSION_UNSUPPORTED("secret.persistence.version-unsupported", "persistence", "secret.error.versionUnsupported", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * READ FAILED classification within secret store failure type.
     * <p>秘密存储失败类型中的读取失败分类。
     */
    READ_FAILED("secret.persistence.read-failed", "persistence", "secret.error.readFailed", FailureRecoveryAction.RETRY),
    /**
     * DECRYPT FAILED classification within secret store failure type.
     * <p>秘密存储失败类型中的解密失败分类。
     */
    DECRYPT_FAILED("secret.persistence.decrypt-failed", "persistence", "secret.error.decryptFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * WINDOWS ONLY classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows仅分类。
     */
    WINDOWS_ONLY("secret.adapter.windows-only", "adapter", "secret.error.windowsOnly", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * WINDOWS WRITE FAILED classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows写入失败分类。
     */
    WINDOWS_WRITE_FAILED("secret.adapter.windows-write-failed", "adapter", "secret.error.windowsWriteFailed", FailureRecoveryAction.RETRY),
    /**
     * WINDOWS DELETE FAILED classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows删除失败分类。
     */
    WINDOWS_DELETE_FAILED("secret.adapter.windows-delete-failed", "adapter", "secret.error.windowsDeleteFailed", FailureRecoveryAction.RETRY),
    /**
     * WINDOWS EMPTY RESPONSE classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows空响应分类。
     */
    WINDOWS_EMPTY_RESPONSE("secret.adapter.windows-empty-response", "adapter", "secret.error.windowsEmptyResponse", FailureRecoveryAction.RETRY),
    /**
     * WINDOWS INVALID RESPONSE classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows无效响应分类。
     */
    WINDOWS_INVALID_RESPONSE("secret.adapter.windows-invalid-response", "adapter", "secret.error.windowsInvalidResponse", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * WINDOWS TIMEOUT classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows超时分类。
     */
    WINDOWS_TIMEOUT("secret.adapter.windows-timeout", "adapter", "secret.error.windowsTimeout", FailureRecoveryAction.RETRY),
    /**
     * WINDOWS OPERATION FAILED classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows操作失败分类。
     */
    WINDOWS_OPERATION_FAILED("secret.adapter.windows-operation-failed", "adapter", "secret.error.windowsOperationFailed", FailureRecoveryAction.RETRY),
    /**
     * WINDOWS START FAILED classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows启动失败分类。
     */
    WINDOWS_START_FAILED("secret.adapter.windows-start-failed", "adapter", "secret.error.windowsStartFailed", FailureRecoveryAction.RESTART_APPLICATION),
    /**
     * WINDOWS INTERRUPTED classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows已中断分类。
     */
    WINDOWS_INTERRUPTED("secret.adapter.windows-interrupted", "adapter", "secret.error.windowsInterrupted", FailureRecoveryAction.NONE),
    /**
     * WINDOWS UNCONTROLLED RESPONSE classification within secret store failure type.
     * <p>秘密存储失败类型中的Windows不受控响应分类。
     */
    WINDOWS_UNCONTROLLED_RESPONSE("secret.adapter.windows-uncontrolled-response", "adapter", "secret.error.windowsUncontrolledResponse", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * APPLICATION VALUE MISSING classification within secret store failure type.
     * <p>秘密存储失败类型中的应用内容缺失分类。
     */
    APPLICATION_VALUE_MISSING("secret.configuration.application-value-missing", "configuration", "secret.error.applicationValueMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * APPLICATION REFERENCE MISSING classification within secret store failure type.
     * <p>秘密存储失败类型中的应用引用缺失分类。
     */
    APPLICATION_REFERENCE_MISSING("secret.configuration.application-reference-missing", "configuration", "secret.error.applicationReferenceMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * SSH CREDENTIAL MISSING classification within secret store failure type.
     * <p>秘密存储失败类型中的SSH凭据缺失分类。
     */
    SSH_CREDENTIAL_MISSING("secret.configuration.ssh-credential-missing", "configuration", "secret.error.sshCredentialMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION);

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
     * Binds the supplied dependencies and state for secret store failure type.
     * <p>为秘密存储失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    SecretStoreFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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
