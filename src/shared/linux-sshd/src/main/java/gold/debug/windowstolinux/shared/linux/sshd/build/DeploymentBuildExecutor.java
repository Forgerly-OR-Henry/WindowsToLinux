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

import gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
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
    private final gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog toolchainCatalog =
            gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog.defaults();
    private final java.util.Map<String, gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet> prepared = new java.util.HashMap<>();

    public gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet prepare(
            DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime, BuildLimitConfiguration limits)
            throws LinuxOperationException {
        if (!"root".equals(username) || limits.runAsRoot()) throw LinuxOperationException.create(
                LinuxOperationFailureType.ROOT_BUILD_REQUIRES_ROOT_SESSION, "Root management with restricted builds is required");
        String key = facts.applicationId() + ":" + runtime + ":" + facts.toolchainRequirements();
        if (!prepared.containsKey(key)) {
            String engine = runtime instanceof DeploymentRuntimeSpecification.Container container
                    ? container.engine().name().toLowerCase(java.util.Locale.ROOT) : "ordinary";
            var preflight = commands.execProtocol(SshCommandExecutor.quote(
                    gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.PATH)
                    + " build-preflight " + engine, Duration.ofSeconds(30), true);
            if (!preflight.succeeded()) throw LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED,
                    "Build isolation is unavailable before source upload: " + preflight.failureEvidence());
            prepared.put(key,
                new gold.debug.windowstolinux.shared.linux.sshd.toolchain.ManagedToolchainPreparer(commands, toolchainCatalog)
                        .prepare(gold.debug.windowstolinux.shared.linux.build.ProjectToolchainRequirements.from(facts, runtime),
                                0, limits.timeoutSeconds()));
        }
        return prepared.get(key);
    }

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
                                       RemoteWorkspace workspace, BuildLimitConfiguration limits, RemoteBuildEnvironment configuration)
            throws LinuxOperationException {
        long deadline = System.nanoTime() + Duration.ofSeconds(limits.timeoutSeconds()).toNanos();
        var tools = prepare(facts, runtime, limits);
        var result = execute(facts, runtime, workspace, limits, configuration, tools, deadline);
        var requirements = gold.debug.windowstolinux.shared.linux.build.ProjectToolchainRequirements.from(facts, runtime);
        if (!result.succeeded() && BuildCompatibilityPolicy.retryable(result.evidence())
                && deadline - System.nanoTime() > Duration.ofSeconds(60).toNanos()
                && tools.selections().stream().anyMatch(s -> {
                    var candidates = toolchainCatalog.candidates(s.requirement());
                    return candidates.size() > 1 && s.version().branch().equals(candidates.getFirst().version());
                })) {
            var second = new gold.debug.windowstolinux.shared.linux.sshd.toolchain.ManagedToolchainPreparer(commands, toolchainCatalog)
                    .prepare(requirements, 1, (int) Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toSeconds()));
            result = execute(facts, runtime, workspace, limits, configuration, second, deadline);
        }
        if (!result.succeeded() && BuildCompatibilityPolicy.retryable(result.evidence()))
            return new DeploymentBuildResult(false, result.sourceSha256(),
                    "TOOLCHAIN_FAILURE=unsupported:compatible catalog candidates exhausted; " + result.evidence(), result.toolchains());
        return result;
    }

    private DeploymentBuildResult execute(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            RemoteWorkspace workspace, BuildLimitConfiguration limits, RemoteBuildEnvironment configuration,
            gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet tools, long deadline) throws LinuxOperationException {
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        workspace = Objects.requireNonNull(workspace, "workspace");
        limits = Objects.requireNonNull(limits, "limits");
        configuration = Objects.requireNonNull(configuration, "configuration");
        if (!"root".equals(username) || limits.runAsRoot()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ROOT_BUILD_REQUIRES_ROOT_SESSION,
                    "Root management is required; project scripts must use a temporary non-root identity");
        }
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("runtime must match the analyzed project type");
        }
        if (!facts.applicationId().equals(configuration.applicationId())) {
            throw new IllegalArgumentException("build configuration must match the analyzed application");
        }
        DeploymentBuildRenderer renderer = renderers.require(facts.projectType(), facts.buildTool());
        String script = BuildConfigurationEnvironmentRenderer.render(configuration)
                + gold.debug.windowstolinux.shared.linux.sshd.toolchain.ToolchainBuildEnvironment.render(tools)
                + renderer.render(facts, runtime, workspace, limits);
        String engine = runtime instanceof DeploymentRuntimeSpecification.Container container
                ? container.engine().name().toLowerCase(java.util.Locale.ROOT) : "ordinary";
        long remainingSeconds = Duration.ofNanos(deadline - System.nanoTime()).toSeconds();
        if (remainingSeconds < 1) return DeploymentBuildResult.failed(workspace.sourceSha256(), "Build time budget exhausted");
        String command = SshCommandExecutor.quote(
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.PATH)
                + " build-run " + SshCommandExecutor.quote(facts.applicationId()) + " "
                + SshCommandExecutor.quote(workspace.candidateId()) + " " + remainingSeconds + " "
                + limits.maxProcesses() + " " + limits.maxMemoryMiB() + " " + limits.maxOutputBytes() + " " + engine;
        var result = commands.execProtocolWithInput(command, script.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Duration.ofSeconds(remainingSeconds + 15L), limits.maxOutputBytes() + 65536);
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        if (!result.succeeded()) {
            String boundary = values.get("BUILD_LIMIT");
            String evidence = "workspace".equals(boundary)
                    ? "Target candidate exceeded the confirmed workspace limit"
                    : "output".equals(boundary) ? "Target build output exceeded the confirmed output limit"
                    : "Target " + facts.projectType() + " build failed: " + result.failureEvidence();
            evidence += "; " + selectionEvidence(tools);
            return new DeploymentBuildResult(false, workspace.sourceSha256(), evidence, tools);
        }
        if (SshCommandExecutor.parseLong(values.get("BUILD_UID")) <= 0 || !engine.equals(values.get("BUILD_ENGINE"))) {
            return DeploymentBuildResult.failed(workspace.sourceSha256(), "Target build did not prove its temporary non-root execution identity");
        }
        if (!facts.buildTool().name().equals(values.get("BUILD_TOOL"))) {
            return DeploymentBuildResult.failed(workspace.sourceSha256(),
                    "Target build entrypoint did not report the reviewed fixed build tool");
        }
        return new DeploymentBuildResult(true, workspace.sourceSha256(),
                "Target " + facts.projectType() + " build completed through " + facts.buildTool()
                        + "; " + selectionEvidence(tools), tools);
    }

    private String selectionEvidence(gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet tools) {
        return "toolchain binding=" + gold.debug.windowstolinux.shared.model.toolchain.ToolchainBindingCodec.identity(tools)
                + "; " + tools.selections().stream().map(s -> s.requirement().ecosystem() + " requested="
                + s.requirement().declaration() + " from=" + s.requirement().source() + " candidates="
                + toolchainCatalog.candidates(s.requirement()).stream().map(b -> b.version()).toList()
                + " selected=" + s.version().text()).collect(java.util.stream.Collectors.joining("; "));
    }
}
