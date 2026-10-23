package gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java;

import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.contract.DeploymentBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;

/**
 * Renders the reviewed Spring Boot Gradle architecture. / 渲染经审阅的 Spring Boot Gradle 架构。
 */
public final class GradleBuildWriter implements DeploymentBuildWriter {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.SPRING_BOOT;
    }

    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.GRADLE_WRAPPER);
    }

    /**
     * Renders gradle build as text without executing the rendered command.
     * <p>渲染Gradle构建为文本，不执行所渲染命令。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeDefinition runtime,
                         DeploymentRemoteWorkspace workspace, DeploymentBuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeDefinition.SpringBoot)
            || facts.buildTool() != DeploymentBuildToolType.GRADLE_WRAPPER) {
            throw new IllegalArgumentException("Gradle renderer requires reviewed Spring Boot Gradle inputs");
        }
        String command = """
            test -f ./gradlew
            test -f ./gradle/wrapper/gradle-wrapper.properties
            test -f ./gradle/wrapper/gradle-wrapper.jar
            chmod 700 -- ./gradlew
            run ./gradlew --no-daemon -x test bootJar "${java_gradle_arguments[@]}"
            """ + SpringBootArtifactBuildScript.verify("./build/libs");
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
