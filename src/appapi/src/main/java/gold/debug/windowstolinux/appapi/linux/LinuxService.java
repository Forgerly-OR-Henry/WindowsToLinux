package gold.debug.windowstolinux.appapi.linux;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;

import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

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
    RemoteSession connect(ServerId server, TaskContext task) throws ApiException;

    /** Authenticates a fresh connection only after checking original task, endpoint, account and wire trust. / 核对原任务、端点、账户及 wire 信任后才认证一个新连接。
     * @param expected fixed original non-secret target, with absent key only on initial first trust / 固定原非秘密目标，仅首次信任可缺密钥
     * @param task authentic original task, including explicit original recovery / 真实原任务，包括明确原恢复
     * @return independently owned fresh authenticated session / 独立所属的新认证会话
     * @throws ApiException if the fixed target differs or this implementation cannot enforce it / 固定目标不符或实现无法强制此合同 */
    default RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget expected,
                                  TaskContext task) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "SSH_FIXED_TARGET_UNAVAILABLE",
            "This Linux capability cannot enforce original target expectations", java.util.Map.of(), java.util.Optional.empty()));
    }

    /** Lists outstanding transfers owned by the original executing or recovering task. / 列出原执行或恢复任务所属的未完成传输。
     * @param task authentic original task / 真实原任务
     * @return immutable original transfer descriptions / 不可变原传输描述
     * @throws ApiException if ownership or stored facts cannot be verified / 无法核验归属或存储事实时 */
    java.util.List<TransferCheckpoint> pendingTransfers(TaskContext task) throws ApiException;
}
