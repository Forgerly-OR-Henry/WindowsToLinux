package gold.debug.windowstolinux.app.service.file;

import java.util.UUID;

import gold.debug.windowstolinux.app.file.source.SourceWorkDirectory;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;

/** Trusted transfer coordinates of a consumed original handle, never author-provided location metadata. / 已消费原句柄的可信转移坐标，绝不是作者提供的位置元数据。
 * @param id original workspace identity / 原工作目录身份
 * @param work exact original physical owner / 精确原物理所有者
 * @param lease original registered workspace lease / 原已登记工作目录占用
 * @param finish exact in-memory transfer confirmation / 精确内存转移确认 */
record PlatformWorkspaceTransfer(UUID id, SourceWorkDirectory work, ResourceLease lease, Confirmation finish) {
    /** Completes only original-handle in-memory revocation after durable transfer. / 持久转移后仅完成原句柄内存撤销。 */
    interface Confirmation {
        /** Drops actual old provider scope ownership without physical deletion. / 不物理删除地移除原生产方实际作用域归属。
         * @throws ApiException if original durable release remains uncertain / 原持久释放仍不确定时 */
        void complete() throws ApiException;
    }
}
