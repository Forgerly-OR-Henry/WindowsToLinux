package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Coordinates reconnect, rollback, candidate cleanup, and recovery state. / 协调重连、回滚、候选清理与恢复状态。
 */
final class MultiComponentRecoveryCoordinator {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private MultiComponentRecoveryCoordinator() {
    }

    /**
     * Recovers component transactions in dependency-safe order, preserving all failures and distinguishing verified rollback from manual recovery.
     * <p>按依赖安全顺序恢复组件事务，保留全部失败，并区分已验证回滚及人工恢复。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param contexts contexts / 上下文集合
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @param applicationEvents application events / 应用事件集合
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     */
    static MultiComponentDeploymentResult recover(
            MultiComponentDeploymentPlan plan,
            Map<String, MultiComponentTransactionContext> contexts,
            DeploymentLinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyEvaluator verifier,
            List<DeploymentEvent> applicationEvents) {
        if (contexts.values().stream().noneMatch(context -> context.snapshot != null)) {
            try (DeploymentRemoteSession session = gateway.connect(endpoint, credential.duplicate(), verifier)) {
                if (!cleanupAll(contexts, session)) {
                    requireManualCleanup(contexts);
                    return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
                }
            } catch (LinuxOperationException failure) {
                applicationEvents.add(DeploymentEvent.failed(
                        DeploymentTraceEvent.CANDIDATE_CLEANUP_RECONNECT, failure.failure()));
                requireManualCleanup(contexts);
                return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
            }
            markDiscarded(contexts, null);
            return result(DeploymentStatus.PRECONDITION_REJECTED, applicationEvents, contexts);
        }
        boolean mutationAttempted = contexts.values().stream()
                .anyMatch(context -> context.stopAttempted || context.publishAttempted);
        if (!mutationAttempted) {
            try (DeploymentRemoteSession recovery = gateway.connect(endpoint, credential.duplicate(), verifier)) {
                if (!cleanupAll(contexts, recovery)) {
                    requireManualCleanup(contexts);
                    return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
                }
                markDiscarded(contexts, null);
                return result(DeploymentStatus.PRECONDITION_REJECTED, applicationEvents, contexts);
            } catch (LinuxOperationException failure) {
                applicationEvents.add(DeploymentEvent.failed(
                        DeploymentTraceEvent.CANDIDATE_CLEANUP_RECONNECT, failure.failure()));
                requireManualCleanup(contexts);
                return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
            }
        }
        try (DeploymentRemoteSession recovery = gateway.connect(endpoint, credential.duplicate(), verifier)) {
            applicationEvents.add(DeploymentEvent.result(DeploymentTraceEvent.APPLICATION_RECOVERY_RECONNECT, true,
                    "SSH host was reverified before whole-application recovery"));
            boolean manual = false;
            boolean hadPrevious = false;
            for (String id : plan.rollbackOrder()) {
                MultiComponentTransactionContext context = contexts.get(id);
                if (context.snapshot == null || (!context.publishAttempted && !context.stopAttempted)) continue;
                hadPrevious |= context.snapshot.hasPreviousRelease();
                var rollback = recovery.rollbackDeployment(context.component.application(), context.snapshot, context.build,
                        context.releaseIdentity, context.component.request().runtime(), DeploymentInputMapper.manifest(context.inputs));
                context.event(DeploymentTraceEvent.ROLLBACK, rollback.succeeded(), rollback.evidence());
                if (!rollback.succeeded()) {
                    context.state = ComponentTransactionState.MANUAL_RECOVERY_REQUIRED;
                    manual = true;
                    continue;
                }
                if (context.snapshot.hasPreviousRelease()) {
                    LifecycleObservation restored = recovery.observeDeployment(context.component.application(),
                            context.component.request().runtime());
                    boolean expected = restored.ownershipVerified() && (context.snapshot.previousWasRunning()
                            ? restored.runtimeState() == RuntimeState.RUNNING : restored.runtimeState() == RuntimeState.STOPPED || restored.runtimeState() == RuntimeState.INSTALLED);
                    context.observation = restored;
                    context.event(DeploymentTraceEvent.ROLLBACK_OBSERVATION, expected, restored.evidence());
                    context.state = expected ? ComponentTransactionState.RESTORED
                            : ComponentTransactionState.MANUAL_RECOVERY_REQUIRED;
                    manual |= !expected;
                } else {
                    context.state = ComponentTransactionState.FIRST_DEPLOYMENT_REVERTED;
                }
            }
            manual |= !cleanupAll(contexts, recovery);
            if (manual) return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
            for (var context : contexts.values()) if (context.publishAttempted) recovery.backupArtifacts().endMaintenance(
                    context.component.application(), "deployment-" + context.releaseIdentity);
            contexts.values().stream().filter(context -> context.state == ComponentTransactionState.PRECONDITION_REJECTED
                    && context.workspace != null).forEach(context -> context.state = ComponentTransactionState.CANDIDATE_DISCARDED);
            return result(hadPrevious ? DeploymentStatus.FAILED_ROLLED_BACK : DeploymentStatus.FAILED_FIRST_DEPLOYMENT,
                    applicationEvents, contexts);
        } catch (LinuxOperationException failure) {
            applicationEvents.add(DeploymentEvent.failed(DeploymentTraceEvent.APPLICATION_ROLLBACK, failure.failure()));
            contexts.values().stream().filter(context -> context.snapshot != null
                    && (context.publishAttempted || context.stopAttempted))
                    .forEach(context -> context.state = ComponentTransactionState.MANUAL_RECOVERY_REQUIRED);
            return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
        }
    }

    /**
     * Cleans up all.
     * <p>清理全部。
     *
     * @param contexts contexts / 上下文集合
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @return true when cleans up all, false otherwise / 清理全部时为 true，否则为 false
     */
    static boolean cleanupAll(Map<String, MultiComponentTransactionContext> contexts,
                              DeploymentRemoteSession session) {
        boolean succeeded = true;
        for (MultiComponentTransactionContext context : contexts.values()) {
            if (context.workspace == null) continue;
            try {
                var cleanup = session.cleanupCandidate(context.workspace);
                context.event(DeploymentTraceEvent.CANDIDATE_CLEANUP, cleanup.succeeded(), cleanup.evidence());
                succeeded &= cleanup.succeeded();
            } catch (LinuxOperationException failure) {
                context.failure(DeploymentTraceEvent.CANDIDATE_CLEANUP, failure.failure());
                succeeded = false;
            }
        }
        return succeeded;
    }

    /**
     * Marks nonfailing rejected component workspaces as discarded after candidate cleanup.
     * <p>候选清理后，将未失败且已拒绝的组件工作区标记为已丢弃。
     *
     * @param contexts contexts / 上下文集合
     * @param failedId failed id / 失败标识
     */
    static void markDiscarded(Map<String, MultiComponentTransactionContext> contexts, String failedId) {
        contexts.forEach((id, context) -> {
            if (!id.equals(failedId) && context.workspace != null
                    && context.state == ComponentTransactionState.PRECONDITION_REJECTED) {
                context.state = ComponentTransactionState.CANDIDATE_DISCARDED;
            }
        });
    }

    /**
     * Marks affected component contexts when candidate cleanup evidence records failure.
     * <p>候选清理证据记录失败时标记受影响组件上下文。
     *
     * @param contexts contexts / 上下文集合
     */
    static void markCleanupFailure(Map<String, MultiComponentTransactionContext> contexts) {
        contexts.values().stream()
                .filter(context -> context.workspace != null
                        && context.events.stream().anyMatch(event -> event.step() == DeploymentTraceEvent.CANDIDATE_CLEANUP
                        && !event.succeeded()))
                .forEach(context -> context.state = ComponentTransactionState.MANUAL_RECOVERY_REQUIRED);
    }

    /**
     * Requires manual cleanup.
     * <p>要求人工清理。
     *
     * @param contexts contexts / 上下文集合
     */
    private static void requireManualCleanup(Map<String, MultiComponentTransactionContext> contexts) {
        contexts.values().stream().filter(context -> context.workspace != null)
                .forEach(context -> context.state = ComponentTransactionState.MANUAL_RECOVERY_REQUIRED);
    }

    /**
     * Builds the application result from the final status, shared events and per-component transaction contexts.
     * <p>根据最终状态、共享事件及各组件事务上下文构建应用结果。
     *
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param applicationEvents application events / 应用事件集合
     * @param contexts contexts / 上下文集合
     * @return the application result from the final status, shared events and per-component transaction contexts / 根据最终状态、共享事件及各组件事务上下文构建应用结果
     */
    private static MultiComponentDeploymentResult result(DeploymentStatus status,
                                                         List<DeploymentEvent> applicationEvents,
                                                         Map<String, MultiComponentTransactionContext> contexts) {
        return MultiComponentTransactionContext.result(status, applicationEvents, contexts, Optional.empty());
    }

}
