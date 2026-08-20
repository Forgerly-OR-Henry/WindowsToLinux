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

/** Renders the dependency-free JDK source architecture. / 渲染无依赖 JDK 源码架构。 */
public final class JdkBuildRenderer implements DeploymentBuildRenderer {
    /** Returns the Java source project type. / 返回 Java 源码项目类型。 */
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.JAVA_SOURCE; }
    /** Returns the JDK build identity. / 返回 JDK 构建身份。 */
    @Override public Set<DeploymentBuildToolType> buildTools() { return Set.of(DeploymentBuildToolType.JDK); }

    /** Renders fixed javac and jar commands with deterministic output paths. / 以确定输出路径渲染固定 javac 与 jar 命令。 */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.JavaSource javaSource)
                || facts.buildTool() != DeploymentBuildToolType.JDK) {
            throw new IllegalArgumentException("JDK renderer requires reviewed Java source inputs");
        }
        String sourceRoot = SafeBuildScriptEnvelope.shellQuote("./" + javaSource.sourceRoot());
        String mainClass = SafeBuildScriptEnvelope.shellQuote(javaSource.mainClass());
        String command = """
                command -v javac >/dev/null
                command -v jar >/dev/null
                javac -version 2>&1 | grep -Eq '^javac 21([.]|$)'
                jar --version 2>&1 | grep -Eq '^jar 21([.]|$)'
                source_root=%s
                main_class=%s
                test -d "$source_root"
                test -f ./windowstolinux-java.properties
                mapfile -d '' -t sources < <(find -P "$source_root" -type f -name '*.java' -print0 | LC_ALL=C sort -z)
                test "${#sources[@]}" -ge 1
                mkdir -p ./.w2l/java/classes
                run javac --release 21 -proc:none -encoding UTF-8 -d ./.w2l/java/classes "${sources[@]}"
                main_path="./.w2l/java/classes/${main_class//.//}.class"
                test -f "$main_path"
                printf 'Manifest-Version: 1.0\nMain-Class: %%s\n\n' "$main_class" > ./.w2l/java/MANIFEST.MF
                run jar --create --file ./.w2l/java/app.jar --date=1980-01-01T00:00:02Z \
                  --manifest ./.w2l/java/MANIFEST.MF -C ./.w2l/java/classes .
                test -f ./.w2l/java/app.jar
                test ! -L ./.w2l/java/app.jar
                printf 'ARTIFACT=%%s\n' ./.w2l/java/app.jar
                """.formatted(sourceRoot, mainClass);
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
