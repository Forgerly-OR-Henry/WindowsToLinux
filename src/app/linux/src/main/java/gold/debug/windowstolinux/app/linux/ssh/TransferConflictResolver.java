package gold.debug.windowstolinux.app.linux.ssh;

import gold.debug.windowstolinux.appapi.linux.TransferConflict;
import gold.debug.windowstolinux.appapi.linux.TransferConflictChoice;

/** Host-owned explicit same-name decision; independent of operation authorization. / 宿主持有的明确同名决定，独立于操作授权。 */
@FunctionalInterface
public interface TransferConflictResolver {
    /** Presents a conflict within the original task's human-interaction boundary. / 在原任务人工交互边界内呈现冲突。
     * @param conflict actual file conflict / 实际文件冲突
     * @return explicit user choice / 明确用户选择
     * @throws Exception if user interaction or its cleanup fails / 用户交互或清理失败时 */
    TransferConflictChoice resolve(TransferConflict conflict) throws Exception;
}
