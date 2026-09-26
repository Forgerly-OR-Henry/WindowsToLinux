package gold.debug.windowstolinux.appapi.linux;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/**
 * Task-authorized SSH access with host trust verification. / 绑定任务授权及主机信任校验的 SSH 能力。
 */
public interface LinuxService {

    /**
     * Authenticates the selected server under the original task and trust decision. / 在原任务及信任决定下认证选定服务器。
     * @param server selected platform server reference / 选定的平台服务器引用
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    RemoteSession connect(ServerRef server, TaskContext task) throws ApiException;

    /** Lists outstanding transfers owned by the original executing or recovering task. / 列出原执行或恢复任务所属的未完成传输。
     * @param task authentic original task / 真实原任务
     * @return immutable original transfer descriptions / 不可变原传输描述
     * @throws ApiException if ownership or stored facts cannot be verified / 无法核验归属或存储事实时 */
    java.util.List<TransferCheckpoint> pendingTransfers(TaskContext task) throws ApiException;
}
