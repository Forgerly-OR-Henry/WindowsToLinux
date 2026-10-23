package gold.debug.windowstolinux.mod.deployment.service.core.restore;

import static gold.debug.windowstolinux.mod.deployment.service.core.restore.DeploymentRestoreEvidence.*;
import static gold.debug.windowstolinux.mod.deployment.service.core.restore.DeploymentRestoreSteps.*;

import java.time.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentHandoffState;
import gold.debug.windowstolinux.mod.deployment.service.core.handoff.DeploymentHandoffChannel;

/** Verified original database and component material cleanup. / 已核实的原数据库及组件材料清理。 */
final class DeploymentRestoreCleanup {
    /** Provider-owned transfer observations for original cleanup. / 原清理使用的提供方所属传输观察。 */
    private final ModContext context;

    /** Binds original cleanup to the actual provider. / 将原清理绑定实际提供方。
     * @param context actual provider context / 实际提供方上下文 */
    DeploymentRestoreCleanup(ModContext context) {
        this.context = context;
    }

    /** Completes compensated cleanup only through exact original step receipts. / 仅经精确原步骤回执完成补偿后的清理。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param channel fixed original helper channel / 固定原helper频道
     * @param observeOnly whether effects are forbidden / 是否禁止副作用
     * @return whether original cleanup is confirmed / 是否原清理已确证
     * @throws Exception if original identity or cleanup evidence is unavailable / 原身份或清理依据不可用时 */
    boolean abortCleanup(DeploymentRestoreState cursor, DeploymentRestorePlan plan, DeploymentHandoffChannel channel,
                         boolean observeOnly) throws Exception {
        if (!context.linux().pendingTransfers(cursor.task).isEmpty())
            return false;
        for (var component : plan.proposal.graph().components()) {
            var unit = cursor.unit(component.componentId());
            if (unit.path("cleaned").asBoolean())
                continue;
            for (var input : databases(plan, component.componentId())) {
                String key = "discard-" + databaseKey(input);
                if (observeOnly || unit.path("steps").path(key).path("attempted").asBoolean()) {
                    if (!stepObserved(channel, cursor, plan, component.componentId(), key, "database-discard-original",
                        databaseArguments(cursor, component, input)))
                        return false;
                } else if (!step(channel, cursor, plan, component.componentId(), key, "database-discard-original",
                    databaseArguments(cursor, component, input), "DATABASE_DISCARDED=1\n"))
                    return false;
            }
            String candidate = unit.path("candidate").asText();
            if (observeOnly || unit.path("steps").path("abort").path("attempted").asBoolean()) {
                if (!stepObserved(channel, cursor, plan, component.componentId(), "abort", "restore-abort",
                    List.of(candidate, token(cursor.task), component.componentId())))
                    return false;
            } else if (!step(channel, cursor, plan, component.componentId(), "abort", "restore-abort",
                List.of(candidate, token(cursor.task), component.componentId()), "RESTORE_ABORTED=1\n"))
                return false;
            if (observeOnly || unit.path("cleanupAttempted").asBoolean()) {
                if (!DeploymentHandoffChannel.receipt(send(channel, "handoff-restore-cleanup-status",
                    identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)), "RESTORE_CLEANUP=1\n"))
                    return false;
            } else {
                unit.put("cleanupAttempted", true);
                cursor.save(DeploymentHandoffState.COMPENSATING);
                if (!DeploymentHandoffChannel.receipt(send(channel, "handoff-restore-cleanup",
                    identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)), "RESTORE_CLEANUP=1\n"))
                    return false;
            }
            unit.put("cleaned", true);
            cursor.save(DeploymentHandoffState.COMPENSATING);
        }
        return true;
    }

    /** Releases only original database rollback and candidate materials after verified activation. / 激活确证后仅释放原数据库回滚与候选材料。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param channel fixed original helper channel / 固定原helper频道
     * @param observeOnly whether effects are forbidden / 是否禁止副作用
     * @return whether all original cleanup is confirmed / 是否全部原清理已确证
     * @throws Exception if original identity or cleanup evidence is unavailable / 原身份或清理依据不可用时 */
    boolean cleanup(DeploymentRestoreState cursor, DeploymentRestorePlan plan, DeploymentHandoffChannel channel,
                    boolean observeOnly) throws Exception {
        if (!context.linux().pendingTransfers(cursor.task).isEmpty())
            return false;
        for (var component : plan.proposal.graph().components()) {
            var unit = cursor.unit(component.componentId());
            if (unit.path("cleaned").asBoolean()) {
                if (!DeploymentHandoffChannel.receipt(send(channel, "handoff-restore-cleanup-status",
                    identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)), "RESTORE_CLEANUP=1\n"))
                    return false;
                continue;
            }
            for (var input : databases(plan, component.componentId())) {
                String key = "finish-" + databaseKey(input);
                if (observeOnly || unit.path("steps").path(key).path("attempted").asBoolean()) {
                    if (!stepObserved(channel, cursor, plan, component.componentId(), key, "database-finish-candidate",
                        databaseArguments(cursor, component, input)))
                        return false;
                } else if (!step(channel, cursor, plan, component.componentId(), key, "database-finish-candidate",
                    databaseArguments(cursor, component, input), "DATABASE_FINISHED=1\n"))
                    return false;
            }
            String candidate = unit.path("candidate").asText();
            if (observeOnly || unit.path("steps").path("finish").path("attempted").asBoolean()) {
                if (!stepObserved(channel, cursor, plan, component.componentId(), "finish", "restore-finish",
                    List.of(candidate, token(cursor.task), component.componentId())))
                    return false;
            } else if (!step(channel, cursor, plan, component.componentId(), "finish", "restore-finish",
                List.of(candidate, token(cursor.task), component.componentId()), "RESTORE_FINISHED=1\n"))
                return false;
            if (observeOnly || unit.path("cleanupAttempted").asBoolean()) {
                if (!DeploymentHandoffChannel.receipt(send(channel, "handoff-restore-cleanup-status",
                    identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)), "RESTORE_CLEANUP=1\n"))
                    return false;
            } else {
                unit.put("cleanupAttempted", true);
                cursor.save(DeploymentHandoffState.ACTIVE);
                if (!DeploymentHandoffChannel.receipt(send(channel, "handoff-restore-cleanup",
                    identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)), "RESTORE_CLEANUP=1\n"))
                    return false;
                unit.put("cleaned", true);
                cursor.save(DeploymentHandoffState.ACTIVE);
            }
        }
        return true;
    }

    /** Discards only attempted unactivated candidates and never replays an uncertain discard. / 仅丢弃曾尝试的未激活候选，绝不重放未知丢弃。
     * @param cursor original authorized journal / 原已授权账本
     * @param plan complete immutable request / 完整不可变请求
     * @param channel fixed original helper channel / 固定原helper频道
     * @return whether all original candidates have confirmed discard / 是否全部原候选已确证丢弃
     * @throws Exception if original identity or discard evidence is unavailable / 原身份或丢弃依据不可用时 */
    boolean discardCandidates(DeploymentRestoreState cursor, DeploymentRestorePlan plan,
                              DeploymentHandoffChannel channel) throws Exception {
        for (var component : plan.proposal.graph().components()) {
            var unit = cursor.unit(component.componentId());
            if (!unit.path("createAttempted").asBoolean())
                continue;
            if (unit.path("discardAttempted").asBoolean()) {
                if (!DeploymentHandoffChannel
                    .receipt(
                        send(channel, "handoff-restore-discard-status",
                            identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)),
                        "RESTORE_DISCARDED=1\n"))
                    return false;
                unit.put("discarded", true);
                cursor.save(DeploymentHandoffState.COMPENSATING);
                continue;
            }
            // A database preparation attempt needs its exact read-only receipt before discard.
            for (var input : databases(plan, component.componentId())) {
                if (unit.path("steps").path("prepare-" + databaseKey(input)).path("attempted").asBoolean())
                    if (!stepObserved(channel, cursor, plan, component.componentId(), "prepare-" + databaseKey(input),
                        "database-restore-candidate", databaseArguments(cursor, component, input)))
                        return false;
                if (unit.path("steps").path("create-" + databaseKey(input)).path("attempted").asBoolean()) {
                    if (!step(channel, cursor, plan, component.componentId(), "discard-" + databaseKey(input),
                        "database-discard-original", databaseArguments(cursor, component, input),
                        "DATABASE_DISCARDED=1\n"))
                        return false;
                }
            }
            unit.put("discardAttempted", true);
            cursor.save(DeploymentHandoffState.COMPENSATING);
            if (!DeploymentHandoffChannel.receipt(send(channel, "handoff-restore-discard",
                identity(plan, cursor, component.componentId()), Duration.ofMinutes(2)), "RESTORE_DISCARDED=1\n"))
                return false;
            unit.put("discarded", true);
            cursor.save(DeploymentHandoffState.COMPENSATING);
        }
        return true;
    }

}
