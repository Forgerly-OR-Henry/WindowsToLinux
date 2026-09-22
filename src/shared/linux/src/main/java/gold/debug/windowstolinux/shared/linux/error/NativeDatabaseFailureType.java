package gold.debug.windowstolinux.shared.linux.error;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Native database failures with stable helper status names. / 保留固定 helper 状态名称的原生数据库失败。
 */
public enum NativeDatabaseFailureType implements FailureDefinition {
    /**
     * AUTH REQUIRED classification within native database failure type.
     * <p>原生数据库失败类型中的认证必需分类。
     */
    AUTH_REQUIRED("auth-required", "nativeDatabaseAuthRequired", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * STATE CHANGED classification within native database failure type.
     * <p>原生数据库失败类型中的状态已变化分类。
     */
    STATE_CHANGED("state-changed", "nativeDatabaseStateChanged", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * INITIALIZATION FAILED classification within native database failure type.
     * <p>原生数据库失败类型中的初始化失败分类。
     */
    INITIALIZATION_FAILED("initialization-failed", "nativeDatabaseInitializationFailed",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * MANUAL RESTORE REQUIRED classification within native database failure type.
     * <p>原生数据库失败类型中的人工恢复必需分类。
     */
    MANUAL_RESTORE_REQUIRED("manual-restore-required", "nativeDatabaseManualRestoreRequired",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * VERSION UNSUPPORTED classification within native database failure type.
     * <p>原生数据库失败类型中的版本不支持分类。
     */
    VERSION_UNSUPPORTED("version-unsupported", "nativeDatabaseVersionUnsupported",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ACTION FAILED classification within native database failure type.
     * <p>原生数据库失败类型中的动作失败分类。
     */
    ACTION_FAILED("action-failed", "nativeDatabaseActionFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION);

    /**
     * Reason.
     * <p>原因。
     */
    private final String reason;

    /**
     * Localized explanation.
     * <p>本地化说明。
     */
    private final String message;

    /**
     * Recovery.
     * <p>恢复。
     */
    private final FailureRecoveryAction recovery;

    /**
     * Binds the supplied dependencies and state for native database failure type.
     * <p>为原生数据库失败类型绑定传入的依赖及状态。
     *
     * @param reason reason / 原因
     * @param message localized explanation / 本地化说明
     * @param recovery recovery / 恢复
     */
    NativeDatabaseFailureType(String reason, String message, FailureRecoveryAction recovery) {
        this.reason = reason;
        this.message = message;
        this.recovery = recovery;
    }

    /**
     * Returns the stable machine-readable code of this classification.
     * <p>返回当前分类的稳定机器可读代码。
     *
     * @return the stable machine-readable code of this classification / 当前分类的稳定机器可读代码
     */
    @Override
    public String code() {
        return "linux.database." + reason;
    }

    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    @Override
    public String domain() {
        return "linux";
    }

    /**
     * Returns the operation stage associated with this failure definition.
     * <p>返回当前失败定义对应的操作阶段。
     *
     * @return the operation stage associated with this failure definition / 当前失败定义对应的操作阶段
     */
    @Override
    public String phase() {
        return "database";
    }

    /**
     * Returns the localization key used to explain this classification.
     * <p>返回用于解释当前分类的本地化键。
     *
     * @return the localization key used to explain this classification / 用于解释当前分类的本地化键
     */
    @Override
    public String messageKey() {
        return "linux.error." + message;
    }

    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    @Override
    public FailureSeverityLevel severity() {
        return FailureSeverityLevel.ERROR;
    }

    /**
     * Returns recovery.
     * <p>返回恢复。
     *
     * @return recovery / 恢复
     */
    @Override
    public FailureRecoveryAction recoveryAction() {
        return recovery;
    }
}
