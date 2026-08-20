package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.ComponentTransactionState;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
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

/** Coordinates reconnect, rollback, candidate cleanup, and recovery state. / 协调重连、回滚、候选清理与恢复状态。 */
final class MultiComponentRecoveryCoordinator {
    private MultiComponentRecoveryCoordinator() {
    }

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
                applicationEvents.add(new DeploymentEvent("candidate-cleanup-reconnect", false, safeMessage(failure)));
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
                applicationEvents.add(new DeploymentEvent("candidate-cleanup-reconnect", false, safeMessage(failure)));
                requireManualCleanup(contexts);
                return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
            }
        }
        try (DeploymentRemoteSession recovery = gateway.connect(endpoint, credential.duplicate(), verifier)) {
            applicationEvents.add(new DeploymentEvent("application-recovery-reconnect", true,
                    "SSH host was reverified before whole-application recovery"));
            boolean manual = false;
            boolean hadPrevious = false;
            for (String id : plan.rollbackOrder()) {
                MultiComponentTransactionContext context = contexts.get(id);
                if (context.snapshot == null || (!context.publishAttempted && !context.stopAttempted)) continue;
                hadPrevious |= context.snapshot.hasPreviousRelease();
                var rollback = recovery.rollbackDeployment(context.component.application(), context.snapshot, context.build,
                        context.releaseIdentity, context.component.request().runtime(), context.inputs);
                context.event("rollback", rollback.succeeded(), rollback.evidence());
                if (!rollback.succeeded()) {
                    context.state = ComponentTransactionState.MANUAL_RECOVERY_REQUIRED;
                    manual = true;
                    continue;
                }
                if (context.snapshot.hasPreviousRelease()) {
                    LifecycleObservation restored = recovery.observeDeployment(context.component.application(),
                            context.component.request().runtime());
                    boolean expected = restored.ownershipVerified() && (context.snapshot.previousWasRunning()
                            ? restored.runtimeState() == RuntimeState.RUNNING : restored.runtimeState() == RuntimeState.STOPPED);
                    context.observation = restored;
                    context.event("rollback-observation", expected, restored.evidence());
                    context.state = expected ? ComponentTransactionState.RESTORED
                            : ComponentTransactionState.MANUAL_RECOVERY_REQUIRED;
                    manual |= !expected;
                } else {
                    context.state = ComponentTransactionState.FIRST_DEPLOYMENT_REVERTED;
                }
            }
            manual |= !cleanupAll(contexts, recovery);
            if (manual) return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
            contexts.values().stream().filter(context -> context.state == ComponentTransactionState.PRECONDITION_REJECTED
                    && context.workspace != null).forEach(context -> context.state = ComponentTransactionState.CANDIDATE_DISCARDED);
            return result(hadPrevious ? DeploymentStatus.FAILED_ROLLED_BACK : DeploymentStatus.FAILED_FIRST_DEPLOYMENT,
                    applicationEvents, contexts);
        } catch (LinuxOperationException failure) {
            applicationEvents.add(new DeploymentEvent("application-rollback", false, safeMessage(failure)));
            contexts.values().stream().filter(context -> context.snapshot != null
                    && (context.publishAttempted || context.stopAttempted))
                    .forEach(context -> context.state = ComponentTransactionState.MANUAL_RECOVERY_REQUIRED);
            return result(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, applicationEvents, contexts);
        }
    }

    static boolean cleanupAll(Map<String, MultiComponentTransactionContext> contexts,
                              DeploymentRemoteSession session) {
        boolean succeeded = true;
        for (MultiComponentTransactionContext context : contexts.values()) {
            if (context.workspace == null) continue;
            try {
                var cleanup = session.cleanupCandidate(context.workspace);
                context.event("candidate-cleanup", cleanup.succeeded(), cleanup.evidence());
                succeeded &= cleanup.succeeded();
            } catch (LinuxOperationException failure) {
                context.event("candidate-cleanup", false, safeMessage(failure));
                succeeded = false;
            }
        }
        return succeeded;
    }

    static void markDiscarded(Map<String, MultiComponentTransactionContext> contexts, String failedId) {
        contexts.forEach((id, context) -> {
            if (!id.equals(failedId) && context.workspace != null
                    && context.state == ComponentTransactionState.PRECONDITION_REJECTED) {
                context.state = ComponentTransactionState.CANDIDATE_DISCARDED;
            }
        });
    }

    static void markCleanupFailure(Map<String, MultiComponentTransactionContext> contexts) {
        contexts.values().stream()
                .filter(context -> context.workspace != null
                        && context.events.stream().anyMatch(event -> event.step().equals("candidate-cleanup")
                        && !event.succeeded()))
                .forEach(context -> context.state = ComponentTransactionState.MANUAL_RECOVERY_REQUIRED);
    }

    private static void requireManualCleanup(Map<String, MultiComponentTransactionContext> contexts) {
        contexts.values().stream().filter(context -> context.workspace != null)
                .forEach(context -> context.state = ComponentTransactionState.MANUAL_RECOVERY_REQUIRED);
    }

    private static MultiComponentDeploymentResult result(DeploymentStatus status,
                                                         List<DeploymentEvent> applicationEvents,
                                                         Map<String, MultiComponentTransactionContext> contexts) {
        return MultiComponentTransactionContext.result(status, applicationEvents, contexts, Optional.empty());
    }

    private static String safeMessage(Exception failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? "Controlled multi-component operation failed" : failure.getMessage();
    }
}
