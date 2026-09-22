package gold.debug.windowstolinux.shared.linux.build;

import gold.debug.windowstolinux.shared.linux.command.RemoteCommandExecutor;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet;

/** Optional standard build capability injected by the composition root. / 由装配入口注入的可选标准构建能力。 */
public interface RemoteBuildPort {
    /** Prepares selected tools. / 准备所选工具。
     * @param facts reviewed facts / 已审阅事实
     * @param runtime reviewed runtime / 已审阅运行方式
     * @param limits execution limits / 执行限制
     * @return verified tool selections / 已验证工具选择
     * @throws LinuxOperationException on remote failure / 远端失败时
     */
    ResolvedToolchainSet prepare(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            BuildLimitConfiguration limits) throws LinuxOperationException;

    /** Builds a standard candidate. / 构建标准候选。
     * @param facts reviewed facts / 已审阅事实
     * @param runtime reviewed runtime / 已审阅运行方式
     * @param workspace owned candidate / 所属候选
     * @param limits execution limits / 执行限制
     * @param configuration build inputs / 构建输入
     * @return observed result / 观测结果
     * @throws LinuxOperationException on remote failure / 远端失败时
     */
    DeploymentBuildResult build(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            RemoteWorkspace workspace, BuildLimitConfiguration limits, RemoteBuildEnvironment configuration)
            throws LinuxOperationException;
    /** Creates a strategy for one authenticated session. / 为单个已认证会话创建策略。 */
    @FunctionalInterface
    interface Factory {
        /** Binds mechanical execution without opening another session. / 绑定机械执行而不另开会话。
         * @param commands command transport / 命令传输
         * @param username authenticated identity / 已认证身份
         * @return session-scoped strategy / 会话范围策略
         */
        RemoteBuildPort create(RemoteCommandExecutor commands, String username);
    }
}
