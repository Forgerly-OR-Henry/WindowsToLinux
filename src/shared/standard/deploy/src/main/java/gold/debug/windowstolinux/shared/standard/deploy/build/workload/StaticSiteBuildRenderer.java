package gold.debug.windowstolinux.shared.standard.deploy.build.workload;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.NodePackageBuildScript;
import gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.SafeBuildScriptEnvelope;

/**
 * Renders pure or Node-built static-site artifacts without assuming a Node version. / 在不假设 Node 版本的情况下渲染纯静态或 Node 构建型站点产物。
 */
public final class StaticSiteBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the supported deployment project type. / 返回支持的部署项目类型。
     *
     * @return the supported deployment project type / 支持的部署项目类型
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.STATIC_SITE;
    }

    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public java.util.Set<DeploymentBuildToolType> buildTools() {
        return java.util.Set.of(DeploymentBuildToolType.STATIC_SITE_BUILD, DeploymentBuildToolType.NPM,
                DeploymentBuildToolType.PNPM, DeploymentBuildToolType.YARN);
    }

    /**
     * Renders the controlled output. / 渲染受控输出。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.StaticSite site)) {
            throw new IllegalArgumentException("Static-site renderer requires reviewed static-site inputs");
        }
        String command;
        if (facts.buildTool() == DeploymentBuildToolType.STATIC_SITE_BUILD) {
            if (site.nodeMajorVersion().isPresent()) {
                throw new IllegalArgumentException("Pure static sites must not declare a Node version");
            }
            String output = SafeBuildScriptEnvelope.shellQuote("./" + site.outputDirectory());
            command = """
                    test -f ./index.html
                    test -d %s
                    printf 'ARTIFACT=%%s\n' %s
                    """.formatted(output, output);
        } else {
            if (site.nodeMajorVersion().isEmpty()) {
                throw new IllegalArgumentException(
                        "Node-built static sites require an explicit reviewed Node major version");
            }
            command = NodePackageBuildScript.render(facts.buildTool(), site.nodeMajorVersion().getAsInt(), true,
                    site.outputDirectory());
        }
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
