package gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.node;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.NodePackageBuildScript;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;

/**
 * Shares only the invariant Node service envelope across named architecture renderers. / 仅在具名架构 Renderer 间共享不变的 Node 服务外壳。
 */
final class NodeArchitectureBuildWriter {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private NodeArchitectureBuildWriter() {
    }

    /**
     * Renders node architecture build as text without executing the rendered command.
     * <p>渲染节点架构构建为文本，不执行所渲染命令。
     *
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    static String render(DeploymentBuildToolType expected, DeploymentProjectFacts facts,
                         DeploymentRuntimeDefinition runtime, DeploymentRemoteWorkspace workspace, DeploymentBuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeDefinition.NodeService node) || facts.buildTool() != expected) {
            throw new IllegalArgumentException("Node architecture renderer requires matching reviewed inputs");
        }
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits,
            NodePackageBuildScript.render(expected, node.nodeMajorVersion(), false, null));
    }
}
