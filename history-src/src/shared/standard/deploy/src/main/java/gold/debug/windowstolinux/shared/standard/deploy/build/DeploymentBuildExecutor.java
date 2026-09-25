package gold.debug.windowstolinux.shared.standard.deploy.build;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildEnvironment;
import gold.debug.windowstolinux.shared.linux.command.RemoteCommandExecutor;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.CargoBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.CmakeBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.DotNetSdkBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.GoBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.java.GradleBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.java.JavaJarBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.java.JdkBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.java.MavenBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.kotlin.KotlinCompilerBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.kotlin.KotlinGradleBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.node.NpmBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.node.PnpmBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.node.YarnBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.php.ComposerBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.php.PhpCliBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.python.PipBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.python.PipenvBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.python.PoetryBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.python.UvBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.ruby.BundlerBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.ruby.RubyCliBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.extension.registry.DeploymentBuildRendererRegistry;
import gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.BuildConfigurationEnvironmentRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.workload.ContainerBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.workload.StaticSiteBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.toolchain.ProjectToolchainRequirements;

/**
 * Executes an implementation-rendered, resource-bounded target-host build.
 *
 *  <p>执行由实现渲染并受资源限制的目标机构建。
 */
public final class DeploymentBuildExecutor implements gold.debug.windowstolinux.shared.linux.build.RemoteBuildPort {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final RemoteCommandExecutor commands;

    /**
     * Account name used by the reviewed connection.
     * <p>已审阅连接使用的账户名。
     */
    private final String username;

    /**
     * Renderers.
     * <p>渲染器集合。
     */
    private final DeploymentBuildRendererRegistry renderers;

    /**
     * Toolchain catalog.
     * <p>工具链目录。
     */
    private final gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog toolchainCatalog = gold.debug.windowstolinux.shared.model.toolchain.ToolchainSupportCatalog
            .defaults();

    /**
     * Prepared.
     * <p>已准备。
     */
    private final java.util.Map<String, gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet> prepared = new java.util.HashMap<>();

    /**
     * Prepares resolved toolchain set.
     * <p>准备已解析工具链集合。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return constructed or resolved resolved toolchain set / 构造或解析得到的已解析工具链集合
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet prepare(DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime, BuildLimitConfiguration limits) throws LinuxOperationException {
        if (!"root".equals(username) || limits.runAsRoot())
            throw LinuxOperationException.create(LinuxOperationFailureType.ROOT_BUILD_REQUIRES_ROOT_SESSION,
                    "Root management with restricted builds is required");
        String key = facts.applicationId() + ":" + runtime + ":" + facts.toolchainRequirements();
        if (!prepared.containsKey(key)) {
            String engine = runtime instanceof DeploymentRuntimeSpecification.Container container
                    ? container.engine().name().toLowerCase(java.util.Locale.ROOT)
                    : "ordinary";
            var preflight = commands.execProtocol(gold.debug.windowstolinux.shared.linux.command.CommandText
                    .quote(gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol.PATH)
                    + " build-preflight " + engine, Duration.ofSeconds(30), true);
            if (!preflight.succeeded())
                throw LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED,
                        "Build isolation is unavailable before source upload: " + preflight.failureEvidence());
            prepared.put(key, new gold.debug.windowstolinux.shared.standard.deploy.toolchain.ManagedToolchainPreparer(
                    commands, toolchainCatalog)
                    .prepare(gold.debug.windowstolinux.shared.standard.deploy.toolchain.ProjectToolchainRequirements
                            .from(facts, runtime), 0, limits.timeoutSeconds()));
        }
        return prepared.get(key);
    }

    /**
     * Creates the executor for one authenticated SSH account. / 为一个已认证 SSH 账户创建执行器。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     */
    public DeploymentBuildExecutor(RemoteCommandExecutor commands, String username) {
        this(commands, username,
                List.of(new GradleBuildRenderer(), new MavenBuildRenderer(), new JavaJarBuildRenderer(),
                        new JdkBuildRenderer(), new NpmBuildRenderer(), new PnpmBuildRenderer(),
                        new YarnBuildRenderer(), new PipBuildRenderer(), new PipenvBuildRenderer(),
                        new PoetryBuildRenderer(), new UvBuildRenderer(), new StaticSiteBuildRenderer(),
                        new ContainerBuildRenderer(), new GoBuildRenderer(), new CargoBuildRenderer(),
                        new DotNetSdkBuildRenderer(), new KotlinGradleBuildRenderer(),
                        new KotlinCompilerBuildRenderer(), new ComposerBuildRenderer(), new PhpCliBuildRenderer(),
                        new BundlerBuildRenderer(), new RubyCliBuildRenderer(), new CmakeBuildRenderer()));
    }

    /**
     * Validates and binds the inputs required by deployment build executor.
     * <p>校验并绑定部署构建执行器所需输入。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param renderers renderers / 渲染器集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    DeploymentBuildExecutor(RemoteCommandExecutor commands, String username, List<DeploymentBuildRenderer> renderers) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.username = Objects.requireNonNull(username, "username");
        this.renderers = new DeploymentBuildRendererRegistry(renderers);
    }

    /**
     * Builds one reviewed source archive with the fixed entrypoint selected by its facts.
     *
     *  <p>使用其事实选定的固定入口构建一个经审阅的源码归档。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @return one reviewed source archive with the fixed entrypoint selected by its facts / 使用其事实选定的固定入口构建一个经审阅的源码归档
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public DeploymentBuildResult build(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            RemoteWorkspace workspace, BuildLimitConfiguration limits, RemoteBuildEnvironment configuration)
            throws LinuxOperationException {
        long deadline = System.nanoTime() + Duration.ofSeconds(limits.timeoutSeconds()).toNanos();
        var tools = prepare(facts, runtime, limits);
        var result = execute(facts, runtime, workspace, limits, configuration, tools, deadline);
        var requirements = gold.debug.windowstolinux.shared.standard.deploy.toolchain.ProjectToolchainRequirements
                .from(facts, runtime);
        if (!result.succeeded() && BuildCompatibilityPolicy.retryable(result.evidence())
                && deadline - System.nanoTime() > Duration.ofSeconds(60).toNanos()
                && tools.selections().stream().anyMatch(s -> {
                    var candidates = toolchainCatalog.candidates(s.requirement());
                    return candidates.size() > 1 && s.version().branch().equals(candidates.getFirst().version());
                })) {
            var second = new gold.debug.windowstolinux.shared.standard.deploy.toolchain.ManagedToolchainPreparer(
                    commands, toolchainCatalog).prepare(requirements, 1,
                            (int) Math.max(1, Duration.ofNanos(deadline - System.nanoTime()).toSeconds()));
            result = execute(facts, runtime, workspace, limits, configuration, second, deadline);
        }
        if (!result.succeeded() && BuildCompatibilityPolicy.retryable(result.evidence()))
            return new DeploymentBuildResult(false, result.sourceSha256(),
                    "TOOLCHAIN_FAILURE=unsupported:compatible catalog candidates exhausted; " + result.evidence(),
                    result.toolchains());
        return result;
    }

    /**
     * Runs the reviewed build in a bounded remote workspace and converts helper evidence into the sealed build result.
     * <p>在有界远端工作区运行已审阅构建，并将 helper 证据转换为封存构建结果。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param tools tools / 工具集合
     * @param deadline deadline / 截止时刻
     * @return constructed or resolved deployment build result / 构造或解析得到的部署构建结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private DeploymentBuildResult execute(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            RemoteWorkspace workspace, BuildLimitConfiguration limits, RemoteBuildEnvironment configuration,
            gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet tools, long deadline)
            throws LinuxOperationException {
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
                + gold.debug.windowstolinux.shared.standard.deploy.toolchain.ToolchainBuildEnvironment.render(tools)
                + renderer.render(facts, runtime, workspace, limits)
                + gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.CompanionBuildScript
                        .render(runtime.workload())
                + (runtime.workload().companions().isEmpty()
                        ? ""
                        : "\ntest \"$(du -sb \"$mutable\" | cut -f1)\" -le " + limits.maxWorkspaceBytes() + "\n");
        String engine = runtime instanceof DeploymentRuntimeSpecification.Container container
                ? container.engine().name().toLowerCase(java.util.Locale.ROOT)
                : "ordinary";
        long remainingSeconds = Duration.ofNanos(deadline - System.nanoTime()).toSeconds();
        if (remainingSeconds < 1)
            return DeploymentBuildResult.failed(workspace.sourceSha256(), "Build time budget exhausted");
        String command = gold.debug.windowstolinux.shared.linux.command.CommandText
                .quote(gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol.PATH) + " build-run "
                + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(facts.applicationId()) + " "
                + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(workspace.candidateId()) + " "
                + remainingSeconds + " " + limits.maxProcesses() + " " + limits.maxMemoryMiB() + " "
                + limits.maxOutputBytes() + " " + engine;
        var result = commands.execProtocolWithInput(command, script.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Duration.ofSeconds(remainingSeconds + 15L), limits.maxOutputBytes() + 65536);
        Map<String, String> values = gold.debug.windowstolinux.shared.linux.command.CommandText.lines(result.output());
        if (!result.succeeded()) {
            String boundary = values.get("BUILD_LIMIT");
            String evidence = "workspace".equals(boundary)
                    ? "Target candidate exceeded the confirmed workspace limit"
                    : "output".equals(boundary)
                            ? "Target build output exceeded the confirmed output limit"
                            : "Target " + facts.projectType() + " build failed: " + result.failureEvidence();
            evidence += "; " + selectionEvidence(tools);
            return new DeploymentBuildResult(false, workspace.sourceSha256(), evidence, tools);
        }
        if (gold.debug.windowstolinux.shared.linux.command.CommandText.parseLong(values.get("BUILD_UID")) <= 0
                || !engine.equals(values.get("BUILD_ENGINE"))) {
            return DeploymentBuildResult.failed(workspace.sourceSha256(),
                    "Target build did not prove its temporary non-root execution identity");
        }
        if (!facts.buildTool().name().equals(values.get("BUILD_TOOL"))) {
            return DeploymentBuildResult.failed(workspace.sourceSha256(),
                    "Target build entrypoint did not report the reviewed fixed build tool");
        }
        return new DeploymentBuildResult(true, workspace.sourceSha256(), "Target " + facts.projectType()
                + " build completed through " + facts.buildTool() + "; " + selectionEvidence(tools), tools);
    }

    /**
     * Formats the toolchain binding digest and each resolved version selection as build evidence.
     * <p>将工具链绑定摘要及各已解析版本选择格式化为构建证据。
     *
     * @param tools tools / 工具集合
     * @return the toolchain binding digest and each resolved version selection as build evidence / 将工具链绑定摘要及各已解析版本选择格式化为构建证据
     */
    private String selectionEvidence(gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet tools) {
        return "toolchain binding="
                + gold.debug.windowstolinux.shared.model.toolchain.ToolchainBindingCodec.identity(tools) + "; "
                + tools.selections().stream()
                        .map(s -> s.requirement().ecosystem() + " requested=" + s.requirement().declaration() + " from="
                                + s.requirement().source() + " candidates="
                                + toolchainCatalog.candidates(s.requirement()).stream().map(b -> b.version()).toList()
                                + " selected=" + s.version().text())
                        .collect(java.util.stream.Collectors.joining("; "));
    }
}
