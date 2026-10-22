package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe;
import gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationGate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlan;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionIdentity;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentConfigStore;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentCurrentRelease;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitRuntimeConfig;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishPlan;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** Adapts the original authenticated task to protected agent tools and shared publication. / 将原认证任务适配至受保护自主工具与共用发布。 */
public final class DeploymentAgentRemoteTools implements DeploymentAgentDeliveryPort {
    /** Exact request with its locally frozen result, never permission. / 精确请求及本地冻结结果，不产生权限。
     * @param patch validated edit / 已验证编辑
     * @param prediction immutable prediction / 不可变预测 */
    private record PendingPatch(DeploymentAgentPatch patch, DeploymentAgentSources.Predicted prediction) {
    }

    /** Validated outstanding patches in the serial original task. / 原串行任务中的已验证未完成补丁。 */
    private final Map<String, PendingPatch> predictedPatches = new HashMap<>();

    /** Borrowed original capabilities and frozen source archive. / 借用的原能力与冻结源码归档。 */
    private final DeploymentRemoteLayout layout;

    private final RemoteSession session;

    private final DeploymentAgentReconnectSession reconnectSession;

    /** Actual original owner capabilities. / 实际原所有者能力。 */
    private final DeploymentCommandReviewScope context;

    /** Exact admitted target server reference. / 精确已准入目标服务器引用。 */
    private final ServerId server;

    /** Authentic original platform task context. / 真实原平台任务上下文。 */
    private final TaskContext task;

    /** Open frozen archive retained for the original task. / 为原任务保留的开放冻结归档。 */
    private final DeploymentSourceArchive archive;

    /** Reviewed fixed candidate operations on the same target. / 同一目标上经审阅固定候选操作。 */
    private DeploymentCandidateChannel candidates;

    private RemoteSession candidateSession;

    /** Fixed limited Agent host observations on the original reviewed channel. / 原经审核通道上的固定有限 Agent 主机观测。 */
    private DeploymentAgentHostChannel hosts;

    /** Protected agent source and build channel on the original session. / 原会话上的受保护自主源码及构建频道。 */
    private final DeploymentAgentProjectChannel projects;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentActionLog journal;

    private final DeploymentAgentIsolationPolicy isolationPolicy;
    private final DeploymentAgentIsolationGate isolationGate;
    private DeploymentAgentPlan isolationPlan;
    private String helperEvidenceSha256;
    private boolean isolationAdmitted;
    private final java.util.function.Consumer<IsolationAdmission> isolationObserver;

    /** Local immutable observation and its original verifier; never an approval or host capability. */
    public record IsolationAdmission(DeploymentAgentIsolationPolicy.PlannedScope scope,
        DeploymentAgentIsolationProbe.Receipt receipt, DeploymentAgentIsolationGate gate) {}

    private FailedPublication failedPublication;

    private record FailedPublication(DeploymentAgentFailedPublication accepted, DeploymentPublishResult proposal,
                                     Map<String, DeploymentGraphPublisher.Candidate> candidates) {
        private FailedPublication {
            candidates = Map.copyOf(candidates);
        }
    }

    /** Exact reviewed saved source, absent for a local directory. / 精确经审阅已存来源，本地目录时为空。 */
    private final Optional<DeploymentProjectRecord> project;

    /** Original frozen all-purpose model-routing identity. / 原冻结全用途模型路由身份。 */
    private String configurationRevision;

    private final String originalSourceRevision;

    private final gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Policy workspacePolicy =
        DeploymentConfigLoader.current().build().workspacePolicy();

    private gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation approvalConversation;

    private gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval approvalAgent;

    private final gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource.Bound reviewSource;

    /** Read-only live observer of the task-authorized AI revision. / 任务授权 AI 修订的只读实时观察器。 */
    private final Supplier<String> currentRevision;

    /** Frozen policy guard checked immediately before dispatch. / 发送紧前检查的冻结策略校验器。 */
    private final DeploymentSendGuard beforeSend;

    /** Original exact host review capability. / 原精确宿主审核能力。 */
    private final InteractionService interaction;

    /** One monotonic budget retained through explicit model replacement. / 明确模型替换中保留的一份单调预算。 */
    private final DeploymentBuildBudget budget = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget.current()
        .map(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget::build).orElseGet(() -> new DeploymentBuildBudget(7200));

    /** One confirmed helper identity and prepared component IDs. / 一份已确认 helper 身份与已准备组件标识。 */
    private boolean helperReady;

    private final String bootstrapOperation = UUID.randomUUID().toString().replace("-", "");

    /** One actual first-helper refusal retained to prevent repeatedly reviewing the same fixed installer. / 保留一次真实首个helper拒绝，防止反复审核同一固定安装器。 */
    private DeploymentAgentAlternativeRequiredException helperRefusal;

    /** Component IDs whose original preparation has already started. / 原准备已开始的组件标识。 */
    private final Set<String> prepared = new HashSet<>();

    /** Current owned source and optional recipe capabilities. / 当前所属来源及可选配方能力。 */
    private final DeploymentAgentSources sources;

    /** Optional common build capabilities / 可选公共构建能力。
     */
    private final DeploymentAgentBuildTools buildTools;

    /** Source-bound database and reference capabilities / 来源绑定数据库与引用能力。
     */
    private final DeploymentAgentResourceTools resourceTools;

    /** Original candidates by component / 按组件保存原候选。
     */
    private final Map<String, String> componentCandidates = new HashMap<>();

    private final Map<String, gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Selection> workspaceQuotas = new HashMap<>();

    /** Creates only real task-bound operations and the shared core publisher. / 仅创建真实任务绑定动作及共用核心发布器。
     * @param context active original Mod / 活动原 Mod
     * @param session authenticated original connection / 原认证连接
     * @param interaction host approval / 宿主审核
     * @param server exact selected server / 精确所选服务器
     * @param task original task / 原任务
     * @param archive open frozen archive / 开放的冻结归档
     * @param configurationRevision frozen AI configuration / 冻结 AI 配置
     * @param currentRevision live revision / 实时修订
     * @param journal dedicated original ledger / 专有原账本
     * @param project reviewed persisted source association / 经审阅已存来源关联
     * @param approvalMode original immutable command policy / 原不可变命令策略
     * @param rememberAuthorization original immutable rememberAuthorization repetition / 原不可变授权重复规则
     * @throws ApiException if an original independent model role is unavailable / 原独立模型角色不可用时
     * @param material Current immutable source material / 当前不可变源码素材
     */
    public DeploymentAgentRemoteTools(ModContext context, RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, DeploymentSourceArchive archive, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material, String configurationRevision,
                                      Supplier<String> currentRevision, DeploymentAgentActionLog journal,
                                      Optional<DeploymentProjectRecord> project, String approvalMode, boolean rememberAuthorization) throws ApiException {
        this(context, session, interaction, server, task, archive, material, configurationRevision, currentRevision, journal, project, approvalMode, rememberAuthorization, DeploymentRemoteLayout.original());
    }

    public DeploymentAgentRemoteTools(ModContext context, RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, DeploymentSourceArchive archive, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material, String configurationRevision,
                                      Supplier<String> currentRevision, DeploymentAgentActionLog journal,
                                      Optional<DeploymentProjectRecord> project, String approvalMode, boolean rememberAuthorization, DeploymentRemoteLayout layout) throws ApiException {
        this(context, session, interaction, server, task, archive, material, configurationRevision, currentRevision, journal,
            project, approvalMode, rememberAuthorization, layout, null);
    }

    public DeploymentAgentRemoteTools(ModContext context, RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, DeploymentSourceArchive archive, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material, String configurationRevision,
                                      Supplier<String> currentRevision, DeploymentAgentActionLog journal,
                                      Optional<DeploymentProjectRecord> project, String approvalMode, boolean rememberAuthorization, DeploymentRemoteLayout layout,
                                      gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource.Bound reviewSource) throws ApiException {
        this(context, session, interaction, server, task, archive, material, configurationRevision, currentRevision,
            journal, project, approvalMode, rememberAuthorization, layout, reviewSource, null);
    }

    /** Isolation is supplied by the trusted caller, never by an Agent tool argument. */
    public DeploymentAgentRemoteTools(ModContext context, RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, DeploymentSourceArchive archive, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material,
                                      String configurationRevision, Supplier<String> currentRevision, DeploymentAgentActionLog journal,
                                      Optional<DeploymentProjectRecord> project, String approvalMode, boolean rememberAuthorization, DeploymentRemoteLayout layout,
                                      gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource.Bound reviewSource,
                                      DeploymentAgentIsolationPolicy isolationPolicy) throws ApiException {
        this(context, session, interaction, server, task, archive, material, configurationRevision, currentRevision,
            journal, project, approvalMode, rememberAuthorization, layout, reviewSource, isolationPolicy, null);
    }

    public DeploymentAgentRemoteTools(ModContext context, RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, DeploymentSourceArchive archive, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial material,
                                      String configurationRevision, Supplier<String> currentRevision, DeploymentAgentActionLog journal,
                                      Optional<DeploymentProjectRecord> project, String approvalMode, boolean rememberAuthorization, DeploymentRemoteLayout layout,
                                      gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReviewSource.Bound reviewSource,
                                      DeploymentAgentIsolationPolicy isolationPolicy, java.util.function.Consumer<IsolationAdmission> isolationObserver) throws ApiException {
        this.isolationPolicy = isolationPolicy;
        this.isolationObserver = isolationObserver;
        this.isolationGate = isolationPolicy == null ? null : new DeploymentAgentIsolationGate(isolationPolicy);
        if (isolationPolicy != null && !isolationPolicy.layout().equals(layout))
            throw new SecurityException("Isolation captured layout changed");
        this.reviewSource = reviewSource;
        this.layout = Objects.requireNonNull(layout);
        this.reconnectSession = new DeploymentAgentReconnectSession(context, task, server, Objects.requireNonNull(session), configurationRevision);
        this.session = reconnectSession;
        session = this.session;
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.archive = Objects.requireNonNull(archive);
        this.journal = Objects.requireNonNull(journal);
        this.project = Objects.requireNonNull(project);
        this.configurationRevision = Objects.requireNonNull(configurationRevision);
        this.currentRevision = Objects.requireNonNull(currentRevision);
        this.originalSourceRevision = material.snapshot().sha256();
        this.approvalConversation = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApprovalConversation(
            context, task, server, session.facts().fingerprint(), originalSourceRevision, configurationRevision, layout);
        this.context = new DeploymentCommandReviewScope(context, interaction, task, approvalMode, rememberAuthorization,
            () -> this.configurationRevision, () -> this.approvalConversation,
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE, () -> this.approvalAgent,
            isolationPolicy == null ? gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE : new IsolationEvidence(),
            new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol(context, () -> this.approvalAgent.protocolReceipts()));
        this.interaction = this.context.interaction();
        this.beforeSend = () -> {
            if (!gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task).equals(layout))
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_LAYOUT_CHANGED",
                    "Original agent directory identity changed before dispatch", Map.of(), Optional.empty()));
            if (!this.configurationRevision.equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task)))
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                    "DEPLOYMENT_AGENT_CONFIGURATION_CHANGED", "AI configuration changed before remote dispatch",
                    Map.of(), Optional.empty()));
        };
        this.candidateSession = candidateDiagnosticSession(configurationRevision);
        this.candidates = new DeploymentCandidateChannel(candidateSession, this.interaction, server, task, configurationRevision,
            budget, beforeSend, layout);
        this.hosts = new DeploymentAgentHostChannel(session, this.interaction, server, task, configurationRevision,
            budget, beforeSend, layout);
        this.projects = new DeploymentAgentProjectChannel(session, this.interaction, server, task, configurationRevision,
            currentRevision, layout, journal);
        this.sources = new DeploymentAgentSources(this.context, task, material);
        this.approvalAgent = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.forTask(context, task, () -> {
            var browsers = new java.util.LinkedHashMap<String, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser>();
            browsers.put("original", sources.browser("original"));
            for (String component : prepared) browsers.put(component, sources.browser(component));
            return Map.copyOf(browsers);
        }, new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.OriginalSource(archive, material, configurationRevision), reviewSource);
        this.buildTools = new DeploymentAgentBuildTools(this.context, task, session, server, configurationRevision, beforeSend, sources, projects, layout, isolationPolicy != null);
        this.resourceTools = new DeploymentAgentResourceTools(this.context, task, server, session, sources, journal, layout);
    }

    /** Returns only authenticated, actually observed host facts. / 仅返回已认证且实际观察的主机事实。
     * @return original connection fingerprint and measured capabilities / 原连接指纹与实测能力
     * @throws Exception if host evidence cannot be confirmed / 主机依据无法确认时 */
    @Override
    public Map<String, String> inspect() throws Exception {
        reconnectSession.requireConnection();
        var facts = value(hosts.baseline());
        var capacity = value(candidates.capacityObservation());
        verifyWorkspaceBoundary();
        var observed = Map.of("fingerprint", session.facts().fingerprint(), "capabilities", facts.facts().toString(),
            "workspace", DeploymentAiJson.facts(Map.of("archiveBytes", archive.byteCount(), "unpackedBytes", archive.uncompressedByteCount(),
                "sourceCopies", 2, "metadataMarginBytes", gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.UNIT,
                "availableBytes", capacity.availableBytes(), "minimumBytes", gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.UNIT,
                "maximumBytes", workspacePolicy.maximumBytes(), "hostReserveBytes", workspacePolicy.reserveBytes(), "hostCost", "2*selectedWorkspaceBytes+hostReserveBytes"), 4000));
        if (isolationPolicy == null) return observed;
        var isolated = new LinkedHashMap<String, String>(observed);
        isolated.put("isolationAdditionalDiskDemands", DeploymentAiJson.facts(Map.of("demands", isolationDiskDemands(),
            "meaning", "Additional existing-toolchain PROBE/binding work; fresh per-filesystem admission adds these demands to selected workspaces. Native availableBytes is unchanged and is not permission or guaranteed future capacity."), 4000));
        return Map.copyOf(isolated);
    }

    /** Executes an independently reviewed prerequisite command through the original session. / 经原会话执行独立审核的前置命令。
     * @param original original task-bound capability owner / 原任务绑定能力所有者
     * @param script complete original reviewed executable script / 完整原已审阅可执行脚本
     * @param seconds bounded command timeout in seconds / 有界命令超时秒数
     * @param reason bounded evidence-supported review explanation / 有界依据支持的审核说明
     * @return classified original prerequisite command result / 分类原前置命令结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    @Override
    public String hostCommand(TaskContext original, String script, int seconds, String reason) throws Exception {
        sameTask(original);
        requireIsolation();
        beforeSend.verify();
        var timed = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget.timedHostSession(session, task, seconds);
        if (script == null) throw new SecurityException("Original host command body is absent");
        var operation = UUID.randomUUID();
        String rationale = "Host prerequisite only; no application publication: " + reason;
        String body = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.digest(script);
        String executable = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.envelope(
            task.id(), operation, body, script, rationale, layout);
        String executableSha = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.digest(executable);
        try {
            gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate.requireFixedAllowed(executable);
        } catch (gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate.Rejection refused) {
            String code = refused.getMessage();
            throw new IllegalArgumentException(code + ": NOT_SENT task=" + task.id() + " rule=" + refused.rule() + " bodySha256=" + body
                + " envelopeSha256=" + executableSha, refused);
        }
        try {
            return DeploymentModelCommandRunner.executeBound(context, timed, server, task, configurationRevision, script,
                seconds, rationale, operation, layout.digest(), executableSha);
        } catch (ApiException refused) {
            if (refused.problem().code() == ProblemCode.ACCESS_DENIED
                && refused.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED"))
                throw DeploymentAgentAlternativeRequiredException.fromReview(task, refused);
            throw refused;
        }
    }

    /** Creates, uploads, hashes and opens a task-owned protected candidate. / 创建、上传、散列并开启任务所属受保护候选。
     * @param applicationId reviewed graph identity / 经审阅图身份
     * @param component reviewed source-backed component / 经审阅来源有据组件
     * @return original protected candidate and source revision / 原受保护候选与来源修订
     * @throws Exception if preparation or its exact receipt fails / 准备或其精确回执失败时 */
    @Override
    public Prepared prepare(String applicationId, DeploymentAgentPlan.Part component) throws Exception {
        requireIsolationComponent(applicationId, component);
        requireIsolation();
        var quota = component.workspaceSelection().orElseThrow(() -> new IllegalArgumentException("WORKSPACE_DEMAND_UNKNOWN: accepted plan workspace quota required"));
        if (!quota.equals(workspaceQuotas.get(component.id())))
            throw new SecurityException("Workspace quota was not selected in this original plan");
        long workspaceBytes = quota.bytes();
        try {
            long sourceBytes = gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.sourceFloor(archive.byteCount(), archive.uncompressedByteCount(), 2);
            if (sourceBytes > workspaceBytes)
                throw new IllegalStateException("Agent source exceeds configured candidate space");
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Agent source exceeds configured candidate space", overflow);
        }
        if (!prepared.add(component.id()))
            throw new IllegalStateException("Agent component was already prepared");
        if (!helperReady) {
            try {
                ensureHelper(component.id());
            } catch (DeploymentAgentPrepareNotSentException stopped) {
                if (stopped.matches(task, component.id(), originalSourceRevision) && stopped.nativeReviewDenial(task).isPresent())
                    prepared.remove(component.id());
                throw stopped;
            } catch (DeploymentAgentAlternativeRequiredException refused) {
                prepared.remove(component.id());
                helperRefusal = DeploymentAgentAlternativeRequiredException.beforePreparation(task, refused);
                throw helperRefusal;
            }
            helperReady = true;
        }
        value(hosts.forRuntime(component.runtime()));
        long requiredHostBytes = quota.policy().reserveBytes();
        for (var entry : workspaceQuotas.entrySet())
            if (!componentCandidates.containsValue(entry.getKey()))
                requiredHostBytes = Math.addExact(requiredHostBytes, Math.multiplyExact(entry.getValue().bytes(), 2));
        var capacity = value(candidates.capacityObservation());
        verifyWorkspaceBoundary();
        if (capacity.availableBytes() < requiredHostBytes) {
            prepared.remove(component.id());
            throw new DeploymentAgentCandidateCapacityException(task, component.id(), originalSourceRevision, configurationRevision, layout, server, quota, capacity, requiredHostBytes);
        }
        var workspace = new DeploymentRemoteWorkspace(DeploymentManagedUnitResolver.managedId(applicationId,
            component.id()), archive.sha256(), gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.currentWorker()
            .flatMap(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.Worker::predecessor)
            .map(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentContinuation.Intent::operation), layout);
        value(candidates.create(workspace, workspaceBytes));
        value(candidates.upload(archive, workspace));
        value(verifyUploaded(workspace));
        String engine = switch (component.runtime()) {
            case
                gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.Container c ->
                c.engine().name().toLowerCase(Locale.ROOT);
            default -> "ordinary";
        };
        componentCandidates.put(workspace.candidateId(), component.id());
        return new Prepared(workspace, projects.open(workspace, engine), quota);
    }

    private void verifyWorkspaceBoundary() throws Exception {
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        beforeSend.verify();
        archive.path();
        sources.browser("original").verify();
        if (!originalSourceRevision.equals(sources.browser("original").revision()))
            throw new SecurityException("Original workspace source changed");
    }

    @Override
    public List<DeploymentAgentPlan.Part> selectWorkspaces(DeploymentAgentPlan plan, Map<String, Prepared> existing) throws Exception {
        reconnectSession.requireConnection();
        DeploymentCandidateChannel.CapacityObservation capacity;
        try {
            capacity = value(candidates.capacityObservation());
        } catch (DeploymentAiJson.CapabilityOutcomeException failure) {
            if (existing.isEmpty() && workspaceQuotas.isEmpty() && prepared.isEmpty() && componentCandidates.isEmpty()
                && DeploymentAgentActionLog.effectsSettled(journal.events(task))
                && !DeploymentModelCommandRunner.unconfirmed(context, task)
                && failure.outcome() == TaskResult.FAILED && failure.problem().isPresent()) {
                beforeSend.verify();
                if (reconnectSession.armCapacityFailure(failure.problem().orElseThrow()))
                    throw new PlanConnectionRefusal(task.id(), originalSourceRevision, configurationRevision, layout.digest(), failure);
            }
            throw failure;
        }
        verifyWorkspaceBoundary();
        var inputs = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input>();
        long preparedReleaseBytes = 0, additionalDiskDemandBytes = 0;
        for (var part : plan.components()) {
            var request = part.workspaceRequest().orElseThrow(() -> new IllegalArgumentException("WORKSPACE_DEMAND_UNKNOWN: source-backed AUTO minimum/preferred build bytes or EXPLICIT candidate bytes required"));
            var current = existing.get(part.id());
            if (current != null) {
                if (current.quota() == null)
                    throw new IllegalArgumentException("Original candidate workspace budget is missing");
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.preserve(
                    new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input(part.id(), archive.byteCount(),
                        gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.sourceFloor(archive.byteCount(), archive.uncompressedByteCount(), 2), request), current.quota());
                if (isolationPolicy != null) preparedReleaseBytes = Math.addExact(preparedReleaseBytes, current.quota().bytes());
            } else
                inputs.add(new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input(part.id(), archive.byteCount(),
                    gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.sourceFloor(archive.byteCount(), archive.uncompressedByteCount(), 2), request));
        }
        if (isolationPolicy != null)
            for (var demand : isolationDiskDemands()) additionalDiskDemandBytes = Math.addExact(additionalDiskDemandBytes, demand.requiredBytes());
        long fixedCostBytes = Math.addExact(preparedReleaseBytes, additionalDiskDemandBytes);
        // Native capacity stays intact; this is only a conservative planning allowance, not a filesystem observation.
        long planningAvailableBytes = Math.max(0, Math.subtractExact(capacity.availableBytes(), fixedCostBytes));
        Map<String, gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Selection> selected;
        try {
            if (isolationPolicy != null && inputs.isEmpty() && capacity.availableBytes() < Math.addExact(fixedCostBytes, workspacePolicy.reserveBytes()))
                throw new IllegalArgumentException("WORKSPACE_HOST_INSUFFICIENT: availableBytes=" + planningAvailableBytes
                    + " requiredBytes=" + workspacePolicy.reserveBytes());
            selected = inputs.isEmpty() ? Map.of()
                : gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.select(inputs, planningAvailableBytes, workspacePolicy,
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Cost.AGENT);
        } catch (IllegalArgumentException refused) {
            throw planCapacityRejection(refused, capacity, inputs, preparedReleaseBytes, additionalDiskDemandBytes);
        }
        var resolved = plan.components().stream().map(part -> part.withWorkspace(existing.containsKey(part.id())
            ? gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.preserve(
            new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input(part.id(), archive.byteCount(),
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.sourceFloor(archive.byteCount(), archive.uncompressedByteCount(), 2), part.workspaceRequest().orElseThrow()), existing.get(part.id()).quota())
            : isolationPolicy == null ? selected.get(part.id()) : observedCapacity(selected.get(part.id()), capacity.availableBytes()))).toList();
        return resolved;
    }

    private static DeploymentWorkspaceQuota.Selection observedCapacity(DeploymentWorkspaceQuota.Selection selected, long actualAvailableBytes) {
        return new DeploymentWorkspaceQuota.Selection(selected.request(), selected.bytes(), selected.sourceFloorBytes(), selected.minimumBytes(),
            selected.preferredBytes(), actualAvailableBytes, selected.policy(), selected.cost());
    }

    /** Projects only the original quota refusal and its measured inputs. / 仅投影原配额拒绝及其实测输入。 */
    private IllegalArgumentException planCapacityRejection(IllegalArgumentException refused,
                                                           DeploymentCandidateChannel.CapacityObservation capacity,
                                                           List<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input> inputs,
                                                           long preparedReleaseBytes, long additionalDiskDemandBytes) {
        var match = java.util.regex.Pattern.compile("WORKSPACE_HOST_INSUFFICIENT: availableBytes=([0-9]{1,19}) requiredBytes=([0-9]{1,19})")
            .matcher(String.valueOf(refused.getMessage()));
        if (!match.matches()) return refused;
        try {
            long available = Long.parseLong(match.group(1)), required = Long.parseLong(match.group(2));
            long fixedCostBytes = Math.addExact(preparedReleaseBytes, additionalDiskDemandBytes);
            if (!Long.toString(available).equals(match.group(1)) || !Long.toString(required).equals(match.group(2))
                || available != Math.max(0, Math.subtractExact(capacity.availableBytes(), fixedCostBytes)) || required < workspacePolicy.reserveBytes())
                return refused;
            long planningAvailableBytes = available;
            available = capacity.availableBytes();
            required = Math.addExact(required, fixedCostBytes);
            if (isolationPolicy != null) refused = new IllegalArgumentException("WORKSPACE_HOST_INSUFFICIENT: availableBytes=" + available
                + " requiredBytes=" + required + " fixedCostBytes=" + fixedCostBytes, refused);
            if (!task.id().equals(capacity.task()) || !server.id().equals(capacity.server())
                || !configurationRevision.equals(capacity.revision()) || !layout.digest().equals(capacity.layoutSha256())
                || !originalSourceRevision.matches("[0-9a-f]{64}") || !configurationRevision.matches("[0-9a-f]{64}")
                || !capacity.requestSha256().matches("[0-9a-f]{64}") || !capacity.resultSha256().matches("[0-9a-f]{64}"))
                return refused;
            var ordered = inputs.stream().sorted(Comparator.comparing(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input::id)).toList();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var facts = new LinkedHashMap<String, Object>();
            facts.put("code", "WORKSPACE_HOST_INSUFFICIENT");
            facts.put("task", task.id().toString());
            facts.put("sourceSha256", originalSourceRevision);
            facts.put("configurationSha256", configurationRevision);
            facts.put("layoutSha256", capacity.layoutSha256());
            facts.put("capacityRequestSha256", capacity.requestSha256());
            facts.put("capacityResultSha256", capacity.resultSha256());
            facts.put("inputsSha256", sha256(json.writeValueAsString(ordered)));
            facts.put("archiveBytes", archive.byteCount());
            facts.put("unpackedBytes", archive.uncompressedByteCount());
            facts.put("availableBytes", available);
            facts.put("hostReserveBytes", workspacePolicy.reserveBytes());
            facts.put("requiredBytes", required);
            facts.put("aggregateHostCostBytes", Math.subtractExact(required, workspacePolicy.reserveBytes()));
            if (isolationPolicy != null) {
                facts.put("preparedReleaseBytes", preparedReleaseBytes);
                facts.put("additionalDiskDemandBytes", additionalDiskDemandBytes);
                facts.put("fixedCostBytes", fixedCostBytes);
                facts.put("candidateHostCostBytes", Math.subtractExact(Math.subtractExact(required, fixedCostBytes), workspacePolicy.reserveBytes()));
                facts.put("planningAvailableBytes", planningAvailableBytes);
                facts.put("budgetMeaning", "Conservative planning from one native capacity observation; availableBytes is unchanged. Owned candidates are already allocated; their future release quota and additional demands remain. Fresh independently reviewed per-filesystem isolation admission is still required.");
            }
            facts.put("cost", gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Cost.AGENT.name());
            facts.put("hostCostMultiplier", gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.hostCost(0,
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.UNIT,
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Cost.AGENT)
                / gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.UNIT);
            facts.put("componentCount", ordered.size());
            var components = new ArrayList<Map<String, Object>>();
            for (var input : ordered) {
                var request = input.request();
                var component = new LinkedHashMap<String, Object>();
                component.put("id", input.id());
                component.put("sourceFloorBytes", input.sourceFloorBytes());
                component.put("mode", request.mode().name());
                component.put("minimumBuildBytes", request.minimumBuildBytes());
                component.put("preferredBuildBytes", request.preferredBuildBytes());
                component.put("explicitBytes", request.explicitBytes());
                if (request.mode() == gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode.EXPLICIT)
                    component.put("hostCostBytes", gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.hostCost(
                        input.archiveBytes(), request.explicitBytes(), gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Cost.AGENT));
                components.add(component);
            }
            facts.put("components", components);
            facts.put("truncated", false);
            facts.put("newInputsNotCreatedByRejectedPlan", true);
            String detail = json.writeValueAsString(facts);
            int maximumDetail = 12000 - "TOOL_REJECTED: WORKSPACE_HOST_INSUFFICIENT DATA=".length();
            if (detail.length() > maximumDetail) {
                facts.put("truncated", true);
                facts.put("componentIds", ordered.stream().map(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Input::id).toList());
                do {
                    if (components.isEmpty()) return refused;
                    components.removeLast();
                    detail = json.writeValueAsString(facts);
                } while (detail.length() > maximumDetail);
            }
            return new PlanCapacityRefusal(task.id(), originalSourceRevision, configurationRevision, capacity.layoutSha256(),
                "TOOL_REJECTED: WORKSPACE_HOST_INSUFFICIENT DATA=" + detail, refused);
        } catch (Exception unavailable) {
            return refused;
        }
    }

    /** Original plan facts, without a dispatch or cleanup proof. / 原计划事实，不构成派发或清理证明。 */
    public static final class PlanCapacityRefusal extends IllegalArgumentException {
        private final UUID task;

        private final String source, configuration, layout, feedback;

        private PlanCapacityRefusal(UUID task, String source, String configuration, String layout, String feedback,
                                    IllegalArgumentException refused) {
            super("WORKSPACE_HOST_INSUFFICIENT", refused);
            this.task = task;
            this.source = source;
            this.configuration = configuration;
            this.layout = layout;
            this.feedback = feedback;
        }

        /** Requires the same current original caller. / 要求当前原调用方一致。 */
        public void requireBinding(TaskContext currentTask, String currentSource, String currentConfiguration, String currentLayout) {
            if (!task.equals(currentTask.id()) || !source.equals(currentSource)
                || !configuration.equals(currentConfiguration) || !layout.equals(currentLayout))
                throw new SecurityException("DEPLOYMENT_AGENT_PLAN_CAPACITY_BINDING_CHANGED");
        }

        /** Returns bounded complete product DATA. / 返回有界完整产品DATA。 */
        public String feedback() {
            return feedback;
        }
    }

    /** A bound initial observation failure; it grants neither reconnection nor command replay. */
    public static final class PlanConnectionRefusal extends IllegalArgumentException {
        private final UUID task;

        private final String source, configuration, layout;

        private final DeploymentAiJson.CapabilityOutcomeException failure;

        private PlanConnectionRefusal(UUID task, String source, String configuration, String layout,
                                      DeploymentAiJson.CapabilityOutcomeException failure) {
            super("PLAN_CAPACITY_AUTHOR_PAYLOAD_NOT_SENT: original bootstrap may have opened; author payload NOT_SENT; original connection closed; reconnect is available; no command replayed", failure);
            this.task = task;
            this.source = source;
            this.configuration = configuration;
            this.layout = layout;
            this.failure = failure;
        }

        public void requireBinding(TaskContext currentTask, String currentSource, String currentConfiguration, String currentLayout) {
            if (!task.equals(currentTask.id()) || !source.equals(currentSource) || !configuration.equals(currentConfiguration) || !layout.equals(currentLayout))
                throw new SecurityException("DEPLOYMENT_AGENT_RECONNECT_BINDING_CHANGED");
        }

        public DeploymentAiJson.CapabilityOutcomeException originalFailure() {
            return failure;
        }

        public String feedback() throws java.io.IOException {
            var facts = new LinkedHashMap<String, Object>();
            facts.put("outcome", "FAILED");
            facts.put("event", "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT");
            facts.put("authorPayloadState", "NOT_SENT");
            facts.put("commandOpenRequested", failure.problem().orElseThrow().context().get("commandOpenRequested"));
            facts.put("originalConnectionClosed", true);
            facts.put("reconnectAvailable", true);
            facts.put("commandsReplayed", 0);
            return "PLAN_CAPACITY_CONNECTION_FAILED DATA=" + DeploymentAiJson.facts(facts, 2000);
        }
    }

    @Override
    public boolean reconnectAvailable() {
        try {
            return workspaceQuotas.isEmpty() && prepared.isEmpty() && componentCandidates.isEmpty() && reconnectSession.available()
                && DeploymentAgentActionLog.effectsSettled(journal.events(task)) && !DeploymentModelCommandRunner.unconfirmed(context, task);
        } catch (Exception unavailable) {
            return false;
        }
    }

    @Override
    public void acceptWorkspaces(DeploymentAgentPlan plan) {
        if (isolationPolicy != null && !isolationPolicy.newApplication().equals(plan.applicationId()))
            throw new SecurityException("Isolation application differs from the accepted plan");
        isolationPlan = plan;
        isolationAdmitted = false;
        workspaceQuotas.clear();
        plan.components().forEach(part -> workspaceQuotas.put(part.id(), part.workspaceSelection().orElseThrow()));
    }


    private void requireIsolationComponent(String applicationId, DeploymentAgentPlan.Part component) {
        if (isolationPolicy == null) return;
        if (isolationPlan == null || !isolationPolicy.newApplication().equals(applicationId) || component == null
            || isolationPlan.components().stream().noneMatch(component::equals))
            throw new SecurityException("Isolation requires this exact accepted component plan");
    }

    private void requireIsolatedDatabases(DeploymentAgentPlan.Part component) throws Exception {
        var material = sources.material(component.id());
        var assessment = new gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker()
            .inspect(material.snapshot().directory().resolve(component.sourceRoot()).normalize(), component.id());
        if (assessment.unknownDatabase() || assessment.databases().stream().anyMatch(database ->
            database.engine() != gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseEngineType.SQLITE))
            throw new SecurityException("Isolation of shared server databases is unconfirmed");
    }

    private DeploymentAgentIsolationPolicy.PlannedScope isolationScope() throws Exception {
        if (isolationPolicy == null || isolationPlan == null) throw new SecurityException("Isolation plan is absent");
        if (isolationPolicy.minimumMemAvailableBytes() < DeploymentAgentBuildTools.isolationMemoryFloor())
            throw new SecurityException("Isolation requires explicit trusted memory headroom covering serial build/PROBE plus 256 MiB");
        var components = new ArrayList<DeploymentAgentIsolationPolicy.ResolvedComponent>();
        var planIdentity = new StringBuilder(isolationPlan.applicationId()).append('\n').append(isolationPlan.healthComponentId());
        for (var part : isolationPlan.components()) {
            var workspace = new DeploymentRemoteWorkspace(DeploymentManagedUnitResolver.managedId(isolationPlan.applicationId(), part.id()),
                archive.sha256(), gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.currentWorker()
                    .flatMap(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.Worker::predecessor)
                    .map(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentContinuation.Intent::operation), layout);
            var quota = part.workspaceSelection().orElseThrow();
            if (!quota.equals(workspaceQuotas.get(part.id()))) throw new SecurityException("Isolation workspace quota drift");
            String unit = workspace.applicationId();
            var paths = new TreeSet<String>(List.of(workspace.candidateRoot(), layout.applicationRoot(unit),
                layout.configurationRoot(unit), layout.dataRoot(unit), layout.snapshotRoot(unit, task.id()), layout.applicationLockRoot(unit),
                "/etc/systemd/system/windowstolinux-" + unit + ".service",
                "/etc/systemd/system/windowstolinux-build-" + workspace.candidateId() + ".service"));
            for (var file : part.resources().fileBindings()) {
                String path = file.location().resolve(unit, file.resourceType(), file.bindingId(), layout);
                paths.add(path);
            }
            for (var database : part.resources().databaseBindings().orElse(List.of())) {
                if (!(database.connection() instanceof gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection.Sqlite sqlite))
                    throw new SecurityException("Shared database isolation is unconfirmed");
                paths.add(sqlite.location().resolve(unit,
                    gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation.StorageResourceType.DATABASE,
                    database.databaseId(), layout) + "/" + sqlite.fileName());
            }
            var ports = new HashSet<DeploymentAgentIsolationPolicy.Port>();
            for (var endpoint : part.runtime().workload().endpoints())
                ports.add(new DeploymentAgentIsolationPolicy.Port(endpoint.protocol().transport().toUpperCase(Locale.ROOT), endpoint.hostPort()));
            var health = part.runtime().healthCheck();
            if (health.portNumber().isPresent()) ports.add(new DeploymentAgentIsolationPolicy.Port(
                health instanceof gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Udp ? "UDP" : "TCP", health.portNumber().getAsInt()));
            if (part.runtime() instanceof DeploymentRuntimeDefinition.Container container)
                container.publishedPorts().keySet().forEach(port -> ports.add(new DeploymentAgentIsolationPolicy.Port("TCP", port)));
            String runtimeSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(new gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentRuntimeCodec().write(part.runtime())));
            String resourceSha = sha256(part.resources().toString());
            planIdentity.append('\n').append(part.id()).append(':').append(part.sourceRoot()).append(':').append(runtimeSha)
                .append(':').append(resourceSha).append(':').append(part.configuration()).append(':').append(part.databaseIds())
                .append(':').append(part.secretReferences()).append(':').append(part.dependencies()).append(':').append(part.evidence())
                .append(':').append(sources.browser(part.id()).revision()).append(':').append(quota);
            components.add(new DeploymentAgentIsolationPolicy.ResolvedComponent(part.id(), unit, workspace.candidateId(), paths, ports,
                quota.bytes(), quota.policy().reserveBytes(), runtimeSha, resourceSha));
        }
        return new DeploymentAgentIsolationPolicy.PlannedScope(task.id(), server.id(), journal.find(task).orElseThrow().identity().targetDigest(),
            originalSourceRevision, configurationRevision, layout.digest(), sha256(planIdentity.toString()), components,
            isolationDiskDemands(),
            isolationPolicy.minimumMemAvailableBytes(), isolationPolicy.minimumCpuCount());
    }

    private List<DeploymentAgentIsolationPolicy.DiskDemand> isolationDiskDemands() {
        return List.of(new DeploymentAgentIsolationPolicy.DiskDemand(layout.toolchains(), 64L * 1024 * 1024, 1024L * 1024 * 1024));
    }

    /** Only a complete current request's strict REFUSED result can produce known business rejection. */
    public static final class IsolationRefusal extends Exception {
        private final TaskContext original;
        private final DeploymentAgentIsolationPolicy.PlannedScope scope;
        private final UUID action;
        private final String requestSha256, receipt, feedback;

        private IsolationRefusal(TaskContext original, DeploymentAgentIsolationProbe.Request request,
                                 OperationResult<CommandResult> actual) throws Exception {
            super("AGENT_ISOLATION_NOT_ADMITTED: confirmed fixed read-only preflight refusal");
            this.original = Objects.requireNonNull(original);
            this.scope = request.scope();
            this.action = request.action();
            if (request.mode() != DeploymentAgentIsolationProbe.Mode.ADMISSION || !request.queryTask().equals(original.id())
                || !scope.task().equals(original.id())) throw new SecurityException("Foreign isolation refusal");
            var safe = new DeploymentAgentIsolationProbe(request.policy()).parse(request, actual);
            this.receipt = safe.canonicalJson();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var body = json.readTree(receipt);
            if (!body.path("status").asText().equals("REFUSED")) throw new SecurityException("Isolation refusal is not confirmed");
            requestSha256 = sha256(RemoteActions.command(new ServerId(scope.server()), scope.configurationSha256(), request.command()).exactAction());
            var output = json.createObjectNode().put("code", "AGENT_ISOLATION_NOT_ADMITTED").put("status", "REFUSED")
                .put("reason", body.path("reason").asText()).put("task", scope.task().toString()).put("planSha256", scope.planSha256())
                .put("requestSha256", requestSha256).put("receiptSha256", sha256(receipt)).put("effectSent", false).put("admitted", false);
            // Preserve the complete canonical receipt separately, including protected metadata; the model needs the budget projection.
            var projection = ((com.fasterxml.jackson.databind.node.ObjectNode) body).deepCopy();
            projection.remove("protectedMetadata");
            output.set("receiptBudget", projection);
            var quotas = output.putArray("selectedWorkspaceBudgets");
            for (var component : scope.components()) quotas.addObject().put("component", component.id())
                .put("workspaceBytes", component.workspaceBytes()).put("reserveBytes", component.reserveBytes());
            output.set("additionalDiskDemands", json.valueToTree(scope.additionalDiskDemands()));
            output.put("rawReceiptRetention", "ISOLATION_REFUSAL_RECEIPT journal parts, bound by receiptSha256; this projection grants no authority");
            output.put("meaning", "Only this attempted effect was not sent. Original per-filesystem free bytes and reserves are unchanged. Replan from all selected workspace plus additional demands, then obtain fresh independent APP/grant and a fresh READY proof; previous UNKNOWN work is not settled.");
            feedback = json.writeValueAsString(output);
            if (feedback.length() > 11000) throw new SecurityException("Isolation refusal exceeds bounded business feedback");
        }

        public void requireBinding(TaskContext task, String source, String configuration, String layout) {
            if (original != task || !scope.task().equals(task.id()) || !scope.sourceSha256().equals(source)
                || !scope.configurationSha256().equals(configuration) || !scope.layoutSha256().equals(layout))
                throw new SecurityException("Isolation refusal changed its original task/plan inputs");
        }
        public String feedback() { return feedback; }
        public String receipt() { return receipt; }
    }

    static IsolationRefusal confirmedIsolationRefusal(TaskContext task, DeploymentAgentIsolationProbe.Request request,
                                                     OperationResult<CommandResult> actual) throws Exception {
        return new IsolationRefusal(task, request, actual);
    }

    private void retainIsolationRefusal(IsolationRefusal refused) throws Exception {
        String action = refused.action.toString();
        journal.event(task, "ISOLATION_REFUSAL", action, "requestSha256=" + refused.requestSha256 + " receiptSha256=" + sha256(refused.receipt)
            + " receiptChars=" + refused.receipt.length() + " budgetSha256=" + sha256(refused.feedback));
        for (int at = 0, part = 0; at < refused.receipt.length(); at += 3000)
            journal.event(task, "ISOLATION_REFUSAL_RECEIPT", action, (part++) + ":" + refused.receipt.substring(at, Math.min(at + 3000, refused.receipt.length())));
        for (int at = 0, part = 0; at < refused.feedback.length(); at += 3000)
            journal.event(task, "ISOLATION_REFUSAL_BUDGET", action, (part++) + ":" + refused.feedback.substring(at, Math.min(at + 3000, refused.feedback.length())));
    }

    /** Trusted Agent session preflight, before recording a potentially mutating attempt; not model authority. */
    public void validateIsolationAdmission() throws Exception {
        requireIsolation();
    }

    private void requireIsolation() throws Exception {
        if (isolationPolicy == null) return;
        if (isolationPlan == null) throw new SecurityException("Isolation accepted plan is absent");
        // This read cannot install anything; record an absent helper before evaluating later admission budgets.
        var helper = helperReady ? null : helperReadiness();
        if (helper != null && helper.status() != DeploymentAgentHelperReadiness.Status.READY)
            throw new SecurityException("Isolation shared helper is not READY");
        var scope = isolationScope();
        if (isolationAdmitted && isolationGate.matches(scope)) {
            isolationGate.requireCurrent(scope);
            return;
        }
        isolationAdmitted = false;
        if (helper == null) helper = helperReadiness();
        if (helper.status() != DeploymentAgentHelperReadiness.Status.READY)
            throw new SecurityException("Isolation shared helper is not READY");
        var probe = new DeploymentAgentIsolationProbe(isolationPolicy);
        try (var request = probe.request(scope, UUID.randomUUID())) {
            var intent = RemoteActions.command(server, configurationRevision, request.command()).withSemantics(
                new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentIsolationProbe.OPERATION, request.resourceTargets()));
            gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentUnsentReview.requireDifferent(
                journal, task, server, "ISOLATION", request.bindingSha256());
            OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant> approved;
            try {
                approved = DeploymentCommandReviewRouter.approve(interaction, intent, request.command(), task);
            } catch (ApiException denied) {
                if (denied.problem().code() == ProblemCode.ACCESS_DENIED
                    && denied.problem().eventCode().equals("DEPLOYMENT_COMMAND_REVIEW_DENIED"))
                    throw gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentUnsentReview.command(
                        journal, task, server, "ISOLATION", request.bindingSha256(), intent, request.command(), denied);
                throw denied;
            }
            var grant = value(approved);
            beforeSend.verify();
            var actual = session.execute(request.command(), grant);
            DeploymentAgentIsolationProbe.Receipt receipt;
            try { receipt = probe.parse(request, actual); }
            catch (RuntimeException uncertain) { throw new SecurityException("Isolation preflight observation is unconfirmed", uncertain); }
            var status = new com.fasterxml.jackson.databind.ObjectMapper().readTree(receipt.canonicalJson()).path("status").asText();
            if (status.equals("REFUSED")) {
                var refused = confirmedIsolationRefusal(task, request, actual);
                retainIsolationRefusal(refused);
                throw refused;
            }
            if (!status.equals("READY")) throw new SecurityException("Isolation preflight did not confirm admission");
            try { isolationGate.accept(request, actual, helper, helperEvidenceSha256); }
            catch (RuntimeException uncertain) { throw new SecurityException("Isolation READY proof is unconfirmed", uncertain); }
            helperReady = true;
            isolationGate.requireCurrent(isolationScope());
            if (isolationObserver != null) isolationObserver.accept(new IsolationAdmission(scope, receipt, isolationGate));
            journal.event(task, "ISOLATION_PREFLIGHT", sha256(intent.exactAction()), request.bindingSha256());
            isolationAdmitted = true;
        }
    }

    private final class IsolationEvidence implements gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence {
        private boolean readOnly(String operation) {
            return Set.of(DeploymentAgentIsolationProbe.OPERATION, DeploymentAgentHelperReadiness.OPERATION,
                "deployment.candidate.host-probe.v1", "deployment.candidate.capacity.v1", "deployment.native-db.inspect.v1",
                "deployment.agent.toolchain.resolve.v1", "deployment.agent.toolchain.controller.readiness.v1").contains(operation);
        }
        public Map<String, ?> facts(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            verify(intent, request, supplied);
            var current = new LinkedHashMap<String, Object>();
            current.putAll(isolationGate.facts());
            current.put("admitted", isolationAdmitted);
            return Map.of("agentIsolation", Collections.unmodifiableMap(current), "agentResourceAdmissionBoundary", Map.of(
                "serialBuildAndProbeMemoryMiB", DeploymentAgentBuildTools.BUILD_MEMORY_MIB,
                "minimumHeadroomMiB", 256, "scopeMemoryFloorBytes", isolationPolicy.minimumMemAvailableBytes(),
                "cpuCountFloor", isolationPolicy.minimumCpuCount(), "runtimeMemoryAndCpuLimits", "NOT_DECLARED",
                "meaning", "Fresh MemAvailable/CPU count lower-bound admission only, not a DAEMON total-memory ceiling, CPU reservation or OS sandbox."));
        }
        public void verify(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            try {
                if (supplied != task) throw new SecurityException("Isolation task changed");
                String operation = intent.semantics().orElseThrow().operation();
                if (Set.of("deployment.helper.install.v1", "deployment.helper.install.tracked.v1", "deployment.helper.install.files.v1",
                    "deployment.helper.install.files.custom.v1", "deployment.agent.toolchain.controller.install.v1",
                    "deployment.agent.toolchain.dependencies.v1", "deployment.agent.toolchain.controller.prepare.v1", "deployment.toolchain.prepare.v1").contains(operation))
                    throw new SecurityException("Isolation forbids shared installation or fallback preparation");
                if (operation.equals("deployment.agent.toolchain.prepare.v1")) {
                    byte[] input = request.stdin();
                    try {
                        if (!Arrays.equals(input, gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms
                            .existingController(layout).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                            throw new SecurityException("Isolation requires the fixed existing-only toolchain preparation");
                    } finally { Arrays.fill(input, (byte) 0); }
                }
                if (!readOnly(operation)) {
                    if (!isolationAdmitted) throw new SecurityException("Isolation has no current READY admission");
                    isolationGate.requireCurrent(isolationScope());
                }
            } catch (Exception failure) {
                var denied = new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_ISOLATION_UNCONFIRMED",
                    "Agent isolation is not confirmed for this exact action", Map.of(), Optional.of(task.id())));
                denied.initCause(failure);
                throw denied;
            }
        }
    }

    private gold.debug.windowstolinux.appapi.task.result.OperationResult<DeploymentRemoteWorkspace> verifyUploaded(DeploymentRemoteWorkspace workspace) throws ApiException {
        var specification = candidates.verification(workspace, archive);
        var reviewed = context.withCurrentEvidence(verificationEvidence(specification, workspace));
        return new DeploymentCandidateChannel(candidateSession, reviewed.interaction(), server, task, configurationRevision,
            budget, beforeSend, layout).verify(specification);
    }

    /** One producer-bound consumer contract for this invocation, never a remote receipt or authority. */
    gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence verificationEvidence(
        DeploymentCandidateChannel.Verification specification, DeploymentRemoteWorkspace workspace) throws ApiException {
        return new UploadedArchiveEvidence(specification, workspace);
    }

    private final class UploadedArchiveEvidence implements gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence {
        private final DeploymentCandidateChannel.Verification specification;

        private final DeploymentRemoteWorkspace workspace;

        private final String revision = configurationRevision;

        private final long maximumSeconds = Math.min(30, budget.remainingSeconds());

        private final String script;

        private String exactAction;

        private UploadedArchiveEvidence(DeploymentCandidateChannel.Verification specification,
                                        DeploymentRemoteWorkspace workspace) throws ApiException {
            this.specification = Objects.requireNonNull(specification);
            this.workspace = Objects.requireNonNull(workspace);
            requireOriginal();
            script = specification.guardedScript();
        }

        private void requireOriginal() throws ApiException {
            beforeSend.verify();
            if (!revision.equals(configurationRevision)
                || !specification.matches(candidateSession, server, task, revision, layout, workspace, archive)
                || !workspace.archiveSha256().equals(archive.sha256())
                || !archive.sourceManifestSha256().equals(originalSourceRevision))
                throw changed();
        }

        private void requireRequest(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            requireOriginal();
            byte[] input = request.stdin();
            boolean empty = input.length == 0;
            Arrays.fill(input, (byte) 0);
            long seconds = request.timeout().getSeconds();
            if (supplied != task || !intent.serverIds().equals(List.of(server.id())) || !intent.revision().equals(revision)
                || intent.semantics().isEmpty() || !intent.semantics().orElseThrow().operation().equals("deployment.candidate.verify.v1")
                || !intent.semantics().orElseThrow().targets().equals(Set.of(new ActionTarget(server.id(), "deployment-candidate", workspace.candidateRoot())))
                || !script.equals(request.script()) || !empty || request.outputLimitBytes() != 4096
                || seconds < 1 || seconds > maximumSeconds || !request.timeout().equals(Duration.ofSeconds(seconds))
                || !intent.exactAction().equals(RemoteActions.command(server, revision, request).exactAction()))
                throw changed();
        }

        public Map<String, ?> facts(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            requireRequest(intent, request, supplied);
            if (exactAction != null && !exactAction.equals(intent.exactAction())) throw changed();
            exactAction = intent.exactAction();
            return Map.ofEntries(Map.entry("formatVersion", 1), Map.entry("contractId", "deployment.candidate.verification.consumer.v1"),
                Map.entry("authority", "NONE"), Map.entry("meaning", "Captured product observer and exact client consumer; not a fresh rehash, remote observation, settlement receipt or permission."),
                Map.entry("phase", "UPLOADED_ARCHIVE_BEFORE_AGENT_OPEN"), Map.entry("task", task.id().toString()),
                Map.entry("server", server.id()), Map.entry("businessRevision", revision), Map.entry("layoutSha256", layout.digest()),
                Map.entry("workspace", Map.of("application", workspace.applicationId(), "candidate", workspace.candidateId(), "root", workspace.candidateRoot())),
                Map.entry("capturedArchive", Map.of("bytes", archive.byteCount(), "sha256", archive.sha256(),
                    "sourceManifestSha256", archive.sourceManifestSha256(), "originSnapshotId", archive.originSnapshotId().toString())),
                Map.entry("request", Map.of("exactActionSha256", sha256(exactAction), "scriptSha256", sha256(script),
                    "stdinBytes", 0, "timeoutSeconds", request.timeout().getSeconds(), "outputLimitBytes", request.outputLimitBytes())),
                Map.entry("consumer", Map.of("outcome", "SUCCEEDED", "valuePresent", true, "exitCode", 0,
                    "truncated", false, "stdoutComparison", "FULL_EXACT_NO_TRIM", "expectedStdout", specification.expectedOutput(),
                    "mismatchOutcome", "UNKNOWN", "successMeaning", "Verified workspace only; earlier preparation effects remain unsettled.")));
        }

        public void verify(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            requireRequest(intent, request, supplied);
            if (exactAction == null || !exactAction.equals(intent.exactAction())) throw changed();
        }

        private ApiException changed() {
            return new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_VERIFICATION_EVIDENCE_CHANGED",
                "Original candidate verification evidence changed", Map.of(), Optional.of(task.id())));
        }
    }

    @Override
    public boolean reviewResponseAvailable() {
        return reviewSource == null;
    }

    @Override
    public Map<String, Object> reviewResponse(TaskContext original, String reviewBinding, String message) {
        sameTask(original);
        if (reviewSource != null)
            throw new IllegalStateException("The bound judgment source has no approval discussion");
        return approvalAgent.respond(original, reviewBinding, message);
    }

    private void ensureHelper(String component) throws Exception {
        var observed = helperReadiness();
        if (observed.status() == DeploymentAgentHelperReadiness.Status.READY) return;
        if (isolationPolicy != null)
            throw new SecurityException("Isolation requires an already matching READY shared helper; installation is forbidden");
        if (observed.status() != DeploymentAgentHelperReadiness.Status.MISSING)
            throw new IllegalStateException("Agent helper readiness prevents installation: " + observed.status() + ":" + observed.reason());
        installHelper(component);
        var installed = helperReadiness();
        if (installed.status() != DeploymentAgentHelperReadiness.Status.READY)
            throw new IllegalStateException("Agent helper installation is not confirmed ready: " + installed.status() + ":" + installed.reason());
    }

    private DeploymentAgentHelperReadiness.Observation helperReadiness() throws Exception {
        byte[] body = DeploymentAgentHelperReadiness.script(layout).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var request = new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, body, Duration.ofSeconds(30), 4096)) {
            Arrays.fill(body, (byte) 0);
            var intent = RemoteActions.command(server, configurationRevision, request).withSemantics(
                new gold.debug.windowstolinux.appapi.task.action.ActionSemantics("deployment.agent.helper.readiness.v1", Set.of(
                    new ActionTarget(server.id(), "deployment-helper", layout.helperPath()),
                    new ActionTarget(server.id(), "file", layout.buildEntry()),
                    new ActionTarget(server.id(), "directory", layout.helperInstallOperationsRoot()),
                    new ActionTarget(server.id(), "file", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission.UNIT_PATH),
                    new ActionTarget(server.id(), "systemd-service", gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentRemoteLayoutAdmission.UNIT_NAME),
                    new ActionTarget(server.id(), "directory", layout.paths().applicationsRoot()),
                    new ActionTarget(server.id(), "directory", layout.paths().stateRoot()),
                    new ActionTarget(server.id(), "directory", layout.paths().configurationRoot()),
                    new ActionTarget(server.id(), "directory", layout.paths().dataRoot()),
                    new ActionTarget(server.id(), "host-prerequisites", "fixed-system-utilities"))));
            var grant = value(DeploymentCommandReviewRouter.approve(interaction, intent, request, task));
            if (task.cancelToken().requested())
                throw new ApiException(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_AGENT_READINESS_CANCELLED",
                    "Agent readiness cancelled before dispatch", Map.of(), Optional.empty()));
            beforeSend.verify();
            var actual = value(session.execute(request, grant));
            var observed = DeploymentAgentHelperReadiness.parse(actual);
            helperEvidenceSha256 = sha256(intent.exactAction() + "\n" + actual.stdout() + "\n" + actual.stderr()
                + "\n" + actual.exitCode() + "\n" + actual.truncated());
            journal.event(task, "HELPER_READINESS_OBSERVED", sha256(intent.exactAction()), observed.status() + ":" + observed.reason());
            return observed;
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private void installHelper(String component) throws Exception {
        var attempt = new InstallReviewAttempt(component);
        var installer = new DeploymentManagedHelper(session, interaction, server, task, configurationRevision,
            () -> {
                attempt.dispatchBoundaryReached = true;
                beforeSend.verify();
            }, attempt::approve, layout);
        try {
            value(installer.installFilesWithPrerequisites(DeploymentAgentHelperInstallerScript.LAUNCHER,
                DeploymentAgentHelperInstallerScript.prerequisites(bootstrapOperation, layout)));
        } catch (ApiException | DeploymentAiJson.CapabilityOutcomeException failure) {
            if (!attempt.dispatchBoundaryReached && attempt.requestSha256 != null
                && (attempt.apiFailure == failure || failure instanceof DeploymentAiJson.CapabilityOutcomeException classified
                && attempt.outcome == classified.outcome() && Objects.equals(attempt.problem, classified.problem())))
                throw new DeploymentAgentPrepareNotSentException(failure, task, component, originalSourceRevision, server,
                    attempt.revision, attempt.installerSha256, attempt.requestSha256);
            throw failure;
        }
    }

    /** One local actual installer approval, discarded after its own installation call. */
    private final class InstallReviewAttempt {
        private final String revision = configurationRevision;

        private boolean dispatchBoundaryReached;

        private ApiException apiFailure;

        private TaskResult outcome;

        private Optional<Problem> problem;

        private String installerSha256;

        private String requestSha256;

        private InstallReviewAttempt(String component) {
            if (!component.matches("[a-z0-9][a-z0-9-]{0,62}"))
                throw new IllegalArgumentException("Invalid Agent component");
        }

        private OperationResult<ApprovalGrant> approve(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            boolean owns = ownsInstallReview(intent, request, supplied, task, server, revision, layout);
            var reviewedIntent = owns ? switch (intent.semantics().orElseThrow().operation()) {
                case "deployment.helper.install.files.custom.v1" ->
                    gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentHelperBootstrapEvidence.upgrade(intent, request, server, revision, bootstrapOperation, layout);
                case "deployment.helper.install.files.v1" ->
                    gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentInstallReviewIntent.upgradeFiles(intent, request, server, revision, layout);
                default ->
                    gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentInstallReviewIntent.upgrade(intent, request, server, revision, layout);
            } : intent;
            String inputDigest = null, requestDigest = null;
            if (owns) {
                byte[] body = request.stdin();
                try {
                    inputDigest = sha256(body);
                    requestDigest = sha256(intent.exactAction());
                } finally {
                    Arrays.fill(body, (byte) 0);
                }
            }
            final OperationResult<ApprovalGrant> result;
            try {
                result = DeploymentCommandReviewRouter.approve(interaction, reviewedIntent, request, supplied);
            } catch (DeploymentAgentAlternativeRequiredException refused) {
                throw refused;
            } catch (ApiException failure) {
                if (owns) {
                    apiFailure = failure;
                    installerSha256 = inputDigest;
                    requestSha256 = requestDigest;
                }
                throw failure;
            }
            if (owns && result.outcome() != TaskResult.SUCCEEDED) {
                outcome = result.outcome();
                problem = result.problem();
                installerSha256 = inputDigest;
                requestSha256 = requestDigest;
            }
            return result;
        }
    }

    static boolean ownsInstallReview(ActionIntent intent, CommandRequest request, TaskContext supplied, TaskContext original,
                                     ServerId server, String revision) {
        return ownsInstallReview(intent, request, supplied, original, server, revision, DeploymentRemoteLayout.original());
    }

    static boolean ownsInstallReview(ActionIntent intent, CommandRequest request, TaskContext supplied, TaskContext original,
                                     ServerId server, String revision, DeploymentRemoteLayout layout) {
        var actual = RemoteActions.command(server, revision, request);
        return supplied == original && intent.type().equals(actual.type()) && intent.serverIds().equals(actual.serverIds())
            && intent.target().equals(actual.target()) && intent.revision().equals(actual.revision())
            && intent.exactAction().equals(actual.exactAction()) && intent.effectSummary().equals(actual.effectSummary())
            && intent.semantics().map(value -> (value.operation().equals("deployment.helper.install.v1") || value.operation().equals("deployment.helper.install.files.v1") || value.operation().equals("deployment.helper.install.files.custom.v1"))
                && value.targets().equals(DeploymentManagedHelper.installationTargets(server, layout)))
            .orElse(false);
    }

    /** Returns actual bounded sandbox feedback. / 返回真实有界沙箱反馈。
     * @param original authentic caller task / 真实调用任务
     * @param candidate original prepared workspace / 原准备工作区
     * @param revision reviewed source revision / 经审阅来源修订
     * @param script exact reviewed command / 精确经审阅命令
     * @param timeout bounded command duration / 有界命令时长
     * @return measured exit and bounded output / 实测退出值与有界输出
     * @throws Exception if the original command receipt is unavailable / 原命令回执不可得时 */
    @Override
    public CommandFeedback command(TaskContext original, Prepared candidate, String revision, String script,
                                   Duration timeout) throws Exception {
        sameTask(original);
        requireIsolation();
        String component = componentCandidates.get(candidate.workspace().candidateId());
        var selected = buildTools.tools(component);
        var result = projects.command(candidate, revision, script, timeout, DeploymentAgentBuildTools.encoded(selected));
        if (result.known()) buildTools.used(component, selected);
        return result;
    }

    /** Reads only the protected current source. / 仅读取受保护当前源码。
     * @param original authentic caller task / 真实调用任务
     * @param candidate original prepared workspace / 原准备工作区
     * @param revision current source identity / 当前来源身份
     * @param path reviewed relative source path / 经审阅相对源码路径
     * @param offset first requested line / 首个请求行
     * @param limit maximum requested lines / 最大请求行数
     * @return measured bounded source excerpt / 实测有界源码片段
     * @throws Exception if exact source cannot be read / 无法读取精确源码时 */
    @Override
    public String read(TaskContext original, Prepared candidate, String revision, String path, int offset, int limit)
        throws Exception {
        sameTask(original);
        return projects.read(candidate, revision, path, offset, limit);
    }

    /** Returns current source including verified patches. / 返回包含已核实补丁的当前来源。
     * @param component original component / 原组件
     * @return current browser / 当前浏览器
     * @throws Exception unavailable source / 来源不可用 */
    @Override
    public gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser source(String component) throws Exception {
        return sources.browser(component);
    }

    /** Runs only the selected common recipe or resource capability. / 只运行选定公共配方或资源能力。
     * @param request original selected request / 原选定请求
     * @param component planned component / 计划组件
     * @param candidate exact candidate / 精确候选
     * @param revision current candidate source / 当前候选来源
     * @return measured feedback / 实测反馈
     * @throws Exception invalid or uncertain result / 无效或不确定结果
     * @param binding Original effect identity / 原效果身份
     * @param applicationId Current declared application used to derive the same component identity before preparation / 当前声明应用，用于准备前推导一致组件身份
     */
    @Override
    public String capability(gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProposal.Capability request,
                             DeploymentAgentPlan.Part component, Prepared candidate, String revision, String binding, String applicationId) throws Exception {
        if (request.name().equals("reconnect")) {
            if (!applicationId.isEmpty() || !reconnectAvailable() || !DeploymentAgentActionLog.effectsSettled(journal.events(task))
                || DeploymentModelCommandRunner.unconfirmed(context, task))
                throw new IllegalArgumentException("RECONNECT_UNAVAILABLE: original operation boundary is unsettled");
            beforeSend.verify();
            var facts = reconnectSession.reconnect();
            beforeSend.verify();
            journal.event(task, "CONNECTION_REPLACED", "", DeploymentAiJson.facts(facts, 2000));
            return DeploymentAiJson.facts(facts, 2000);
        }
        if (request.name().equals("check")) {
            var facts = value(hosts.forRuntime(component.runtime()));
            return "ENVIRONMENT " + facts.facts()
                + "; limited Agent observations only; UNKNOWN coverage is not absence. This observation does not perform full platform, CPU, security or runtime admission. Assess missing facts against the proposed action; custom runtime/recipe applicability still requires independent assessment";
        }
        if (!Set.of("analyze", "database_select_source", "secrets", "secret_select").contains(request.name())
            && !request.name().equals("database_inspect")) {
            requireIsolationComponent(applicationId, component);
            if (isolationPolicy != null && request.name().equals("databases")) requireIsolatedDatabases(component);
            requireIsolation();
        }
        if (Set.of("prepare_tools", "build").contains(request.name())) validateCapability(request, component);
        if (Set.of("analyze", "prepare_tools", "build").contains(request.name()))
            return buildTools.run(request, component, candidate, revision);
        String managed = component == null ? applicationId : DeploymentManagedUnitResolver.managedId(applicationId, component.id());
        return resourceTools.run(managed, request, component, candidate, binding);
    }

    @Override
    public void validateCapability(gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProposal.Capability request,
                                   DeploymentAgentPlan.Part component) throws Exception {
        if (request.name().equals("databases")) resourceTools.validatePreparation(component);
        else buildTools.validateCapability(request, component);
    }

    @Override
    public Map<String, Object> capabilityState(DeploymentAgentPlan.Part component) {
        return buildTools.state(component);
    }

    /** Projects only original verified resource references. / 只投影原已核实资源引用。
     * @param component independent plan / 独立计划
     * @return bound plan / 绑定计划
     * @throws Exception invalid or stale reference / 无效或过时引用 */
    @Override
    public DeploymentAgentPlan.Part resolve(DeploymentAgentPlan.Part component) throws Exception {
        return resourceTools.resolve(component);
    }

    /** Applies the exact reviewed structured patch. / 应用精确已审结构化补丁。
     * @param original authentic caller task / 真实调用任务
     * @param candidate original prepared workspace / 原准备工作区
     * @param revision reviewed current source identity / 经审阅当前来源身份
     * @param patch exact bounded structured edit / 精确有界结构化编辑
     * @return confirmed updated source revision / 已确认更新来源修订
     * @throws Exception if approval, application or receipt fails / 审批、应用或回执失败时 */
    @Override
    public String patch(TaskContext original, Prepared candidate, String revision, DeploymentAgentPatch patch)
        throws Exception {
        sameTask(original);
        requireIsolation();
        if (!revision.equals(patch.sourceRevision()))
            throw new IllegalArgumentException("Agent patch revision changed");
        String component = componentCandidates.get(candidate.workspace().candidateId());
        var pending = predictedPatches.remove(candidate.workspace().candidateId());
        if (pending == null || !pending.patch().equals(patch))
            throw new IllegalArgumentException("Exact locally validated patch required");
        var predicted = pending.prediction();
        String actual = projects.patch(candidate, patch);
        sources.promote(component, predicted, actual);
        journal.event(task, "DERIVATIVE_SOURCE", component, predicted.material().snapshot().id() + ":" + predicted.material().snapshot().sha256());
        return actual;
    }

    /** Verifies current local bytes and saves expected identity before approval. / 审批前核实当前本地字节并保存预期身份。
     * @param original original task / 原任务
     * @param candidate protected source / 受保护来源
     * @param patch exact edit / 精确编辑
     * @throws Exception conflict or unavailable snapshot / 冲突或快照不可用 */
    @Override
    public void validatePatch(TaskContext original, Prepared candidate, DeploymentAgentPatch patch) throws Exception {
        sameTask(original);
        String component = componentCandidates.get(candidate.workspace().candidateId());
        var predicted = sources.predict(component, patch);
        journal.event(task, "DERIVATIVE_SOURCE_EXPECTED", component, predicted.material().snapshot().id() + ":"
            + predicted.material().snapshot().sha256() + ":" + predicted.revision());
        predictedPatches.put(candidate.workspace().candidateId(), new PendingPatch(patch, predicted));
    }

    @Override
    public void validateSeal(TaskContext original, Prepared candidate, String revision) {
        sameTask(original);
        projects.requireSealable(candidate, revision);
    }

    /** Seals one actual source-bound output. / 封存一项真实来源绑定输出。
     * @param original authentic caller task / 真实调用任务
     * @param candidate original prepared workspace / 原准备工作区
     * @param revision exact built source identity / 精确构建来源身份
     * @return confirmed sealed artifact digest / 已确认封存制品摘要
     * @throws Exception if output cannot be verified and sealed / 无法校验及封存输出时 */
    @Override
    public String seal(TaskContext original, Prepared candidate, String revision) throws Exception {
        sameTask(original);
        requireIsolation();
        return projects.seal(candidate, revision);
    }

    /** Delegates to the same managed publication core used by standard mode. / 委托标准模式共用的受管发布核心。
     * @param original authentic caller task / 真实调用任务
     * @param plan source-backed reviewed graph / 来源有据经审阅图
     * @param selected exact original component candidates / 精确原组件候选
     * @param revisions confirmed component source identities / 已确认组件来源身份
     * @param artifacts confirmed sealed artifact identities / 已确认封存制品身份
     * @param remainingDecisions original remaining Decisions bound to this operation / 当前操作绑定的原remainingDecisions值
     * @return classified formal publication result / 已分类正式发布结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    @Override
    public OperationResult<Published> deliver(TaskContext original, DeploymentAgentPlan plan,
                                              Map<String, Prepared> selected, Map<String, String> revisions, Map<String, String> artifacts,
                                              int remainingDecisions) throws Exception {
        sameTask(original);
        if (isolationPolicy != null && !plan.equals(isolationPlan)) throw new SecurityException("Isolation publication plan changed");
        requireIsolation();
        plan.requireKnownDatabases();
        if (!selected.keySet().equals(artifacts.keySet()) || !selected.keySet().equals(revisions.keySet()))
            throw new IllegalArgumentException("Unsealed agent graph");
        if (!configurationRevision.equals(currentRevision.get()))
            throw new SecurityException("Agent configuration changed before publication");
        var proposal = proposal(plan, selected, revisions, artifacts);
        var inherited = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.currentWorker()
            .flatMap(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.Worker::predecessor);
        if (inherited.isPresent())
            gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentContinuation.requireResources(inherited.orElseThrow(), proposal);
        var neutral = new LinkedHashMap<String, DeploymentGraphPublisher.Candidate>();
        for (var component : plan.components()) {
            String id = component.id();
            neutral.put(id, new DeploymentGraphPublisher.Candidate(selected.get(id).workspace().candidateId(),
                artifacts.get(id), revisions.get(id),
                buildTools.executed(id).selections().isEmpty() ? "0".repeat(64)
                    : gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.identity(buildTools.executed(id)),
                !buildTools.executed(id).selections().isEmpty(), resourceTools.credentials(component)));
            journal.event(task, "SEALED_TOOLS", id, neutral.get(id).toolchainBindingSha256() + ":" + neutral.get(id).hasTools());
        }
        var originalCommit = new DeploymentOriginalPublishCommit(context);
        var credentials = new HashMap<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef, gold.debug.windowstolinux.appapi.secret.model.SecretId>();
        for (var candidate : neutral.values()) credentials.putAll(candidate.credentials());
        String binding = sha256(plan.toString() + neutral);
        var intent = new DeploymentPublishPlan(UUID.randomUUID(), project, plan.applicationId(), server,
            DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, binding,
            DeploymentOriginalPublishCommit.references(server, proposal, credentials), Instant.now());
        var outcome = originalCommit.execute(intent, proposal, ignored -> {
            var workspaces = new LinkedHashMap<String, DeploymentRemoteWorkspace>();
            selected.forEach((id, candidate) -> workspaces.put(id, candidate.workspace()));
            var evidence = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentPublicationEvidence(
                context, session, server, original, configurationRevision, proposal, neutral, workspaces, journal);
            return DeploymentAgentPublishRunner.run(context.withCurrentEvidence(evidence), session, server, original,
                configurationRevision, proposal, neutral, journal, () -> {
                    beforeSend.verify();
                    evidence.verifyPendingBinding();
                }, remainingDecisions, resourceTools::releaseKnown,
                accepted -> failedPublication = new FailedPublication(accepted, proposal, neutral));
        }, original);
        if (outcome.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(outcome.outcome(), Optional.empty(), outcome.problem());
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new Published(artifacts.keySet(), "Verified managed graph and whole-application health")),
            Optional.empty());
    }

    @Override
    public Optional<DeploymentAgentFailedPublication> failedPublication(TaskContext original) {
        sameTask(original);
        return Optional.ofNullable(failedPublication).map(FailedPublication::accepted);
    }

    @Override
    public OperationResult<Void> settleFailedPublication(TaskContext original, DeploymentAgentFailedPublication accepted) {
        sameTask(original);
        try {
            var failed = Objects.requireNonNull(failedPublication);
            if (!failed.accepted().equals(accepted))
                throw new IllegalArgumentException("Original failure acceptance changed");
            beforeSend.verify();
            accepted.requireSettlement(context, session, server, task, configurationRevision, failed.proposal(), failed.candidates(), journal);
            resourceTools.releaseKnown();
            accepted.requireSettlement(context, session, server, task, configurationRevision, failed.proposal(), failed.candidates(), journal);
            gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.releaseKnown(context, task);
            var evidence = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCleanupEvidence(
                context, session, server, task, configurationRevision, failed.proposal(), failed.candidates(), journal, beforeSend, false);
            var publisher = new DeploymentGraphPublisher(evidence.context(), evidence, server, task,
                (type, action, detail) -> journal.event(task, type, action, detail), beforeSend);
            var cleaned = publisher.cleanup(failed.proposal(), failed.candidates(), false);
            if (cleaned.outcome() != TaskResult.SUCCEEDED)
                return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), cleaned.problem());
            journal.event(task, "AI_PUBLICATION_FAILED_CLEANED", accepted.execution().applicationId(), "ABSENT");
            return cleaned;
        } catch (Exception uncertain) {
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                "DEPLOYMENT_AGENT_FAILED_CLEANUP_UNCONFIRMED", "Original failed publication cleanup requires reconciliation",
                Map.of(), Optional.of(task.id()))));
        }
    }

    /** Maps Agent evidence into complete typed business graph records. / 将自主依据映射为完整类型化业务图记录。
     * @param plan source-backed graph / 来源有据图
     * @param selected actual candidates / 真实候选
     * @param revisions current source revisions / 当前来源修订
     * @param artifacts sealed artifact identities / 封存制品身份
     * @return complete formal publication proposal / 完整正式发布提案
     * @throws Exception on conflicting ownership or configuration / 归属或配置冲突时 */
    private DeploymentPublishResult proposal(DeploymentAgentPlan plan, Map<String, Prepared> selected,
                                             Map<String, String> revisions, Map<String, String> artifacts) throws Exception {
        var resolver = new DeploymentManagedUnitResolver(context.db());
        var configs = new DeploymentConfigStore(context.db());
        var graph = new ArrayList<DeploymentApplicationGraph.Part>();
        var commits = new ArrayList<DeploymentUnitCommit>();
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        for (var component : plan.components()) {
            String id = component.id();
            String unitId = DeploymentManagedUnitResolver.managedId(plan.applicationId(), id);
            var unit = resolver.resolve(task, unitId, server, layout);
            if (!selected.get(id).workspace().applicationId().equals(unit.id()))
                throw new IllegalArgumentException("Agent candidate unit identity changed");
            var runtime = component.runtime();
            var activation = new DeploymentUnitRuntimeConfig(runtime.healthCheck(), Optional.empty(),
                runtime.identityPolicy(), runtime.workload());
            var config = DeploymentConfigSnapshot.create(unit.id(), configs.nextRevision(task, unit.id()),
                "agent", now, DeploymentAgentRuntimeEnvironment.entries(runtime, component.configuration(), buildTools.executed(id)));
            String release = gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.publicationReleaseIdentity(
                artifacts.get(id), revisions.get(id), config.sha256(), runtime, component.resources());
            graph.add(new DeploymentApplicationGraph.Part(id, unit, activation, component.dependencies(), runtime,
                component.resources().fileBindings().stream().map(DeploymentFileBinding::dataPath).toList(),
                component.resources()));
            if (component.databaseIds().isEmpty())
                throw new IllegalArgumentException("Database requirements remain unknown");
            commits.add(new DeploymentUnitCommit(unit, activation, new DeploymentCurrentRelease(unit.id(), release,
                now), config, component.secretReferences()));
        }
        return new DeploymentPublishResult(new DeploymentApplicationGraph(plan.applicationId(),
            plan.healthComponentId(), plan.health(), graph), commits);
    }

    /** Hashes the exact nonsecret publication binding. / 散列精确不含秘密发布绑定。
     * @param value bounded identity text / 有界身份文本
     * @return lowercase SHA-256 / 小写 SHA-256 */
    private static String sha256(String value) {
        return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Releases only owned derivatives; the workflow owns its borrowed session. / 只释放所属衍生素材，工作流持有借用会话。
     * @throws Exception unconfirmed derivative cleanup / 衍生素材清理未确认时 */
    @Override
    public void close() throws Exception {
        try {
            sources.close();
            prepared.clear();
        } finally {
            reconnectSession.close();
        }
    }

    /** Rebinds exact send approvals without recreating candidates or resetting execution time. / 重新绑定精确发送审批，不重建候选或重置执行时间。
     * @param revision explicitly verified replacement route / 明确核实的替换路由
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    @Override
    public void replaceConfiguration(String revision) throws Exception {
        if (!revision.matches("[0-9a-f]{64}") || !revision.equals(currentRevision.get())
            || !context.linux().pendingTransfers(task).isEmpty())
            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_REFRESH_UNSETTLED",
                "Original model replacement boundary is unsettled", Map.of(), Optional.empty()));
        DeploymentModelCommandRunner.replaceConfiguration(context, task, configurationRevision, revision);
        configurationRevision = revision;
        approvalConversation = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApprovalConversation(
            context, task, server, session.facts().fingerprint(), originalSourceRevision, revision, layout);
        candidateSession = candidateDiagnosticSession(revision);
        candidates = new DeploymentCandidateChannel(candidateSession, interaction, server, task, revision, budget, beforeSend, layout);
        hosts = new DeploymentAgentHostChannel(session, interaction, server, task, revision, budget, beforeSend, layout);
        projects.replaceConfiguration(revision);
        buildTools.replaceConfiguration(revision);
    }

    @Override
    public Optional<gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation> approvalConversation() {
        return Optional.of(approvalConversation);
    }

    /** Observes candidate API failures before the shared channel preserves UNKNOWN. / 在共用通道保留UNKNOWN前观察候选API失败。 */
    private RemoteSession candidateDiagnosticSession(String revision) {
        return new RemoteSession() {
            @Override
            public RemoteFacts facts() {
                return session.facts();
            }

            @Override
            public OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant) throws ApiException {
                try {
                    return session.execute(request, grant);
                } catch (ApiException failure) {
                    candidateApiFailure(request, revision, failure);
                    throw failure;
                }
            }

            @Override
            public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant) throws ApiException {
                return session.upload(request, grant);
            }

            @Override
            public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
                return session.upload(request, grant, choice);
            }

            @Override
            public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant) throws ApiException {
                return session.download(request, grant);
            }

            @Override
            public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
                return session.download(request, grant, choice);
            }

            @Override
            public OperationResult<TransferResult> resumeTransfer(UUID transferId, ApprovalGrant grant) throws ApiException {
                return session.resumeTransfer(transferId, grant);
            }

            @Override
            public OperationResult<Void> discardTransfer(UUID transferId, ApprovalGrant grant) throws ApiException {
                return session.discardTransfer(transferId, grant);
            }

            @Override
            public void close() throws ApiException {
                session.close();
            }
        };
    }

    private void candidateApiFailure(CommandRequest request, String revision, ApiException failure) {
        String requestSha256;
        String metadata;
        try {
            requestSha256 = sha256(RemoteActions.command(server, revision, request).exactAction());
            var facts = new LinkedHashMap<String, String>();
            facts.put("category", "CANDIDATE_EXECUTE");
            String name = failure.getClass().getSimpleName();
            facts.put("exceptionClass", name.matches("[A-Za-z_$][A-Za-z0-9_$]{0,127}") ? name : "ApiException");
            facts.put("problemCode", failure.problem().code().name());
            String event = failure.problem().eventCode();
            if (event != null && Set.of("TASK_CHECKPOINT_READ", "TASK_CHECKPOINT_RELEASE", "TASK_CHECKPOINT_UPDATE", "SERVICE_WORKER", "MOD_TASK_BINDING_CLOSED").contains(event))
                facts.put("problemEventCode", event);
            else facts.put("problemEventCodeSha256", event == null ? "ABSENT" : sha256(event));
            facts.put("messageSha256", failure.getMessage() == null ? "ABSENT" : sha256(failure.getMessage()));
            facts.put("exactRequestSha256", requestSha256);
            facts.put("task", task.id().toString());
            metadata = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(facts);
        } catch (Exception diagnosticFailure) {
            if (diagnosticFailure != failure) failure.addSuppressed(diagnosticFailure);
            return;
        }
        try {
            journal.event(task, "AI_CANDIDATE_COMMAND_API_FAILURE", requestSha256, metadata);
        } catch (Exception diagnosticFailure) {
            if (diagnosticFailure != failure) failure.addSuppressed(diagnosticFailure);
        }
        try {
            task.progress("DEPLOYMENT_AGENT_CANDIDATE_API_FAILURE " + metadata);
        } catch (Exception diagnosticFailure) {
            if (diagnosticFailure != failure) failure.addSuppressed(diagnosticFailure);
        }
    }

    /** Requires the same noncopyable platform task object. / 要求同一不可复制的平台任务对象。
     * @param original task argument / 任务参数 */
    private void sameTask(TaskContext original) {
        if (original != task)
            throw new SecurityException("Foreign agent task");
    }

    /** Keeps original classified failures and uncertainty. / 保留原分类失败与不确定性。
     * @param result original capability result / 原能力结果
     * @param <T> real payload type / 真实载荷类型
     * @return original confirmed value / 原已确认值
     * @throws DeploymentAiJson.CapabilityOutcomeException on non-success / 非成功时 */
    private static <T> T value(OperationResult<T> result)
        throws gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException {
        if (result.outcome() != TaskResult.SUCCEEDED)
            throw new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException(
                result.outcome(), result.problem());
        return result.value().orElseThrow();
    }
}
