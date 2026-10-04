package gold.debug.windowstolinux.mod.deployment.service.core.delivery;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRef;

/** Commits a verified graph inside an already admitted original task with exact reference protection. / 在已接纳原任务内以精确引用保护提交经核实图。 */
public final class DeploymentOriginalPublishCommit {
    /** Original platform and formal execution repository. / 原平台与正式执行仓库。 */
    private final ModContext context;

    /** Original formal execution evidence repository. / 原正式执行依据仓库。 */
    private final DeploymentExecutionStore executions;

    /** Retained current and historical reference associations. / 保留的当前及历史引用关联。 */
    private final DeploymentRefAssociations associations;

    /** Binds one activation without submitting another task. / 绑定一次启用而不提交另一任务。
     * @param context original Mod / 原 Mod */
    public DeploymentOriginalPublishCommit(ModContext context) {
        this.context = Objects.requireNonNull(context);
        this.executions = new DeploymentExecutionStore(context.db());
        this.associations = new DeploymentRefAssociations(context.db());
    }

    /** Runs one author publication after its exact resource intent is durable. / 在精确资源意图持久后执行一次作者发布。
     * @param plan complete original intent / 完整原意图
     * @param proposal complete nonsecret graph before dispatch / 发送前的完整不含秘密图
     * @param work actual single-use remote publication / 真实一次性远端发布
     * @param task original accepted task / 原接纳任务
     * @return classified actual publication / 分类真实发布 */
    public OperationResult<DeploymentPublish> execute(DeploymentPublishPlan plan,
                                                      DeploymentPublish proposal, TaskWork<DeploymentPublish> work, TaskContext task) {
        return execute(plan, proposal, work, task, "deployment-agent");
    }

    /** Commits one caller-owned original task using its exact recovery type. / 使用精确恢复类型提交一项调用方所属原任务。
     * @param plan original reference intent / 原引用意图
     * @param proposal complete original graph / 完整原图
     * @param work one-shot original effects / 一次性原副作用
     * @param task authentic original task / 真实原任务
     * @param operationType caller-owned recovery type / 调用方所属恢复类型
     * @return real result or retained uncertainty / 真实结果或留存未知 */
    public OperationResult<DeploymentPublish> execute(DeploymentPublishPlan plan,
                                                      DeploymentPublish proposal, TaskWork<DeploymentPublish> work, TaskContext task,
                                                      String operationType) {
        if (operationType == null || !operationType.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid original publication operation type");
        var identity = new DeploymentExecutionIdentity(plan.executionId(), task.id(),
            plan.project().map(DeploymentProjectRecord::id), plan.applicationId(), plan.server(), plan.mode(),
            plan.reviewedRevision());
        boolean effectsStarted = false;
        try {
            begin(plan, proposal, task, operationType);
            effectsStarted = true;
            OperationResult<DeploymentPublish> result = Objects.requireNonNull(work.run(task));
            if (result.outcome() == TaskResult.SUCCEEDED) {
                if (!proposal.equals(result.value().orElseThrow()))
                    return unknown(task, identity.executionId(), "DEPLOYMENT_AGENT_PROPOSAL_CHANGED");
                return commitVerified(task, identity.executionId(), proposal);
            }
            if (result.outcome() == TaskResult.UNKNOWN)
                return unknown(task, identity.executionId(), result.problem().orElseThrow().eventCode());
            var failure = result.problem().orElse(null);
            return completeUncommittedVerified(task, identity.executionId(), result.outcome(), failure);
        } catch (Exception uncertain) {
            if (effectsStarted)
                return unknown(task, identity.executionId(), "DEPLOYMENT_AGENT_COMMIT_UNCONFIRMED");
            return unknown(task, identity.executionId(), "DEPLOYMENT_AGENT_RESERVATION_UNCONFIRMED");
        }
    }

    /** Persists one original intent and crosses its effect boundary exactly once. / 持久化一份原意图并且仅一次越过其副作用边界。
     * @param plan original reviewed reference intent / 原经审阅引用意图
     * @param proposal complete immutable original graph / 完整不可变原图
     * @param task authentic accepted task / 真实接纳任务
     * @param operationType caller-owned recovery type / 调用方所属恢复类型
     * @throws Exception if identity, reservations or durable boundary cannot be confirmed / 身份、预留或持久边界无法确认时 */
    public void begin(DeploymentPublishPlan plan, DeploymentPublish proposal, TaskContext task,
                      String operationType) throws Exception {
        if (operationType == null || !operationType.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid original publication operation type");
        if (executions.findForTask(task).isPresent())
            throw new IllegalStateException("Original publication cannot begin again");
        var identity = new DeploymentExecutionIdentity(plan.executionId(), task.id(),
            plan.project().map(DeploymentProjectRecord::id), plan.applicationId(), plan.server(), plan.mode(),
            plan.reviewedRevision());
        executions.begin(task, identity, operationType, plan.references(), plan.project(), plan.createdAt());
        for (var intent : plan.references())
            reserve(task, identity, intent);
        executions.startEffects(task, identity.executionId(), Instant.now());
        executions.recordPublicationTemplate(task, identity.executionId(),
            new DeploymentPublishProposal(proposal.graph(), proposal.units()), Instant.now());
    }

    /** Commits verified original effects, or reads an exact already committed result without writing again. / 提交已核实原副作用，或不再次写入地读取精确已提交结果。
     * @param task authentic original task / 真实原任务
     * @param executionId exact original execution / 精确原执行
     * @param proposal verified original immutable graph / 已核实原不可变图
     * @return confirmed formal result or retained uncertainty / 已确认正式结果或留存未知 */
    public OperationResult<DeploymentPublish> commitVerified(TaskContext task, UUID executionId,
                                                             DeploymentPublish proposal) {
        try {
            var record = executions.find(task, executionId).orElseThrow();
            var expected = new DeploymentPublishProposal(proposal.graph(), proposal.units());
            if (!record.identity().taskId().equals(task.id())
                || !executions.publicationTemplate(record).equals(Optional.of(expected)))
                return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_PROPOSAL_CHANGED");
            boolean committed = record.phase().equals("COMMITTED") && record.businessCommitted()
                && record.outcome().equals(Optional.of(TaskResult.SUCCEEDED));
            if (!committed) {
                if (!record.phase().equals("EXECUTING") || record.businessCommitted()
                    || record.outcome().filter(value -> value != TaskResult.UNKNOWN).isPresent())
                    return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_STAGE_CHANGED");
                executions.commitApplication(task, executionId, proposal.graph(), proposal.units(), Instant.now());
            }
            for (var entry : executions.references(record).entrySet()) {
                var intent = entry.getKey();
                DeploymentRefKey.requireOriginal(intent, record.identity());
                if (entry.getValue().isEmpty() || !entry.getValue().equals(find(task, intent))
                    || associations.observe(task, record.identity(), intent)
                    != DeploymentRefAssociations.AssociationState.REFERENCED)
                    return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_REFERENCE_CHANGED");
                if (!committed)
                    resolve(task, intent, ResourceRefResult.REFERENCED);
            }
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(proposal), Optional.empty());
        } catch (Exception unavailable) {
            return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_COMMIT_UNCONFIRMED");
        }
    }

    /** Records verified compensation and resolves only retained original business associations. / 记录已核实补偿并仅处理保留的原业务关联。
     * @param task authentic original task / 真实原任务
     * @param executionId exact original execution / 精确原执行
     * @param outcome verified failure or cancellation / 已核实失败或取消
     * @param reason original structured reason, nullable for cancellation / 原结构化原因，取消时可空
     * @return exact known result or retained uncertainty / 精确已知结果或留存未知 */
    public OperationResult<DeploymentPublish> completeUncommittedVerified(TaskContext task, UUID executionId,
                                                                          TaskResult outcome, Problem reason) {
        if (!Set.of(TaskResult.FAILED, TaskResult.CANCELLED).contains(outcome))
            throw new IllegalArgumentException("Uncommitted completion requires a verified failure or cancellation");
        if (reason == null)
            reason = outcome == TaskResult.CANCELLED
                ? problem(ProblemCode.CANCELLED, "DEPLOYMENT_ORIGINAL_CANCELLED")
                : problem(ProblemCode.CONTRACT_VIOLATION, "DEPLOYMENT_ORIGINAL_FAILURE_REASON_MISSING");
        try {
            var record = executions.find(task, executionId).orElseThrow();
            if (!record.identity().taskId().equals(task.id()) || record.businessCommitted()
                || !record.phase().equals("EXECUTING"))
                return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_STAGE_CHANGED");
            boolean known = record.outcome().filter(value -> value != TaskResult.UNKNOWN).isPresent();
            if (known) {
                var original = executions.problem(record);
                if (!record.outcome().equals(Optional.of(outcome)) || original.isEmpty()
                    || original.orElseThrow().code() != reason.code()
                    || !original.orElseThrow().eventCode().equals(reason.eventCode()))
                    return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_RESULT_CHANGED");
            } else
                executions.recordUncommittedOutcome(task, executionId, outcome, reason, Instant.now());
            for (var entry : executions.references(record).entrySet()) {
                var intent = entry.getKey();
                DeploymentRefKey.requireOriginal(intent, record.identity());
                var association = associations.observe(task, record.identity(), intent);
                var observed = find(task, intent);
                boolean released = known && observed.isEmpty()
                    && association == DeploymentRefAssociations.AssociationState.ABSENT;
                if (entry.getValue().isEmpty() || !released && !entry.getValue().equals(observed))
                    return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_REFERENCE_CHANGED");
                if (known) {
                    if (association == DeploymentRefAssociations.AssociationState.CONFLICT)
                        return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_REFERENCE_CONFLICT");
                } else if (!resolveObserved(task, record.identity(), intent))
                    return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_REFERENCE_CONFLICT");
            }
            return new OperationResult<>(outcome, Optional.empty(), Optional.of(reason));
        } catch (Exception unavailable) {
            return unknown(task, executionId, "DEPLOYMENT_ORIGINAL_COMPENSATION_UNCONFIRMED");
        }
    }

    /** Builds exact server and secret-revision intent for a neutral graph. / 为中立图构造精确服务器与秘密修订意图。
     * @param server selected target / 所选目标
     * @param proposal complete graph / 完整图
     * @param credentials exact revision-to-platform-reference map / 精确修订至平台引用映射
     * @return immutable original association set / 不可变原关联集合 */
    public static List<DeploymentRefIntent> references(ServerId server, DeploymentPublish proposal,
                                                       Map<DeploymentSecretRef, SecretId> credentials) {
        var values = new LinkedHashSet<DeploymentRefIntent>();
        values.add(DeploymentRefKey.application(server, proposal.graph().applicationId()));
        for (var commit : proposal.units()) {
            values.add(DeploymentRefKey.unit(server, commit.unit().id()));
            for (var revision : commit.secrets()) {
                SecretId ref = credentials.get(revision);
                if (ref == null)
                    throw new IllegalArgumentException("Missing exact secret reference");
                values.add(DeploymentRefKey.secretRevision(server, ref, revision));
            }
        }
        return List.copyOf(values);
    }

    /** Finishes only a remotely verified original result and its exact reserved references. / 仅结束经远端核实的原结果与精确预留引用。
     * @param task original recovery context / 原恢复上下文
     * @param record persisted original execution / 持久原执行
     * @return known result only after all resources are released / 仅全部资源释放后返回已知结果
     * @throws Exception when original evidence or acknowledgement is unavailable / 原依据或回执不可用时 */
    public OperationResult<Void> finishKnown(TaskContext task, DeploymentExecutionRecord record) throws Exception {
        var verified = verifyKnownReferences(task, record);
        if (verified.outcome() != TaskResult.SUCCEEDED)
            return verified;
        context.file().closeTaskResources(task);
        if (!context.tasks().resourcesReleased(task))
            return unknownVoid("DEPLOYMENT_AGENT_SOURCE_RETAINED");
        executions.finishCleanup(task, record.identity().executionId(), Instant.now());
        return new OperationResult<>(record.outcome().orElseThrow(), Optional.empty(), executions.problem(record));
    }

    /** Marks provider cleanup after verified remote release while the caller retains its own root checkpoint. / 调用方仍持有根检查点时，在已核实远端释放后标记提供方收尾。
     * @param task original authenticated caller task / 原已认证调用方任务
     * @param record exact original provider execution / 精确原提供方执行
     * @return verified provider result without closing caller resources / 不关闭调用方资源的已核实提供方结果
     * @throws Exception if formal identity or original reference evidence is unavailable / 正式身份或原引用依据不可用时 */
    public OperationResult<Void> finishProviderKnown(TaskContext task, DeploymentExecutionRecord record) throws Exception {
        var verified = verifyKnownReferences(task, record);
        if (verified.outcome() != TaskResult.SUCCEEDED)
            return verified;
        executions.finishCleanup(task, record.identity().executionId(), Instant.now());
        return new OperationResult<>(record.outcome().orElseThrow(), Optional.empty(), executions.problem(record));
    }

    /** Verifies one exact known formal result and its retained business associations. / 核实一项精确已知正式结果及保留业务关联。
     * @param task authentic original task / 真实原任务
     * @param record original formal evidence / 原正式依据
     * @return confirmed references or classified uncertainty / 已确认引用或分类未知
     * @throws Exception if original evidence cannot be read or resolved / 无法读取或处理原依据时 */
    private OperationResult<Void> verifyKnownReferences(TaskContext task, DeploymentExecutionRecord record) throws Exception {
        if (!record.identity().taskId().equals(task.id()) || record.outcome().isEmpty()
            || record.outcome().orElseThrow() == TaskResult.UNKNOWN
            || record.businessCommitted() != (record.outcome().orElseThrow() == TaskResult.SUCCEEDED))
            return unknownVoid("DEPLOYMENT_AGENT_FORMAL_RESULT_UNCONFIRMED");
        var references = executions.references(record);
        for (var entry : references.entrySet()) {
            var intent = entry.getKey();
            DeploymentRefKey.requireOriginal(intent, record.identity());
            var observed = find(task, intent);
            var association = associations.observe(task, record.identity(), intent);
            if (association == DeploymentRefAssociations.AssociationState.CONFLICT)
                return unknownVoid("DEPLOYMENT_AGENT_REFERENCE_ASSOCIATION_UNCONFIRMED");
            boolean released = !record.businessCommitted() && observed.isEmpty()
                && association == DeploymentRefAssociations.AssociationState.ABSENT;
            if (entry.getValue().isEmpty() || !released && !entry.getValue().equals(observed))
                return unknownVoid("DEPLOYMENT_AGENT_REFERENCE_CHANGED");
            if (released)
                continue;
            if (!resolveObserved(task, record.identity(), intent))
                return unknownVoid("DEPLOYMENT_AGENT_REFERENCE_ASSOCIATION_UNCONFIRMED");
        }
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Retains uncertainty without recasting an original problem. / 保留未知且不重写原问题。
     * @param event fixed diagnostic / 固定诊断
     * @return unknown result / 未知结果 */
    private static OperationResult<Void> unknownVoid(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem(ProblemCode.UNKNOWN,
            event)));
    }

    /** Finds or reserves the exact original platform reference. / 查找或预留精确原平台引用。
     * @param task original task / 原任务
     * @param identity original operation / 原操作
     * @param intent exact intent / 精确意图
     * @throws Exception when reservation evidence is unavailable / 预留依据不可用时 */
    private void reserve(TaskContext task, DeploymentExecutionIdentity identity, DeploymentRefIntent intent)
        throws Exception {
        Optional<UUID> existing = find(task, intent);
        UUID id = existing.orElseGet(() -> {
            try {
                if (intent.kind() == DeploymentRefIntent.DeploymentRefKind.SERVER)
                    return context.servers().reserve(new ServerId(intent.resourceId()), intent.businessKey(), task)
                        .id();
                return context.secrets().reserve(new SecretId(intent.resourceId(), intent.purpose().orElseThrow()),
                    intent.businessKey(), task).id();
            } catch (ApiException failure) {
                throw new IllegalStateException("Original reference reservation unconfirmed", failure);
            }
        });
        executions.recordReference(task, identity.executionId(), intent, id, Instant.now());
    }

    /** Reads only the exact original platform reference. / 仅读取精确原平台引用。
     * @param task original task / 原任务
     * @param intent exact intent / 精确意图
     * @return original reference ID or absence / 原引用标识或不存在
     * @throws ApiException when authority is unavailable / 权限不可用时 */
    private Optional<UUID> find(TaskContext task, DeploymentRefIntent intent) throws ApiException {
        if (intent.kind() == DeploymentRefIntent.DeploymentRefKind.SERVER)
            return context.servers().findReference(new ServerId(intent.resourceId()), intent.businessKey(), task)
                .map(value -> value.id());
        return context.secrets().findReference(new SecretId(intent.resourceId(), intent.purpose().orElseThrow()),
            intent.businessKey(), task).map(value -> value.id());
    }

    /** Resolves only the already recorded original reference. / 仅处理已记录原引用。
     * @param task original task / 原任务
     * @param intent exact intent / 精确意图
     * @param resolution proven business association / 已证实业务关联
     * @throws Exception when original evidence cannot be confirmed / 原依据无法确认时 */
    private void resolve(TaskContext task, DeploymentRefIntent intent, ResourceRefResult resolution)
        throws Exception {
        UUID id = find(task, intent).orElseThrow(() -> new IllegalStateException("Original reference disappeared"));
        if (intent.kind() == DeploymentRefIntent.DeploymentRefKind.SERVER)
            context.servers().resolve(id, resolution, task);
        else
            context.secrets().resolve(id, resolution, task);
    }

    /** Resolves a reused reference according to retained business rows, including release history. / 按保留业务行（包括发布历史）处理复用引用。
     * @param task original task / 原任务
     * @param identity original operation / 原操作
     * @param intent exact reserved intent / 精确预留意图
     * @return whether association was proved / 关联是否已证实
     * @throws Exception if original evidence is unavailable / 原依据不可用时 */
    private boolean resolveObserved(TaskContext task, DeploymentExecutionIdentity identity,
                                    DeploymentRefIntent intent) throws Exception {
        var observed = associations.observe(task, identity, intent);
        if (observed == DeploymentRefAssociations.AssociationState.CONFLICT)
            return false;
        resolve(task, intent, observed == DeploymentRefAssociations.AssociationState.REFERENCED
            ? ResourceRefResult.REFERENCED : ResourceRefResult.NOT_REFERENCED);
        return true;
    }

    /** Records unresolved effects without releasing any original reference. / 记录未决副作用而不释放原引用。
     * @param task original task / 原任务
     * @param execution original execution / 原执行
     * @param event bounded diagnostic / 有界诊断
     * @return explicit unknown / 明确未知 */
    private OperationResult<DeploymentPublish> unknown(TaskContext task, UUID execution, String event) {
        var problem = problem(ProblemCode.UNKNOWN, event);
        try {
            executions.recordUncommittedOutcome(task, execution, TaskResult.UNKNOWN, problem, Instant.now());
        } catch (Exception unconfirmed) {
            // A failed record cannot prove rollback. / 记录失败不能证明已回滚。
        }
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(problem));
    }

    /** Builds one nonsecret public failure. / 构造一项不含秘密的公开失败。
     * @param code category / 分类
     * @param event stable event / 稳定事件
     * @return structured failure / 结构化失败 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Agent publication requires original-task reconciliation", Map.of(),
            Optional.empty());
    }
}
