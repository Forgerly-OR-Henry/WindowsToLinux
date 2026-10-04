package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import static gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentRestoreEvidence.*;
import static gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentRestoreSteps.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Original-task complete restore, with immutable step receipts and no unknown-effect replay. / 原任务完整恢复，使用不可变步骤回执且不重放未知效果。 */
public final class DeploymentRestoreFlow {
    /** Actual provider target and approval authority. / 实际提供方目标与审批权限。 */
    private final ModContext context;

    /** Original authorized handoff phase journal. / 原已授权交接阶段账本。 */
    private final DeploymentHandoffLedger ledger;

    /** Canonical archived publication and release validation. / 规范归档发布及发布校验。 */
    private final DeploymentReleaseStore releases;

    /** Original formal execution identity and outcomes. / 原正式执行身份及结果。 */
    private final DeploymentExecutionStore executions;

    /** Shared atomic publication and exact reference checks. / 共用原子发布与精确引用核对。 */
    private final DeploymentOriginalPublishCommit commit;

    /** Complete target authority and candidate preparation phases. / 完整目标权限与候选准备阶段。 */
    private final DeploymentRestoreSetup inputs;

    /** Original candidate and rollback-material cleanup. / 原候选及回滚材料清理。 */
    private final DeploymentRestoreCleanup cleanup;

    /** Original compensation and known activation recovery. / 原补偿及已知激活恢复。 */
    private final DeploymentRestoreRecovery recovery;

    /** Binds original deployment capabilities. / 绑定原部署能力。
     * @param context active provider / 活动提供方 */
    public DeploymentRestoreFlow(ModContext context) {
        this.context = Objects.requireNonNull(context);
        ledger = new DeploymentHandoffLedger(context);
        releases = new DeploymentReleaseStore(context.db());
        executions = new DeploymentExecutionStore(context.db());
        commit = new DeploymentOriginalPublishCommit(context);
        inputs = new DeploymentRestoreSetup(context, commit);
        cleanup = new DeploymentRestoreCleanup(context);
        recovery = new DeploymentRestoreRecovery(context, releases, executions, commit, cleanup, inputs);
    }

    /** Reviews the current authenticated target, including every actual current release. / 审阅当前已认证目标，含全部实际当前发布。
     * @param archived original immutable archive / 原不可变归档
     * @param target actual target / 实际目标
     * @param archiveSha caller-verified complete archive digest / 调用方核实的完整归档摘要
     * @param task authentic original task / 真实原任务
     * @return exact reviewed target digest / 精确已审阅目标摘要
     * @throws ApiException if target scope is unavailable / 目标范围不可用时 */
    public OperationResult<String> review(DeploymentApplicationSnapshot archived, ServerId target, String archiveSha,
                                          TaskContext task) throws ApiException {
        context.tasks().inspect(task);
        try {
            var archive = DeploymentArchivedRuntimeCodec.restore(archived, releases);
            var server = context.servers().get(target, task);
            try (var session = context.linux().connect(target, task)) {
                var helper = new DeploymentManagedHelper(session, context.interaction(), target, task,
                    archived.revisionSha256()).probe();
                if (helper.outcome() != TaskResult.SUCCEEDED)
                    return copy(helper);
                var channel = new DeploymentHandoffChannel(session, context.interaction(), target, task,
                    archived.revisionSha256());
                var current = targets(archive.publication().graph(), channel);
                return success(
                    reviewed(archived, archiveSha, context.servers().get(target, task), session.facts(), current));
            }
        } catch (ApiException denied) {
            throw denied;
        } catch (Exception invalid) {
            return failed("DEPLOYMENT_RESTORE_TARGET_REVIEW_FAILED");
        }
    }

    /** Verifies the complete archive and every file before reserving or creating remote candidates. / 预留或创建远端候选前核实完整归档及全部文件。
     * @param request exact target review / 精确目标审阅
     * @param task original caller task / 原调用任务
     * @return durable prepared handoff, or the original handoff with uncertainty on an exact request repeat / 持久已准备交接，或精确请求重查时携未知结果的原交接
     * @throws ApiException if original task scope is unavailable / 原任务范围不可用时 */
    public OperationResult<DeploymentHandoff> prepare(DeploymentRestoreRequest request, TaskContext task)
        throws ApiException {
        context.tasks().inspect(task);
        DeploymentRestoreState cursor = null;
        try {
            var plan = new DeploymentRestorePlan(request, releases);
            var original = ledger.findRestore(request, task);
            if (original.isPresent() && (original.orElseThrow().publicValue().state() != DeploymentHandoffState.REVIEWED
                || ledger.recovery(original.orElseThrow()).path("changed").asBoolean())) {
                var handoff = original.orElseThrow().publicValue();
                return handoff.state() == DeploymentHandoffState.PREPARED
                    ? success(handoff)
                    : new OperationResult<>(TaskResult.UNKNOWN, Optional.of(handoff), Optional
                    .of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_RESTORE_PREPARATION_RECONCILE_REQUIRED")));
            }
            for (var file : plan.files)
                DeploymentRestorePlan.verifyLocal(file.file());
            inputs.collision(plan, task);
            var credentials = inputs.authorize(plan, task);
            var server = context.servers().get(request.target(), task);
            try (var session = context.linux().connect(request.target(), task)) {
                var helper = new DeploymentManagedHelper(session, context.interaction(), request.target(), task,
                    request.reviewedTargetSha256()).probe();
                if (helper.outcome() != TaskResult.SUCCEEDED)
                    return copy(helper);
                var channel = channel(context, session, request, task);
                var current = targets(plan.proposal.graph(), channel);
                if (!reviewed(request.archivedApplication(), request.archiveSha256(),
                    context.servers().get(request.target(), task), session.facts(), current)
                    .equals(request.reviewedTargetSha256()))
                    return failed("DEPLOYMENT_RESTORE_TARGET_CHANGED");
                var handoff = ledger.beginRestore(request, task);
                cursor = new DeploymentRestoreState(ledger, required(handoff.id(), task), task);
                if (cursor.entry.publicValue().state() == DeploymentHandoffState.PREPARED)
                    return success(cursor.entry.publicValue());
                if (cursor.entry.publicValue().state() != DeploymentHandoffState.REVIEWED)
                    return unknown("DEPLOYMENT_RESTORE_PREPARATION_RECONCILE_REQUIRED");
                if (task.cancelToken().requested())
                    return cancelled("DEPLOYMENT_RESTORE_CANCELLED_BEFORE_EFFECT");
                long requiredSpace = 0;
                for (var file : plan.files)
                    requiredSpace = Math.addExact(requiredSpace, Math.multiplyExact(file.file().byteCount(), 3));
                if (!DeploymentHandoffChannel.receipt(send(channel, "handoff-restore-preflight",
                    List.of(Long.toString(requiredSpace)), Duration.ofMinutes(2)), "RESTORE_SPACE_VERIFIED=1\n"))
                    return failed("DEPLOYMENT_RESTORE_TARGET_SPACE_UNCONFIRMED");
                var begun = inputs.beginPreparation(cursor, plan, session, current, credentials, task);
                if (begun.outcome() != TaskResult.SUCCEEDED)
                    return copy(begun);
                var publisher = publication(context, session, request, task);
                for (var component : plan.proposal.graph().components()) {
                    var prepared = inputs.prepareComponent(cursor, plan, component, channel, publisher, session, task);
                    if (prepared.outcome() != TaskResult.SUCCEEDED)
                        return copy(prepared);
                }
                cursor.save(DeploymentHandoffState.PREPARED);
            }
            return success(cursor.entry.publicValue());
        } catch (ApiException failure) {
            if (cursor == null || !cursor.facts.path("changed").asBoolean())
                throw failure;
            return retain(cursor, "DEPLOYMENT_RESTORE_PREPARE_CALL_UNKNOWN");
        } catch (Exception failure) {
            return cursor == null || !cursor.facts.path("changed").asBoolean()
                ? failed("DEPLOYMENT_RESTORE_INPUT_INVALID")
                : retain(cursor, "DEPLOYMENT_RESTORE_PREPARE_CALL_UNKNOWN");
        }
    }

    /** Activates only the complete prepared original candidate, then atomically records the whole graph. / 仅激活完整已准备原候选，再原子记录整图。
     * @param id original handoff / 原交接
     * @param task authentic original task / 真实原任务
     * @return verified activation and actual cleanup / 已核实激活及实际清理
     * @throws ApiException if original authority is unavailable / 原权限不可用时 */
    public OperationResult<DeploymentActivation> activate(UUID id, TaskContext task) throws ApiException {
        var cursor = new DeploymentRestoreState(ledger, required(id, task), task);
        if (cursor.entry.publicValue().state() == DeploymentHandoffState.FINISHED) {
            if (!cursor.facts.path("verifiedActive").asBoolean())
                return failed("DEPLOYMENT_RESTORE_FINISHED_WITHOUT_ACTIVATION");
            try {
                if (commit.finishProviderKnown(task, executions.find(task, id).orElseThrow())
                    .outcome() != TaskResult.SUCCEEDED)
                    return unknown("DEPLOYMENT_RESTORE_RESOURCES_UNCONFIRMED");
            } catch (Exception missing) {
                return unknown("DEPLOYMENT_RESTORE_FORMAL_RESULT_UNCONFIRMED");
            }
            return success(activation(cursor, true));
        }
        if (cursor.entry.publicValue().state() == DeploymentHandoffState.ACTIVE)
            return recovery.finishActive(cursor, task);
        if (cursor.entry.publicValue().state() != DeploymentHandoffState.PREPARED)
            return unknown("DEPLOYMENT_RESTORE_ACTIVATION_RECONCILE_REQUIRED");
        try {
            var plan = new DeploymentRestorePlan(DeploymentRestorePlan.read(cursor.entry), releases);
            inputs.authorize(plan, task);
            inputs.collision(plan, task);
            var request = plan.request;
            var server = context.servers().get(request.target(), task);
            OperationResult<DeploymentActivation> businessResult;
            try (var session = context.linux().connect(request.target(), task)) {
                if (new DeploymentManagedHelper(session, context.interaction(), request.target(), task,
                    request.reviewedTargetSha256()).probe().outcome() != TaskResult.SUCCEEDED)
                    return unknown("DEPLOYMENT_RESTORE_HELPER_UNCONFIRMED");
                var channel = channel(context, session, request, task);
                var publisher = publication(context, session, request, task);
                var before = before(cursor);
                if (!targets(plan.proposal.graph(), channel).equals(before)
                    || !reviewed(request.archivedApplication(), request.archiveSha256(), server, session.facts(),
                    before).equals(request.reviewedTargetSha256()))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_TARGET_CHANGED");
                if (task.cancelToken().requested())
                    return unknown("DEPLOYMENT_RESTORE_CANCELLED_PREPARED_DISCARD_REQUIRED");
                cursor.save(DeploymentHandoffState.ACTIVATING);
                var activated = activatePrepared(cursor, plan, channel);
                if (activated.outcome() != TaskResult.SUCCEEDED)
                    return copy(activated);
                var health = health(plan, channel, publisher);
                if (health.outcome() != TaskResult.SUCCEEDED) {
                    if (health.outcome() == TaskResult.UNKNOWN)
                        return cursor.uncertain("DEPLOYMENT_RESTORE_HEALTH_UNKNOWN");
                    businessResult = recovery.compensate(cursor, plan, channel, publisher,
                        health.problem().orElseThrow());
                    if (businessResult.outcome() == TaskResult.UNKNOWN)
                        return businessResult;
                } else {
                    var formal = commit.commitVerified(task, id,
                        new DeploymentPublish(plan.proposal.graph(), plan.proposal.units()));
                    if (formal.outcome() != TaskResult.SUCCEEDED)
                        return cursor.uncertain("DEPLOYMENT_RESTORE_LOCAL_COMMIT_UNKNOWN");
                    cursor.facts.put("verifiedActive", true);
                    cursor.save(DeploymentHandoffState.ACTIVE);
                    if (!cleanup.cleanup(cursor, plan, channel, false))
                        return cursor.uncertain("DEPLOYMENT_RESTORE_CLEANUP_UNKNOWN");
                    cursor.save(DeploymentHandoffState.FINISHED);
                    businessResult = success(activation(cursor, true));
                }
            }
            var finished = commit.finishProviderKnown(task, executions.find(task, id).orElseThrow());
            if (finished.outcome() != businessResult.outcome())
                return unknown("DEPLOYMENT_RESTORE_RESOURCES_UNCONFIRMED");
            return businessResult;
        } catch (Exception unavailable) {
            return retain(cursor, "DEPLOYMENT_RESTORE_ACTIVATION_CALL_UNKNOWN");
        }
    }

    /** Discards only original candidates before a snapshot or formal activation was attempted. / 仅在尝试快照或正式激活前丢弃原候选。
     * @param id original handoff / 原交接
     * @param task authentic original task / 真实原任务
     * @return known original cleanup / 已知原收尾
     * @throws ApiException if original authority is unavailable / 原权限不可用时 */
    public OperationResult<DeploymentHandoff> discard(UUID id, TaskContext task) throws ApiException {
        var cursor = new DeploymentRestoreState(ledger, required(id, task), task);
        if (cursor.entry.publicValue().state() == DeploymentHandoffState.FINISHED) {
            try {
                var record = executions.find(task, id);
                if (record.isPresent()
                    && commit.finishProviderKnown(task, record.orElseThrow()).outcome() == TaskResult.UNKNOWN)
                    return unknown("DEPLOYMENT_RESTORE_FORMAL_CLEANUP_UNCONFIRMED");
            } catch (Exception uncertain) {
                return unknown("DEPLOYMENT_RESTORE_FORMAL_CLEANUP_UNCONFIRMED");
            }
            return success(cursor.entry.publicValue());
        }
        try {
            if (cursor.facts.path("compensated").asBoolean())
                return recovery.finishCompensated(cursor, task);
            if (cursor.facts.path("components").findValues("snapshot").stream().anyMatch(
                value -> value.path("attempted").asBoolean()) || cursor.facts.path("verifiedActive").asBoolean())
                return unknown("DEPLOYMENT_RESTORE_ACTIVATION_REQUIRES_RECONCILE");
            var plan = new DeploymentRestorePlan(DeploymentRestorePlan.read(cursor.entry), releases);
            var originalExecution = executions.find(task, id);
            if (originalExecution.isPresent() && originalExecution.orElseThrow().phase().equals("PREPARED"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_RESERVATION_RECOVERY_REQUIRED");
            if (originalExecution.isPresent())
                cursor.facts.put("executionStarted", true);
            if (!cursor.facts.path("changed").asBoolean()) {
                cursor.save(DeploymentHandoffState.FINISHED);
                return success(cursor.entry.publicValue());
            }
            try (var session = context.linux().connect(plan.request.target(), task)) {
                if (new DeploymentManagedHelper(session, context.interaction(), plan.request.target(), task,
                    plan.request.reviewedTargetSha256()).probe().outcome() != TaskResult.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_HELPER_UNCONFIRMED");
                if (!sameTarget(context, cursor, plan, session))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_TARGET_CHANGED");
                var channel = channel(context, session, plan.request, task);
                if (!targets(plan.proposal.graph(), channel).equals(before(cursor)))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_CURRENT_CHANGED");
                if (!new DeploymentRestoreTransfers(context).discard(cursor, plan, session))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_ORIGINAL_TRANSFER_UNCONFIRMED");
                if (!cleanup.discardCandidates(cursor, plan, channel))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_DISCARD_UNKNOWN");
                if (cursor.facts.path("executionStarted").asBoolean() && commit
                    .completeUncommittedVerified(task, id, TaskResult.CANCELLED,
                        problem(ProblemCode.CANCELLED, "DEPLOYMENT_RESTORE_DISCARDED"))
                    .outcome() != TaskResult.CANCELLED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_FORMAL_DISCARD_UNCONFIRMED");
                cursor.facts.put("terminalOutcome", "CANCELLED");
                cursor.save(DeploymentHandoffState.FINISHED);
            }
            if (cursor.facts.path("executionStarted").asBoolean()) {
                var finished = commit.finishProviderKnown(task, executions.find(task, id).orElseThrow());
                if (finished.outcome() == TaskResult.UNKNOWN)
                    return unknown("DEPLOYMENT_RESTORE_RESOURCES_UNCONFIRMED");
            }
            return success(cursor.entry.publicValue());
        } catch (Exception unavailable) {
            return retain(cursor, "DEPLOYMENT_RESTORE_DISCARD_CALL_UNKNOWN");
        }
    }

    /** Reads original step receipts and current health without repeating preparation or activation. / 读取原步骤回执及当前健康，不重复准备或激活。
     * @param id original handoff / 原交接
     * @param task authentic original task / 真实原任务
     * @return observed complete phase or retained uncertainty / 已观察完整阶段或留存未知
     * @throws ApiException if original authority is unavailable / 原权限不可用时 */
    public OperationResult<DeploymentHandoff> reconcile(UUID id, TaskContext task) throws ApiException {
        var cursor = new DeploymentRestoreState(ledger, required(id, task), task);
        if (cursor.entry.publicValue().state() == DeploymentHandoffState.FINISHED) {
            try {
                var record = executions.find(task, id);
                if (record.isPresent()
                    && commit.finishProviderKnown(task, record.orElseThrow()).outcome() == TaskResult.UNKNOWN)
                    return unknown("DEPLOYMENT_RESTORE_FORMAL_CLEANUP_UNCONFIRMED");
                return success(cursor.entry.publicValue());
            } catch (Exception unavailable) {
                return unknown("DEPLOYMENT_RESTORE_FORMAL_CLEANUP_UNCONFIRMED");
            }
        }
        if (cursor.entry.publicValue().state() != DeploymentHandoffState.UNKNOWN)
            return success(cursor.entry.publicValue());
        try {
            var plan = new DeploymentRestorePlan(DeploymentRestorePlan.read(cursor.entry), releases);
            if (!context.linux().pendingTransfers(task).isEmpty())
                return cursor.uncertain("DEPLOYMENT_RESTORE_ORIGINAL_TRANSFER_PENDING");
            try (var session = context.linux().connect(plan.request.target(), task)) {
                if (new DeploymentManagedHelper(session, context.interaction(), plan.request.target(), task,
                    plan.request.reviewedTargetSha256()).probe().outcome() != TaskResult.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_HELPER_UNCONFIRMED");
                if (!sameTarget(context, cursor, plan, session))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_TARGET_CHANGED");
                var channel = channel(context, session, plan.request, task);
                var publisher = publication(context, session, plan.request, task);
                if (cursor.facts.path("components").findValues("discardAttempted").stream()
                    .anyMatch(value -> value.asBoolean())) {
                    for (var component : plan.proposal.graph().components()) {
                        var unit = cursor.unit(component.componentId());
                        if (!unit.path("discardAttempted").asBoolean())
                            continue;
                        if (!DeploymentHandoffChannel.receipt(
                            send(channel, "handoff-restore-discard-status",
                                identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)),
                            "RESTORE_DISCARDED=1\n"))
                            return cursor.uncertain("DEPLOYMENT_RESTORE_DISCARD_UNKNOWN");
                        unit.put("discarded", true);
                    }
                    cursor.save(DeploymentHandoffState.COMPENSATING);
                    return success(cursor.entry.publicValue());
                }
                boolean activationStarted = cursor.facts.path("components").findValues("snapshot").stream()
                    .anyMatch(value -> value.path("attempted").asBoolean());
                if (!activationStarted)
                    return reconcilePreparation(cursor, plan, channel);
                if (cursor.facts.path("compensationAttempted").asBoolean()) {
                    if (!recovery.compensationObserved(cursor, plan, channel, publisher))
                        return cursor.uncertain("DEPLOYMENT_RESTORE_COMPENSATION_REQUIRES_ORIGINAL_EVIDENCE");
                    cursor.facts.put("compensated", true);
                    cursor.save(DeploymentHandoffState.COMPENSATING);
                    return success(cursor.entry.publicValue());
                }
                for (var component : plan.proposal.graph().components()) {
                    String candidate = cursor.unit(component.componentId()).path("candidate").asText();
                    if (cursor.unit(component.componentId()).path("cleanupAttempted").asBoolean()
                        && DeploymentHandoffChannel.receipt(
                        send(channel, "handoff-restore-cleanup-status",
                            identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)),
                        "RESTORE_CLEANUP=1\n")) {
                        cursor.unit(component.componentId()).put("cleaned", true);
                        cursor.save(DeploymentHandoffState.UNKNOWN);
                        continue;
                    }
                    if (!stepObserved(channel, cursor, plan, component.componentId(), "formal", "restore-start-formal",
                        List.of(candidate, token(task), component.componentId())))
                        return cursor.uncertain("DEPLOYMENT_RESTORE_FORMAL_STEP_UNCONFIRMED");
                    for (var input : databases(plan, component.componentId()))
                        if (!stepObserved(channel, cursor, plan, component.componentId(),
                            "commit-" + databaseKey(input), "database-commit-candidate",
                            databaseArguments(cursor, component, input)))
                            return cursor.uncertain("DEPLOYMENT_RESTORE_DATABASE_COMMIT_UNCONFIRMED");
                }
                if (health(plan, channel, publisher).outcome() != TaskResult.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_HEALTH_UNCONFIRMED");
                if (commit
                    .commitVerified(task, id,
                        new DeploymentPublish(plan.proposal.graph(), plan.proposal.units()))
                    .outcome() != TaskResult.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_LOCAL_COMMIT_UNCONFIRMED");
                cursor.facts.put("verifiedActive", true);
                cursor.save(DeploymentHandoffState.ACTIVE);
                // Reconciliation never repeats an unknown cleanup. Known unattempted cleanup follows explicit activate.
                if (!cleanup.cleanup(cursor, plan, channel, true))
                    return success(cursor.entry.publicValue());
                cursor.save(DeploymentHandoffState.FINISHED);
            }
            if (commit.finishProviderKnown(task, executions.find(task, id).orElseThrow())
                .outcome() != TaskResult.SUCCEEDED)
                return unknown("DEPLOYMENT_RESTORE_RESOURCES_UNCONFIRMED");
            return success(cursor.entry.publicValue());
        } catch (Exception unavailable) {
            return retain(cursor, "DEPLOYMENT_RESTORE_RECONCILE_CALL_UNKNOWN");
        }
    }

    /** Observes every original preparation receipt without uploading or preparing again. / 观察全部原准备回执而不再次上传或准备。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param channel original sealed helper channel / 原封印helper频道
     * @return confirmed prepared phase or retained uncertainty / 已确证准备阶段或留存未知
     * @throws Exception if original receipt identity or persistence is unavailable / 原回执身份或持久化不可用时 */
    private OperationResult<DeploymentHandoff> reconcilePreparation(DeploymentRestoreState cursor,
                                                                    DeploymentRestorePlan plan, DeploymentHandoffChannel channel) throws Exception {

        var execution = executions.find(cursor.task, cursor.entry.publicValue().id());
        if (execution.isEmpty() || !execution.orElseThrow().phase().equals("EXECUTING"))
            return cursor.uncertain("DEPLOYMENT_RESTORE_ORIGINAL_EXECUTION_UNCONFIRMED");
        if (!targets(plan.proposal.graph(), channel).equals(before(cursor)))
            return cursor.uncertain("DEPLOYMENT_RESTORE_CURRENT_CHANGED");
        for (var component : plan.proposal.graph().components()) {
            if (!stepObserved(channel, cursor, plan, component.componentId(), "prepare", "restore-prepare",
                preparation(plan, cursor, component)))
                return cursor.uncertain("DEPLOYMENT_RESTORE_PREPARATION_INCOMPLETE");
            for (var input : databases(plan, component.componentId()))
                if (!stepObserved(channel, cursor, plan, component.componentId(), "prepare-" + databaseKey(input),
                    "database-restore-candidate", databaseArguments(cursor, component, input)))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_DATABASE_PREPARATION_INCOMPLETE");
        }
        cursor.save(DeploymentHandoffState.PREPARED);
        return success(cursor.entry.publicValue());
    }

    /** Loads only the original authorized restore intent. / 仅加载原已授权恢复意图。
     * @param id original handoff ID / 原交接ID
     * @param task authentic original task / 真实原任务
     * @return exact original restore row / 精确原恢复行
     * @throws ApiException if original ownership, identity or kind differs / 原归属、身份或种类不符时 */
    private DeploymentHandoffLedger.Entry required(UUID id, TaskContext task) throws ApiException {
        var value = ledger.find(id, task).orElseThrow(
            () -> new ApiException(problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_RESTORE_HANDOFF_ABSENT")));
        if (!value.request().path("kind").asText().equals("RESTORE"))
            throw new ApiException(problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_RESTORE_KIND_CHANGED"));
        return value;
    }

    /** Reloads after a call or close failure without replacing confirmed terminal facts. / 调用或关闭失败后重读而不替换已确证终态事实。
     * @param <T> payload type / 载荷类型
     * @param cursor original authorized journal / 原已授权账本
     * @param event original uncertainty event / 原未知事件
     * @return retained unknown result / 留存未知结果
     * @throws ApiException if original evidence cannot be loaded or saved / 原依据无法加载或保存时 */
    private <T> OperationResult<T> retain(DeploymentRestoreState cursor, String event) throws ApiException {
        cursor.entry = required(cursor.entry.publicValue().id(), cursor.task);
        if (cursor.entry.publicValue().state() != DeploymentHandoffState.FINISHED)
            return cursor.uncertain(event);
        return unknown(event);
    }
}
