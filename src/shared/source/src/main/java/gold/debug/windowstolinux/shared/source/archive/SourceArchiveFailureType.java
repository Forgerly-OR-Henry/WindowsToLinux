package gold.debug.windowstolinux.shared.source.archive;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by source-boundary validation and deterministic archiving. / 源码边界校验与确定性归档持有的失败类型。
 */
public enum SourceArchiveFailureType implements FailureDefinition {
    /**
     * SOURCE DIRECTORY INVALID classification within source archive failure type.
     * <p>源码归档失败类型中的源码目录无效分类。
     */
    SOURCE_DIRECTORY_INVALID("source.validation.directory-invalid", "validation", "source.error.directoryInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * DESTINATION INVALID classification within source archive failure type.
     * <p>源码归档失败类型中的目的地无效分类。
     */
    DESTINATION_INVALID("source.validation.destination-invalid", "validation", "source.error.destinationInvalid",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * BOUNDARY ESCAPE classification within source archive failure type.
     * <p>源码归档失败类型中的边界转义分类。
     */
    BOUNDARY_ESCAPE("source.validation.boundary-escape", "validation", "source.error.boundaryEscape",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * SYMBOLIC LINK REJECTED classification within source archive failure type.
     * <p>源码归档失败类型中的符号链接已拒绝分类。
     */
    SYMBOLIC_LINK_REJECTED("source.validation.symbolic-link-rejected", "validation",
            "source.error.symbolicLinkRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ENTRY UNREADABLE classification within source archive failure type.
     * <p>源码归档失败类型中的条目不可读分类。
     */
    ENTRY_UNREADABLE("source.collection.entry-unreadable", "collection", "source.error.entryUnreadable",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * ENTRY CHANGED classification within source archive failure type.
     * <p>源码归档失败类型中的条目已变化分类。
     */
    ENTRY_CHANGED("source.archive.entry-changed", "archive", "source.error.entryChanged", FailureRecoveryAction.RETRY),
    /**
     * PATH TOO LONG classification within source archive failure type.
     * <p>源码归档失败类型中的路径过于长整型分类。
     */
    PATH_TOO_LONG("source.archive.path-too-long", "archive", "source.error.pathTooLong",
            FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * WRITE FAILED classification within source archive failure type.
     * <p>源码归档失败类型中的写入失败分类。
     */
    WRITE_FAILED("source.archive.write-failed", "archive", "source.error.writeFailed", FailureRecoveryAction.CLEANUP),
    /**
     * HASH FAILED classification within source archive failure type.
     * <p>源码归档失败类型中的哈希失败分类。
     */
    HASH_FAILED("source.archive.hash-failed", "verification", "source.error.hashFailed", FailureRecoveryAction.CLEANUP),
    /**
     * CLEANUP FAILED classification within source archive failure type.
     * <p>源码归档失败类型中的清理失败分类。
     */
    CLEANUP_FAILED("source.archive.cleanup-failed", "cleanup", "source.error.cleanupFailed",
            FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    /**
     * INTERRUPTED classification within source archive failure type.
     * <p>源码归档失败类型中的已中断分类。
     */
    INTERRUPTED("source.archive.interrupted", "archive", "source.error.interrupted", FailureRecoveryAction.CLEANUP);

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
     * Binds the supplied dependencies and state for source archive failure type.
     * <p>为源码归档失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    SourceArchiveFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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
    @Override
    public String code() {
        return code;
    }

    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    @Override
    public String domain() {
        return "source";
    }

    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    @Override
    public String phase() {
        return phase;
    }

    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    @Override
    public String messageKey() {
        return messageKey;
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
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    @Override
    public FailureRecoveryAction recoveryAction() {
        return recoveryAction;
    }
}
