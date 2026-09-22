package gold.debug.windowstolinux.shared.standard.deploy.build.contract.spi;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Renders one reviewed project type through a fixed, bounded target-host build entrypoint. / 通过固定且有界的目标机构建入口渲染一种经审阅项目类型。
 */
public interface DeploymentBuildRenderer {
    /**
     * Returns the only project type owned by this renderer. / 返回此渲染器唯一负责的项目类型。
     *
     * @return the only project type owned by this renderer / 此渲染器唯一负责的项目类型
     */
    DeploymentProjectType projectType();

    /**
     * Returns the exact build-tool identities owned by this architecture renderer. / 返回此架构 Renderer 负责的精确构建工具身份。
     *
     * @return the exact build-tool identities owned by this architecture renderer / 此架构 Renderer 负责的精确构建工具身份
     */
    java.util.Set<DeploymentBuildToolType> buildTools();

    /**
     * Renders the complete safe build script. / 渲染完整安全构建脚本。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     */
    String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime, RemoteWorkspace workspace,
            BuildLimitConfiguration limits);
}
