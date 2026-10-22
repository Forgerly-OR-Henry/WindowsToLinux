package gold.debug.windowstolinux.mod.deployment.linux.build;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion;

/** Sends only fixed managed-helper build verbs through one borrowed, host-reviewed session. / 仅通过借用且经宿主审核的会话发送固定 helper 构建动作。 */
public final class DeploymentBuildCommandChannel {
    /** Borrowed original connection. / 借用的原连接。 */
    private final RemoteSession session;

    /** Original host review capability. / 原宿主审核能力。 */
    private final InteractionService interaction;

    /** Exact saved server identity. / 已保存的精确服务器身份。 */
    private final ServerId server;

    /** Original admitted task. / 原准入任务。 */
    private final TaskContext task;

    /** New-domain reviewed plan revision. / 新域已审阅计划修订。 */
    private final String planRevision;

    /** One cumulative remote execution budget; approval waits are excluded. / 一份累计远端执行预算；不计审核等待。 */
    private final DeploymentBuildBudget budget;

    /** Caller-owned check immediately before remote dispatch. / 远端发送紧前调用方持有的核对。 */
    private final DeploymentSendGuard beforeSend;

    private final DeploymentRemoteLayout layout;

    private final DeploymentPreparationObserver preparationObserver;

    private final java.util.ArrayDeque<String> preparationReceipts = new java.util.ArrayDeque<>();

    /** Binds the original task, server and borrowed session; the caller closes the session. / 绑定原任务、服务器和借用会话；由调用方关闭会话。
     * @param session original authenticated session / 原已认证会话
     * @param interaction actual host review / 实际宿主审核
     * @param server exact saved server / 精确已保存服务器
     * @param task original admitted task / 原准入任务
     * @param planRevision reviewed-plan identity, not final release identity / 已审阅计划身份，非最终发布身份
     * @param budget cumulative execution budget / 累计执行预算 */
    public DeploymentBuildCommandChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                         TaskContext task, String planRevision, DeploymentBuildBudget budget) {
        this(session, interaction, server, task, planRevision, budget, DeploymentSendGuard.NONE,
            DeploymentRemoteLayout.original());
    }

    /** Binds an additional caller-owned pre-dispatch check. / 绑定调用方附加的发送前核对。
     * @param session original authenticated session / 原已认证会话
     * @param interaction actual host review / 真实宿主审核
     * @param server exact saved server / 精确已保存服务器
     * @param task original task / 原任务
     * @param planRevision reviewed plan digest / 已审阅计划摘要
     * @param budget shared build budget / 共用构建预算
     * @param beforeSend caller-owned check / 调用方持有的核对 */
    public DeploymentBuildCommandChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                         TaskContext task, String planRevision, DeploymentBuildBudget budget, DeploymentSendGuard beforeSend) {
        this(session, interaction, server, task, planRevision, budget, beforeSend, DeploymentRemoteLayout.original());
    }

    public DeploymentBuildCommandChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                         TaskContext task, String planRevision, DeploymentBuildBudget budget,
                                         DeploymentRemoteLayout layout) {
        this(session, interaction, server, task, planRevision, budget, DeploymentSendGuard.NONE, layout);
    }

    public DeploymentBuildCommandChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                         TaskContext task, String planRevision, DeploymentBuildBudget budget,
                                         DeploymentSendGuard beforeSend, DeploymentRemoteLayout layout) {
        this(session, interaction, server, task, planRevision, budget, beforeSend, layout, DeploymentPreparationObserver.NONE);
    }

    public DeploymentBuildCommandChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                         TaskContext task, String planRevision, DeploymentBuildBudget budget,
                                         DeploymentSendGuard beforeSend, DeploymentRemoteLayout layout,
                                         DeploymentPreparationObserver preparationObserver) {
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        if (planRevision == null || !planRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Reviewed plan identity required");
        this.planRevision = planRevision;
        this.budget = Objects.requireNonNull(budget);
        this.beforeSend = Objects.requireNonNull(beforeSend);
        this.layout = Objects.requireNonNull(layout);
        this.preparationObserver = Objects.requireNonNull(preparationObserver);
    }

    /** Rebinds reviewed request identity while preserving original execution budget and guard. / 重新绑定已审阅请求身份，保留原执行预算与守卫。
     * @param revision explicitly accepted replacement identity / 明确接受的替换身份
     * @return same-task channel with the existing budget / 共用原预算的同任务通道 */
    public DeploymentBuildCommandChannel rebound(String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid replacement identity");
        return new DeploymentBuildCommandChannel(session, interaction, server, task, revision, budget, beforeSend, layout, preparationObserver);
    }

    /** Observes the original cumulative execution deadline. / 观察原累计执行期限。 */
    public long remainingSeconds() {
        return budget.remainingSeconds();
    }

    /** Prepares one catalog branch with the helper's fixed official release protocol. / 使用 helper 的固定官方发布协议准备一个目录分支。
     * @param ecosystem reviewed ecosystem / 已审阅生态
     * @param branch selected catalog branch / 已选目录分支
     * @param exact exact patch or hyphen / 精确补丁版本或连字符
     * @param seconds remaining preparation budget / 剩余准备预算秒数
     * @param javaHome preceding exact Java selection or hyphen / 已先行选择的精确 Java 目录或连字符
     * @return original remote outcome and complete bounded receipt / 原远端结果及完整有界回执
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<CommandResult> prepare(DeploymentToolchainType ecosystem, String branch, String exact,
                                                  int seconds, String javaHome) throws ApiException {
        long available = budget.remainingSeconds();
        if (available < 45)
            return timeout();
        seconds = (int) Math.min(seconds, available - 15);
        if (seconds < 30 || seconds > 7200 || !Objects.requireNonNull(branch).matches("[0-9]{1,3}(?:\\.[0-9]{1,3})?")
            || !("-".equals(exact) || DeploymentToolchainVersion.parse(ecosystem, exact)
            .filter(version -> version.text().equals(exact) && !version.preview()
                && version.branch().equals(branch))
            .isPresent())
            || javaHome == null || javaHome.length() > 2048)
            throw new IllegalArgumentException("Invalid fixed toolchain preparation");
        var observed = send("deployment.toolchain.prepare.v1", layout.toolchains(),
            helper("prepare-toolchains",
                List.of("prepare", Objects.requireNonNull(ecosystem).name(), branch, exact,
                    Integer.toString(seconds), javaHome)),
            new byte[0], Duration.ofSeconds(seconds + 15L), 65536);
        if (observed.outcome() == TaskResult.SUCCEEDED && observed.value().isPresent()) {
            var receipt = observed.value().orElseThrow();
            if (receipt.exitCode().equals(Optional.of(0)) && !receipt.truncated() && receipt.stdout().length() <= 8192
                && receipt.stdout().startsWith("TOOLCHAIN=") && receipt.stdout().endsWith("\n")
                && receipt.stdout().indexOf('\n') == receipt.stdout().length() - 1) {
                if (preparationReceipts.size() == 32) preparationReceipts.removeFirst();
                preparationReceipts.addLast(receipt.stdout());
            }
        }
        return observed;
    }

    /** Matches validated typed selections to this channel's actual complete successful preparation receipts. */
    public void toolchainsPrepared(gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools) throws ApiException {
        if (tools.selections().isEmpty()) return;
        for (var selection : tools.selections()) {
            String expected = "TOOLCHAIN=" + selection.requirement().ecosystem() + "|" + selection.version().text() + "|"
                + selection.directory() + "|" + selection.origin() + "|" + selection.source() + "|" + selection.sha256() + "\n";
            if (!preparationReceipts.contains(expected))
                throw new IllegalArgumentException("Validated tools differ from this channel's observed successful preparation");
        }
        preparationReceipts.clear();
        preparationObserver.toolsPrepared(server, tools, task);
    }

    /** Seals and re-probes the exact prepared binding. / 封存并回探精确已准备绑定。
     * @param encoded canonical binding in Base64 / Base64 规范绑定
     * @return host-observed binding receipt / 宿主观测绑定回执
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<CommandResult> bind(String encoded) throws ApiException {
        long available = budget.remainingSeconds();
        if (available < 1)
            return timeout();
        if (encoded == null || !encoded.matches("[A-Za-z0-9+/=]{1,90000}"))
            throw new IllegalArgumentException("Invalid encoded binding");
        return send("deployment.toolchain.bind.v1", layout.toolchains(),
            helper("prepare-toolchains", List.of("bind", encoded)), new byte[0],
            Duration.ofSeconds(Math.min(180, available)), 65536);
    }

    /** Checks isolation before a source candidate is built. / 构建来源候选前检查隔离能力。
     * @param engine fixed reviewed engine / 固定已审阅引擎
     * @return observed helper preflight / 已观察 helper 预检
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<CommandResult> preflight(String engine) throws ApiException {
        requireEngine(engine);
        long available = budget.remainingSeconds();
        if (available < 1)
            return timeout();
        return send("deployment.build.preflight.v1", layout.helperPath(),
            helper("build-preflight", List.of(engine)), new byte[0], Duration.ofSeconds(Math.min(30, available)),
            65536);
    }

    /** Runs the fixed, restricted helper through a streaming, bounded receipt. / 通过流式有界回执运行固定受限 helper。
     * @param workspace original uploaded candidate identity / 原已上传候选身份
     * @param limits reviewed remote limits / 已审阅远端限制
     * @param seconds remaining total build deadline / 剩余总构建期限秒数
     * @param engine fixed reviewed engine / 固定已审阅引擎
     * @param buildScript complete fixed build script / 完整固定构建脚本
     * @param toolchainBinding canonical selected toolchain binding / 规范所选工具链绑定
     * @param compatibilityGroups original Java compatibility phrases / 原Java兼容性短语
     * @return original remote outcome and compact complete receipt / 原远端结果及紧凑完整回执
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<CommandResult> build(DeploymentRemoteWorkspace workspace, DeploymentBuildLimits limits,
                                                long seconds, String engine, String buildScript, String toolchainBinding,
                                                List<List<String>> compatibilityGroups)
        throws ApiException {
        Objects.requireNonNull(workspace);
        if (!workspace.layout().equals(layout))
            throw new IllegalArgumentException("Build workspace differs from the captured layout");
        Objects.requireNonNull(limits);
        requireEngine(engine);
        if (seconds < 1 || seconds > 7200 || seconds + 15 > budget.remainingSeconds())
            throw new IllegalArgumentException("Invalid remaining build deadline");
        byte[] input = Objects.requireNonNull(buildScript).getBytes(StandardCharsets.UTF_8);
        try {
            if (input.length > 1048576)
                throw new IllegalArgumentException("Fixed build script exceeds helper limit");
            return send("deployment.build.run.v1", workspace.candidateRoot(),
                DeploymentBuildOutputProtocol.script(workspace, seconds, limits.maxProcesses(),
                    limits.maxMemoryMiB(), limits.maxOutputBytes(), engine, sha256(input), toolchainBinding, compatibilityGroups),
                input, Duration.ofSeconds(seconds + 15L), 65536);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    /** Computes the canonical SHA256 digest for the original evidence bytes. / 为原依据字节计算规范 SHA256 摘要。
     * @param input original input bytes or checked field value / 原输入字节或已检查字段值
     * @return canonical lowercase SHA256 / 规范小写 SHA256 */
    private static String sha256(byte[] input) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Builds one fixed helper command with literal arguments. / 以字面参数构造一个固定 helper 命令。
     * @param verb fixed helper verb / 固定 helper 动作
     * @param values bounded literal arguments / 有界字面参数
     * @return exact command script / 精确命令脚本 */
    private String helper(String verb, List<String> values) {
        return shellQuote(layout.helperPath()) + " " + verb + " "
            + values.stream().map(DeploymentBuildCommandChannel::shellQuote).collect(Collectors.joining(" "));
    }

    /** Quotes one fixed literal without permitting shell evaluation. / 引用固定字面量而不允许shell求值。
     * @param value bounded fixed argument / 有界固定参数
     * @return quoted shell literal / 已引用shell字面量 */
    public static String shellQuote(String value) {
        return "'" + Objects.requireNonNull(value).replace("'", "'\"'\"'") + "'";
    }

    /** Executes a separately reviewed original command once. / 对原命令单独审核并仅执行一次。
     * @param operation stable fixed operation / 稳定固定动作
     * @param target exact business target / 精确业务目标
     * @param script exact script / 精确脚本
     * @param input owned caller bytes / 调用方所属字节
     * @param timeout original operation timeout / 原操作超时
     * @param output captured receipt ceiling / 捕获回执上限
     * @return host-observed operation / 宿主观测操作
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<CommandResult> send(String operation, String target, String script, byte[] input, Duration timeout,
                                               long output) throws ApiException {
        return send(operation, "deployment-build", target, script, input, timeout, output);
    }

    /** Executes a fixed deployment candidate command with its exact target kind. / 使用精确目标类别执行固定部署候选命令。
     * @param operation stable operation / 稳定操作
     * @param kind stable target kind / 稳定目标类别
     * @param target exact target / 精确目标
     * @param script fixed script / 固定脚本
     * @param input owned bytes / 所属字节
     * @param timeout approved deadline / 已审阅期限
     * @param output bounded capture / 有界输出
     * @return original host observation / 原宿主观测
     * @throws ApiException if admission fails before dispatch / 发送前准入失败时 */
    public OperationResult<CommandResult> send(String operation, String kind, String target, String script, byte[] input,
                                               Duration timeout, long output) throws ApiException {
        return send(operation, Set.of(new ActionTarget(server.id(), kind, target)), script, input, timeout, output);
    }

    /** Reviews the complete effect target set with the original guard and execution budget. / 沿用原守卫与执行预算，审核完整副作用目标集合。 */
    public OperationResult<CommandResult> send(String operation, Set<ActionTarget> targets, String script, byte[] input,
                                               Duration timeout, long output) throws ApiException {
        return send(operation, targets, script, input, timeout, output, () -> {
        });
    }

    /** Observes this live invocation immediately before execute, after its original approval and guard. */
    public OperationResult<CommandResult> send(String operation, Set<ActionTarget> targets, String script, byte[] input,
                                               Duration timeout, long output, Runnable dispatchBoundary) throws ApiException {
        Objects.requireNonNull(dispatchBoundary);
        targets = Set.copyOf(targets);
        if (targets.isEmpty() || targets.stream().anyMatch(target -> !target.serverId().equals(server.id())))
            throw new IllegalArgumentException("Original server effect targets required");
        long available = budget.remainingSeconds();
        if (available < 1)
            return timeout();
        if (!layout.helperPath().equals(DeploymentManagedHelperBundle.PATH) && script.contains(DeploymentManagedHelperBundle.PATH))
            throw new IllegalArgumentException("Helper invocation differs from the captured layout");
        CommandRequest request = new CommandRequest(script.contains(layout.helperPath())
            ? DeploymentManagedHelperBundle.verifiedCommand(script, layout) : script, input,
            Duration.ofSeconds(Math.min(timeout.toSeconds(), available)), output);
        var intent = RemoteActions.command(server, planRevision, request)
            .withSemantics(new ActionSemantics(operation, targets));
        var grant = DeploymentCommandReviewRouter.approve(interaction, intent, request, task);
        if (grant.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(grant.outcome(), Optional.empty(), grant.problem());
        beforeSend.verify();
        try {
            long started = System.nanoTime();
            try {
                dispatchBoundary.run();
                return session.execute(request, grant.value().orElseThrow());
            } finally {
                budget.charge(System.nanoTime() - started);
            }
        } catch (ApiException failure) {
            if (failure.problem().code() == ProblemCode.ACCESS_DENIED
                || failure.problem().code() == ProblemCode.INVALID_INPUT)
                throw failure;
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_BUILD_DISPATCH_UNCONFIRMED",
                    "Remote build effect is unconfirmed", Map.of(), Optional.empty())));
        }
    }

    /** Returns a known exhausted budget before any further dispatch. / 在继续发送前返回已知预算耗尽。
     * @return known timeout without a remote effect / 无远端副作用的已知超时 */
    private static OperationResult<CommandResult> timeout() {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.TIMEOUT, "DEPLOYMENT_BUILD_BUDGET_EXHAUSTED",
                "Reviewed build execution budget is exhausted", Map.of(), Optional.empty())));
    }

    /** Rejects an engine outside the fixed helper protocol. / 拒绝固定 helper 协议之外的引擎。
     * @param engine reviewed engine / 已审阅引擎 */
    private static void requireEngine(String engine) {
        if (!"ordinary".equals(engine) && !"docker".equals(engine) && !"podman".equals(engine))
            throw new IllegalArgumentException("Unknown build engine");
    }
}
