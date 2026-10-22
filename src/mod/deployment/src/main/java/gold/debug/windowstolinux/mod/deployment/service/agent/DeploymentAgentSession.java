package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentDatabaseSourceSelection;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentDatabaseScope;

import java.util.function.Supplier;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlan;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlanProtocol;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlanNetworkConstraints;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProposal;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.service.agent.control.DeploymentAgentControl;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentModelBinding;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCommandNotSentException;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentPrepareNotSentException;

/** Serial evidence-driven deployment loop, independent of standard analysis. / 独立于标准分析的串行证据驱动部署循环。 */
public final class DeploymentAgentSession {
    /** Original platform and task. / 原平台与任务。 */
    private final ModContext context;

    /** Authentic original platform task context. / 真实原平台任务上下文。 */
    private final TaskContext task;

    /** Exact admitted server. / 精确已准入服务器。 */
    private final ServerId server;

    /** Frozen nonsecret constraints and source. / 冻结的不含秘密约束与来源。 */
    private final Map<String, String> requirements;

    /** Read-only original frozen source browser. / 原冻结来源只读浏览器。 */
    private final DeploymentSourceBrowser source;

    /** Strict model, real tools, durable own state. / 严格模型、真实工具与持久专有状态。 */
    private final DeploymentAgentModelBinding models;

    /** Original task-scoped candidate and publication operations. / 原任务范围内候选及发布操作。 */
    private final DeploymentAgentDeliveryPort tools;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentActionLog journal;

    /** Original cooperative safe-boundary control. / 原协作安全边界控制。 */
    private final DeploymentAgentControl control;

    /** Actual recent observations. / 最近真实观察。 */
    private String lastObservation = "OBSERVED";

    private String lastObservationId = "";

    /** Bounded correction data from the latest rejected batch, never new source evidence or execution authority. */
    private List<Map<String, Object>> recentRejections = List.of();

    /** Files actually read by the model. / 模型实际读过的文件。 */
    private final Set<String> read = new HashSet<>();

    /** Current independent judgement and actual evidence identities. / 当前独立判断及真实证据身份。 */
    private final DeploymentAgentEvidence evidence = new DeploymentAgentEvidence();

    /** Scope of the current serial observation. / 当前串行观察范围。 */
    private String observationComponent = "";

    /** Exact prepared candidate, source and output revisions. / 精确已准备候选、来源及输出修订。 */
    private final Map<String, DeploymentAgentDeliveryPort.Prepared> candidates = new LinkedHashMap<>();

    /** Verified protected source revision for each original candidate. / 每个原候选经核实的受保护源码修订。 */
    private final Map<String, String> revisions = new LinkedHashMap<>();

    /** Sealed output digests invalidated by edits or new builds. / 修改或新构建使之失效的封存输出摘要。 */
    private final Map<String, String> artifacts = new LinkedHashMap<>();

    private record CapabilityReceipt(String id, String revision, long plan, String root,
                                     gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition runtime,
                                     Integer exitStatus) {
    }

    private final Map<String, Map<String, CapabilityReceipt>> capabilityReceipts = new HashMap<>();

    private record RuntimeValidationReceipt(String binding, String observation) {
    }

    private final Map<String, RuntimeValidationReceipt> runtimeValidations = new HashMap<>();

    /** Invalidating attempts, not an invented hash or measurement of output contents. */
    private final Map<String, Long> outputMutationRevisions = new HashMap<>();

    /** Latest validated graph. / 最近已校验图。 */
    private DeploymentAgentPlan plan;

    /** Last accepted model declaration before resource resolution, never an approval. / 资源解析前最近接受的模型声明，绝不是审批。
     */
    private DeploymentAgentProposal.Plan acceptedPlan;

    /** Acceptance version distinct from later patch revisions. / 区别于后续补丁修订的声明接受版本。
     */
    private long acceptedAtPlanRevision;

    /** Monotonic plan and decision budget. / 单调计划与决策额度。 */
    private long planRevision;

    /** Source identities accepted by the current plan. / 当前计划接受的来源身份。 */
    private Map<String, String> acceptedSourceRevisions = Map.of();

    /** Monotonic decision allowance shared by all model turns. / 所有模型轮次共用的单调决策额度。 */
    private int remaining = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().agentModelResponses();

    private int toolAttempts;

    private int rejectedBatches;

    private String lastRejectedBatch = "";

    private long progressRevision;

    private int repeatedEvidenceBatches;

    private boolean repeatedEvidence;

    private final Set<BrowsePage> queriedPages = new HashSet<>();

    private gold.debug.windowstolinux.mod.deployment.service.agent.skill.DeploymentAgentSkills skills;

    private gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeHistory nativeHistory;

    private final Map<String, Integer> rejectedActions = new HashMap<>();

    /** Identity of an actually observed source page. / 已实际观察的源码页面身份。
     * @param component selected component or original source / 选定组件或原始来源
     * @param revision exact source revision / 精确源码修订
     * @param kind browse operation / 浏览操作
     * @param subject path or query / 路径或查询
     * @param offset page cursor / 页面游标
     * @param outputDigest actual page digest / 实际页面摘要 */
    private record BrowsePage(String component, String revision, String kind, String subject, int offset,
                              String outputDigest) {
    }

    /** Retained page IDs independent of rolling model history. / 独立于模型滚动历史的留存页面标识。 */
    private final Map<BrowsePage, String> browsed = new HashMap<>();

    /** Binds real task capabilities without starting work. / 绑定真实任务能力而不启动工作。
     * @param context active Mod / 活动 Mod
     * @param task original task / 原任务
     * @param server exact admitted server / 精确已准入服务器
     * @param requirements bounded nonsecret user requirements / 有界且不含秘密的用户要求
     * @param source frozen read-only source / 冻结只读来源
     * @param tools real scoped delivery tools / 真实限定交付工具
     * @param journal dedicated durable ledger / 专有持久账本
     * @param approvalMode selected task review policy / 任务所选审核策略
     * @param rememberAuthorization selected rememberAuthorization repetition / 所选授权重复规则
     * @param configurationRevision exact frozen model configuration / 精确冻结模型配置
     * @param currentConfigurationRevision live task-authorized revision / 实时任务授权修订 */
    public DeploymentAgentSession(ModContext context, TaskContext task, ServerId server,
                                  Map<String, String> requirements, DeploymentSourceBrowser source, DeploymentAgentDeliveryPort tools,
                                  DeploymentAgentActionLog journal, String approvalMode, boolean rememberAuthorization, String configurationRevision,
                                  Supplier<String> currentConfigurationRevision) {
        this(context, task, server, requirements, source, tools, journal, approvalMode, rememberAuthorization, configurationRevision,
            currentConfigurationRevision, new DeploymentAgentControl());
    }

    /** Binds explicit user control to the same original task and budgets. / 将显式用户控制绑定到同一原任务及预算。
     * @param context active Mod / 活动Mod
     * @param task original task / 原任务
     * @param server exact target / 精确目标
     * @param requirements bounded nonsecret requirements / 有界非秘密要求
     * @param source original frozen source / 原冻结来源
     * @param tools actual scoped tools / 实际限定工具
     * @param journal original evidence / 原依据
     * @param approvalMode immutable policy / 不可变策略
     * @param rememberAuthorization immutable rememberAuthorization repetition / 不可变授权重复规则
     * @param configurationRevision initial frozen route / 初始冻结路由
     * @param currentConfigurationRevision live route observer / 实时路由观察器
     * @param control original worker control / 原工作线程控制 */
    public DeploymentAgentSession(ModContext context, TaskContext task, ServerId server,
                                  Map<String, String> requirements, DeploymentSourceBrowser source, DeploymentAgentDeliveryPort tools,
                                  DeploymentAgentActionLog journal, String approvalMode, boolean rememberAuthorization, String configurationRevision,
                                  Supplier<String> currentConfigurationRevision, DeploymentAgentControl control) {
        this(context, task, server, requirements, source, tools, journal, approvalMode, rememberAuthorization, configurationRevision,
            currentConfigurationRevision, control, null);
    }

    public DeploymentAgentSession(ModContext context, TaskContext task, ServerId server,
                                  Map<String, String> requirements, DeploymentSourceBrowser source, DeploymentAgentDeliveryPort tools,
                                  DeploymentAgentActionLog journal, String approvalMode, boolean rememberAuthorization, String configurationRevision,
                                  Supplier<String> currentConfigurationRevision, DeploymentAgentControl control,
                                  gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource.Bound reviewSource) {
        this.context = Objects.requireNonNull(context);
        this.task = Objects.requireNonNull(task);
        this.server = Objects.requireNonNull(server);
        this.source = Objects.requireNonNull(source);
        this.tools = Objects.requireNonNull(tools);
        this.journal = Objects.requireNonNull(journal);
        this.control = Objects.requireNonNull(control);
        if (requirements.size() > 64 || requirements.toString().length() > 8000 || requirements.keySet().stream()
            .anyMatch(k -> k.matches("(?i).*(password|secret|credential|token|key).*")))
            throw new IllegalArgumentException("Agent requirements exceed nonsecret budget");
        this.requirements = Map.copyOf(requirements);
        this.models = new DeploymentAgentModelBinding(context, task, server, approvalMode, rememberAuthorization, configurationRevision,
            currentConfigurationRevision, journal, tools, reviewSource);
    }

    /** Runs until actual whole-application health succeeds or a bounded stop occurs. / 运行至真实整应用健康成功或有界停止。
     * @return actual publication outcome / 真实发布结果
     * @throws Exception on denied, unknown, cancelled or exhausted work / 工作被拒绝、未知、取消或耗尽时 */
    public OperationResult<DeploymentAgentDeliveryPort.Published> run() throws Exception {
        var goal = DeploymentAgentGoalBudget.current();
        if (goal.isPresent()) {
            remaining = goal.orElseThrow().snapshot().remainingResponses();
            toolAttempts = goal.orElseThrow().snapshot().mainTools();
        }
        try (var budget = DeploymentAgentGoalBudget.openToolBudget(task)) {
            return runBounded();
        }
    }

    private OperationResult<DeploymentAgentDeliveryPort.Published> runBounded() throws Exception {
        nativeHistory = new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeHistory();
        var callIds = new HashSet<String>();
        while (remaining > 0 && DeploymentAgentGoalBudget.current().map(value -> value.snapshot().remainingResponses() > 0).orElse(true)
            && toolAttempts < gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.maximum()
            && gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.remaining() > 0) {
            long priorProgress = progressRevision;
            repeatedEvidence = false;
            checkpoint();
            requireConfiguration();
            source.verifyBoundaries();
            String input = gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.facts(context(), remaining - 1);
            var offeredTools = gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.catalog(context())
                .stream().map(gold.debug.windowstolinux.appapi.ai.model.AiToolDefinition::name).collect(java.util.stream.Collectors.toSet());
            journal.event(task, "DECISION", "", Integer.toString(remaining));
            var response = models.model().decide(context(), nativeHistory, --remaining, task);
            if (response.finishReason().equals("length"))
                throw stopped("OUTPUT_BUDGET_EXHAUSTED", "Native response exhausted its output budget before a complete tool batch; no action was dispatched");
            var calls = response.toolCalls();
            if (calls.isEmpty())
                throw stopped("NO_NATIVE_CALLS", "Natural language cannot finish deployment; native finish gates are required");
            toolAttempts += calls.size();
            boolean withinBudget = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.consume(calls);
            DeploymentAgentGoalBudget.mainAttempts(task, calls.size());
            List<gold.debug.windowstolinux.appapi.ai.model.AiToolResult> results;
            var rejectionEvidence = new HashMap<String, String>();
            var openReviews = new HashSet<String>();
            boolean duplicate = calls.stream().anyMatch(call -> !callIds.add(call.id()));
            if (calls.size() > 4 || toolAttempts > gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.maximum()
                || !withinBudget || duplicate || !response.finishReason().equals("tool_calls")) {
                results = calls.stream().map(call -> new gold.debug.windowstolinux.appapi.ai.model.AiToolResult(call.id(),
                    "BATCH_REJECTED: at most four complete native calls, within the original "
                        + gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.maximum() + "-attempt budget", true)).toList();
                recordRejections(calls, results, rejectionEvidence);
            } else {
                final var terminal = new java.util.concurrent.atomic.AtomicReference<OperationResult<DeploymentAgentDeliveryPort.Published>>();
                results = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.execute(calls,
                    new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Adapter() {
                        public gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Execution execution(gold.debug.windowstolinux.appapi.ai.model.AiToolCall call) {
                            return gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.execution(call.name());
                        }

                        public void checkpoint() throws Exception {
                            DeploymentAgentSession.this.checkpoint();
                            requireConfiguration();
                            source.verifyBoundaries();
                        }

                        public boolean stopped() {
                            return terminal.get() != null;
                        }

                        public gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Prepared prepare(gold.debug.windowstolinux.appapi.ai.model.AiToolCall call) throws Exception {
                            if (!offeredTools.contains(call.name()) || !gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.available(call.name(), context()))
                                throw new IllegalArgumentException("Tool is currently unavailable");
                            DeploymentAgentProposal proposal;
                            try {
                                proposal = gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProtocol.parse(call.name(), DeploymentAiJson.arguments(call));
                            } catch (java.io.IOException invalid) {
                                throw new IllegalArgumentException("Invalid native parameters: " + invalid.getMessage());
                            }
                            if (execution(call) == gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Execution.LOCAL_READ)
                                return prepareRead(proposal);
                            return new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Prepared() {
                                public Object run() throws Exception {
                                    try {
                                        return perform(proposal);
                                    } catch (
                                        gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools.PlanConnectionRefusal refused) {
                                        if (plan != null || !candidates.isEmpty() || !DeploymentAgentActionLog.effectsSettled(journal.events(task)))
                                            throw refused.originalFailure();
                                        requireConfiguration();
                                        refused.requireBinding(task, source.revision(), models.revision(),
                                            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task).digest());
                                        rejectionEvidence.put(call.id(), observe("PLAN_CONNECTION", refused.feedback()));
                                        return refused;
                                    } catch (
                                        gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools.PlanCapacityRefusal refused) {
                                        requireConfiguration();
                                        refused.requireBinding(task, source.revision(), models.revision(),
                                            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task).digest());
                                        rejectionEvidence.put(call.id(), observe("PLAN_CAPACITY", refused.feedback()));
                                        return refused;
                                    } catch (gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools.IsolationRefusal refused) {
                                        requireIsolationRefusal(refused);
                                        if (!DeploymentAgentActionLog.effectsSettled(journal.events(task)))
                                            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_ISOLATION_REQUIRES_RECOVERY", "Earlier work still requires reconciliation", Map.of(), Optional.of(task.id())));
                                        rejectionEvidence.put(call.id(), observe("ISOLATION_ADMISSION", refused.feedback()));
                                        return refused;
                                    } catch (
                                        gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException refused) {
                                        if (!DeploymentAgentActionLog.effectsSettled(journal.events(task)))
                                            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_CAPACITY_REQUIRES_RECOVERY", "Earlier work still requires reconciliation", Map.of(), Optional.of(task.id())));
                                        requireConfiguration();
                                        rejectionEvidence.put(call.id(), observe("WORKSPACE_CAPACITY", refused.feedback()));
                                        return refused;
                                    } catch (DeploymentAgentAlternativeRequiredException refused) {
                                        if (!DeploymentAgentActionLog.effectsSettled(journal.events(task)))
                                            throw new ApiException(new Problem(ProblemCode.UNKNOWN,
                                                "DEPLOYMENT_ALTERNATIVE_PATH_REQUIRES_RECOVERY", "Earlier work still requires reconciliation", Map.of(), Optional.of(task.id())));
                                        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval.requireRoute(context, task, models.revision());
                                        String feedback = "APPROVAL_DENIED: the exact action was not sent. Independent review feedback DATA:\n"
                                            + refused.feedback();
                                        if (refused.discussionOpen()) openReviews.add(call.id());
                                        rejectionEvidence.put(call.id(), observe("ALTERNATIVE_REQUIRED", feedback));
                                        return refused;
                                    }
                                }

                                public gold.debug.windowstolinux.appapi.ai.model.AiToolResult commit(gold.debug.windowstolinux.appapi.ai.model.AiToolCall original, Object value) {
                                    if (value instanceof gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools.IsolationRefusal || value instanceof DeploymentAgentAlternativeRequiredException || value instanceof gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException
                                        || value instanceof gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools.PlanCapacityRefusal
                                        || value instanceof gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools.PlanConnectionRefusal)
                                        return new gold.debug.windowstolinux.appapi.ai.model.AiToolResult(original.id(), lastObservation, true);
                                    if (value != null)
                                        terminal.set((OperationResult<DeploymentAgentDeliveryPort.Published>) value);
                                    return new gold.debug.windowstolinux.appapi.ai.model.AiToolResult(original.id(), lastObservation, false);
                                }
                            };
                        }
                    });
                recordRejections(calls, results, rejectionEvidence);
                if (terminal.get() != null) return terminal.get();
            }
            nativeHistory.retain(input, response, results);
            if (progressRevision != priorProgress) {
                repeatedEvidenceBatches = 0;
                rejectedActions.clear();
                rejectedBatches = 0;
                lastRejectedBatch = "";
            } else if (openReviews.isEmpty() && results.stream().allMatch(gold.debug.windowstolinux.appapi.ai.model.AiToolResult::error)) {
                var failures = new ArrayList<String>();
                for (int index = 0; index < results.size(); index++)
                    failures.add(calls.get(index).name() + ":" + digest(calls.get(index).arguments()) + ":" + results.get(index).text());
                failures.sort(String::compareTo);
                String signature = digest(source.revision() + ":" + planRevision + ":" + progressRevision + ":" + String.join("\n", failures));
                rejectedBatches = signature.equals(lastRejectedBatch) ? rejectedBatches + 1 : 1;
                lastRejectedBatch = signature;
                if (rejectedBatches >= 2)
                    throw stopped("NO_PROGRESS", "Two consecutive native batches repeated the same rejection; correct the recorded parameters or evidence");
            } else {
                rejectedBatches = 0;
                lastRejectedBatch = "";
            }
            if (progressRevision == priorProgress && repeatedEvidence && ++repeatedEvidenceBatches >= 2)
                throw stopped("REPEATED_EVIDENCE", "Repeated evidence queries without new source, identification, plan or execution progress");
            for (int index = 0; index < results.size(); index++)
                if (results.get(index).error() && !openReviews.contains(calls.get(index).id())) {
                    var call = calls.get(index);
                    String identity = source.revision() + ":" + planRevision + ":" + call.name() + ":" + digest(call.arguments());
                    if (rejectedActions.merge(identity, 1, Integer::sum) >= 2)
                        throw stopped("REPEATED_PROPOSAL", "Repeated rejected " + call.name() + " parameters without new validated progress");
                }
        }
        throw stopped("BUDGET_EXHAUSTED", "Autonomous task model or tool-attempt budget exhausted");
    }

    private void recordRejections(List<gold.debug.windowstolinux.appapi.ai.model.AiToolCall> calls,
                                  List<gold.debug.windowstolinux.appapi.ai.model.AiToolResult> results, Map<String, String> rejectionEvidence) throws Exception {
        var rejected = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < results.size(); index++) {
            gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentToolRejected(
                context, task, calls.get(index), results.get(index));
            var result = results.get(index);
            if (result.error()) {
                String reason = DeploymentAgentFeedback.safe("PROPOSAL_REJECTED", result.text());
                var feedback = new LinkedHashMap<String, Object>();
                feedback.put("callId", calls.get(index).id());
                feedback.put("tool", calls.get(index).name());
                feedback.put("reason", reason.substring(0, Math.min(512, reason.length())));
                feedback.put("truncated", reason.length() > 512);
                feedback.put("evidenceId", rejectionEvidence.get(calls.get(index).id()));
                rejected.add(Collections.unmodifiableMap(feedback));
            }
        }
        if (!rejected.isEmpty()) recentRejections = List.copyOf(rejected.subList(0, Math.min(4, rejected.size())));
    }

    private IllegalStateException stopped(String reason, String message) {
        gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentStopped(context, task, reason);
        return new IllegalStateException(message);
    }

    private record ReadPage(String component, String revision, String kind, String subject, int offset, int limit,
                            String output, DeploymentSourceBrowser.ReadPage sourcePage) {
        private ReadPage(String component, String revision, String kind, String subject, int offset, int limit, String output) {
            this(component, revision, kind, subject, offset, limit, output, null);
        }
    }

    private gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Prepared prepareRead(DeploymentAgentProposal proposal) throws Exception {
        final java.util.concurrent.Callable<ReadPage> work;
        switch (proposal) {
            case DeploymentAgentProposal.CurrentSource value -> {
                component(value.component());
                var browser = tools.source(value.component());
                work = () -> value.kind().equals("read") ? sourcePage(browser, value.component(), value.path(), value.offset(), value.limit())
                    : new ReadPage(value.component(), browser.revision(), value.kind().toUpperCase(Locale.ROOT), value.path(), value.offset(), value.limit(), switch (value.kind()) {
                    case "list" ->
                        String.join("\n", browser.list(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit()));
                    case "index" ->
                        browser.index(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit());
                    case "search" -> String.join("\n", browser.search(value.path(), value.offset(), value.limit()));
                    default -> throw new IllegalArgumentException("Invalid source kind");
                });
            }
            case DeploymentAgentProposal.ReadSource value ->
                work = () -> sourcePage(source, "", value.path(), value.offset(), value.limit());
            case DeploymentAgentProposal.ListSource value ->
                work = () -> new ReadPage("", source.revision(), "LIST", value.path(), value.offset(), value.limit(),
                    String.join("\n", source.list(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit())));
            case DeploymentAgentProposal.SourceIndex value ->
                work = () -> new ReadPage("", source.revision(), "INDEX", value.path(), value.offset(), value.limit(),
                    source.index(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit()));
            case DeploymentAgentProposal.SearchSource value ->
                work = () -> new ReadPage("", source.revision(), "SEARCH", value.query(), value.offset(), value.limit(),
                    String.join("\n", source.search(value.query(), value.offset(), value.limit())));
            case DeploymentAgentProposal.PlanState value -> {
                work = () -> planPage(value);
            }
            case DeploymentAgentProposal.Observation value -> {
                String output = evidence.read(value.id(), value.offset(), value.limit());
                work = () -> new ReadPage("", source.revision(), "OBSERVATION", value.id(), value.offset(), value.limit(), output);
            }
            case DeploymentAgentProposal.EvidenceIndex value -> {
                String output = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(evidence.index(value.offset(), value.limit()));
                work = () -> new ReadPage("", source.revision(), "EVIDENCE_INDEX", "index", value.offset(), value.limit(), output);
            }
            default -> throw new IllegalArgumentException("Tool is not an independent local read");
        }
        return new gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Prepared() {
            public Object run() throws Exception {
                try {
                    return work.call();
                } catch (DeploymentSourceBrowser.MissingMemberException missing) {
                    throw new IllegalArgumentException("FILE_NOT_FOUND: " + missing.getMessage());
                }
            }

            public gold.debug.windowstolinux.appapi.ai.model.AiToolResult commit(gold.debug.windowstolinux.appapi.ai.model.AiToolCall call, Object value) throws Exception {
                var page = (ReadPage) value;
                observationComponent = page.component();
                if (Set.of("OBSERVATION", "EVIDENCE_INDEX", "PLAN_STATE").contains(page.kind())) {
                    query(page);
                    return new gold.debug.windowstolinux.appapi.ai.model.AiToolResult(call.id(), lastObservation, false);
                }
                retainPage(page);
                return new gold.debug.windowstolinux.appapi.ai.model.AiToolResult(call.id(), lastObservation, false);
            }
        };
    }

    private static ReadPage sourcePage(DeploymentSourceBrowser browser, String component, String path, int offset, int limit) throws Exception {
        var page = browser.readPage(path, offset, limit, 11000);
        String output = page.path() + "\nsourceSha256=" + page.sourceSha256()
            + "\ntotalLines=" + page.totalLines() + " offset=" + page.offset() + " nextLine=" + page.nextLine() + " hasMore=" + page.hasMore()
            + "\nOnly if hasMore=true, continue unread source with read or source kind=read at line offset nextLine. Observation offsets page characters of this retained body only, never the unread file tail.\n"
            + String.join("\n", page.lines());
        if (output.length() > 12000)
            throw new IllegalArgumentException("Source READ metadata exceeds retention budget");
        return new ReadPage(component, browser.revision(), "READ", page.path(), page.offset(), limit, output, page);
    }

    private void retainPage(ReadPage page) throws Exception {
        observeBrowse(page.component(), page.revision(), page.kind(), page.subject(), page.offset(), page.output());
        evidence.page(lastObservationId, page.subject(), page.offset(), page.limit(), page.output().length() > 12000);
        if (page.sourcePage() != null) {
            var sourcePage = page.sourcePage();
            evidence.readPage(lastObservationId, sourcePage.offset(), sourcePage.totalLines(), sourcePage.nextLine(), sourcePage.hasMore());
        }
        if (page.kind().equals("READ") && page.component().isEmpty()) read.add(page.subject());
    }

    private void query(ReadPage page) throws Exception {
        var identity = new BrowsePage(page.component(), page.revision(), page.kind(), page.subject(), page.offset(), digest(page.output()));
        repeatedEvidence |= !queriedPages.add(identity) && nativeHistory.visibleToolResult(page.output());
        lastObservation = page.output();
        journal.event(task, "AGENT_QUERY", page.kind(), digest(page.output()));
        gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentObservation(context, task, page.kind());
        task.progress(DeploymentAgentFeedback.label(context, page.kind()), Optional.empty());
    }

    /** Dispatches only one strict proposal using actual tool feedback. / 仅根据真实工具反馈调度一项严格提案。
     * @param proposal checked model proposal / 已检查模型提案
     * @return terminal publication or null to continue / 终态发布结果，继续时为空
     * @throws Exception on denied or unknown tool effect / 工具影响被拒绝或未知时 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> perform(DeploymentAgentProposal proposal)
        throws Exception {
        if (proposal instanceof DeploymentAgentProposal.Assessed value) {
            evidence.assess(value.action().getClass().getSimpleName(), value.assessment(), evidenceRevisions(), source.revision(), planRevision);
            journal.event(task, "AGENT_ASSESSMENT", value.action().getClass().getSimpleName(), digest(value.assessment().toString()));
            proposal = value.action();
        }
        observationComponent = switch (proposal) {
            case DeploymentAgentProposal.Capability value -> value.component();
            case DeploymentAgentProposal.CurrentSource value -> value.component();
            case DeploymentAgentProposal.Command value -> value.component();
            case DeploymentAgentProposal.ValidateRuntime value -> value.component();
            case DeploymentAgentProposal.ReadRemote value -> value.component();
            case DeploymentAgentProposal.Patch value -> value.component();
            case DeploymentAgentProposal.Seal value -> value.component();
            default -> "";
        };
        return dispatch(proposal);
    }

    /** Dispatches one validated serial action. / 派发一项已校验串行动作。
     * @param proposal selected action / 选定动作
     * @return verified publication or continuing feedback / 已核实发布或继续反馈
     * @throws Exception failed or uncertain original action / 原动作失败或未知 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> dispatch(DeploymentAgentProposal proposal) throws Exception {
        return switch (proposal) {
            case DeploymentAgentProposal.LoadSkill value -> {
                if (skills == null)
                    skills = new gold.debug.windowstolinux.mod.deployment.service.agent.skill.DeploymentAgentSkills(
                        task, gold.debug.windowstolinux.mod.deployment.service.agent.skill.DeploymentAgentSkills.Role.DEPLOYMENT);
                var loaded = skills.read(value.id(), task);
                query(new ReadPage("", source.revision(), "PRODUCT_SKILL", value.id(), 0, 1,
                    DeploymentAiJson.facts(loaded, 10000)));
                yield null;
            }
            case DeploymentAgentProposal.ReviewResponse value -> {
                observe("REVIEW_RESPONSE", DeploymentAiJson.facts(tools.reviewResponse(task, value.reviewBinding(), value.message()), 1000));
                yield null;
            }
            case DeploymentAgentProposal.EvidenceIndex ignored ->
                throw new IllegalArgumentException("Evidence index requires a local read group");
            case DeploymentAgentProposal.PlanState value -> {
                readPlan(value);
                yield null;
            }
            case DeploymentAgentProposal.Observation value -> {
                query(new ReadPage("", source.revision(), "OBSERVATION", value.id(), value.offset(), value.limit(),
                    evidence.read(value.id(), value.offset(), value.limit())));
                yield null;
            }
            case DeploymentAgentProposal.Assessed ignored ->
                throw new IllegalArgumentException("Nested decision envelope");
            case DeploymentAgentProposal.Identify value -> {
                evidence.identify(value.findings(), evidenceRevisions(), source.revision());
                observe("IDENTIFY", value.findings().toString());
                progressRevision++;
                yield null;
            }
            case DeploymentAgentProposal.CurrentSource value -> {
                browse(proposal);
                yield null;
            }
            case DeploymentAgentProposal.Capability value -> {
                capability(value);
                yield null;
            }
            case DeploymentAgentProposal.ListSource value -> {
                browse(proposal);
                yield null;
            }
            case DeploymentAgentProposal.SourceIndex value -> {
                browse(proposal);
                yield null;
            }
            case DeploymentAgentProposal.ReadSource value -> {
                browse(proposal);
                yield null;
            }
            case DeploymentAgentProposal.SearchSource value -> {
                browse(proposal);
                yield null;
            }
            case DeploymentAgentProposal.InspectServer ignored -> {
                observe("SERVER", new TreeMap<>(tools.inspect()).toString());
                yield null;
            }
            case DeploymentAgentProposal.Plan value -> {
                plan(value);
                yield null;
            }
            case DeploymentAgentProposal.Command value -> {
                command(value);
                yield null;
            }
            case DeploymentAgentProposal.ValidateRuntime value -> {
                validateRuntime(value.component());
                yield null;
            }
            case DeploymentAgentProposal.HostCommand value -> {
                checkpoint();
                requireConfiguration();
                observe("HOST_COMMAND", tools.hostCommand(task, value.script(), value.seconds(), value.reason()));
                yield null;
            }
            case DeploymentAgentProposal.ReadRemote value -> {
                var candidate = prepare(value.component());
                observe("READ", value.path() + "\n" + tools.read(task, candidate, revisions.get(value.component()), value.path(),
                    value.offset(), value.limit()));
                yield null;
            }
            case DeploymentAgentProposal.Patch value -> {
                patch(value);
                yield null;
            }
            case DeploymentAgentProposal.Seal value -> {
                seal(value.component());
                yield null;
            }
            case DeploymentAgentProposal.NeedInput value -> {
                input(value.question());
                yield null;
            }
            case DeploymentAgentProposal.Unable value -> {
                observe("HANDOFF", value.reason());
                throw new IllegalStateException("Deployment model cannot continue");
            }
            case DeploymentAgentProposal.Deliver ignored -> deliver();
        };
    }

    /** Reads current plan details without relying on rolling feedback. / 不依赖滚动反馈读取当前计划详情。
     * @param value bounded plan page / 有界计划页
     * @throws Exception unavailable current plan / 当前计划不可用时 */
    private void readPlan(DeploymentAgentProposal.PlanState value) throws Exception {
        query(planPage(value));
    }

    /** Pages the accepted declaration with current status outside its native fields. / 将当前状态置于原生字段外，分页读取已接受声明。
     * @param value selected component and character cursor / 选定组件与字符游标
     * @return bounded JSON query without new evidence or permission / 不新增依据或权限的有界JSON查询
     * @throws Exception if the accepted declaration or actual current source cannot be inspected / 无法核验已接受声明或实际当前来源时 */
    private ReadPage planPage(DeploymentAgentProposal.PlanState value) throws Exception {
        component(value.component());
        var part = acceptedPlan.plan().components().stream().filter(candidate -> candidate.id().equals(value.component())).findFirst().orElseThrow();
        String revision = tools.source(value.component()).revision();
        boolean sourceCurrent = revision.equals(acceptedSourceRevisions.get(value.component()));
        boolean readsCurrent;
        try {
            evidence.requireReads(part.id(), part.sourceRoot(), part.evidence(), revision);
            readsCurrent = true;
        } catch (IllegalArgumentException stale) {
            readsCurrent = false;
        }
        String declaration = DeploymentAgentPlanProtocol.encode(acceptedPlan).toString();
        var needsSeal = plan.components().stream().map(DeploymentAgentPlan.Part::id).filter(id -> !artifacts.containsKey(id)).sorted().toList();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var output = json.createObjectNode().put("component", value.component()).put("acceptedAtPlanRevision", acceptedAtPlanRevision)
            .put("currentPlanRevision", planRevision).put("sourceCurrent", sourceCurrent).put("readsCurrent", readsCurrent)
            .put("selectedComponentNeedsSeal", needsSeal.contains(value.component()));
        output.set("needsSeal", json.valueToTree(needsSeal));
        output.set("executionLayout", json.valueToTree(executionLayout(part)));
        output.set("workspace", json.valueToTree(component(value.component()).workspaceSelection().orElseThrow().facts()));
        output.set("capabilities", json.valueToTree(capabilityFacts(component(value.component()))));
        output.set("acceptedSourceRevisions", json.valueToTree(new TreeMap<>(acceptedSourceRevisions)));
        output.put("statusSha256", digest(output.toString())).put("declarationSha256", digest(declaration)).put("total", declaration.length());
        int start = Math.min(value.offset(), declaration.length()), end = Math.min(start + value.limit(), declaration.length());
        output.put("offset", start);
        int low = start, high = end;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            output.put("next", middle).put("hasMore", middle < declaration.length()).put("declarationPage", declaration.substring(start, middle));
            if (output.toString().length() <= 12000) low = middle;
            else high = middle - 1;
        }
        end = low;
        output.put("next", end).put("hasMore", end < declaration.length()).put("declarationPage", declaration.substring(start, end));
        if (output.toString().length() > 12000 || start < declaration.length() && end == start)
            throw new IllegalArgumentException("Accepted plan status exceeds bounded query budget");
        return new ReadPage(value.component(), revision, "PLAN_STATE", value.component(), value.offset(), value.limit(), output.toString());
    }

    /** Browses current source without executing effects / 浏览当前来源而不执行效果。
     * @param proposal Current source-backed proposal / 当前来源支持提案
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    private void browse(DeploymentAgentProposal proposal) throws Exception {
        switch (proposal) {
            case DeploymentAgentProposal.CurrentSource value -> {
                var current = tools.source(value.component());
                if (value.kind().equals("read")) {
                    retainPage(sourcePage(current, value.component(), value.path(), value.offset(), value.limit()));
                    return;
                }
                String output = switch (value.kind()) {
                    case "list" ->
                        String.join("\n", current.list(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit()));
                    case "search" -> String.join("\n", current.search(value.path(), value.offset(), value.limit()));
                    case "index" ->
                        current.index(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit());
                    default -> throw new IllegalArgumentException("Invalid current-source operation");
                };
                observeBrowse(value.component(), current.revision(), value.kind().toUpperCase(Locale.ROOT),
                    value.path(), value.offset(), output);

            }
            case DeploymentAgentProposal.ListSource value -> {
                observeBrowse("", source.revision(), "LIST", value.path(), value.offset(), String.join("\n",
                    source.list(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit())));

            }
            case DeploymentAgentProposal.ReadSource value -> {
                retainPage(sourcePage(source, "", value.path(), value.offset(), value.limit()));

            }
            case DeploymentAgentProposal.SourceIndex value -> {
                observeBrowse("", source.revision(), "INDEX", value.path(), value.offset(),
                    source.index(value.path().equals(".") ? "" : value.path(), value.offset(), value.limit()));
            }
            case DeploymentAgentProposal.SearchSource value -> {
                observeBrowse("", source.revision(), "SEARCH", value.query(), value.offset(),
                    String.join("\n", source.search(value.query(), value.offset(), value.limit())));

            }
            default -> throw new IllegalArgumentException("Not a source browsing action");
        }
    }

    /** Rejects an unchanged page as no progress and exposes its real retained ID. / 将未变化页面判为无进展并提供其真实留存标识。
     * @param component selected component or original source / 选定组件或原始来源
     * @param revision exact source revision / 精确源码修订
     * @param kind actual browse operation / 实际浏览操作
     * @param subject path or query / 路径或查询
     * @param offset page cursor / 页面游标
     * @param output actual page content / 实际页面内容
     * @throws Exception if repeated or actual evidence cannot be retained / 重复或实际依据无法留存时 */
    private void observeBrowse(String component, String revision, String kind, String subject, int offset, String output) throws Exception {
        var page = new BrowsePage(component, revision, kind, subject, offset, digest(output));
        String previous = browsed.get(page);
        if (previous != null)
            throw new IllegalArgumentException("Unchanged evidence page already retained as " + previous
                + "; use observation with that id to recover existing evidence, then choose a new action");
        browsed.put(page, observe(kind, output));
        if (Set.of("READ", "LIST", "SEARCH").contains(kind)
            && output.lines().skip(kind.equals("READ") ? 4 : 0).anyMatch(line -> !line.isBlank())) progressRevision++;
    }

    /** Selects a shared capability and preserves its original effect boundary. / 选择公共能力并保留其原效果边界。
     * @param value validated selection / 已验证选择
     * @throws Exception unavailable or uncertain capability / 能力不可用或未知 */
    private void capability(DeploymentAgentProposal.Capability value) throws Exception {

        var selected = Set.of("secrets", "reconnect").contains(value.name()) ? null : component(value.component());
        if (selected != null)
            evidence.requireIdentified(selected.id(), selected.sourceRoot(), evidenceRevisions().get(selected.id()));
        if (value.name().equals("database_select_source"))
            evidence.requireDatabaseSource(selected.id(), selected.sourceRoot(),
                DeploymentAgentDatabaseSourceSelection.parse(value.arguments().get("sourceSelection")), evidenceRevisions().get(selected.id()));
        if (Set.of("prepare_tools", "build", "databases").contains(value.name()))
            tools.validateCapability(value, selected);
        boolean remoteInventory = value.name().equals("database_inspect")
            && DeploymentAgentDatabaseScope.parse(value.arguments()) == DeploymentAgentDatabaseScope.REMOTE;
        var candidate = (Set.of("build", "prepare_tools", "databases").contains(value.name()) || remoteInventory)
            ? prepare(value.component()) : candidates.get(value.component());
        if (value.name().equals("build")) {
            requireCurrentPlan(selected);
            if (!artifacts.keySet().containsAll(selected.dependencies()))
                throw new IllegalArgumentException("Build dependencies must be sealed");
            artifacts.remove(value.component());
            invalidateRuntimeValidation(value.component());
        }
        String binding = digest(value + ":" + planRevision + ":" + revisions.get(value.component()));
        boolean effect = Set.of("prepare_tools", "build", "databases", "secret_create").contains(value.name());
        if (effect) requireIsolationAdmission();
        if (effect) journal.event(task, "CAPABILITY_EXECUTING", binding, value.name());
        String output;
        try {
            output = tools.capability(value, selected, candidate, revisions.get(value.component()), binding, plan == null ? "" : plan.applicationId());
        } catch (DeploymentAgentCommandNotSentException stopped) {
            if (value.name().equals("build") && candidate != null && value.arguments().path("seconds").isIntegralNumber()
                && stopped.matches(task.id(), candidate.workspace().candidateId(), revisions.get(value.component()),
                value.arguments().path("seconds").longValue())) {
                journal.event(task, "CAPABILITY_DISPATCH_NOT_SENT", binding, stopped.dispatcherProof());
                journal.event(task, "CAPABILITY_NOT_SENT", binding, DeploymentAgentCommandNotSentException.DETAIL);
            }
            throw stopped.originalFailure();
        } catch (DeploymentAiJson.CapabilityOutcomeException classified) {
            if (effect && classified.outcome() != TaskResult.UNKNOWN)
                journal.event(task, "CAPABILITY_RESULT", binding, classified.outcome().name());
            String diagnostic = gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainFailureEvidence.observation(classified.problem());
            if (!diagnostic.isEmpty())
                observe("TOOL_" + value.name(), classified.outcome() + " " + classified.problem().map(Problem::eventCode).orElse("unconfirmed failure") + diagnostic);
            if (classified.outcome() == TaskResult.UNKNOWN || classified.outcome() == TaskResult.CANCELLED)
                throw classified;
            if (diagnostic.isEmpty())
                observe("TOOL_" + value.name(), classified.outcome() + " " + classified.problem().map(Problem::eventCode).orElse("known failure"));
            return;
        } catch (DeploymentAgentAlternativeRequiredException refused) {
            if (effect) journal.event(task, "CAPABILITY_NOT_SENT", binding, "ALTERNATIVE_REQUIRED");
            throw refused;
        } catch (IllegalArgumentException known) {
            if (effect) journal.event(task, "CAPABILITY_RESULT", binding, "REJECTED");
            throw known;
        }
        if (effect) journal.event(task, "CAPABILITY_RESULT", binding, value.name());
        observe("TOOL_" + value.name(), output);
        if (Set.of("analyze", "prepare_tools", "build").contains(value.name()))
            rememberCapability(selected, value.name(), null);
        progressRevision++;
        return;
    }

    /** Retains only an evidence-backed plan with stable prepared backends. / 仅保留有依据且已准备后端稳定的计划。
     * @param proposal graph proposal / 图提案
     * @throws Exception if original evidence cannot be saved / 原依据无法保存时 */
    private void plan(DeploymentAgentProposal.Plan proposal) throws Exception {
        DeploymentAgentPlanNetworkConstraints.require(proposal.plan(), requirements);
        for (var component : proposal.plan().components()) {
            String revision = evidenceRevisions().getOrDefault(component.id(), source.revision());
            evidence.requireIdentified(component.id(), component.sourceRoot(), revision);
            evidence.requireReads(component.id(), component.sourceRoot(), component.evidence(), revision);
        }
        if (plan != null)
            for (var candidate : candidates.keySet()) {
                var before = component(candidate);
                var after = proposal.plan().components().stream().filter(c -> c.id().equals(candidate)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Prepared component removed"));
                if (!before.runtime().getClass().equals(after.runtime().getClass()))
                    throw new IllegalArgumentException("Prepared sandbox backend changed");
            }
        var inherited = DeploymentAgentGoal.currentWorker().flatMap(DeploymentAgentGoal.Worker::predecessor);
        if (inherited.isPresent() && (!proposal.plan().applicationId().equals(inherited.orElseThrow().application())
            || !proposal.plan().components().stream().map(DeploymentAgentPlan.Part::id).collect(java.util.stream.Collectors.toSet())
            .equals(inherited.orElseThrow().resources().keySet())))
            throw new IllegalArgumentException("Successor application or component ownership differs from the original goal");
        var resolved = new ArrayList<DeploymentAgentPlan.Part>();
        var resourceLayout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        for (var component : proposal.plan().components()) {
            var part = tools.resolve(component);
            gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlanResourceConstraints.require(part,
                Optional.ofNullable(candidates.get(part.id())).map(candidate -> candidate.workspace().applicationId()), resourceLayout);
            resolved.add(part);
        }
        var declared = new DeploymentAgentPlan(proposal.plan().applicationId(), proposal.plan().healthComponentId(), proposal.plan().health(), resolved);
        var nextPlan = new DeploymentAgentPlan(declared.applicationId(), declared.healthComponentId(), declared.health(), tools.selectWorkspaces(declared, Map.copyOf(candidates)));
        var sources = new LinkedHashMap<String, String>();
        for (var component : proposal.plan().components())
            sources.put(component.id(), tools.source(component.id()).revision());
        tools.acceptWorkspaces(nextPlan);
        plan = nextPlan;
        acceptedPlan = proposal;
        acceptedSourceRevisions = Map.copyOf(sources);
        planRevision++;
        acceptedAtPlanRevision = planRevision;
        evidence.invalidate("");
        journal.event(task, "PLAN_APPLICATION", "", plan.applicationId());
        for (String id : artifacts.keySet())
            journal.event(task, "ARTIFACT_INVALIDATED", id, "plan");
        artifacts.clear();
        runtimeValidations.clear();
        journal.event(task, "PLAN", "", digest(plan.toString()));
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        for (var part : plan.components()) {
            var quota = part.workspaceSelection().orElseThrow();
            journal.event(task, "WORKSPACE_QUOTA", part.id(), new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("task", task.id().toString()).put("server", server.id()).put("source", source.revision()).put("configuration", models.revision())
                .put("layout", layout.digest()).put("plan", digest(plan.toString())).put("workspaceBytes", quota.bytes())
                .put("reserveBytes", quota.policy().reserveBytes()).put("mode", quota.request().mode().name()).toString());
        }
        observe("PLAN", proposal.explanation() + "\n" + plan);
        progressRevision++;
    }

    /** Runs one approved sandbox command and preserves uncertain effects. / 执行一项已审核沙箱命令并保留不确定影响。
     * @param proposal exact command / 精确命令
     * @throws Exception on denied or unknown work / 工作被拒绝或未知时 */
    private void command(DeploymentAgentProposal.Command proposal) throws Exception {
        executeCommand(proposal, false);
    }

    private DeploymentAgentDeliveryPort.CommandFeedback executeCommand(DeploymentAgentProposal.Command proposal,
                                                                       boolean runtimeValidation) throws Exception {
        try {
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate.requireAllowed(proposal.script());
        } catch (SecurityException refused) {
            String code = refused.getMessage();
            if (!"DEPLOYMENT_COMMAND_STATIC_INVALID".equals(code) && !"DEPLOYMENT_COMMAND_STATIC_DENIED".equals(code))
                throw refused;
            String component = proposal.component();
            String identity = component != null && component.matches("[A-Za-z][A-Za-z0-9_-]{0,62}") ? component : "INVALID";
            throw new IllegalArgumentException(code + ": NOT_SENT task=" + task.id() + " component=" + identity
                + " scriptSha256=" + (proposal.script() == null ? "ABSENT" : digest(proposal.script())), refused);
        }
        var component = component(proposal.component());
        requireCurrentPlan(component);
        if (!artifacts.keySet().containsAll(component.dependencies()))
            throw new IllegalArgumentException("Build dependencies first");
        var candidate = prepare(component.id());
        var goal = DeploymentAgentGoalBudget.current();
        if (goal.isPresent() && proposal.seconds() > goal.orElseThrow().build().remainingSeconds())
            throw new IllegalArgumentException("Original goal build allowance cannot fund this exact candidate command");
        if (artifacts.containsKey(component.id()))
            journal.event(task, "ARTIFACT_INVALIDATED", component.id(), "command");
        artifacts.remove(component.id());
        invalidateRuntimeValidation(component.id());
        String binding = digest(component.id() + ":" + revisions.get(component.id()) + ":" + proposal.script());
        requireConfiguration();
        checkpoint();
        requireConfiguration();
        source.verify();
        requireIsolationAdmission();
        journal.event(task, "COMMAND_EXECUTING", binding, Integer.toString(proposal.seconds()));
        DeploymentAgentDeliveryPort.CommandFeedback result;
        try {
            result = tools.command(task, candidate, revisions.get(component.id()), proposal.script(),
                Duration.ofSeconds(proposal.seconds()));
        } catch (DeploymentAgentCommandNotSentException stopped) {
            if (stopped.matches(task.id(), candidate.workspace().candidateId(), revisions.get(component.id()), proposal.seconds())
                && stopped.scriptSha256().equals(digest(proposal.script()))) {
                journal.event(task, "COMMAND_DISPATCH_NOT_SENT", binding, stopped.dispatcherProof());
                journal.event(task, "COMMAND_NOT_SENT", binding, DeploymentAgentCommandNotSentException.DETAIL);
            }
            throw stopped.originalFailure();
        } catch (DeploymentAgentAlternativeRequiredException refused) {
            journal.event(task, "COMMAND_NOT_SENT", binding, "ALTERNATIVE_REQUIRED");
            throw refused;
        } catch (Exception unknown) {
            journal.state(task, "UNKNOWN");
            throw unknown;
        }
        if (!result.known()) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Unknown agent command must not be replayed");
        }
        journal.event(task, "COMMAND_RESULT", binding, Integer.toString(result.exitStatus()));
        observe(runtimeValidation ? "TOOL_validate_runtime" : "COMMAND_RESULT",
            (runtimeValidation ? "CANDIDATE_INVOCATION_ONLY publishedHealth=NOT_VERIFIED\n" : "")
                + "component=" + component.id() + " exit=" + result.exitStatus() + "\n" + result.evidenceOutput());
        if (!runtimeValidation) rememberCapability(component, "command", result.exitStatus());
        if (result.succeeded()) progressRevision++;
        return result;
    }

    private void validateRuntime(String id) throws Exception {
        var part = component(id);
        requireCurrentPlan(part);
        String reason = DeploymentAgentRuntimeValidation.inapplicable(part);
        if (!reason.isEmpty()) {
            runtimeValidations.remove(id);
            observe("TOOL_validate_runtime", "NOT_APPLICABLE: " + reason
                + "; candidate invocation is not verified; deployment-time health remains required");
            return;
        }
        var health = (gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Command) part.runtime().healthCheck();
        var result = executeCommand(new DeploymentAgentProposal.Command(id,
            DeploymentAgentRuntimeValidation.script(part), health.timeoutSeconds()), true);
        if (result.succeeded() && result.exitStatus() == 0)
            runtimeValidations.put(id, new RuntimeValidationReceipt(runtimeValidationBinding(part), lastObservationId));
    }

    private void invalidateRuntimeValidation(String id) {
        runtimeValidations.remove(id);
        outputMutationRevisions.merge(id, 1L, Long::sum);
    }

    private String runtimeValidationBinding(DeploymentAgentPlan.Part part) {
        return digest(part + ":" + planRevision + ":" + candidates.get(part.id()) + ":"
            + revisions.get(part.id()) + ":" + acceptedSourceRevisions.get(part.id()) + ":"
            + outputMutationRevisions.getOrDefault(part.id(), 0L));
    }

    private void requireRuntimeValidation(DeploymentAgentPlan.Part part) {
        if (!DeploymentAgentRuntimeValidation.inapplicable(part).isEmpty()) return;
        if (!runtimeValidationCurrent(part))
            throw new IllegalArgumentException("Current exact candidate verification required before seal or deliver: use validate_runtime for " + part.id());
    }

    private boolean runtimeValidationCurrent(DeploymentAgentPlan.Part part) {
        var receipt = runtimeValidations.get(part.id());
        if (receipt == null || !receipt.binding().equals(runtimeValidationBinding(part))) return false;
        try {
            return tools.source(part.id()).revision().equals(acceptedSourceRevisions.get(part.id()));
        } catch (Exception unavailable) {
            return false;
        }
    }

    private void requireIsolationAdmission() throws Exception {
        if (!(tools instanceof gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools remote)) return;
        try {
            remote.validateIsolationAdmission();
        } catch (gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools.IsolationRefusal refused) {
            requireIsolationRefusal(refused);
            throw refused;
        } catch (Exception uncertain) {
            journal.state(task, "UNKNOWN");
            throw uncertain;
        }
    }

    private void requireIsolationRefusal(gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentRemoteTools.IsolationRefusal refused) throws Exception {
        requireConfiguration();
        refused.requireBinding(task, source.revision(), models.revision(),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task).digest());
    }

    /** Opens a task-owned protected source candidate exactly once. / 恰好一次开启任务所属受保护来源候选。
     * @param id declared component / 已声明组件
     * @return actual candidate / 真实候选
     * @throws Exception on unavailable or uncertain preparation / 准备不可用或不确定时 */
    private DeploymentAgentDeliveryPort.Prepared prepare(String id) throws Exception {
        var present = candidates.get(id);
        if (present != null)
            return present;
        var component = component(id);
        checkpoint();
        requireConfiguration();
        String binding = gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.binding(task.id(), id, source.revision(), digest(plan.toString()), component.workspaceSelection().orElseThrow().bytes());
        requireIsolationAdmission();
        journal.event(task, "PREPARE_EXECUTING", binding, id);
        var preparation = journal.events(task).getLast();
        DeploymentAgentDeliveryPort.Prepared candidate;
        try {
            candidate = tools.prepare(plan.applicationId(), component);
        } catch (
            gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException rejected) {
            try {
                if (!rejected.matches(task, id, source.revision(), component.workspaceSelection().orElseThrow().bytes()) || task.cancelToken().requested())
                    throw new SecurityException("Foreign or cancelled workspace refusal");
                requireConfiguration();
                source.verify();
                journal.event(task, gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.EVENT, binding, rejected.proof(preparation, digest(plan.toString())));
            } catch (Exception unknown) {
                journal.state(task, "UNKNOWN");
                throw unknown;
            }
            throw rejected;
        } catch (DeploymentAgentPrepareNotSentException stopped) {
            if (stopped.matches(task, id, source.revision())) {
                journal.event(task, "PREPARE_DISPATCH_NOT_SENT", binding, stopped.dispatcherProof());
                journal.event(task, "PREPARE_NOT_SENT", binding, DeploymentAgentPrepareNotSentException.DETAIL);
                var denied = stopped.nativeReviewDenial(task);
                if (denied.isPresent())
                    throw DeploymentAgentAlternativeRequiredException.fromReview(task, denied.orElseThrow());
            } else journal.state(task, "UNKNOWN");
            throw stopped.originalFailure();
        } catch (DeploymentAgentAlternativeRequiredException refused) {
            if (refused.preparationUnsent()) journal.event(task, "PREPARE_NOT_SENT", binding, "ALTERNATIVE_REQUIRED");
            else journal.state(task, "UNKNOWN");
            throw refused;
        } catch (Exception unknown) {
            journal.state(task, "UNKNOWN");
            throw unknown;
        }
        if (!candidate.revision().matches("[0-9a-f]{64}")) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Agent source revision invalid");
        }
        if (candidate.quota() == null || !candidate.quota().equals(component.workspaceSelection().orElseThrow())) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Original candidate workspace budget differs from the accepted plan");
        }
        journal.event(task, "CANDIDATE_QUOTA", id, new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
            .put("task", task.id().toString()).put("server", server.id()).put("source", source.revision()).put("configuration", models.revision())
            .put("layout", candidate.workspace().layout().digest()).put("plan", digest(plan.toString())).put("prepareAction", binding)
            .put("candidate", candidate.workspace().candidateId()).put("workspaceBytes", candidate.quota().bytes()).put("reserveBytes", candidate.quota().policy().reserveBytes()).toString());
        journal.event(task, "PREPARE_RESULT", binding, candidate.revision());
        journal.event(task, "CANDIDATE", id, candidate.workspace().candidateId());
        journal.event(task, "CURRENT_SOURCE", id, candidate.revision());
        candidates.put(id, candidate);
        revisions.put(id, candidate.revision());
        observe("WORKSPACE", gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.facts(Map.of(
            "component", id, "candidate", candidate.workspace().candidateId(), "sourceRevision", candidate.revision(),
            "executionLayout", executionLayout(component), "workspace", candidate.quota().facts()), 12000));
        return candidate;
    }

    /** Requires a declared component. / 要求已声明组件。
     * @param id component ID / 组件标识
     * @return declared component / 已声明组件 */
    private DeploymentAgentPlan.Part component(String id) {
        if (plan == null)
            throw new IllegalArgumentException("Source-backed plan required");
        return plan.components().stream().filter(c -> c.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Undeclared agent component"));
    }

    /** Rejects stale plans after source changes. / 拒绝来源变化后的旧计划。
     * @param part current component / 当前组件
     * @throws Exception if source evidence changed / 来源依据变化时 */
    private void requireCurrentPlan(DeploymentAgentPlan.Part part) throws Exception {
        String current = tools.source(part.id()).revision();
        evidence.requireIdentified(part.id(), part.sourceRoot(), current);
        evidence.requireReads(part.id(), part.sourceRoot(), part.evidence(), current);
        if (!current.equals(acceptedSourceRevisions.get(part.id())))
            throw new IllegalArgumentException("Replan from current immutable source before execution");
    }

    /** Provides current real facts, without inferred success. / 提供当前真实事实，不推断成功。
     * @return bounded model context / 有界模型上下文 */
    private Map<String, Object> context() {
        var judgement = evidence.state();
        boolean identified = !((List<?>) judgement.get("identification")).isEmpty();
        String stage = plan == null ? identified ? "IDENTIFIED" : "INVESTIGATING"
            : artifacts.size() == plan.components().size() ? "SEALED" : candidates.isEmpty() ? "PLANNED" : "PREPARED";
        return Map.ofEntries(Map.entry("task", task.id().toString()), Map.entry("requirements", requirements), Map.entry("sourceRevision", source.revision()),
            Map.entry("activeSkills", skills == null ? List.of() : skills.active()),
            Map.entry("reviewResponseAvailable", tools.reviewResponseAvailable()),
            Map.entry("reconnectAvailable", plan == null && tools.reconnectAvailable()),
            Map.entry("budget", DeploymentAgentBudgetFacts.observe(Math.max(0, remaining - 1), toolAttempts)),
            Map.entry("stage", stage), Map.entry("nextStep", nextStep(stage)), Map.entry("judgement", judgement), Map.entry("evidenceIndex", evidence.latestIndex()),
            Map.entry("recentRejections", recentRejections), Map.entry("target", server.id()),
            Map.entry("planRevision", planRevision), Map.entry("applicationId", plan == null ? "" : plan.applicationId()), Map.entry("components",
                plan == null ? List.of() : plan.components().stream().map(this::componentFacts).toList()),
            Map.entry("remoteRevisions", Map.copyOf(revisions)), Map.entry("sealed", Map.copyOf(artifacts)), Map.entry("readFiles", read.stream().sorted().toList()));
    }

    private Map<String, Object> componentFacts(DeploymentAgentPlan.Part part) {
        var facts = new LinkedHashMap<String, Object>(Map.of("id", part.id(), "sourceRoot", part.sourceRoot(),
            "dependencies", part.dependencies(), "runtime", part.runtime().getClass().getSimpleName(),
            "entrypoint", part.runtime().workload().command().entrypoint(), "resourceState", part.databaseIds().isPresent() ? "DECLARED" : "UNKNOWN",
            "references", part.secretReferences(), "executionLayout", executionLayout(part)));
        facts.put("workspace", part.workspaceSelection().map(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Selection::facts).orElse(Map.of()));
        var capabilities = capabilityFacts(part);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("localFactsAvailable", capabilities.get("localFactsAvailable"));
        summary.put("details", "plan_state for this component returns the full existing capability facts and selected recipe parameters");
        for (String key : List.of("analysis", "selectedRecipe", "executedBinding")) {
            if (!(capabilities.get(key) instanceof Map<?, ?> full)) continue;
            var bounded = new LinkedHashMap<String, Object>();
            for (String field : List.of("present", "sourceCurrent", "rootCurrent", "workloadCurrent", "bindingCurrent", "admissible", "admission", "projectType", "buildTool"))
                if (full.containsKey(field)) bounded.put(field, full.get(field));
            summary.put(key, Map.copyOf(bounded));
        }
        if (capabilities.containsKey("recipeApplicable"))
            summary.put("recipeApplicable", capabilities.get("recipeApplicable"));
        if (capabilities.containsKey("recipeOutputSemantics"))
            summary.put("recipeOutputSemantics", capabilities.get("recipeOutputSemantics"));
        for (String key : List.of("selectedToolchains", "executedToolchains")) {
            if (!(capabilities.get(key) instanceof Map<?, ?> full)) continue;
            var bounded = new LinkedHashMap<String, Object>();
            for (String field : List.of("present", "catalogRevision", "bindingSha256"))
                if (full.containsKey(field)) bounded.put(field, full.get(field));
            summary.put(key, Map.copyOf(bounded));
        }
        for (String key : List.of("latestKnownBuild", "latestKnownCommand")) {
            if (!(capabilities.get(key) instanceof Map<?, ?> full)) continue;
            var bounded = new LinkedHashMap<String, Object>();
            for (String field : List.of("present", "exitStatus", "succeeded", "sourceCurrent", "rootCurrent", "workloadCurrent", "bindingCurrent",
                "outputGeneration", "outputCurrent", "successfulRunCurrent", "id", "planRevision", "assessmentCurrent"))
                if (full.containsKey(field)) bounded.put(field, full.get(field));
            summary.put(key, Map.copyOf(bounded));
        }
        summary.put("observations", capabilities.get("observations"));
        summary.put("runtimeValidation", capabilities.get("runtimeValidation"));
        facts.put("capabilities", Map.copyOf(summary));
        return Map.copyOf(facts);
    }

    private Map<String, Object> capabilityFacts(DeploymentAgentPlan.Part part) {
        var facts = new LinkedHashMap<String, Object>(tools.capabilityState(part));
        facts.put("localFactsAvailable", !facts.isEmpty());
        var observations = new TreeMap<String, Object>();
        var retained = capabilityReceipts.getOrDefault(part.id(), Map.of());
        retained.forEach((kind, receipt) -> observations.put(kind, receiptFacts(part, receipt)));
        facts.put("observations", Map.copyOf(observations));
        if (facts.get("latestKnownBuild") instanceof Map<?, ?> build && Boolean.TRUE.equals(build.get("present"))) {
            var result = new LinkedHashMap<String, Object>();
            build.forEach((key, value) -> result.put((String) key, value));
            var receipt = retained.get("build");
            if (receipt != null) {
                result.put("id", receipt.id());
                result.put("planRevision", receipt.plan());
                result.put("assessmentCurrent", receiptFacts(part, receipt).get("assessmentCurrent"));
            }
            facts.put("latestKnownBuild", Map.copyOf(result));
        }
        facts.put("latestKnownCommand", retained.containsKey("command") ? receiptFacts(part, retained.get("command")) : Map.of("present", false));
        String reason = DeploymentAgentRuntimeValidation.inapplicable(part);
        var validation = runtimeValidations.get(part.id());
        facts.put("runtimeValidation", Map.of("applicable", reason.isEmpty(), "reason", reason,
            "current", runtimeValidationCurrent(part),
            "observation", validation == null ? "" : validation.observation(),
            "scope", "CANDIDATE_INVOCATION_ONLY", "publishedHealth", "NOT_VERIFIED"));
        return Map.copyOf(facts);
    }

    private Map<String, Object> receiptFacts(DeploymentAgentPlan.Part part, CapabilityReceipt receipt) {
        boolean sourceCurrent = receipt.revision().equals(revisions.getOrDefault(part.id(), source.revision()));
        var facts = new LinkedHashMap<String, Object>(Map.of("present", true, "id", receipt.id(), "planRevision", receipt.plan(),
            "sourceCurrent", sourceCurrent, "rootCurrent", receipt.root().equals(part.sourceRoot()),
            "workloadCurrent", receipt.runtime().workload().equals(part.runtime().workload()),
            "assessmentCurrent", sourceCurrent && receipt.plan() == planRevision));
        if (receipt.exitStatus() != null) {
            facts.put("exitStatus", receipt.exitStatus());
            facts.put("succeeded", receipt.exitStatus() == 0);
        }
        return Map.copyOf(facts);
    }

    private void rememberCapability(DeploymentAgentPlan.Part part, String kind, Integer exitStatus) throws Exception {
        capabilityReceipts.computeIfAbsent(part.id(), ignored -> new HashMap<>()).put(kind,
            new CapabilityReceipt(lastObservationId, evidenceRevisions().getOrDefault(part.id(), source.revision()),
                planRevision, part.sourceRoot(), part.runtime(), exitStatus));
    }

    /** Reports fixed execution semantics and accepted component paths, never output existence or permission. / 返回固定执行语义和已接受组件路径，不证明输出存在或授权。 */
    private Map<String, Object> executionLayout(DeploymentAgentPlan.Part part) {
        return Map.of("sourceVariable", "WTL_SOURCE", "sourceScope", "COMPLETE_TASK_ARCHIVE_READ_ONLY",
            "recipeSourceRoot", part.sourceRoot(), "outputVariable", "WTL_OUTPUT", "commandInitialDirectory", "WTL_OUTPUT",
            "commandEnvironmentPersists", false, "recipeResetsOutput", true, "commandInvalidatesSeal", true,
            "dependencyArtifactsMounted", false,
            "dependenciesNeedingSeal", part.dependencies().stream().filter(id -> !artifacts.containsKey(id)).sorted().toList());
    }

    private Map<String, String> nextStep(String stage) {
        return switch (stage) {
            case "INVESTIGATING" -> read.isEmpty()
                ? Map.of("suggestedTool", "read", "condition", "READ component manifests and startup/storage configuration; LIST alone cannot support identification.")
                : Map.of("suggestedTool", "identify", "condition", "Identify the components supported by current READ IDs now. Record unresolved build, host and storage facts in uncertainties; identification does not require resolving them or auditing every business file. Further reads should resolve a specific missing identification fact.");
            case "IDENTIFIED" ->
                Map.of("suggestedTool", "plan", "condition", "Create the initial plan from current identified roots and READ IDs. Use databases:null for unknown bindings; prepare and replan from actual bindings afterward. Runtime paths are relative to the intended artifact.");
            case "PLANNED", "PREPARED" -> plan.components().stream().anyMatch(part -> part.databaseIds().isEmpty())
                ? Map.of("suggestedTool", "component capability", "condition", "Some component resourceState is UNKNOWN. Before building or sealing when possible, assess actual source READs; use database_inspect scope SOURCE only as needed for declarations, or REMOTE for native instances. Independently replan databases:[] when sufficient source facts show none is needed, or every prepared database ID. Source inspection alone does not establish business database absence. Unknown resource declarations cannot be delivered. Build investigation remains available under its own evidence and permission gates.")
                : Map.of("suggestedTool", "component capability", "condition", "Choose check/analyze/prepare_tools/build or a reviewed command for the planned component. Resolve concrete build failures and resource bindings from actual feedback; inspect output, then use validate_runtime for applicable direct ON_DEMAND components before seal. Any later build/command/patch/replan invalidates that candidate invocation proof.");
            case "SEALED" -> plan.components().stream().anyMatch(part -> part.databaseIds().isEmpty())
                ? Map.of("suggestedTool", "plan", "condition", "Some component resourceState is UNKNOWN. Assess actual source READs and use database_inspect scope SOURCE only as needed; submit databases:[] when sufficient source facts show none is needed, or every prepared database ID. Native preparation requires REMOTE inventory. Replanning invalidates sealed artifacts; seal current output again before deliver.")
                : Map.of("suggestedTool", "deliver", "condition", "All planned components have sealed artifacts and declared database requirements. Deliver the whole graph, then satisfy actual publication, verification and finish gates.");
            default -> throw new IllegalArgumentException("Unknown Agent stage");
        };
    }

    /** Records only a digest durably while retaining bounded redacted live feedback. / 仅持久化摘要，保留有界脱敏实时反馈。
     * @param tool actual tool / 真实工具
     * @param output actual nonsecret observation / 真实不含秘密观察
     * @return stable actual observation ID / 稳定实际观察标识
     * @throws Exception on failed durable evidence / 持久依据失败时 */
    private String observe(String tool, String output) throws Exception {
        String safe = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback.safe(tool, Objects.requireNonNull(output));
        String retainedOutput = safe;
        if (safe.length() > 12000)
            safe = safe.substring(0, 11900) + "\n[TRUNCATED: feedback exceeds retention limit; completeness is unconfirmed]";
        String revision = evidenceRevisions().getOrDefault(observationComponent, source.revision());
        String path = tool.equals("READ") ? safe.lines().findFirst().orElse("") : "";
        String id = evidence.observe(observationComponent, revision, planRevision, tool, path,
            tool.startsWith("TOOL_") || Set.of("SERVER", "COMMAND_RESULT", "SEALED", "SOURCE_REVISION").contains(tool));
        evidence.output(id, retainedOutput);
        lastObservationId = id;
        lastObservation = id + "\n" + safe;
        journal.event(task, "AGENT_OBSERVATION", tool, digest(safe));
        gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentObservation(context, task, tool);
        task.progress(DeploymentAgentFeedback.label(context, tool), Optional.empty());
        return id;
    }

    /** Waits only at an original settled boundary and discards a proposal after explicit refresh. / 仅在原已结清边界等待，并在明确刷新后丢弃提案。
     * @throws Exception if the original boundary cannot safely continue / 原边界不能安全继续时 */
    private void checkpoint() throws Exception {
        if (control.checkpoint(task, models)) throw new ProposalInvalidatedException(task.id());
    }

    /** Explicit replacement invalidates a decision without resetting its consumed budget. / 明确替换使决策失效且不重置其已消费预算。 */
    private static final class ProposalInvalidatedException extends ApiException {
        /** Creates one original-task-bound invalidation. / 创建一次原任务绑定失效。
         * @param originalTask authentic original identity / 真实原身份 */
        private ProposalInvalidatedException(UUID originalTask) {
            super(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_AGENT_PROPOSAL_INVALIDATED",
                "Explicit model replacement invalidated the old proposal", Map.of("task", originalTask.toString()),
                Optional.of(originalTask)));
        }
    }

    /** Retrieves current immutable evidence identities. / 取得当前不可变证据身份。
     * @return source snapshot identities / 来源快照身份
     * @throws Exception unavailable current source / 当前来源不可用 */
    private Map<String, String> evidenceRevisions() throws Exception {
        var values = new HashMap<String, String>();
        if (plan != null) for (var component : plan.components())
            values.put(component.id(), tools.source(component.id()).revision());
        return values;
    }

    /** Hashes a nonsecret action binding. / 散列不含秘密的动作绑定。
     * @param value binding text / 绑定文本
     * @return lowercase SHA-256 / 小写 SHA-256 */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Applies an approved source edit and invalidates build evidence. / 应用经批准源码修改并使构建依据失效。
     * @param proposal source patch / 源码补丁
     * @throws Exception when edit is denied or unknown / 修改被拒绝或未知时 */
    private void patch(DeploymentAgentProposal.Patch proposal) throws Exception {
        var candidate = prepare(proposal.component());
        String old = revisions.get(proposal.component());
        DeploymentAgentPatch patch = proposal.patch();
        if (!old.equals(patch.sourceRevision()))
            throw new IllegalArgumentException("Stale or invalid source patch");
        String path = patch.path().toLowerCase(Locale.ROOT);
        for (String part : path.split("/"))
            if (Set.of(".git", ".ssh", ".env", ".npmrc", ".pypirc", ".w2l").contains(part) || part.startsWith(".env.")
                || part.endsWith(".pem") || part.endsWith(".key"))
                throw new SecurityException("Protected source path");
        String binding = digest(old + ":" + patch);
        tools.validatePatch(task, candidate, patch);
        models.reviewer().patch(patch);
        requireConfiguration();
        checkpoint();
        requireConfiguration();
        requireIsolationAdmission();
        journal.event(task, "PATCH_EXECUTING", binding, old + ":" + patch.beforeDigest());
        String revised;
        try {
            revised = tools.patch(task, candidate, old, patch);
        } catch (DeploymentAgentAlternativeRequiredException refused) {
            journal.event(task, "PATCH_NOT_SENT", binding, "ALTERNATIVE_REQUIRED");
            throw refused;
        } catch (Exception uncertain) {
            journal.state(task, "UNKNOWN");
            throw uncertain;
        }
        if (!revised.matches("[0-9a-f]{64}") || revised.equals(old)) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Patch revision was not confirmed");
        }
        journal.event(task, "PATCH_RESULT", binding, revised);
        journal.event(task, "CURRENT_SOURCE", proposal.component(), revised);
        revisions.put(proposal.component(), revised);
        evidence.invalidate(proposal.component());
        for (String id : artifacts.keySet())
            journal.event(task, "ARTIFACT_INVALIDATED", id, "patch");
        artifacts.clear();
        runtimeValidations.clear();
        planRevision++;
        observe("SOURCE_REVISION", proposal.component() + ":" + old + "->" + revised);
        source.verifyBoundaries();
    }

    /** Seals actual output for one current revision. / 为一个当前修订封存真实输出。
     * @param id component / 组件
     * @throws Exception on missing or invalid output / 输出缺失或无效时 */
    private void seal(String id) throws Exception {
        var part = component(id);
        requireCurrentPlan(part);
        requireRuntimeValidation(part);
        var candidate = prepare(id);
        String revision = revisions.get(id);
        tools.validateSeal(task, candidate, revision);
        checkpoint();
        requireConfiguration();
        String binding = digest(id + ":" + revision + ":seal");
        requireIsolationAdmission();
        journal.event(task, "SEAL_EXECUTING", binding, revision);
        String digest;
        try {
            digest = tools.seal(task, candidate, revision);
        } catch (
            gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentArtifactRejectedException rejected) {
            if (!rejected.matches(task.id(), candidate.workspace().candidateId(), revision)) {
                journal.state(task, "UNKNOWN");
                throw rejected;
            }
            artifacts.remove(id);
            journal.event(task, "SEAL_RESULT", binding, "REJECTED");
            observe("ARTIFACT_REJECTED", rejected.feedback());
            throw new IllegalArgumentException("ARTIFACT_REJECTED: " + rejected.feedback(), rejected);
        } catch (Exception uncertain) {
            journal.state(task, "UNKNOWN");
            throw uncertain;
        }
        if (!digest.matches("[0-9a-f]{64}")) {
            journal.state(task, "UNKNOWN");
            throw new IllegalStateException("Sealed artifact identity missing");
        }
        artifacts.put(id, digest);
        journal.event(task, "SEAL_RESULT", binding, digest);
        journal.event(task, "SEALED_ARTIFACT", id, digest);
        observe("SEALED", id + ":" + digest);
        progressRevision++;
    }

    /** Obtains explicit user input. / 获取明确用户输入。
     * @param question bounded question / 有界问题
     * @throws Exception if user input is unavailable / 用户输入不可用时 */
    private void input(String question) throws Exception {
        if (question.isBlank() || question.length() > 2000)
            throw new IllegalArgumentException("Invalid agent question");
        var answer = context.interaction().requestText("Autonomous deployment input", question, false, task);
        if (answer.outcome() != TaskResult.SUCCEEDED)
            throw new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException(
                answer.outcome(), answer.problem());
        observe("USER_INPUT", answer.value().orElseThrow());
    }

    /** Publishes only after full artifact and health evidence. / 仅在完整制品及健康依据后发布。
     * @return actual result or continuation / 真实结果或继续
     * @throws Exception on uncertain delivery / 交付不确定时 */
    private OperationResult<DeploymentAgentDeliveryPort.Published> deliver() throws Exception {
        if (plan == null || artifacts.size() != plan.components().size())
            throw new IllegalArgumentException("Every declared component needs sealed output");
        plan.requireKnownDatabases();
        for (var part : plan.components()) {
            requireCurrentPlan(part);
            requireRuntimeValidation(part);
        }
        checkpoint();
        requireConfiguration();
        source.verify();
        String binding = digest(plan + ":" + new TreeMap<>(artifacts));
        requireIsolationAdmission();
        journal.event(task, "DELIVERY_EXECUTING", binding, Long.toString(planRevision));
        OperationResult<DeploymentAgentDeliveryPort.Published> result;
        try {
            result = tools.deliver(task, plan, Map.copyOf(candidates), Map.copyOf(revisions), Map.copyOf(artifacts), Math.min(remaining, gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.remaining()));
        } catch (Exception uncertain) {
            journal.state(task, "UNKNOWN");
            throw uncertain;
        }
        if (result.outcome() == TaskResult.UNKNOWN) {
            journal.state(task, "UNKNOWN");
            return result;
        }
        journal.event(task, "DELIVERY_RESULT", binding, result.outcome().name());
        if (result.outcome() == TaskResult.SUCCEEDED) {
            var published = result.value().orElseThrow();
            if (!published.healthy().equals(artifacts.keySet())) {
                journal.state(task, "UNKNOWN");
                throw new IllegalStateException("Whole application health evidence missing");
            }
            source.verify();
            journal.state(task, "SUCCEEDED");
            return result;
        }
        if (result.outcome() == TaskResult.FAILED) {
            var accepted = tools.failedPublication(task);
            if (accepted.isEmpty()) {
                journal.state(task, "UNKNOWN");
                return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                    "DEPLOYMENT_AGENT_FAILURE_ASSESSMENT_MISSING", "Original publication failure has not been accepted and settled",
                    Map.of(), Optional.of(task.id()))));
            }
            var settled = tools.settleFailedPublication(task, accepted.orElseThrow());
            if (settled.outcome() != TaskResult.SUCCEEDED) {
                journal.state(task, "UNKNOWN");
                return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), settled.problem());
            }
        }
        observe("DELIVERY", result.problem().map(Problem::eventCode).orElse(result.outcome().name()));
        journal.state(task, result.outcome() == TaskResult.CANCELLED ? "CANCELLED" : "FAILED");
        return result;
    }

    /** Invalidates approvals when any purpose-specific model binding changed. / 任一专用模型绑定变化时使审批失效。
     * @throws SecurityException when the frozen revision changed / 冻结修订改变时 */
    private void requireConfiguration() {
        models.verify();
    }
}
