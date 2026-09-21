package gold.debug.windowstolinux.app.windows.update;

/**
 * Exact terminal desktop update status. / 精确桌面更新终态。
 */
public enum DesktopUpdateStatus {
    /**
     * SUCCEEDED classification within desktop update status.
     * <p>Desktop更新状态中的已成功分类。
     */
    SUCCEEDED,
    /**
     * PRECONDITION REJECTED classification within desktop update status.
     * <p>Desktop更新状态中的前提条件已拒绝分类。
     */
    PRECONDITION_REJECTED,
    /**
     * FAILED ROLLED BACK classification within desktop update status.
     * <p>Desktop更新状态中的失败已回滚回退分类。
     */
    FAILED_ROLLED_BACK,
    /**
     * MANUAL RECOVERY REQUIRED classification within desktop update status.
     * <p>Desktop更新状态中的人工恢复必需分类。
     */
    MANUAL_RECOVERY_REQUIRED
}
