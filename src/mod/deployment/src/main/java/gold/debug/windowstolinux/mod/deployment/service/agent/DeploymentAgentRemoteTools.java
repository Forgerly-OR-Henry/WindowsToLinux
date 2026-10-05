package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublish;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublishPlan;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentManagedUnitResolver;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRemoteWorkspace;

/** Adapts the original authenticated task to protected agent tools and shared publication. / 将原认证任务适配至受保护自主工具与共用发布。 */
public final class DeploymentAgentRemoteTools implements DeploymentAgentDeliveryPort {
    /** Borrowed original capabilities and frozen source archive. / 借用的原能力与冻结源码归档。 */
    private final RemoteSession session;

    /** Actual original owner capabilities. / 实际原所有者能力。 */
    private final ModContext context;

    /** Exact admitted target server reference. / 精确已准入目标服务器引用。 */
    private final ServerId server;

    /** Authentic original platform task context. / 真实原平台任务上下文。 */
    private final TaskContext task;

    /** Open frozen archive retained for the original task. / 为原任务保留的开放冻结归档。 */
    private final DeploymentSourceArchive archive;

    /** Reviewed fixed candidate operations on the same target. / 同一目标上经审阅固定候选操作。 */
    private DeploymentCandidateChannel candidates;

    /** Exact packaged helper installer and identity verifier. / 精确随附 helper 安装器及身份校验器。 */
    private DeploymentManagedHelper helper;

    /** Protected agent source and build channel on the original session. / 原会话上的受保护自主源码及构建频道。 */
    private final DeploymentAgentProjectChannel projects;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentActionLog journal;

    /** Exact reviewed saved source, absent for a local directory. / 精确经审阅已存来源，本地目录时为空。 */
    private final Optional<DeploymentProjectRecord> project;

    /** Original frozen all-purpose model-routing identity. / 原冻结全用途模型路由身份。 */
    private String configurationRevision;

    /** Read-only live observer of the task-authorized AI revision. / 任务授权 AI 修订的只读实时观察器。 */
    private final Supplier<String> currentRevision;

    /** Frozen policy guard checked immediately before dispatch. / 发送紧前检查的冻结策略校验器。 */
    private final DeploymentSendGuard beforeSend;

    /** Original exact host review capability. / 原精确宿主审核能力。 */
    private final InteractionService interaction;

    /** One monotonic budget retained through explicit model replacement. / 明确模型替换中保留的一份单调预算。 */
    private final DeploymentBuildBudget budget = new DeploymentBuildBudget(7200);

    /** One confirmed helper identity and prepared component IDs. / 一份已确认 helper 身份与已准备组件标识。 */
    private boolean helperReady;

    /** One actual first-helper refusal retained to prevent repeatedly reviewing the same fixed installer. / 保留一次真实首个helper拒绝，防止反复审核同一固定安装器。 */
    private DeploymentLowRiskRequiredException helperRefusal;

    /** Component IDs whose original preparation has already started. / 原准备已开始的组件标识。 */
    private final Set<String> prepared = new HashSet<>();

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
     * @param authorizationMode original immutable authorization repetition / 原不可变授权重复规则
     * @throws ApiException if an original independent model role is unavailable / 原独立模型角色不可用时 */
    public DeploymentAgentRemoteTools(ModContext context, RemoteSession session, InteractionService interaction, ServerId server,
                                      TaskContext task, DeploymentSourceArchive archive, String configurationRevision,
                                      Supplier<String> currentRevision, DeploymentAgentActionLog journal,
                                      Optional<DeploymentProjectRecord> project, String approvalMode, String authorizationMode) throws ApiException {
        this.session = Objects.requireNonNull(session);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        this.archive = Objects.requireNonNull(archive);
        this.journal = Objects.requireNonNull(journal);
        this.project = Objects.requireNonNull(project);
        this.configurationRevision = Objects.requireNonNull(configurationRevision);
        this.currentRevision = Objects.requireNonNull(currentRevision);
        this.context = new DeploymentCommandReviewScope(context, interaction, task, approvalMode, authorizationMode, () -> this.configurationRevision);
        this.interaction = this.context.interaction();
        this.beforeSend = () -> {
            if (!this.configurationRevision.equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task)))
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                    "DEPLOYMENT_AGENT_CONFIGURATION_CHANGED", "AI configuration changed before remote dispatch",
                    Map.of(), Optional.empty()));
        };
        this.candidates = new DeploymentCandidateChannel(session, this.interaction, server, task, configurationRevision,
            budget, beforeSend);
        this.helper = new DeploymentManagedHelper(session, this.interaction, server, task, configurationRevision,
            beforeSend);
        this.projects = new DeploymentAgentProjectChannel(session, this.interaction, server, task, configurationRevision,
            currentRevision);
    }

    /** Returns only authenticated, actually observed host facts. / 仅返回已认证且实际观察的主机事实。
     * @return original connection fingerprint and measured capabilities / 原连接指纹与实测能力
     * @throws Exception if host evidence cannot be confirmed / 主机依据无法确认时 */
    @Override
    public Map<String, String> inspect() throws Exception {
        var facts = value(candidates.hostFacts());
        return Map.of("fingerprint", session.facts().fingerprint(), "capabilities", facts.toString());
    }

    /** Creates, uploads, hashes and opens a task-owned protected candidate. / 创建、上传、散列并开启任务所属受保护候选。
     * @param applicationId reviewed graph identity / 经审阅图身份
     * @param component reviewed source-backed component / 经审阅来源有据组件
     * @return original protected candidate and source revision / 原受保护候选与来源修订
     * @throws Exception if preparation or its exact receipt fails / 准备或其精确回执失败时 */
    @Override
    public Prepared prepare(String applicationId, DeploymentAgentPlan.Part component) throws Exception {
        if (helperRefusal != null) throw helperRefusal;
        if (!prepared.add(component.id()))
            throw new IllegalStateException("Agent component was already prepared");
        if (!helperReady) {
            try {
                value(helper.install());
            } catch (DeploymentLowRiskRequiredException refused) {
                prepared.remove(component.id());
                helperRefusal = DeploymentLowRiskRequiredException.beforePreparation(task, refused);
                throw helperRefusal;
            }
            helperReady = true;
        }
        var facts = value(candidates.hostFacts());
        if (facts.toString().isBlank())
            throw new IllegalStateException("Agent host facts missing");
        long minimum = Math.max(256L * 1024 * 1024, Math.multiplyExact(archive.byteCount(), 3));
        if (value(candidates.availableBytes()) < minimum)
            throw new IllegalStateException("Insufficient verified agent candidate space");
        var workspace = new DeploymentRemoteWorkspace(DeploymentManagedUnitResolver.managedId(applicationId,
            component.id()), archive.sha256());
        value(candidates.create(workspace, minimum));
        value(candidates.upload(archive, workspace));
        value(candidates.verify(workspace, archive));
        String engine = switch (component.runtime()) {
            case gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition.Container c ->
                c.engine().name().toLowerCase(Locale.ROOT);
            default -> "ordinary";
        };
        return new Prepared(workspace, projects.open(workspace, engine));
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
        return projects.command(candidate, revision, script, timeout);
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
        if (!revision.equals(patch.sourceRevision()))
            throw new IllegalArgumentException("Agent patch revision changed");
        return projects.patch(candidate, patch);
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
        return projects.seal(candidate, revision);
    }

    /** Delegates to the same managed publication core used by standard mode. / 委托标准模式共用的受管发布核心。
     * @param original authentic caller task / 真实调用任务
     * @param plan source-backed reviewed graph / 来源有据经审阅图
     * @param selected exact original component candidates / 精确原组件候选
     * @param revisions confirmed component source identities / 已确认组件来源身份
     * @param artifacts confirmed sealed artifact identities / 已确认封存制品身份
     * @return classified formal publication result / 已分类正式发布结果
     * @throws Exception if evidence or business preparation fails / 依据或业务准备失败时 */
    @Override
    public OperationResult<Published> deliver(TaskContext original, DeploymentAgentPlan plan,
                                              Map<String, Prepared> selected, Map<String, String> revisions, Map<String, String> artifacts)
        throws Exception {
        sameTask(original);
        if (!selected.keySet().equals(artifacts.keySet()) || !selected.keySet().equals(revisions.keySet()))
            throw new IllegalArgumentException("Unsealed agent graph");
        if (!configurationRevision.equals(currentRevision.get()))
            throw new SecurityException("Agent configuration changed before publication");
        var proposal = proposal(plan, selected, revisions, artifacts);
        var neutral = new LinkedHashMap<String, DeploymentGraphPublisher.Candidate>();
        for (var component : plan.components()) {
            String id = component.id();
            neutral.put(id, new DeploymentGraphPublisher.Candidate(selected.get(id).workspace().candidateId(),
                artifacts.get(id), revisions.get(id), "0".repeat(64), false, Map.of()));
        }
        var originalCommit = new DeploymentOriginalPublishCommit(context);
        String binding = sha256(plan.toString() + neutral);
        var intent = new DeploymentPublishPlan(UUID.randomUUID(), project, plan.applicationId(), server,
            DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, binding,
            DeploymentOriginalPublishCommit.references(server, proposal, Map.of()), Instant.now());
        var publisher = new DeploymentGraphPublisher(context, session, server, original,
            (type, action, detail) -> journal.event(original, type, action, detail), beforeSend);
        var outcome = originalCommit.execute(intent, proposal, ignored -> {
            var published = publisher.publish(proposal, neutral);
            if (published.outcome() == TaskResult.SUCCEEDED || published.outcome() == TaskResult.FAILED) {
                var cleaned = publisher.cleanup(proposal, neutral, published.outcome() == TaskResult.SUCCEEDED);
                if (cleaned.outcome() != TaskResult.SUCCEEDED)
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), cleaned.problem());
            }
            return published;
        }, original);
        if (outcome.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(outcome.outcome(), Optional.empty(), outcome.problem());
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new Published(artifacts.keySet(), "Verified managed graph and whole-application health")),
            Optional.empty());
    }

    /** Maps Agent evidence into complete typed business graph records. / 将自主依据映射为完整类型化业务图记录。
     * @param plan source-backed graph / 来源有据图
     * @param selected actual candidates / 真实候选
     * @param revisions current source revisions / 当前来源修订
     * @param artifacts sealed artifact identities / 封存制品身份
     * @return complete formal publication proposal / 完整正式发布提案
     * @throws Exception on conflicting ownership or configuration / 归属或配置冲突时 */
    private DeploymentPublish proposal(DeploymentAgentPlan plan, Map<String, Prepared> selected,
                                       Map<String, String> revisions, Map<String, String> artifacts) throws Exception {
        var resolver = new DeploymentManagedUnitResolver(context.db());
        var configs = new DeploymentConfigStore(context.db());
        var graph = new ArrayList<DeploymentApplicationGraph.Part>();
        var commits = new ArrayList<DeploymentUnitCommit>();
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        for (var component : plan.components()) {
            String id = component.id();
            String unitId = DeploymentManagedUnitResolver.managedId(plan.applicationId(), id);
            var unit = resolver.resolve(task, unitId, server);
            if (!selected.get(id).workspace().applicationId().equals(unit.id()))
                throw new IllegalArgumentException("Agent candidate unit identity changed");
            var runtime = component.runtime();
            var activation = new DeploymentUnitRuntimeConfig(runtime.healthCheck(), Optional.empty(),
                runtime.identityPolicy(), runtime.workload());
            var config = DeploymentConfigSnapshot.create(unit.id(), configs.nextRevision(task, unit.id()),
                "agent-v2", now, component.configuration());
            String release = sha256("agent-release-v2:" + artifacts.get(id) + ":" + revisions.get(id) + ":"
                + config.sha256() + ":" + runtime + ":" + component.resources());
            graph.add(new DeploymentApplicationGraph.Part(id, unit, activation, component.dependencies(), runtime,
                component.resources().fileBindings().stream().map(DeploymentFileBinding::dataPath).toList(),
                component.resources()));
            commits.add(new DeploymentUnitCommit(unit, activation, new DeploymentCurrentRelease(unit.id(), release,
                now), config, List.of()));
        }
        return new DeploymentPublish(new DeploymentApplicationGraph(plan.applicationId(),
            plan.healthComponentId(), plan.health(), graph), commits);
    }

    /** Hashes the exact nonsecret publication binding. / 散列精确不含秘密发布绑定。
     * @param value bounded identity text / 有界身份文本
     * @return lowercase SHA-256 / 小写 SHA-256 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    /** Releases only this adapter; the workflow owns its borrowed SSH session. / 仅释放本适配器；工作流持有借用 SSH 会话。 */
    @Override
    public void close() {
        prepared.clear();
    }

    /** Rebinds exact send approvals without recreating candidates or resetting execution time. / 重新绑定精确发送审批，不重建候选或重置执行时间。
     * @param revision explicitly verified replacement route / 明确核实的替换路由
     * @throws ApiException if actual configuration differs or transfers remain / 实际配置不同或传输仍存在时 */
    @Override
    public void replaceConfiguration(String revision) throws ApiException {
        if (!revision.matches("[0-9a-f]{64}") || !revision.equals(currentRevision.get())
            || !context.linux().pendingTransfers(task).isEmpty())
            throw new ApiException(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_REFRESH_UNSETTLED",
                "Original model replacement boundary is unsettled", Map.of(), Optional.empty()));
        configurationRevision = revision;
        candidates = new DeploymentCandidateChannel(session, interaction, server, task, revision, budget, beforeSend);
        helper = new DeploymentManagedHelper(session, interaction, server, task, revision, beforeSend);
        projects.replaceConfiguration(revision);
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
