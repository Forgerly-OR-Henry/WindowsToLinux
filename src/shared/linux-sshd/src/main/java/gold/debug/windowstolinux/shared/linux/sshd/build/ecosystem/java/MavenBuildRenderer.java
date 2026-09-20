package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/** Renders reviewed Spring Boot Maven and Maven Wrapper entries. / 渲染经审阅的 Spring Boot Maven 与 Maven Wrapper 入口。 */
public final class MavenBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.SPRING_BOOT; }
    @Override public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.MAVEN, DeploymentBuildToolType.MAVEN_WRAPPER);
    }

    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.SpringBoot) || !buildTools().contains(facts.buildTool())) {
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
                """ + downloadRetry() + build + SpringBootArtifactBuildScript.verify("./target"));
    }

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
