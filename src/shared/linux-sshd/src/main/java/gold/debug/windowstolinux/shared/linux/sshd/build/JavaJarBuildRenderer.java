package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders the reviewed pre-built Java JAR boundary. / 渲染经审阅的预构建 Java JAR 边界。 */
public final class JavaJarBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_JAR; }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.JavaJar javaJar)
                || facts.buildTool() != DeploymentBuildTool.JAVA) {
            throw new IllegalArgumentException("Java JAR renderer requires reviewed Java inputs");
        }
        String command = """
                artifact=%s
                test -f "$artifact"
                test ! -L "$artifact"
                printf 'ARTIFACT=%%s\n' "$artifact"
                """.formatted(SafeBuildScriptEnvelope.shellQuote("./" + javaJar.jarRelativePath()));
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
