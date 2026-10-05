package gold.debug.windowstolinux.appapi.task.action;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Host-issued delegation covering only its original task callback. / 仅覆盖原任务回调的宿主签发委托。
 * Closing revokes permission before releasing actual occupancy; it never supplies a command grant. / 关闭先撤销权限再释放实际占用；不提供命令许可。 */
public interface WorkflowGrant extends AutoCloseable {
    /** Revokes this original delegation and confirms its actual resource release. / 撤销此原委托并确认实际资源释放。
     * @throws ApiException if release remains uncertain; the original occupancy is retained for retry / 释放仍不确定时，保留原占用供重试 */
    @Override
    void close() throws ApiException;
}
