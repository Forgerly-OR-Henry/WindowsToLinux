package gold.debug.windowstolinux.app.linux.ssh;

import gold.debug.windowstolinux.appapi.task.ResourceLease;

/** Host journal required before the first staging-file mutation. / 首次暂存文件变更前必需的宿主日志。
 */
@FunctionalInterface
public interface TransferJournal {
    /** Atomically retains a file lease and its original recovery facts. / 原子保留文件占用及其原恢复事实。
     * @param plan verified source and selected destination / 已核验源及选定目标
     * @return lease released only after verified publication or confirmed no mutation / 仅在已核验发布或确认无变更后释放的占用
     * @throws Exception if durable ownership cannot be confirmed / 无法确认持久归属时 */
    ResourceLease prepare(TransferPlan plan) throws Exception;
}
