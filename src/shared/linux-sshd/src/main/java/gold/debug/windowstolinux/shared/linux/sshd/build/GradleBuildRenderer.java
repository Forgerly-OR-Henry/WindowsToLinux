package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders the fixed Gradle Wrapper Spring Boot build. / 渲染固定的 Gradle Wrapper Spring Boot 构建。 */
public final class GradleBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.GRADLE_SPRING_BOOT; }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.GradleSpringBoot)
                || facts.buildTool() != DeploymentBuildTool.GRADLE_WRAPPER) {
            throw new IllegalArgumentException("Gradle renderer requires reviewed Gradle Wrapper Spring Boot inputs");
        }
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, """
                test -f ./gradlew
                test -f ./gradle/wrapper/gradle-wrapper.properties
                chmod 700 -- ./gradlew
                run ./gradlew --no-daemon -x test bootJar
                mapfile -t artifacts < <(find ./build/libs -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' -printf '%p\n' | LC_ALL=C sort)
                test "${#artifacts[@]}" -eq 1
                printf 'ARTIFACT=%s\n' "${artifacts[0]}"
                """);
    }
}
