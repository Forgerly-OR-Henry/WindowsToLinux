package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReview;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;

/** Sends only the old protected agent helper verbs through the task-owned reviewed SSH session. / 仅经任务所属已审核 SSH 会话发送旧受保护 agent helper 动作。 */
public final class DeploymentAgentProjectChannel {
    /** Exact original task-bound candidate observation. / 精确原任务绑定候选观察。
     * @param present whether the candidate remains / 候选是否仍存在
     * @param buildActive whether its build is still active / 构建是否仍活动 */
    public record CandidateStatus(boolean present, boolean buildActive) {
    }

    /** One complete command result; it establishes no artifact digest or publication health. */
    record RunReceipt(DeploymentRemoteWorkspace workspace, String sourceRevision, long outputGeneration,
                      String scriptSha256, int exitStatus) {
    }

    /** Borrowed authenticated session and exact original task. / 借用的已认证会话与精确原任务。 */
    private final DeploymentRemoteLayout layout;

    private final RemoteSession session;

    /** Real host approval and input capability. / 真实宿主审批及输入能力。 */
    private final InteractionService interaction;

    /** Exact admitted target server reference. / 精确已准入目标服务器引用。 */
    private final ServerId server;

    /** Authentic original platform task context. / 真实原平台任务上下文。 */
    private final TaskContext task;

    /** Frozen and live model-policy revision. / 冻结和实时模型策略修订。 */
    private String configurationRevision;

    /** Read-only live observer of the task-authorized AI revision. / 任务授权 AI 修订的只读实时观察器。 */
    private final Supplier<String> currentRevision;

    /** Original opened workspaces and backend. / 原已开启工作区与后端。 */
    private final Map<String, String> engines = new HashMap<>();

    private final Map<String, Long> outputGenerations = new HashMap<>();

    private final Map<String, RunReceipt> latestRuns = new HashMap<>();

    /** Bounded structured patch wire serializer. / 有界结构化补丁传输序列化器。 */
    private final ObjectMapper json = new ObjectMapper();

    /** Binds real capabilities without taking ownership of the session. / 绑定真实能力但不取得会话所有权。
     * @param session borrowed authenticated SSH / 借用已认证 SSH
     * @param interaction host approval service / 宿主审核服务
     * @param server exact server / 精确服务器
     * @param task original task / 原任务
     * @param configurationRevision frozen model-policy revision / 冻结模型策略修订
     * @param currentRevision live task-authorized revision / 实时任务授权修订 */
    public DeploymentAgentProjectChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                         TaskContext task, String configurationRevision, Supplier<String> currentRevision) {
        this(session, interaction, server, task, configurationRevision, currentRevision, DeploymentRemoteLayout.original());
    }

    public DeploymentAgentProjectChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                         TaskContext task, String configurationRevision, Supplier<String> currentRevision, DeploymentRemoteLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.configurationRevision = Objects.requireNonNull(configurationRevision);
        this.currentRevision = Objects.requireNonNull(currentRevision);
    }

    /** Rebinds new action reviews while preserving every original opened backend. / 保留全部原已开启后端，同时重新绑定新动作审核。
     * @param revision explicitly adopted whole-route revision / 明确采用的整路由修订 */
    void replaceConfiguration(String revision) {
        if (!revision.matches("[0-9a-f]{64}") || !revision.equals(currentRevision.get()))
            throw new SecurityException("Explicit agent configuration differs before dispatch");
        configurationRevision = revision;
    }

    /** Opens one uploaded protected source using its exact archive digest. / 使用精确归档摘要开启一份已上传受保护来源。
     * @param workspace original task candidate / 原任务候选
     * @param engine ordinary, docker or podman / ordinary、docker 或 podman
     * @return actual source revision / 实际来源修订
     * @throws Exception on denied or unknown opening / 开启被拒绝或未知时 */
    public String open(DeploymentRemoteWorkspace workspace, String engine) throws Exception {
        if (!Set.of("ordinary", "docker", "podman").contains(engine) || engines.containsKey(workspace.candidateId()))
            throw new IllegalArgumentException("Invalid or already opened agent backend");
        String source = digest(send(render("agent-open", workspace, workspace.archiveSha256()), new byte[0],
            Duration.ofMinutes(2), 4096), "SOURCE_REVISION");
        engines.put(workspace.candidateId(), engine);
        outputGenerations.put(workspace.candidateId(), 0L);
        return source;
    }

    /** Reads a bounded current protected file. / 读取有界的当前受保护文件。
     * @param candidate opened candidate / 已开启候选
     * @param revision current source revision / 当前来源修订
     * @param path relative file / 相对文件
     * @param offset line cursor / 行游标
     * @param limit line limit / 行上限
     * @return actual redacted helper output / 真实脱敏 helper 输出
     * @throws Exception on unavailable or uncertain read / 读取不可用或未知时 */
    public String read(DeploymentAgentDeliveryPort.Prepared candidate, String revision, String path, int offset,
                       int limit) throws Exception {
        scope(candidate, revision);
        if (!path.matches("[\\p{L}\\p{N}_. /-]{1,500}") || path.startsWith("/") || path.contains("..") || offset < 0
            || offset > 1000000 || limit < 1 || limit > 100)
            throw new IllegalArgumentException("Invalid agent read page");
        var result = send(render("agent-read", candidate.workspace(), revision, path, Integer.toString(offset),
            Integer.toString(limit)), new byte[0], Duration.ofSeconds(30), 12000);
        knownSuccess(result);
        return result.value().orElseThrow().stdout();
    }

    /** Runs a reviewed script only inside the helper's restricted build sandbox. / 仅在 helper 受限构建沙箱中运行经审脚本。
     * @param candidate opened candidate / 已开启候选
     * @param revision current source revision / 当前来源修订
     * @param script exact build script / 精确构建脚本
     * @param timeout deadline / 截止时间
     * @return actual bounded feedback / 真实有界反馈
     * @throws Exception on denied or unknown execution / 执行被拒绝或未知时 */
    public DeploymentAgentDeliveryPort.CommandFeedback command(DeploymentAgentDeliveryPort.Prepared candidate,
                                                               String revision, String script, Duration timeout) throws Exception {
        return command(candidate, revision, script, timeout, "-");
    }

    /** Runs with an exact product-selected toolchain binding. / 使用产品选定的精确工具链绑定运行。
     * @param candidate original candidate / 原候选
     * @param revision current source / 当前来源
     * @param script full executable body / 完整可执行正文
     * @param timeout deadline / 期限
     * @param tools verified actual tool binding / 已核实实际工具绑定
     * @return actual feedback / 实际反馈
     * @throws Exception denied or unknown result / 被拒或未知结果 */
    public DeploymentAgentDeliveryPort.CommandFeedback command(DeploymentAgentDeliveryPort.Prepared candidate,
                                                               String revision, String script, Duration timeout, String tools) throws Exception {
        scope(candidate, revision);
        if (script.isBlank() || script.length() > 24000 || script.indexOf('\0') >= 0 || timeout.toSeconds() < 1
            || timeout.toSeconds() > 7200)
            throw new IllegalArgumentException("Invalid agent command budget");
        var goal = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget.current();
        if (goal.isPresent() && timeout.toSeconds() > goal.orElseThrow().build().remainingSeconds())
            throw new IllegalArgumentException("Original goal build allowance cannot fund this exact candidate command");
        byte[] input = script.getBytes(StandardCharsets.UTF_8);
        String command = render("agent-run", candidate.workspace(), revision, Long.toString(timeout.toSeconds()),
            "1048576", engines.get(candidate.workspace().candidateId()), sha256(input), tools);
        var result = send(command, input, timeout.plusSeconds(20), 1048576, true,
            new PendingCommand(candidate.workspace().candidateId(), revision, sha256(input), timeout.toSeconds()), candidate.workspace().candidateId());
        if (result.outcome() == TaskResult.UNKNOWN)
            return new DeploymentAgentDeliveryPort.CommandFeedback(false, false, -1, "Unknown command outcome");
        var output = result.value().orElse(null);
        boolean knownNonzero = result.outcome() == TaskResult.FAILED
            && result.problem().filter(problem -> problem.code() == ProblemCode.IO_FAILURE
            && problem.eventCode().equals("SSH_COMMAND_NONZERO")).isPresent()
            && output != null && !output.truncated()
            && output.exitCode().filter(exit -> exit > 0 && exit <= 255).isPresent()
            && java.util.stream.Stream.concat(output.stdout().lines(), output.stderr().lines())
            .noneMatch(line -> line.matches("MANAGED_HELPER_REJECT=[a-z0-9][a-z0-9-]*"));
        if (result.outcome() != TaskResult.SUCCEEDED && !knownNonzero)
            throw new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException(
                result.outcome(), result.problem());
        if (output == null || output.exitCode().isEmpty() || output.truncated())
            return new DeploymentAgentDeliveryPort.CommandFeedback(false, false, -1, "Missing command exit");
        if (result.outcome() == TaskResult.SUCCEEDED && output.exitCode().orElseThrow() != 0)
            throw new IllegalStateException("Successful Agent command has a nonzero exit");
        var feedback = new DeploymentAgentDeliveryPort.CommandFeedback(true, output.exitCode().orElseThrow() == 0,
            output.exitCode().orElseThrow(), output.stdout() + "\n" + output.stderr());
        latestRuns.put(candidate.workspace().candidateId(), new RunReceipt(candidate.workspace(), revision,
            outputGenerations.get(candidate.workspace().candidateId()), sha256(input), feedback.exitStatus()));
        return feedback;
    }

    Optional<RunReceipt> latestRun(DeploymentAgentDeliveryPort.Prepared candidate) {
        return Optional.ofNullable(latestRuns.get(candidate.workspace().candidateId()));
    }

    boolean outputCurrent(RunReceipt receipt) {
        return receipt.equals(latestRuns.get(receipt.workspace().candidateId()))
            && Objects.equals(outputGenerations.get(receipt.workspace().candidateId()), receipt.outputGeneration());
    }

    /** Checks only retained actual execution evidence, before any seal review or dispatch. */
    public void requireSealable(DeploymentAgentDeliveryPort.Prepared candidate, String revision) {
        scope(candidate, revision);
        var receipt = latestRun(candidate).orElse(null);
        if (receipt == null || !receipt.workspace().equals(candidate.workspace()) || !receipt.sourceRevision().equals(revision)
            || !outputCurrent(receipt) || receipt.exitStatus() != 0)
            throw new IllegalArgumentException("SEAL_PRECONDITION: current candidate output needs a complete successful agent-run; lastKnownExit="
                + (receipt == null ? "unobserved" : receipt.exitStatus())
                + ". A later command or source mutation invalidates prior success; inspect the actual result and submit a separately reviewed correction. No seal was sent.");
    }

    /** Sends a structured patch as input data, then checks actual revision. / 以输入数据发送结构化补丁，再核实真实修订。
     * @param candidate opened candidate / 已开启候选
     * @param patch exact reviewed patch / 精确已审核补丁
     * @return actual new source revision / 真实新来源修订
     * @throws Exception on denied, conflicting or unknown edit / 修改被拒绝、冲突或未知时 */
    public String patch(DeploymentAgentDeliveryPort.Prepared candidate, DeploymentAgentPatch patch) throws Exception {
        scope(candidate, patch.sourceRevision());
        byte[] bytes = json.writeValueAsBytes(patch);
        if (bytes.length > 65536)
            throw new IllegalArgumentException("Agent patch wire budget exceeded");
        String binding = sha256(bytes);
        try {
            var result = send(render("agent-patch", candidate.workspace(), patch.sourceRevision(), binding), bytes,
                Duration.ofSeconds(45), 4096, true, null, candidate.workspace().candidateId());
            if (result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
                && result.value().orElseThrow().exitCode().equals(Optional.of(0)))
                return digest(result, "SOURCE_REVISION");
            if (result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
                && result.value().orElseThrow().exitCode().isPresent()
                && result.value().orElseThrow().exitCode().orElseThrow() != 0
                && !result.value().orElseThrow().truncated()) {
                read(candidate, patch.sourceRevision(), patch.path(), 0, 1);
                throw new IllegalArgumentException("Patch rejected; old source revision verified");
            }
            if (result.outcome() != TaskResult.SUCCEEDED)
                throw new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException(
                    result.outcome(), result.problem());
            throw new IllegalStateException("Agent patch outcome unknown");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Seals source-bound actual output using the fixed helper. / 使用固定 helper 封存来源绑定的真实输出。
     * @param candidate opened candidate / 已开启候选
     * @param revision current source revision / 当前来源修订
     * @return actual artifact digest / 真实制品摘要
     * @throws Exception on missing or uncertain sealing / 封存缺失或不确定时 */
    public String seal(DeploymentAgentDeliveryPort.Prepared candidate, String revision) throws Exception {
        requireSealable(candidate, revision);
        var result = send(render("agent-seal", candidate.workspace(), revision), new byte[0], Duration.ofMinutes(2), 4096);
        var rejected = DeploymentAgentArtifactRejectedException.receipt(result, task.id(), candidate.workspace().candidateId(), revision);
        if (rejected.isPresent()) throw rejected.orElseThrow();
        return digest(result, "DIGEST");
    }

    /** Reads only the original task-owned candidate and build state. / 仅读取原任务所属候选与构建状态。
     * @param workspace exact original candidate / 精确原候选
     * @return confirmed state / 已确认状态
     * @throws Exception on unavailable or malformed evidence / 依据不可用或无效时 */
    public CandidateStatus candidateStatus(DeploymentRemoteWorkspace workspace) throws Exception {
        return candidateStatus(task.id(), workspace);
    }

    /** Queries an original candidate using this actual query task's authority, never its old context. / 使用此实际查询任务的权限核对原候选，绝不使用旧上下文。
     * @param originalTask exact persisted original owner UUID / 精确已持久原所有者UUID
     * @param workspace exact original candidate / 精确原候选
     * @return actual present and build state / 实际存在及构建状态
     * @throws Exception if exact identity or transport is unconfirmed / 精确身份或传输不明时 */
    public CandidateStatus candidateStatus(UUID originalTask, DeploymentRemoteWorkspace workspace) throws Exception {
        requireLayout(workspace);
        return switch (candidateStatusReceipt(originalTask, workspace).stdout()) {
            case "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n" -> new CandidateStatus(true, false);
            case "CANDIDATE_STATE=owned\nBUILD_ACTIVE=yes\n" -> new CandidateStatus(true, true);
            case "CANDIDATE_STATE=absent\nBUILD_ACTIVE=no\n" -> new CandidateStatus(false, false);
            case "CANDIDATE_STATE=absent\nBUILD_ACTIVE=yes\n" -> new CandidateStatus(false, true);
            default -> throw new IllegalStateException("Original candidate status invalid");
        };
    }

    /** Returns the complete original query receipt for bounded compensation evidence. */
    public CommandResult candidateStatusReceipt(UUID originalTask, DeploymentRemoteWorkspace workspace) throws Exception {
        return candidateStatusReceipt(originalTask, workspace, () -> {
        });
    }

    public CommandResult candidateStatusReceipt(UUID originalTask, DeploymentRemoteWorkspace workspace,
                                                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard guard) throws Exception {
        requireLayout(workspace);
        var result = send(originalStatusInvocation(originalTask, workspace), new byte[0], Duration.ofSeconds(20), 4096, false, null, null, guard);
        knownSuccess(result);
        return result.value().orElseThrow();
    }

    private static String originalStatusInvocation(UUID originalTask, DeploymentRemoteWorkspace workspace) {
        return List.of(workspace.layout().helperPath(), "candidate-query-task", workspace.applicationId(),
                workspace.candidateId(), Objects.requireNonNull(originalTask).toString()).stream()
            .map(DeploymentAgentProjectChannel::quote).collect(java.util.stream.Collectors.joining(" "));
    }

    /** Derives the same exact query frame consumed by candidateStatusReceipt. */
    public static String originalStatusRequestDigest(UUID task, String server, String configuration, DeploymentRemoteWorkspace workspace) {
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(originalStatusInvocation(task, workspace), workspace.layout()),
            new byte[0], Duration.ofSeconds(20), 4096)) {
            return sha256(RemoteActions.command(new ServerId(server), configuration, request).exactAction().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Removes only an inactive original task-owned candidate, then re-reads absence. / 仅清理原任务所属非活动候选，随后回查不存在。
     * @param workspace exact original candidate / 精确原候选
     * @throws Exception if ownership, outcome or cleanup is unconfirmed / 归属、结果或清理不明时 */
    public void cleanupOriginalCandidate(DeploymentRemoteWorkspace workspace) throws Exception {
        var observed = candidateStatus(workspace);
        if (observed.buildActive())
            throw new IllegalStateException("Original candidate build remains active");
        if (!observed.present()) {
            outputGenerations.merge(workspace.candidateId(), 1L, Long::sum);
            return;
        }
        var result = send(render("candidate-cleanup-task", workspace), new byte[0], Duration.ofSeconds(45), 4096,
            false);
        knownSuccess(result);
        if (!result.value().orElseThrow().stdout().equals("CANDIDATE_CLEANED=1\n")
            || candidateStatus(workspace).present())
            throw new IllegalStateException("Original candidate cleanup unconfirmed");
        outputGenerations.merge(workspace.candidateId(), 1L, Long::sum);
    }

    /** A dispatched cleanup may be unconfirmed; strict absence is established separately by recovery. */
    public record CompensationReceipt(boolean succeeded, String requestSha256) {
    }

    public static String originalCleanupRequestDigest(UUID task, String server, String configuration,
                                                      DeploymentRemoteWorkspace workspace) {
        try (var request = originalCleanupRequest(task, workspace)) {
            return sha256(RemoteActions.command(new ServerId(server), configuration, request).exactAction().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static CommandRequest originalCleanupRequest(UUID task, DeploymentRemoteWorkspace workspace) {
        String script = List.of(workspace.layout().helperPath(), "candidate-cleanup-task", workspace.applicationId(),
                workspace.candidateId(), task.toString()).stream().map(DeploymentAgentProjectChannel::quote)
            .collect(java.util.stream.Collectors.joining(" "));
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, workspace.layout()), new byte[0], Duration.ofSeconds(45), 4096);
    }

    /** Only the narrow recovery caller supplies prior ownership admission and a fresh before-send guard. */
    public CompensationReceipt compensateOriginalCandidate(DeploymentRemoteWorkspace workspace,
                                                           gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard guard) throws Exception {
        return compensateOriginalCandidate(workspace, guard, () -> {
        });
    }

    /** Persists the caller's exact send boundary after approval and guard, before any SDK execution. */
    public CompensationReceipt compensateOriginalCandidate(DeploymentRemoteWorkspace workspace,
                                                           gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard guard,
                                                           gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard dispatch) throws Exception {
        requireLayout(workspace);
        try (var request = originalCleanupRequest(task.id(), workspace)) {
            var intent = RemoteActions.command(server, configurationRevision, request).withSemantics(
                DeploymentAgentQuestionScope.meaning(server.id(), render("candidate-cleanup-task", workspace), new byte[0],
                    sha256((request.script() + "\n" + sha256(new byte[0])).getBytes(StandardCharsets.UTF_8))));
            String digest = sha256(intent.exactAction().getBytes(StandardCharsets.UTF_8));
            var review = DeploymentCommandReviewRouter.approve(interaction, intent, request, task);
            if (review.outcome() != TaskResult.SUCCEEDED || review.value().isEmpty())
                throw new DeploymentAiJson.CapabilityOutcomeException(review.outcome(), review.problem());
            guard.verify();
            dispatch.verify();
            guard.verify();
            try {
                var result = session.execute(request, review.value().orElseThrow());
                boolean succeeded = result != null && result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
                    && result.value().orElseThrow().exitCode().equals(Optional.of(0)) && !result.value().orElseThrow().truncated()
                    && result.value().orElseThrow().stderr().isEmpty() && result.value().orElseThrow().stdout().equals("CANDIDATE_CLEANED=1\n");
                return new CompensationReceipt(succeeded, digest);
            } catch (Exception unconfirmed) {
                return new CompensationReceipt(false, digest);
            }
        }
    }

    /** Checks candidate and current source binding. / 检查候选与当前来源绑定。
     * @param candidate opened candidate / 已开启候选
     * @param revision current revision / 当前修订 */
    private void scope(DeploymentAgentDeliveryPort.Prepared candidate, String revision) {
        if (!engines.containsKey(candidate.workspace().candidateId()) || !revision.matches("[0-9a-f]{64}"))
            throw new SecurityException("Unopened or foreign agent candidate");
    }

    /** Renders only fixed helper verbs and quoted scalar arguments. / 仅渲染固定 helper 动作和带引号的标量参数。
     * @param verb implementation-owned helper verb / 实现持有的 helper 动作
     * @param workspace exact candidate / 精确候选
     * @param arguments typed scalar arguments / 类型化标量参数
     * @return shell-safe fixed command / Shell 安全的固定命令 */
    private void requireLayout(DeploymentRemoteWorkspace workspace) {
        if (!layout.equals(workspace.layout()))
            throw new IllegalArgumentException("Agent workspace differs from the original layout");
    }

    private String render(String verb, DeploymentRemoteWorkspace workspace, String... arguments) {
        requireLayout(workspace);
        var values = new ArrayList<>(List.of(layout.helperPath(), verb, workspace.applicationId(),
            workspace.candidateId(), task.id().toString()));
        values.addAll(List.of(arguments));
        return values.stream().map(DeploymentAgentProjectChannel::quote)
            .collect(java.util.stream.Collectors.joining(" "));
    }

    /** Quotes one scalar for the fixed helper command. / 为固定 helper 命令引用一个标量。
     * @param value trusted scalar / 已核实标量
     * @return shell-safe literal / Shell 安全字面量 */
    private static String quote(String value) {
        if (value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("NUL in helper argument");
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Sends one exact reviewed operation with a fresh configuration check. / 经配置最新检查发送一项精确已审操作。
     * @param script fixed helper script / 固定 helper 脚本
     * @param stdin copied input / 复制输入
     * @param timeout deadline / 截止时间
     * @param limit output limit / 输出上限
     * @return original classified result / 原分类结果
     * @throws Exception on failed approval or transport / 审批或传输失败时 */
    private OperationResult<CommandResult> send(String script, byte[] stdin, Duration timeout, long limit)
        throws Exception {
        return send(script, stdin, timeout, limit, true);
    }

    /** Sends a fixed helper query or guarded effect through the same reviewed session. / 经同一已审阅会话发送固定 helper 查询或受保护效果。
     * @param script fixed helper script / 固定 helper 脚本
     * @param stdin copied input / 复制输入
     * @param timeout deadline / 截止时间
     * @param limit output limit / 输出上限
     * @param effect whether live configuration must match / 是否必须匹配实时配置
     * @return original classified result / 原分类结果
     * @throws Exception on review or transport failure / 审阅或传输失败时 */
    private OperationResult<CommandResult> send(String script, byte[] stdin, Duration timeout, long limit,
                                                boolean effect) throws Exception {
        return send(script, stdin, timeout, limit, effect, null, null);
    }

    private record PendingCommand(String candidate, String sourceRevision, String scriptSha256, long seconds) {
    }

    private OperationResult<CommandResult> send(String script, byte[] stdin, Duration timeout, long limit,
                                                boolean effect, PendingCommand pending, String mutatedCandidate) throws Exception {
        return send(script, stdin, timeout, limit, effect, pending, mutatedCandidate, () -> {
        });
    }

    private OperationResult<CommandResult> send(String script, byte[] stdin, Duration timeout, long limit,
                                                boolean effect, PendingCommand pending, String mutatedCandidate,
                                                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard guard) throws Exception {
        if (effect)
            requireConfiguration();
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(script, layout);
        try (var request = new CommandRequest(guarded, stdin, timeout, limit)) {
            var intent = RemoteActions.command(server, configurationRevision, request).withSemantics(
                DeploymentAgentQuestionScope.meaning(server.id(), script, stdin,
                    sha256((guarded + "\n" + sha256(stdin)).getBytes(StandardCharsets.UTF_8))));
            OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant> review;
            try {
                review = DeploymentCommandReviewRouter.approve(interaction, intent, request, task);
                if (review.outcome() != TaskResult.SUCCEEDED)
                    throw new DeploymentAiJson.CapabilityOutcomeException(review.outcome(), review.problem());
            } catch (ApiException | DeploymentAiJson.CapabilityOutcomeException failure) {
                if (!script.startsWith(quote(layout.helperPath()) + " 'agent-run' "))
                    throw failure;
                if (failure instanceof ApiException denied && denied.problem().code() == ProblemCode.ACCESS_DENIED
                    && denied.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED")) {
                    requireConfiguration();
                    throw DeploymentAgentAlternativeRequiredException.fromReview(task, denied);
                }
                if (pending == null) throw failure;
                throw new DeploymentAgentCommandNotSentException(failure, task.id(), pending.candidate(),
                    pending.sourceRevision(), pending.scriptSha256(), pending.seconds(),
                    sha256(intent.exactAction().getBytes(StandardCharsets.UTF_8)));
            }
            if (effect)
                requireConfiguration();
            if (mutatedCandidate != null)
                outputGenerations.merge(mutatedCandidate, 1L, Long::sum);
            long started = System.nanoTime();
            try {
                guard.verify();
                var result = session.execute(request, review.value().orElseThrow());
                guard.verify();
                return result;
            } finally {
                if (pending != null)
                    gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget.current()
                        .ifPresent(goal -> goal.build().charge(System.nanoTime() - started));
            }
        }
    }

    /** Rejects stale model policy before each tool approval and dispatch. / 每次工具审核与发送前拒绝过时模型策略。 */
    private void requireConfiguration() {
        if (!configurationRevision.equals(currentRevision.get()))
            throw new SecurityException("Agent model configuration changed");
    }

    /** Requires a complete known zero-exit result. / 要求完整已知零退出结果。
     * @param result original result / 原结果
     * @throws Exception if outcome or complete receipt is not successful / 结果或完整回执不成功时 */
    private static void knownSuccess(OperationResult<CommandResult> result) throws Exception {
        if (result.outcome() != TaskResult.SUCCEEDED)
            throw new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException(
                result.outcome(), result.problem());
        if (result.value().isEmpty() || !result.value().orElseThrow().exitCode().equals(Optional.of(0))
            || result.value().orElseThrow().truncated())
            throw new IllegalStateException("Agent helper result is not confirmed");
    }

    /** Extracts exact lowercase digest from a confirmed helper result. / 从已确认 helper 结果提取精确小写摘要。
     * @param result original result / 原结果
     * @param key original indexed checkpoint key / 原索引检查点键
     * @return digest / 摘要
     * @throws Exception if the original result or digest cannot be confirmed / 原结果或摘要无法确认时 */
    private static String digest(OperationResult<CommandResult> result, String key) throws Exception {
        knownSuccess(result);
        String prefix = key + "=";
        for (String line : result.value().orElseThrow().stdout().lines().toList())
            if (line.startsWith(prefix) && line.substring(prefix.length()).matches("[0-9a-f]{64}"))
                return line.substring(prefix.length());
        throw new IllegalStateException("Agent helper digest missing");
    }

    /** Hashes exact structured input for helper-side binding. / 对精确结构化输入散列以绑定 helper。
     * @param bytes wire payload / 传输载荷
     * @return lowercase digest / 小写摘要 */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
