package gold.debug.windowstolinux.api.linux;

import gold.debug.windowstolinux.api.server.ServerRef;
import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

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
}
