package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.BuildConfigurationEnvironmentRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.CargoBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.CmakeBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.DotNetSdkBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.GoBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.kotlin.KotlinCompilerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.kotlin.KotlinGradleBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.php.ComposerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.php.PhpCliBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.ruby.BundlerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.ruby.RubyCliBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java.GradleBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java.JavaJarBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java.JdkBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java.MavenBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.node.NpmBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.node.PnpmBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.node.YarnBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python.PipBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python.PipenvBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python.PoetryBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.python.UvBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.extension.registry.DeploymentBuildRendererRegistry;
import gold.debug.windowstolinux.shared.linux.sshd.build.workload.ContainerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.build.workload.StaticSiteBuildRenderer;

import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes an implementation-rendered, resource-bounded target-host build.
 *
 * <p>执行由实现渲染并受资源限制的目标机构建。
 */
public final class DeploymentBuildExecutor {
    private final SshCommandExecutor commands;
    private final String username;
    private final DeploymentBuildRendererRegistry renderers;

    /** Creates the executor for one authenticated SSH account. / 为一个已认证 SSH 账户创建执行器。 */
    public DeploymentBuildExecutor(SshCommandExecutor commands, String username) {
        this(commands, username, List.of(new GradleBuildRenderer(), new MavenBuildRenderer(),
                new JavaJarBuildRenderer(), new JdkBuildRenderer(), new NpmBuildRenderer(), new PnpmBuildRenderer(),
                new YarnBuildRenderer(), new PipBuildRenderer(), new PipenvBuildRenderer(), new PoetryBuildRenderer(),
                new UvBuildRenderer(), new StaticSiteBuildRenderer(), new ContainerBuildRenderer(),
                new GoBuildRenderer(), new CargoBuildRenderer(), new DotNetSdkBuildRenderer(),
                new KotlinGradleBuildRenderer(), new KotlinCompilerBuildRenderer(), new ComposerBuildRenderer(),
                new PhpCliBuildRenderer(), new BundlerBuildRenderer(), new RubyCliBuildRenderer(),
                new CmakeBuildRenderer()));
    }

    DeploymentBuildExecutor(SshCommandExecutor commands, String username, List<DeploymentBuildRenderer> renderers) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.username = Objects.requireNonNull(username, "username");
        this.renderers = new DeploymentBuildRendererRegistry(renderers);
    }

    /**
     * Builds one reviewed source archive with the fixed entrypoint selected by its facts.
     *
     * <p>使用其事实选定的固定入口构建一个经审阅的源码归档。
     */
    public DeploymentBuildResult build(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                       RemoteWorkspace workspace, BuildLimitConfiguration limits, ConfigurationSnapshot configuration)
            throws LinuxOperationException {
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        workspace = Objects.requireNonNull(workspace, "workspace");
        limits = Objects.requireNonNull(limits, "limits");
        configuration = Objects.requireNonNull(configuration, "configuration");
        if (limits.runAsRoot() != "root".equals(username)) {
            throw LinuxOperationException.localized("linux.error.rootBuildRequiresRootSession",
                    "Root build approval must match the authenticated SSH account");
        }
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("runtime must match the analyzed project type");
        }
        if (!facts.applicationId().equals(configuration.applicationId())) {
            throw new IllegalArgumentException("build configuration must match the analyzed application");
        }
        DeploymentBuildRenderer renderer = renderers.require(facts.projectType(), facts.buildTool());
        String script = BuildConfigurationEnvironmentRenderer.render(configuration)
                + renderer.render(facts, runtime, workspace, limits);
        String command = "env -i PATH=/usr/local/bin:/usr/bin:/bin HOME="
                + SshCommandExecutor.quote(workspace.candidateRoot() + "/mutable/home")
                + " /bin/bash -lc " + SshCommandExecutor.quote(script);
        var result = commands.exec(command, Duration.ofSeconds(limits.timeoutSeconds() + 30L), true);
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        if (!result.succeeded()) {
            String boundary = values.get("BUILD_LIMIT");
            String evidence = "workspace".equals(boundary)
                    ? "Target candidate exceeded the confirmed workspace limit"
                    : "output".equals(boundary) ? "Target build output exceeded the confirmed output limit"
                    : "Target " + facts.projectType() + " build failed: " + result.failureEvidence();
            return DeploymentBuildResult.failed(workspace.sourceSha256(), evidence);
        }
        if (!facts.buildTool().name().equals(values.get("BUILD_TOOL"))) {
            return DeploymentBuildResult.failed(workspace.sourceSha256(),
                    "Target build entrypoint did not report the reviewed fixed build tool");
        }
        return DeploymentBuildResult.succeeded(workspace.sourceSha256(),
                "Target " + facts.projectType() + " build completed through " + facts.buildTool());
    }
}
