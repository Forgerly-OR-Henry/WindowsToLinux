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

/** Renders the reviewed Spring Boot Gradle architecture. / 渲染经审阅的 Spring Boot Gradle 架构。 */
public final class GradleBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.SPRING_BOOT; }
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.GRADLE_WRAPPER); }

    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.SpringBoot)
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
