package gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.java;

import java.util.Set;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.SafeBuildScriptEnvelope;

/**
 * Renders reviewed Spring Boot Maven and Maven Wrapper entries. / 渲染经审阅的 Spring Boot Maven 与 Maven Wrapper 入口。
 */
public final class MavenBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.SPRING_BOOT;
    }

    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.MAVEN, DeploymentBuildToolType.MAVEN_WRAPPER);
    }

    /**
     * Renders maven build as text without executing the rendered command.
     * <p>渲染Maven构建为文本，不执行所渲染命令。
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
        if (!(runtime instanceof DeploymentRuntimeSpecification.SpringBoot)
                || !buildTools().contains(facts.buildTool())) {
            throw new IllegalArgumentException("Maven renderer requires reviewed Spring Boot Maven inputs");
        }
        String build = switch (facts.buildTool()) {
            case MAVEN_WRAPPER -> """
                    test -f ./mvnw
                    test -f ./.mvn/wrapper/maven-wrapper.properties
                    chmod 700 -- ./mvnw
                    run ./mvnw -v
                    maven_package ./mvnw -B "${maven_toolchain_arguments[@]}" -DskipTests package
                    """;
            case MAVEN -> """
                    command -v mvn >/dev/null 2>&1
                    run mvn -v
                    maven_package mvn -B "${maven_toolchain_arguments[@]}" -DskipTests package
                    """;
            default -> throw new IllegalArgumentException("Maven renderer requires a Maven build identity");
        };
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits,
                """
                        maven_toolchain_arguments=()
                        if [ -n "${WTL_JAVA_HOME:-}" ]; then
                          toolchains="$mutable/home/wtl-toolchains.xml"
                          printf '<toolchains><toolchain><type>jdk</type><provides><version>%s</version></provides><configuration><jdkHome>%s</jdkHome></configuration></toolchain></toolchains>\\n' "$WTL_JAVA_BRANCH" "$WTL_JAVA_HOME" > "$toolchains"
                          maven_toolchain_arguments=(--global-toolchains "$toolchains" --toolchains "$toolchains" "-Dmaven.compiler.executable=$WTL_JAVA_HOME/bin/javac" -Dmaven.compiler.fork=true)
                        fi
                        """
                        + downloadRetry() + build + SpringBootArtifactBuildScript.verify("./target"));
    }

    /**
     * Downloads retry.
     * <p>下载重试。
     *
     * @return download retry text / 下载重试文本
     */
    static String downloadRetry() {
        return """
                maven_package() {
                  local attempt status log="$mutable/maven-download-attempt.log"
                  local -a pipeline_status
                  for attempt in 1 2; do
                    if "$@" 2>&1 | tee "$log"; then rm -f -- "$log"; return 0
                    else pipeline_status=("${PIPESTATUS[@]}"); fi
                    status="${pipeline_status[0]}"
                    if [ "${pipeline_status[1]}" -ne 0 ]; then rm -f -- "$log"; return "${pipeline_status[1]}"; fi
                    if [ "$attempt" -eq 2 ] || ! grep -Eq '^\\[ERROR\\].*Could not transfer artifact.*(Premature end of Content-Length|Connection reset|Read timed out)' "$log"; then
                      rm -f -- "$log"; return "$status"
                    fi
                    printf 'BUILD_DOWNLOAD_RETRY=maven:%s/2\\n' "$attempt"
                    sleep 2
                  done
                }
                """;
    }
}
