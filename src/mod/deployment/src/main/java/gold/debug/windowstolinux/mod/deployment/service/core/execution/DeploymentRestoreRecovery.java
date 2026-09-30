package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import static gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentRestoreEvidence.*;
import static gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentRestoreSteps.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.linux.*;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublicationCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Original activation compensation and verified post-commit recovery. / 原激活补偿与已核实提交后恢复。 */
final class DeploymentRestoreRecovery {
    /** Actual provider target and approval authority. / 实际提供方目标与审批权限。 */
    private final ModContext context;

    /** Existing canonical archive and release validator. / 既有规范归档与发布校验器。 */
    private final DeploymentReleaseRepository releases;

    /** Original formal execution records. / 原正式执行记录。 */
    private final DeploymentExecutionRepository executions;

    /** Shared original transaction and reference verification. / 共用原事务与引用核验。 */
    private final DeploymentOriginalPublicationCommit commit;

    /** Exact original cleanup and receipt observations. / 精确原清理与回执观察。 */
    private final DeploymentRestoreCleanup cleanup;

    /** Current target secret and configuration authority checks. / 当前目标秘密与配置权限核对。 */
    private final DeploymentRestorePreparation inputs;
    /** Reuses the original publication, authority and cleanup boundaries. / 复用原发布、权限与清理边界。
     * @param context actual provider context / 实际提供方上下文
     * @param releases canonical release validator / 规范发布校验器
     * @param executions original formal records / 原正式记录
     * @param commit shared transaction boundary / 共用事务边界
     * @param cleanup original cleanup phases / 原清理阶段
     * @param inputs original input authority checks / 原输入权限核对 */
    DeploymentRestoreRecovery(ModContext context, DeploymentReleaseRepository releases,
            DeploymentExecutionRepository executions, DeploymentOriginalPublicationCommit commit,
            DeploymentRestoreCleanup cleanup, DeploymentRestorePreparation inputs) {
        this.context = context;
        this.releases = releases;
        this.executions = executions;
        this.commit = commit;
        this.cleanup = cleanup;
        this.inputs = inputs;
    }

    /** Stops new writers, restores original databases and runtime, then confirms known failure. / 停止新写入者、恢复原数据库与运行时，再确证已知失败。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param channel original sealed helper channel / 原封印helper频道
     * @param publisher original runtime observations / 原运行时观察
     * @param original known health failure / 已知健康失败
     * @return compensated original failure or retained uncertainty / 已补偿原失败或留存未知
     * @throws Exception if compensation or original evidence is unavailable / 补偿或原依据不可用时 */
    OperationResult<DeploymentActivation> compensate(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
            DeploymentHandoffChannel channel, DeploymentPublicationChannel publisher, Problem original)
            throws Exception {
        cursor.facts.put("compensationAttempted", true);
        cursor.save(DeploymentHandoffState.COMPENSATING);
        cursor.facts.put("failureEvent", original.eventCode()).put("failureCode", original.code().name());
        cursor.save(DeploymentHandoffState.COMPENSATING);
        // Stop every new writer before recovering any database.
        for (var component : order(plan.proposal.graph()).reversed()) {
            if (!step(channel, cursor, plan, component.componentId(), "recovery-stop", "restore-quiesce-recovery",
                    List.of(cursor.unit(component.componentId()).path("candidate").asText(), token(cursor.task),
                            component.componentId()),
                    "QUIESCED=1\n"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_COMPENSATION_STOP_UNKNOWN");
        }
        for (var component : order(plan.proposal.graph()).reversed())
            for (var input : databases(plan, component.componentId()))
                if (!step(channel, cursor, plan, component.componentId(), "recover-" + databaseKey(input),
                        "database-recover-candidate", databaseArguments(cursor, component, input),
                        "CANDIDATE_ID=" + databaseCandidate(cursor, component, input)
                                + "\nRECOVERED=1\nPREVIOUS_VERIFIED=1\nCANDIDATE_REMOVED=1\n"))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_DATABASE_RECOVERY_UNKNOWN");
        for (var component : order(plan.proposal.graph())) {
            String candidate = cursor.unit(component.componentId()).path("candidate").asText();
            String previous = cursor.unit(component.componentId()).path("beforeDigest").asText().equals("-")
                    ? "0"
                    : "1";
            if (!step(channel, cursor, plan, component.componentId(), "recover", "restore-recover",
                    List.of(candidate, token(cursor.task), component.componentId()),
                    "ROLLED_BACK=1\nRECOVERED=1\nPREVIOUS=" + previous + "\nAPP=" + component.unit().id() + "\n"))
                return cursor.uncertain("DEPLOYMENT_RESTORE_COMPONENT_RECOVERY_UNKNOWN");
        }
        if (!targets(plan.proposal.graph(), channel).equals(before(cursor)))
            return cursor.uncertain("DEPLOYMENT_RESTORE_OLD_CURRENT_UNCONFIRMED");
        for (var component : plan.proposal.graph().components())
            if (!cursor.unit(component.componentId()).path("beforeDigest").asText().equals("-")) {
                var observed = publisher.observeRestored(component.unit());
                if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty()
                        || !observed.value().orElseThrow().state().name()
                                .equals(cursor.unit(component.componentId()).path("beforeState").asText()))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_OLD_STATE_UNCONFIRMED");
            }
        var completed = commit.completeUncommittedVerified(cursor.task, cursor.entry.publicValue().id(), Outcome.FAILED,
                original);
        if (completed.outcome() != Outcome.FAILED)
            return cursor.uncertain("DEPLOYMENT_RESTORE_COMPENSATED_FORMAL_UNKNOWN");
        cursor.facts.put("compensated", true);
        cursor.save(DeploymentHandoffState.COMPENSATING);
        if (!cleanup.abortCleanup(cursor, plan, channel, false))
            return cursor.uncertain("DEPLOYMENT_RESTORE_COMPENSATED_CLEANUP_REQUIRED");
        cursor.facts.put("terminalOutcome", "FAILED");
        cursor.save(DeploymentHandoffState.FINISHED);
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(original));
    }

    /** Rechecks committed activation and completes only its original cleanup. / 复核已提交激活并仅完成其原清理。
     * @param cursor original authorized journal / 原已授权账本
     * @param task authentic original task / 真实原任务
     * @return verified activation or retained uncertainty / 已确证激活或留存未知
     * @throws ApiException if original evidence cannot be retained / 无法保留原依据时 */
    OperationResult<DeploymentActivation> finishActive(DeploymentRestoreState cursor, TaskContext task)
            throws ApiException {
        try {
            var plan = new DeploymentRestorePlan(DeploymentRestorePlan.read(cursor.entry), releases);
            inputs.authorize(plan, task);
            try (var session = context.linux().connect(plan.request.target(), task)) {
                if (new DeploymentManagedHelper(session, context.interaction(), plan.request.target(), task,
                        plan.request.reviewedTargetSha256()).probe().outcome() != Outcome.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_HELPER_UNCONFIRMED");
                if (!sameTarget(context, cursor, plan, session))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_TARGET_CHANGED");
                var channel = channel(context, session, plan.request, task);
                var publisher = publication(context, session, plan.request, task);
                if (health(plan, channel, publisher).outcome() != Outcome.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_ACTIVE_HEALTH_UNCONFIRMED");
                if (commit
                        .commitVerified(task, cursor.entry.publicValue().id(),
                                new DeploymentPublication(plan.proposal.graph(), plan.proposal.units()))
                        .outcome() != Outcome.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_LOCAL_COMMIT_UNCONFIRMED");
                if (!cleanup.cleanup(cursor, plan, channel, false))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_CLEANUP_UNKNOWN");
                cursor.save(DeploymentHandoffState.FINISHED);
            }
            if (commit.finishProviderKnown(task, executions.find(task, cursor.entry.publicValue().id()).orElseThrow())
                    .outcome() != Outcome.SUCCEEDED)
                return unknown("DEPLOYMENT_RESTORE_RESOURCES_UNCONFIRMED");
            return success(activation(cursor, true));
        } catch (Exception unavailable) {
            return cursor.retain("DEPLOYMENT_RESTORE_FINISH_CALL_UNKNOWN");
        }
    }

    /** Confirms original compensation before finishing the known failed execution. / 完成已知失败执行前确证原补偿。
     * @param cursor original authorized journal / 原已授权账本
     * @param task authentic original task / 真实原任务
     * @return confirmed cleanup or retained uncertainty / 已确证清理或留存未知
     * @throws ApiException if original evidence cannot be retained / 无法保留原依据时 */
    OperationResult<DeploymentHandoff> finishCompensated(DeploymentRestoreState cursor, TaskContext task)
            throws ApiException {
        try {
            var plan = new DeploymentRestorePlan(DeploymentRestorePlan.read(cursor.entry), releases);
            try (var session = context.linux().connect(plan.request.target(), task)) {
                if (new DeploymentManagedHelper(session, context.interaction(), plan.request.target(), task,
                        plan.request.reviewedTargetSha256()).probe().outcome() != Outcome.SUCCEEDED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_HELPER_UNCONFIRMED");
                if (!sameTarget(context, cursor, plan, session))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_TARGET_CHANGED");
                var channel = channel(context, session, plan.request, task);
                if (!compensationObserved(cursor, plan, channel, publication(context, session, plan.request, task)))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_COMPENSATION_UNCONFIRMED");
                var original = problem(ProblemCode.valueOf(cursor.facts.path("failureCode").asText()),
                        cursor.facts.path("failureEvent").asText());
                if (commit.completeUncommittedVerified(task, cursor.entry.publicValue().id(), Outcome.FAILED, original)
                        .outcome() != Outcome.FAILED)
                    return cursor.uncertain("DEPLOYMENT_RESTORE_COMPENSATED_FORMAL_UNKNOWN");
                if (!cleanup.abortCleanup(cursor, plan, channel, false))
                    return cursor.uncertain("DEPLOYMENT_RESTORE_COMPENSATED_CLEANUP_UNKNOWN");
                cursor.facts.put("terminalOutcome", "FAILED");
                cursor.save(DeploymentHandoffState.FINISHED);
            }
            if (commit.finishProviderKnown(task, executions.find(task, cursor.entry.publicValue().id()).orElseThrow())
                    .outcome() != Outcome.FAILED)
                return unknown("DEPLOYMENT_RESTORE_RESOURCES_UNCONFIRMED");
            return success(cursor.entry.publicValue());
        } catch (Exception unknown) {
            return cursor.retain("DEPLOYMENT_RESTORE_COMPENSATED_FINISH_CALL_UNKNOWN");
        }
    }

    /** Observes original rollback receipts, current digests and before-states without replay. / 不重放地观察原回滚回执、当前摘要与变更前状态。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param channel original sealed helper channel / 原封印helper频道
     * @param publisher original runtime observations / 原运行时观察
     * @return whether all original compensation is confirmed / 是否全部原补偿已确证
     * @throws Exception if original observations are unavailable / 原观察不可用时 */
    boolean compensationObserved(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
            DeploymentHandoffChannel channel, DeploymentPublicationChannel publisher) throws Exception {
        if (!targets(plan.proposal.graph(), channel).equals(before(cursor)))
            return false;
        for (var component : plan.proposal.graph().components()) {
            var unit = cursor.unit(component.componentId());
            if (unit.path("cleanupAttempted").asBoolean()
                    && DeploymentHandoffChannel.receipt(
                            send(channel, "handoff-restore-cleanup-status",
                                    identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)),
                            "RESTORE_CLEANUP=1\n")) {
                unit.put("cleaned", true);
            } else {
                String candidate = unit.path("candidate").asText();
                if (!stepObserved(channel, cursor, plan, component.componentId(), "recover", "restore-recover",
                        List.of(candidate, token(cursor.task), component.componentId())))
                    return false;
                for (var input : databases(plan, component.componentId()))
                    if (!stepObserved(channel, cursor, plan, component.componentId(), "recover-" + databaseKey(input),
                            "database-recover-candidate", databaseArguments(cursor, component, input)))
                        return false;
            }
            if (!unit.path("beforeDigest").asText().equals("-")) {
                var observed = publisher.observeRestored(component.unit());
                if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty()
                        || !observed.value().orElseThrow().state().name().equals(unit.path("beforeState").asText()))
                    return false;
            }
        }
        return true;
    }

}
