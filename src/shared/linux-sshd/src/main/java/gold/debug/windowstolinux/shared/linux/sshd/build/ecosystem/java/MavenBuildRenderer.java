package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java;

import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
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
                    run ./mvnw -B -DskipTests package
                    """;
            case MAVEN -> """
                    command -v mvn >/dev/null 2>&1
                    run mvn -B -DskipTests package
                    """;
            default -> throw new IllegalArgumentException("Maven renderer requires a Maven build identity");
        };
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits,
                build + SpringBootArtifactBuildScript.verify("./target"));
    }
}
