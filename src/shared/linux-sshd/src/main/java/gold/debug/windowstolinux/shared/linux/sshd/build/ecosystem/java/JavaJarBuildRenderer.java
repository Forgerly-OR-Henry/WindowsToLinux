package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java;

import gold.debug.windowstolinux.shared.linux.sshd.build.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders the reviewed pre-built Java JAR boundary. / 渲染经审阅的预构建 Java JAR 边界。 */
public final class JavaJarBuildRenderer implements DeploymentBuildRenderer {
    /** Returns the supported deployment project type. / 返回支持的部署项目类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_JAR; }

    /** Renders the controlled output. / 渲染受控输出。 */
    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.JavaJar javaJar)
                || facts.buildTool() != DeploymentBuildToolType.JAVA) {
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
