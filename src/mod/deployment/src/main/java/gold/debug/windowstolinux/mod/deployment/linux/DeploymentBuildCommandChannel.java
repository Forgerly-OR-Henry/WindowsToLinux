package gold.debug.windowstolinux.mod.deployment.linux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import gold.debug.windowstolinux.appapi.linux.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.linux.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentToolchainVersion;

/** Sends only fixed managed-helper build verbs through one borrowed, host-reviewed session. / 仅通过借用且经宿主审核的会话发送固定 helper 构建动作。 */
public final class DeploymentBuildCommandChannel {
    /** Borrowed original connection. / 借用的原连接。 */
    private final RemoteSession session;

    /** Original host review capability. / 原宿主审核能力。 */
    private final InteractionService interaction;

    /** Exact saved server identity. / 已保存的精确服务器身份。 */
    private final ServerRef server;

    /** Original admitted task. / 原准入任务。 */
    private final TaskContext task;

    /** New-domain reviewed plan revision. / 新域已审阅计划修订。 */
    private final String planRevision;

    /** One cumulative remote execution budget; approval waits are excluded. / 一份累计远端执行预算；不计审核等待。 */
    private final DeploymentBuildBudget budget;

    /** Caller-owned check immediately before remote dispatch. / 远端发送紧前调用方持有的核对。 */
    private final DeploymentSendGuard beforeSend;

    /** Binds the original task, server and borrowed session; the caller closes the session. / 绑定原任务、服务器和借用会话；由调用方关闭会话。
     * @param session original authenticated session / 原已认证会话
     * @param interaction actual host review / 实际宿主审核
     * @param server exact saved server / 精确已保存服务器
     * @param task original admitted task / 原准入任务
     * @param planRevision reviewed-plan identity, not final release identity / 已审阅计划身份，非最终发布身份
     * @param budget cumulative execution budget / 累计执行预算 */
    public DeploymentBuildCommandChannel(RemoteSession session, InteractionService interaction, ServerRef server,
            TaskContext task, String planRevision, DeploymentBuildBudget budget) {
        this(session, interaction, server, task, planRevision, budget, DeploymentSendGuard.NONE);
    }

    /** Binds an additional caller-owned pre-dispatch check. / 绑定调用方附加的发送前核对。
     * @param session original authenticated session / 原已认证会话
     * @param interaction actual host review / 真实宿主审核
     * @param server exact saved server / 精确已保存服务器
     * @param task original task / 原任务
     * @param planRevision reviewed plan digest / 已审阅计划摘要
     * @param budget shared build budget / 共用构建预算
     * @param beforeSend caller-owned check / 调用方持有的核对 */
    public DeploymentBuildCommandChannel(RemoteSession session, InteractionService interaction, ServerRef server,
            TaskContext task, String planRevision, DeploymentBuildBudget budget, DeploymentSendGuard beforeSend) {
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        if (planRevision == null || !planRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Reviewed plan identity required");
        this.planRevision = planRevision;
        this.budget = Objects.requireNonNull(budget);
        this.beforeSend = Objects.requireNonNull(beforeSend);
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
        return send("deployment.toolchain.prepare.v1", "/usr/local/lib/windowstolinux/toolchains",
                helper("prepare-toolchains",
                        List.of("prepare", Objects.requireNonNull(ecosystem).name(), branch, exact,
                                Integer.toString(seconds), javaHome)),
                new byte[0], Duration.ofSeconds(seconds + 15L), 65536);
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
        return send("deployment.toolchain.bind.v1", "/usr/local/lib/windowstolinux/toolchains",
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
        return send("deployment.build.preflight.v1", DeploymentManagedHelperBundle.PATH,
                helper("build-preflight", List.of(engine)), new byte[0], Duration.ofSeconds(Math.min(30, available)),
                65536);
    }

    /** Runs the fixed, restricted helper through a streaming, bounded receipt. / 通过流式有界回执运行固定受限 helper。
     * @param workspace original uploaded candidate identity / 原已上传候选身份
     * @param limits reviewed remote limits / 已审阅远端限制
     * @param seconds remaining total build deadline / 剩余总构建期限秒数
     * @param engine fixed reviewed engine / 固定已审阅引擎
     * @param buildScript complete fixed build script / 完整固定构建脚本
     * @param compatibilityGroups original Java compatibility phrases / 原Java兼容性短语
     * @return original remote outcome and compact complete receipt / 原远端结果及紧凑完整回执
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<CommandResult> build(DeploymentRemoteWorkspace workspace, DeploymentBuildLimits limits,
            long seconds, String engine, String buildScript, List<List<String>> compatibilityGroups)
            throws ApiException {
        Objects.requireNonNull(workspace);
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
                            limits.maxMemoryMiB(), limits.maxOutputBytes(), engine, compatibilityGroups),
                    input, Duration.ofSeconds(seconds + 15L), 65536);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    /** Builds one fixed helper command with literal arguments. / 以字面参数构造一个固定 helper 命令。
     * @param verb fixed helper verb / 固定 helper 动作
     * @param values bounded literal arguments / 有界字面参数
     * @return exact command script / 精确命令脚本 */
    private static String helper(String verb, List<String> values) {
        return shellQuote(DeploymentManagedHelperBundle.PATH) + " " + verb + " "
                + values.stream().map(DeploymentBuildCommandChannel::shellQuote).collect(Collectors.joining(" "));
    }

    /** Quotes one fixed literal without permitting shell evaluation. / 引用固定字面量而不允许shell求值。
     * @param value bounded fixed argument / 有界固定参数
     * @return quoted shell literal / 已引用shell字面量 */
    static String shellQuote(String value) {
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
    OperationResult<CommandResult> send(String operation, String target, String script, byte[] input, Duration timeout,
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
    OperationResult<CommandResult> send(String operation, String kind, String target, String script, byte[] input,
            Duration timeout, long output) throws ApiException {
        long available = budget.remainingSeconds();
        if (available < 1)
            return timeout();
        CommandRequest request = new CommandRequest(script, input,
                Duration.ofSeconds(Math.min(timeout.toSeconds(), available)), output);
        var intent = RemoteActions.command(server, planRevision, request)
                .withSemantics(new ActionSemantics(operation, Set.of(new ActionTarget(server.id(), kind, target))));
        var grant = interaction.approve(intent, task);
        if (grant.outcome() != Outcome.SUCCEEDED)
            return new OperationResult<>(grant.outcome(), Optional.empty(), grant.problem());
        beforeSend.verify();
        try {
            long started = System.nanoTime();
            try {
                return session.execute(request, grant.value().orElseThrow());
            } finally {
                budget.charge(System.nanoTime() - started);
            }
        } catch (ApiException failure) {
            if (failure.problem().code() == ProblemCode.ACCESS_DENIED
                    || failure.problem().code() == ProblemCode.INVALID_INPUT)
                throw failure;
            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_BUILD_DISPATCH_UNCONFIRMED",
                            "Remote build effect is unconfirmed", Map.of(), Optional.empty())));
        }
    }

    /** Returns a known exhausted budget before any further dispatch. / 在继续发送前返回已知预算耗尽。
     * @return known timeout without a remote effect / 无远端副作用的已知超时 */
    private static OperationResult<CommandResult> timeout() {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
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
