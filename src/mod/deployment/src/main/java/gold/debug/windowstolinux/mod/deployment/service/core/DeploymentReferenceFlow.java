package gold.debug.windowstolinux.mod.deployment.service.core;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskWork;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.*;

import gold.debug.windowstolinux.mod.deployment.db.*;

/** Submits a real publication task and reconciles its original references without replaying effects. / 提交真实发布任务，并在不重放副作用的条件下核对原引用。 */
public final class DeploymentReferenceFlow implements TaskRecovery {
    /** Recoverable original task type. / 可恢复原任务类型。 */
    public static final String TASK_TYPE = "deployment-publication";

    /** Bound platform and durable execution facts. / 绑定的平台与持久执行事实。 */
    private final ModContext context;

    /** Original durable execution ledger. / 原持久执行账本。 */
    private final DeploymentExecutionRepository executions;

    /** Authoritative retained business associations. / 权威保留业务关联。 */
    private final DeploymentReferenceAssociations associations;

    /** Optional real remote recovery for standard publications, absent in isolated reference-only callers. / 标准发布的真实远端恢复；孤立引用调用方可不存在。 */
    private final Optional<DeploymentRemoteRecovery> remoteRecovery;

    /** Publication prepared within the same original platform task before durable reservation. / 在同一原平台任务内、持久预留前准备的发布。
     * @param plan complete original reference intent / 完整原引用意图
     * @param author one-shot original effect callback / 一次性原副作用回调
     * @param cleanup original prepared source and archive cleanup / 原已准备来源及归档清理 */
    public record PreparedPublication(DeploymentPublicationPlan plan, TaskWork<DeploymentPublication> author,
                                      AutoCloseable cleanup) {
        /** Requires the actual plan, author and cleanup. / 要求真实计划、作者及清理。
         * @param plan complete intent / 完整意图
         * @param author original callback / 原回调
         * @param cleanup original resource cleanup / 原资源清理 */
        public PreparedPublication {
            Objects.requireNonNull(plan);
            Objects.requireNonNull(author);
            Objects.requireNonNull(cleanup);
        }
    }

    /** Binds one actual Mod activation. / 绑定一个实际插件启用。
     * @param context original activation / 原启用 */
    public DeploymentReferenceFlow(ModContext context) {
        this(context, Optional.empty());
    }

    /** Binds original references and a narrow remote recovery assembled by the same Mod activation. / 绑定原引用及同一Mod启用装配的窄远端恢复。
     * @param context actual original activation / 实际原启用
     * @param recovery original remote recovery / 原远端恢复 */
    public DeploymentReferenceFlow(ModContext context, DeploymentRemoteRecovery recovery) {
        this(context, Optional.of(Objects.requireNonNull(recovery)));
    }

    /** Shares one original setup for reference-only and real standard callers. / 引用调用方和真实标准调用方共用一份原装配。
     * @param context original activation / 原启用
     * @param recovery optional narrow recovery / 可选窄恢复 */
    private DeploymentReferenceFlow(ModContext context, Optional<DeploymentRemoteRecovery> recovery) {
        this.context = Objects.requireNonNull(context);
        this.executions = new DeploymentExecutionRepository(context.db());
        this.associations = new DeploymentReferenceAssociations(context.db());
        this.remoteRecovery = recovery;
    }

    /** Submits a reviewed one-shot publication whose author callback supplies actual verified effects. / 提交作者回调提供实际已核实结果的一次性已审阅发布。
     * @param plan complete intent before reservation / 预留前的完整意图
     * @param author actual work and verification; never replayed by recovery / 实际工作及核实；恢复不重放
     * @return platform task handle / 平台任务句柄
     * @throws ApiException if task admission fails / 任务准入失败时 */
    public TaskHandle<Void> submit(DeploymentPublicationPlan plan, TaskWork<DeploymentPublication> author)
        throws ApiException {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(author);
        return context.tasks().submit(
            new TaskRequest(TASK_TYPE, "Deployment publication", List.of(plan.server().id()), true),
            task -> execute(plan, author, task));
    }

    /** Prepares source, plan and exact references inside the same original publication task. / 在同一原发布任务内准备来源、计划及精确引用。
     * @param server original selected server / 原选定服务器
     * @param preparation task-bound work producing intent before any publication effect / 发布副作用前产出意图的任务绑定工作
     * @return original platform task / 原平台任务
     * @throws ApiException if task admission fails / 任务准入失败时 */
    public TaskHandle<Void> submitPrepared(ServerRef server, TaskWork<PreparedPublication> preparation)
        throws ApiException {
        Objects.requireNonNull(server);
        Objects.requireNonNull(preparation);
        return context.tasks().submit(new TaskRequest(TASK_TYPE, "Deployment publication", List.of(server.id()), true),
            task -> {
                OperationResult<PreparedPublication> prepared;
                try {
                    prepared = Objects.requireNonNull(preparation.run(task));
                } catch (Exception | LinkageError | AssertionError uncertain) {
                    return unknown();
                }
                if (prepared.outcome() != Outcome.SUCCEEDED)
                    return new OperationResult<>(prepared.outcome(), Optional.empty(), prepared.problem());
                if (prepared.value().isEmpty())
                    return unknown();
                var ready = prepared.value().orElseThrow();
                var closed = new java.util.concurrent.atomic.AtomicBoolean();
                try {
                    if (!server.equals(ready.plan().server()))
                        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional
                            .of(problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_PREPARED_SERVER_CHANGED")));
                    return execute(ready.plan(), original -> {
                        OperationResult<DeploymentPublication> result = null;
                        try {
                            result = ready.author().run(original);
                            return result;
                        } finally {
                            if (closed.compareAndSet(false, true)) {
                                try {
                                    ready.cleanup().close();
                                } catch (Exception uncertainCleanup) {
                                    if (result == null || result.outcome() == Outcome.UNKNOWN)
                                        throw uncertainCleanup;
                                }
                            }
                        }
                    }, task);
                } finally {
                    if (closed.compareAndSet(false, true))
                        ready.cleanup().close();
                }
            });
    }

    /** Reconciles exact original evidence only; unknown external work is never run again. / 仅核对精确原证据；未知外部工作绝不重跑。
     * @param previous retained original task / 留存原任务
     * @param task authentic original recovery context / 真实原恢复上下文
     * @return known final result or explicit uncertainty / 已知最终结果或明确未知
     * @throws Exception if original evidence access fails / 原证据访问失败时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws Exception {
        if (!TASK_TYPE.equals(previous.type()) || !previous.id().equals(task.id()))
            return unknown();
        var original = executions.findForTask(task);
        if (original.isEmpty())
            return unknown();
        var record = original.orElseThrow();
        if ((record.phase().equals("EXECUTING") || record.phase().equals("COMMITTED")) && remoteRecovery.isPresent()) {
            var remote = remoteRecovery.orElseThrow().reconcile(task, record);
            if (remote.isPresent()) {
                var verified = remote.orElseThrow();
                if (verified.outcome() == Outcome.UNKNOWN)
                    return reconcileRecord(task, record);
                if (record.outcome().isEmpty() || record.outcome().orElseThrow() == Outcome.UNKNOWN) {
                    if (verified.outcome() == Outcome.SUCCEEDED) {
                        var proposal = verified.value().orElseThrow();
                        executions.commitApplication(task, record.identity().executionId(), proposal.graph(),
                            proposal.units(), Instant.now());
                    } else if (verified.outcome() == Outcome.FAILED || verified.outcome() == Outcome.CANCELLED) {
                        executions.recordUncommittedOutcome(task, record.identity().executionId(), verified.outcome(),
                            verified.problem().orElseThrow(), Instant.now());
                    } else
                        return unknown();
                } else if (record.outcome().orElseThrow() != verified.outcome())
                    return unknown();
                record = executions.find(task, record.identity().executionId()).orElseThrow();
            }
        }
        return reconcileRecord(task, record);
    }

    /** Runs one admitted callback with all reference intent durable before reservation. / 在所有引用意图持久化后运行一次准入回调。
     * @param plan original reviewed plan / 原审阅计划
     * @param author one-shot author work / 一次性作者工作
     * @param task authentic accepted task / 真实准入任务
     * @return observed result / 观测结果 */
    private OperationResult<Void> execute(DeploymentPublicationPlan plan, TaskWork<DeploymentPublication> author,
                                          TaskContext task) {
        var identity = new DeploymentExecutionIdentity(plan.executionId(), task.id(),
            plan.project().map(DeploymentProjectRecord::id), plan.applicationId(), plan.server(), plan.mode(),
            plan.reviewedRevision());
        try {
            executions.begin(task, identity, TASK_TYPE, plan.references(), plan.project(), plan.createdAt());
            for (var intent : plan.references())
                reserveOriginal(task, identity, intent);
            executions.startEffects(task, identity.executionId(), Instant.now());
            OperationResult<DeploymentPublication> result;
            try {
                result = Objects.requireNonNull(author.run(task));
            } catch (Exception | LinkageError | AssertionError uncertain) {
                recordUnknown(task, identity.executionId());
                return unknown();
            }
            if (result.outcome() == Outcome.SUCCEEDED) {
                var publication = result.value().orElseThrow();
                requirePublication(plan, publication, task);
                executions.commitApplication(task, identity.executionId(), publication.graph(), publication.units(),
                    Instant.now());
            } else {
                if (result.outcome() == Outcome.UNKNOWN) {
                    var uncertainty = result.problem()
                        .orElseGet(() -> problem(ProblemCode.UNKNOWN, "DEPLOYMENT_EFFECT_UNKNOWN"));
                    recordUnknown(task, identity.executionId(), uncertainty);
                    return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(uncertainty));
                }
                executions.recordUncommittedOutcome(task, identity.executionId(), result.outcome(),
                    result.outcome() == Outcome.CANCELLED
                        ? result.problem()
                        .orElseGet(() -> problem(ProblemCode.CANCELLED, "DEPLOYMENT_CANCELLED"))
                        : result.problem().orElseThrow(),
                    Instant.now());
            }
            var finalRecord = executions.find(task, identity.executionId()).orElseThrow();
            var reconciled = reconcileRecord(task, finalRecord);
            if (reconciled.outcome() == Outcome.SUCCEEDED)
                return reconciled;
            if (reconciled.outcome() == Outcome.CANCELLED || reconciled.outcome() == Outcome.FAILED)
                return new OperationResult<>(reconciled.outcome(), Optional.empty(),
                    result.problem().or(() -> reconciled.problem()));
            return unknown();
        } catch (Exception | LinkageError | AssertionError uncertain) {
            recordUnknown(task, identity.executionId());
            return unknown();
        }
    }

    /** Finds an original reservation first; only new normal execution may reserve. / 先查原预留；仅新的正常执行可以预留。
     * @param task current original task / 当前原任务
     * @param identity original operation / 原操作
     * @param intent already persisted intent / 已持久意图
     * @throws SQLException if recording fails / 记录失败时
     * @throws ApiException if platform access fails / 平台访问失败时 */
    private void reserveOriginal(TaskContext task, DeploymentExecutionIdentity identity,
                                 DeploymentReferenceIntent intent) throws SQLException, ApiException {
        var found = find(task, intent);
        UUID id = found.isPresent() ? found.orElseThrow() : reserve(task, intent);
        executions.recordReference(task, identity.executionId(), intent, id, Instant.now());
    }

    /** Finds the exact original platform association without mutating it. / 不修改地查找精确原平台关联。
     * @param task original current task / 原当前任务
     * @param intent persisted exact intent / 持久精确意图
     * @return observed UUID or absence / 观测UUID或不存在
     * @throws ApiException if platform access fails / 平台访问失败时 */
    private Optional<UUID> find(TaskContext task, DeploymentReferenceIntent intent) throws ApiException {
        if (intent.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SERVER)
            return context.servers().findReference(new ServerRef(intent.resourceId()), intent.businessKey(), task)
                .map(ServerReference::id);
        return context.secrets().findReference(new SecretRef(intent.resourceId(), intent.purpose().orElseThrow()),
            intent.businessKey(), task).map(gold.debug.windowstolinux.appapi.secret.SecretReference::id);
    }

    /** Reserves only inside original normal execution. / 仅在原正常执行期间预留。
     * @param task original task / 原任务
     * @param intent already persisted intent / 已持久意图
     * @return actual reservation UUID / 实际预留UUID
     * @throws ApiException if platform reservation fails / 平台预留失败时 */
    private UUID reserve(TaskContext task, DeploymentReferenceIntent intent) throws ApiException {
        if (intent.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SERVER)
            return context.servers().reserve(new ServerRef(intent.resourceId()), intent.businessKey(), task).id();
        return context.secrets()
            .reserve(new SecretRef(intent.resourceId(), intent.purpose().orElseThrow()), intent.businessKey(), task)
            .id();
    }

    /** Checks that the author supplied only originally reserved publication associations. / 核实作者仅返回原已预留发布关联。
     * @param plan original plan / 原计划
     * @param publication verified publication / 已核实发布
     * @param task authentic original task / 真实原任务
     * @throws SQLException if exact durable template conflicts / 精确持久模板冲突时
     * @throws ApiException if original task access is denied / 原任务访问被拒时 */
    private void requirePublication(DeploymentPublicationPlan plan, DeploymentPublication publication, TaskContext task)
        throws SQLException, ApiException {
        if (!publication.graph().applicationId().equals(plan.applicationId()))
            throw new IllegalArgumentException("Publication application changed");
        var published = new HashSet<DeploymentReferenceIntent>();
        published.add(DeploymentReferenceKey.application(plan.server(), plan.applicationId()));
        for (var unit : publication.units()) {
            published.add(DeploymentReferenceKey.unit(plan.server(), unit.unit().id()));
            for (var secret : unit.secrets()) {
                boolean matched = false;
                for (var intent : plan.references())
                    if (intent.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SECRET
                        && intent
                        .businessKey().equals(
                            DeploymentReferenceKey
                                .secretRevision(plan.server(),
                                    new SecretRef(intent.resourceId(),
                                        intent.purpose().orElseThrow()),
                                    secret)
                                .businessKey()))
                        matched = true;
                if (!matched)
                    throw new IllegalArgumentException(
                        "Publication secret revision and credential were not originally reserved");
            }
        }
        if (!plan.references().containsAll(published))
            throw new IllegalArgumentException("Publication unit was not originally reserved");
        var formal = executions.find(task, plan.executionId()).orElseThrow();
        if (executions.publicationTemplate(formal).isPresent())
            executions.recordPublicationTemplate(task, plan.executionId(),
                new DeploymentPublicationProposal(publication.graph(), publication.units()), Instant.now());
    }

    /** Reconciles exact platform UUIDs and business rows, then confirms actual resource release. / 核对精确平台UUID与业务行，再确认真实资源释放。
     * @param task original current task / 原当前任务
     * @param record original durable execution / 原持久执行
     * @return known result or uncertainty / 已知结果或未知
     * @throws SQLException if durable evidence fails / 持久证据失败时
     * @throws ApiException if platform evidence fails / 平台证据失败时 */
    private OperationResult<Void> reconcileRecord(TaskContext task, DeploymentExecutionRecord record)
        throws SQLException, ApiException {
        if (!record.identity().taskId().equals(task.id()))
            return unknown();
        var intents = executions.references(record);
        var outcome = record.outcome();
        if (record.phase().equals("PREPARED") && (outcome.isEmpty() || outcome.get() == Outcome.UNKNOWN))
            return reconcileUnstarted(task, record, intents);
        if (outcome.isEmpty() || outcome.get() == Outcome.UNKNOWN) {
            protectUnknown(task, record, intents);
            return outcome.isPresent()
                ? new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), executions.problem(record))
                : unknown();
        }
        if (record.businessCommitted() != (outcome.get() == Outcome.SUCCEEDED))
            return unknown();
        for (var entry : intents.entrySet()) {
            var intent = entry.getKey();
            DeploymentReferenceKey.requireOriginal(intent, record.identity());
            var observed = find(task, intent);
            UUID original = entry.getValue().orElse(null);
            if (original == null) {
                if (observed.isPresent()) {
                    original = observed.orElseThrow();
                    if (record.phase().equals("PREPARED"))
                        executions.recordReference(task, record.identity().executionId(), intent, original,
                            Instant.now());
                }
            } else if (observed.isPresent() && !original.equals(observed.orElseThrow()))
                return unknown();
            var association = associations.observe(task, record.identity(), intent);
            if (association == DeploymentReferenceAssociations.AssociationState.CONFLICT)
                return unknown();
            if (association == DeploymentReferenceAssociations.AssociationState.REFERENCED) {
                if (observed.isEmpty() || original == null)
                    return unknown();
                resolve(task, intent, original, ReferenceResolution.REFERENCED);
            } else if (outcome.get() != Outcome.SUCCEEDED) {
                if (original != null)
                    resolve(task, intent, original, ReferenceResolution.NOT_REFERENCED);
            } else
                return unknown();
        }
        if (!context.tasks().resourcesReleased(task))
            return unknown();
        executions.finishCleanup(task, record.identity().executionId(), Instant.now());
        return new OperationResult<>(outcome.get(), Optional.empty(), executions.problem(record));
    }

    /** Resolves only a persisted PREPARED boundary with no possible author effect. / 仅核对不可能已有作者副作用的持久PREPARED边界。
     * @param task original task / 原任务
     * @param record original PREPARED execution / 原PREPARED执行
     * @param intents exact retained intents / 精确留存意图
     * @return proven unstarted failure or unknown / 已证明未开始失败或未知
     * @throws SQLException if business evidence fails / 业务证据失败时
     * @throws ApiException if platform evidence fails / 平台证据失败时 */
    private OperationResult<Void> reconcileUnstarted(TaskContext task, DeploymentExecutionRecord record,
                                                     Map<DeploymentReferenceIntent, Optional<UUID>> intents) throws SQLException, ApiException {
        if (!context.tasks().resourcesReleased(task))
            return unknown();
        for (var entry : intents.entrySet()) {
            var intent = entry.getKey();
            DeploymentReferenceKey.requireOriginal(intent, record.identity());
            var association = associations.observe(task, record.identity(), intent);
            if (association == DeploymentReferenceAssociations.AssociationState.CONFLICT)
                return unknown();
            var found = find(task, intent);
            var id = entry.getValue();
            if (id.isPresent() && found.isPresent() && !id.equals(found))
                return unknown();
            if (association == DeploymentReferenceAssociations.AssociationState.REFERENCED && found.isEmpty())
                return unknown();
            if (id.isEmpty() && found.isPresent()) {
                executions.recordReference(task, record.identity().executionId(), intent, found.orElseThrow(),
                    Instant.now());
                id = found;
            }
            if (id.isPresent())
                resolve(task, intent, id.orElseThrow(),
                    association == DeploymentReferenceAssociations.AssociationState.REFERENCED
                        ? ReferenceResolution.REFERENCED
                        : ReferenceResolution.NOT_REFERENCED);
        }
        var failure = problem(ProblemCode.UNAVAILABLE, "DEPLOYMENT_UNSTARTED");
        executions.recordUncommittedOutcome(task, record.identity().executionId(), Outcome.FAILED, failure,
            Instant.now());
        executions.finishCleanup(task, record.identity().executionId(), Instant.now());
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(failure));
    }

    /** Protects only proven original UUIDs while an author effect remains uncertain. / 作者副作用不明时仅保护已证明的原UUID。
     * @param task original task / 原任务
     * @param record retained execution / 留存执行
     * @param intents retained intent and UUID evidence / 留存意图及UUID证据
     * @throws SQLException if business evidence is unavailable / 业务证据不可用时
     * @throws ApiException if platform evidence is unavailable / 平台证据不可用时 */
    private void protectUnknown(TaskContext task, DeploymentExecutionRecord record,
                                Map<DeploymentReferenceIntent, Optional<UUID>> intents) throws SQLException, ApiException {
        for (var entry : intents.entrySet()) {
            var intent = entry.getKey();
            DeploymentReferenceKey.requireOriginal(intent, record.identity());
            var found = find(task, intent);
            if (entry.getValue().isPresent() && found.isPresent() && !entry.getValue().equals(found))
                continue;
            if (found.isEmpty())
                continue;
            var original = entry.getValue().orElseGet(found::orElseThrow);
            if (entry.getValue().isEmpty() && record.phase().equals("PREPARED"))
                executions.recordReference(task, record.identity().executionId(), intent, original, Instant.now());
            var association = associations.observe(task, record.identity(), intent);
            resolve(task, intent, original,
                association == DeploymentReferenceAssociations.AssociationState.REFERENCED
                    ? ReferenceResolution.REFERENCED
                    : ReferenceResolution.UNKNOWN);
        }
    }

    /** Applies only a proven resolution to its exact original platform reference. / 仅对精确原平台引用应用已证明结果。
     * @param task original task / 原任务
     * @param intent original intent / 原意图
     * @param referenceId original UUID / 原UUID
     * @param resolution proven association / 已证明关联
     * @throws ApiException if platform acknowledgement fails / 平台确认失败时 */
    private void resolve(TaskContext task, DeploymentReferenceIntent intent, UUID referenceId,
                         ReferenceResolution resolution) throws ApiException {
        if (intent.kind() == DeploymentReferenceIntent.DeploymentReferenceKind.SERVER)
            context.servers().resolve(referenceId, resolution, task);
        else
            context.secrets().resolve(referenceId, resolution, task);
    }

    /** Retains uncertainty when original effects or their acknowledgement are not known. / 原副作用或其确认不明时保留未知。
     * @param task original task / 原任务
     * @param executionId original business UUID / 原业务UUID */
    private void recordUnknown(TaskContext task, UUID executionId) {
        recordUnknown(task, executionId, problem(ProblemCode.UNKNOWN, "DEPLOYMENT_EFFECT_UNKNOWN"));
    }

    /** Saves only safe public failure metadata and protects observed original references. / 仅保存安全公开失败元数据并保护已观察原引用。
     * @param task original task / 原任务
     * @param executionId original operation / 原操作
     * @param uncertainty actual bounded public problem / 实际有界公开问题 */
    private void recordUnknown(TaskContext task, UUID executionId, Problem uncertainty) {
        try {
            executions.recordUncommittedOutcome(task, executionId, Outcome.UNKNOWN, uncertainty, Instant.now());
        } catch (Exception failedToRecord) {
            // The platform task remains UNKNOWN; failed recording does not prove rollback. / 平台任务仍为UNKNOWN；记录失败不证明回滚。
        }
        try {
            var record = executions.findForTask(task);
            if (record.isPresent())
                protectUnknown(task, record.orElseThrow(), executions.references(record.orElseThrow()));
        } catch (Exception unresolved) {
            // Missing evidence cannot authorize release. / 证据缺失不能授权释放。
        }
    }

    /** Creates a bounded non-secret problem. / 创建有界且不含秘密的问题。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return structured problem / 结构化问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Deployment publication needs reconciliation", Map.of(), Optional.empty());
    }

    /** Returns explicit uncertainty with no replay. / 返回不重放的明确未知。
     * @return unknown task result / 未知任务结果 */
    private static OperationResult<Void> unknown() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_REFERENCE_UNKNOWN")));
    }
}
