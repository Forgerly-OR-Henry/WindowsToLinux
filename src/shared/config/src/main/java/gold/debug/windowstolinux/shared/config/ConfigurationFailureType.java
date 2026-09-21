package gold.debug.windowstolinux.shared.config;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by deterministic deployment configuration validation. / 确定性部署配置校验持有的失败类型。
 */
public enum ConfigurationFailureType implements FailureDefinition {
    /**
     * IDENTIFIER INVALID classification within configuration failure type.
     * <p>配置失败类型中的标识无效分类。
     */
    IDENTIFIER_INVALID("configuration.validation.identifier-invalid", "validation", "configuration.error.identifierInvalid"),
    /**
     * REVISION INVALID classification within configuration failure type.
     * <p>配置失败类型中的修订无效分类。
     */
    REVISION_INVALID("configuration.validation.revision-invalid", "validation", "configuration.error.revisionInvalid"),
    /**
     * HASH INVALID classification within configuration failure type.
     * <p>配置失败类型中的哈希无效分类。
     */
    HASH_INVALID("configuration.validation.hash-invalid", "validation", "configuration.error.hashInvalid"),
    /**
     * SIZE LIMIT EXCEEDED classification within configuration failure type.
     * <p>配置失败类型中的大小限制已超限分类。
     */
    SIZE_LIMIT_EXCEEDED("configuration.validation.size-limit-exceeded", "validation", "configuration.error.sizeLimitExceeded"),
    /**
     * SECRET VALUE INVALID classification within configuration failure type.
     * <p>配置失败类型中的秘密内容无效分类。
     */
    SECRET_VALUE_INVALID("configuration.secret.value-invalid", "validation", "configuration.error.secretValueInvalid"),
    /**
     * SECRET COLLISION classification within configuration failure type.
     * <p>配置失败类型中的秘密冲突分类。
     */
    SECRET_COLLISION("configuration.secret.identifier-collision", "validation", "configuration.error.secretCollision"),
    /**
     * SNAPSHOT EMPTY classification within configuration failure type.
     * <p>配置失败类型中的快照空分类。
     */
    SNAPSHOT_EMPTY("configuration.snapshot.empty", "validation", "configuration.error.snapshotEmpty"),
    /**
     * DUPLICATE KEY classification within configuration failure type.
     * <p>配置失败类型中的重复键分类。
     */
    DUPLICATE_KEY("configuration.snapshot.duplicate-key", "validation", "configuration.error.duplicateKey"),
    /**
     * SNAPSHOT INTEGRITY FAILED classification within configuration failure type.
     * <p>配置失败类型中的快照完整性失败分类。
     */
    SNAPSHOT_INTEGRITY_FAILED("configuration.snapshot.integrity-failed", "validation", "configuration.error.snapshotIntegrityFailed"),
    /**
     * CONFIGURATION KEY INVALID classification within configuration failure type.
     * <p>配置失败类型中的配置键无效分类。
     */
    CONFIGURATION_KEY_INVALID("configuration.entry.key-invalid", "validation", "configuration.error.keyInvalid"),
    /**
     * SECRET LIKE KEY classification within configuration failure type.
     * <p>配置失败类型中的秘密类似键分类。
     */
    SECRET_LIKE_KEY("configuration.entry.secret-like-key", "validation", "configuration.error.secretLikeKey"),
    /**
     * PORT INVALID classification within configuration failure type.
     * <p>配置失败类型中的端口无效分类。
     */
    PORT_INVALID("configuration.entry.port-invalid", "validation", "configuration.error.portInvalid"),
    /**
     * TEXT VALUE INVALID classification within configuration failure type.
     * <p>配置失败类型中的文本内容无效分类。
     */
    TEXT_VALUE_INVALID("configuration.entry.text-invalid", "validation", "configuration.error.textInvalid"),
    /**
     * HASH ALGORITHM UNAVAILABLE classification within configuration failure type.
     * <p>配置失败类型中的哈希算法不可用分类。
     */
    HASH_ALGORITHM_UNAVAILABLE("configuration.runtime.hash-unavailable", "runtime", "configuration.error.hashUnavailable");

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
     * Binds the supplied dependencies and state for configuration failure type.
     * <p>为配置失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    ConfigurationFailureType(String code, String phase, String messageKey) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
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
    @Override public String domain() { return "configuration"; }
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
     * Returns the prescribed recovery action for this failure definition.
     * <p>返回当前失败定义规定的恢复动作。
     *
     * @return the prescribed recovery action for this failure definition / 当前失败定义规定的恢复动作
     */
    @Override public FailureRecoveryAction recoveryAction() { return FailureRecoveryAction.REQUEST_USER_CORRECTION; }
}
