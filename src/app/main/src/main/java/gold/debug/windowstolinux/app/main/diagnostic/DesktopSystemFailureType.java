package gold.debug.windowstolinux.app.main.diagnostic;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Desktop-process failures owned by app/main. / app/main 持有的桌面进程失败类型。
 */
public enum DesktopSystemFailureType implements FailureDefinition {
    /**
     * STARTUP LAYOUT INVALID classification within desktop system failure type.
     * <p>Desktop系统失败类型中的启动布局无效分类。
     */
    STARTUP_LAYOUT_INVALID("desktop.startup.layout-invalid", "startup", "desktop.error.startupLayoutInvalid", FailureSeverityLevel.FATAL, FailureRecoveryAction.EXIT_PROCESS),
    /**
     * DATA DIRECTORY UNAVAILABLE classification within desktop system failure type.
     * <p>Desktop系统失败类型中的数据目录不可用分类。
     */
    DATA_DIRECTORY_UNAVAILABLE("desktop.startup.data-directory-unavailable", "startup", "desktop.error.dataDirectoryUnavailable", FailureSeverityLevel.FATAL, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DATABASE INITIALIZATION FAILED classification within desktop system failure type.
     * <p>Desktop系统失败类型中的数据库初始化失败分类。
     */
    DATABASE_INITIALIZATION_FAILED("desktop.startup.database-initialization-failed", "startup", "desktop.error.databaseInitializationFailed", FailureSeverityLevel.FATAL, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * UI INITIALIZATION FAILED classification within desktop system failure type.
     * <p>Desktop系统失败类型中的界面初始化失败分类。
     */
    UI_INITIALIZATION_FAILED("desktop.startup.ui-initialization-failed", "startup", "desktop.error.uiInitializationFailed", FailureSeverityLevel.FATAL, FailureRecoveryAction.RESTART_APPLICATION),
    /**
     * UNKNOWN RUNTIME FAILURE classification within desktop system failure type.
     * <p>Desktop系统失败类型中的未知运行时失败分类。
     */
    UNKNOWN_RUNTIME_FAILURE("desktop.runtime.unknown-failure", "runtime", "desktop.error.unknownRuntimeFailure", FailureSeverityLevel.ERROR, FailureRecoveryAction.RESTART_APPLICATION),
    /**
     * DIAGNOSTIC REPORT WRITE FAILED classification within desktop system failure type.
     * <p>Desktop系统失败类型中的诊断报告写入失败分类。
     */
    DIAGNOSTIC_REPORT_WRITE_FAILED("desktop.diagnostic.report-write-failed", "diagnostic", "desktop.error.diagnosticReportWriteFailed", FailureSeverityLevel.WARNING, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * RESOURCE EXHAUSTED classification within desktop system failure type.
     * <p>Desktop系统失败类型中的资源已耗尽分类。
     */
    RESOURCE_EXHAUSTED("desktop.runtime.resource-exhausted", "runtime", "desktop.error.resourceExhausted", FailureSeverityLevel.FATAL, FailureRecoveryAction.EXIT_PROCESS),
    /**
     * SHUTDOWN FAILED classification within desktop system failure type.
     * <p>Desktop系统失败类型中的关闭失败分类。
     */
    SHUTDOWN_FAILED("desktop.shutdown.failed", "shutdown", "desktop.error.shutdownFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.RESTART_APPLICATION);

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
     * Severity level assigned to the failure definition.
     * <p>分配给失败定义的严重级别。
     */
    private final FailureSeverityLevel severity;
    /**
     * Action required to recover from the classified failure.
     * <p>从已分类失败中恢复所需的动作。
     */
    private final FailureRecoveryAction recoveryAction;

    /**
     * Binds the supplied dependencies and state for desktop system failure type.
     * <p>为Desktop系统失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param severity severity level assigned to the failure definition / 分配给失败定义的严重级别
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    DesktopSystemFailureType(String code, String phase, String messageKey,
                             FailureSeverityLevel severity, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.severity = severity;
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
    @Override public String domain() { return "desktop"; }
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
     * Returns severity level assigned to the failure definition.
     * <p>返回分配给失败定义的严重级别。
     *
     * @return severity level assigned to the failure definition / 分配给失败定义的严重级别
     */
    @Override public FailureSeverityLevel severity() { return severity; }
    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}
