package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.sshd.build.config.BuildConfigEnvironment;
import gold.debug.windowstolinux.shared.linux.sshd.build.registry.DeploymentBuildRendererRegistry;
import gold.debug.windowstolinux.shared.linux.sshd.build.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.jvm.build.jar.JavaJarBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.jvm.build.springboot.SpringBootBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.node.build.NodeBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.python.build.PythonBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.dotnet.build.DotNetBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.go.build.GoBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.jvm.build.kotlin.KotlinBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.php.build.PhpBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.ruby.build.RubyBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.ecosystem.rust.build.RustBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.workload.container.ContainerBuildRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.workload.staticweb.StaticSiteBuildRenderer;

import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

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
        this(commands, username, List.of(new SpringBootBuildRenderer(), new JavaJarBuildRenderer(), new NodeBuildRenderer(),
                new PythonBuildRenderer(), new StaticSiteBuildRenderer(), new ContainerBuildRenderer(),
                new GoBuildRenderer(), new RustBuildRenderer(), new DotNetBuildRenderer(),
                new KotlinBuildRenderer(), new PhpBuildRenderer(), new RubyBuildRenderer()));
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
                                       RemoteWorkspace workspace, BuildLimits limits, ConfigurationSnapshot configuration)
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
        DeploymentBuildRenderer renderer = renderers.require(facts.projectType());
        String script = BuildConfigEnvironment.render(configuration)
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
