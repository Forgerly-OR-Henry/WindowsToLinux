package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol;

import java.time.Duration;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;

/**
 * Controls only candidate workspace creation and cleanup through fixed helper verbs. / 仅通过固定 helper 动词控制候选工作区创建与清理。
 */
public final class CandidateWorkspaceExecutor {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Creates a candidate workspace controller. / 创建候选工作区控制器。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public CandidateWorkspaceExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Creates the exact reviewed candidate workspace. / 创建精确的经审阅候选工作区。
     *
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param maxWorkspaceBytes max workspace bytes / 最大工作区字节
     * @return the exact reviewed candidate workspace / 精确的经审阅候选工作区
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public RemoteStepResult create(RemoteWorkspace workspace, long maxWorkspaceBytes) throws LinuxOperationException {
        if (maxWorkspaceBytes < 64L * 1024 * 1024 || maxWorkspaceBytes > 128L * 1024 * 1024 * 1024) {
            throw new IllegalArgumentException("candidate workspace budget is outside the supported hard limit");
        }
        var scope = gold.debug.windowstolinux.shared.linux.transfer.DeploymentRemoteTaskScope.current();
        String task = scope.map(value -> value.preparing(workspace)).orElse("");
        var result = commands.exec(command(task.isEmpty() ? "candidate-create" : "candidate-create-task", workspace)
                + " " + maxWorkspaceBytes
                + (task.isEmpty() ? "" : " " + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(task)),
                Duration.ofMinutes(5), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper created the managed candidate directory"
                : "Controlled helper could not create the managed candidate directory: " + result.failureEvidence());
    }

    /**
     * Cleans the exact reviewed candidate workspace. / 清理精确的经审阅候选工作区。
     *
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteStepResult cleanup(RemoteWorkspace workspace) throws LinuxOperationException {
        var result = commands.exec(command("candidate-cleanup", workspace), Duration.ofSeconds(30), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper cleaned the current candidate directory"
                : "Controlled helper could not clean the current candidate directory: " + result.failureEvidence());
    }

    /**
     * Prepares a restore candidate without authorizing project builds. / 准备恢复候选，不授权项目构建。
     *
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @return constructed or resolved remote step result / 构造或解析得到的远端步骤结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public RemoteStepResult createRestore(RemoteWorkspace workspace) throws LinuxOperationException {
        var result = commands.exec(command("candidate-restore-create", workspace), Duration.ofSeconds(20), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.failureEvidence());
    }

    /** Reads a task candidate without creating or adopting resources. / 读取任务候选项，不创建或接管资源。
     * @param workspace candidate identity / 候选身份
     * @param task task identity / 任务身份
     * @return bounded verified facts / 有界已验证事实
     * @throws LinuxOperationException if observation fails / 观测失败时
     */
    public java.util.Map<String, String> inspectTask(RemoteWorkspace workspace, String task)
            throws LinuxOperationException {
        gold.debug.windowstolinux.shared.linux.transfer.DeploymentRemoteTaskScope.requireTask(task);
        var result = commands.exec(
                command("candidate-query-task", workspace) + " "
                        + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(task),
                Duration.ofSeconds(20), true);
        var values = gold.debug.windowstolinux.shared.linux.command.CommandText.lines(result.output());
        if (!result.succeeded()
                || !java.util.Set.of("absent", "owned").contains(values.getOrDefault("CANDIDATE_STATE", "")))
            throw LinuxOperationException.create(
                    gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.CANDIDATE_PREPARATION_FAILED,
                    "Task candidate could not be safely observed");
        return java.util.Map.of("candidate", workspace.candidateId(), "state", values.get("CANDIDATE_STATE"),
                "buildActive", values.getOrDefault("BUILD_ACTIVE", "unknown"));
    }

    /** Uses the existing process-stop and volume-cleanup implementation after exact task binding. / 精确任务绑定后使用既有进程停止及卷清理实现。
     * @param workspace candidate identity / 候选身份
     * @param task task identity / 任务身份
     * @return verified cleanup result / 已验证清理结果
     * @throws LinuxOperationException if remote execution fails / 远端执行失败时
     */
    public RemoteStepResult cleanupTask(RemoteWorkspace workspace, String task) throws LinuxOperationException {
        gold.debug.windowstolinux.shared.linux.transfer.DeploymentRemoteTaskScope.requireTask(task);
        var result = commands.exec(
                command("candidate-cleanup-task", workspace) + " "
                        + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(task),
                Duration.ofMinutes(2), true);
        return new RemoteStepResult(
                result.succeeded() && "1".equals(gold.debug.windowstolinux.shared.linux.command.CommandText
                        .lines(result.output()).get("CANDIDATE_CLEANED")),
                result.timedOut(), "Task-bound candidate cleanup; no application data paths are accepted");
    }

    /**
     * Renders a fixed helper invocation with individually quoted reviewed arguments; does not execute it.
     * <p>使用逐项引用的已审阅参数渲染固定 helper 调用，不执行该调用。
     *
     * @param verb verb / 操作动词
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @return command text / 命令文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String command(String verb, RemoteWorkspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        return gold.debug.windowstolinux.shared.linux.command.CommandText.quote(ManagedHelperBundle.PATH) + ' '
                + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(verb) + ' '
                + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(workspace.applicationId()) + ' '
                + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(workspace.candidateId());
    }
}
