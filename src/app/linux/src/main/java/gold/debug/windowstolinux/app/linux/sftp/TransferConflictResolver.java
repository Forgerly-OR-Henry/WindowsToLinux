package gold.debug.windowstolinux.app.linux.sftp;

import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;

/** Host-owned explicit same-name decision; independent of operation authorization. / 宿主持有的明确同名决定，独立于操作授权。 */
@FunctionalInterface
public interface TransferConflictResolver {
    /** Rechecks a host-preplanned target even when no collision is observed. / 未观测冲突时也复核宿主预计划目标。
     * @throws Exception if the original target facts changed / 原目标事实改变时 */
    default void validateTarget() throws Exception {
    }

    /** Presents a conflict within the original task's human-interaction boundary. / 在原任务人工交互边界内呈现冲突。
     * @param conflict actual file conflict / 实际文件冲突
     * @return explicit user choice / 明确用户选择
     * @throws Exception if user interaction or its cleanup fails / 用户交互或清理失败时 */
    TransferConflictChoice resolve(TransferConflict conflict) throws Exception;
}
