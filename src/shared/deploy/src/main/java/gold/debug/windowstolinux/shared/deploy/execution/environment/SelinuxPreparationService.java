package gold.debug.windowstolinux.shared.deploy.execution.environment;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyObservation;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentApprovalException;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentApprovalFailureType;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/** Orchestrates approved system changes, bounded reboot recovery and fresh authenticated verification. / 编排已批准系统变更、有界重启恢复和重新认证验证。 */
final class SelinuxPreparationService {
    private final Duration timeout;
    private final Duration interval;

    SelinuxPreparationService() {
        this(Duration.ofMinutes(15), Duration.ofSeconds(5));
    }

    SelinuxPreparationService(Duration timeout, Duration interval) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (timeout.isNegative() || timeout.isZero() || interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("System preparation waits must be positive");
        }
    }

    HostKeyEvaluator prepare(LinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
                             HostKeyEvaluator verifier, Predicate<SelinuxPreparationPlan> confirmation)
            throws LinuxOperationException {
        HostKeyEvaluator pinned = pinned(verifier);
        boolean reboot;
        try (var session = gateway.connect(endpoint, credential.duplicate(), pinned)) {
            var operation = session.selinuxPreparation();
            var inspected = operation.inspect();
            if (inspected.isEmpty()) return pinned;
            var plan = inspected.orElseThrow();
            if (plan.state() == SelinuxPreparationState.COMPLETE) return pinned;
            if (!endpoint.serverId().equals(plan.serverId()) || !confirmation.test(plan)) {
                throw new DeploymentApprovalException(DeploymentApprovalFailureType.CONFIRMATION_REQUIRED,
                        "Explicit confirmation is required for SELinux changes and server reboot");
            }
            if (Thread.currentThread().isInterrupted()) throw incomplete("System preparation cancelled before mutation");
            reboot = plan.state() == SelinuxPreparationState.UNPREPARED
                    || plan.state() == SelinuxPreparationState.REBOOT_PENDING;
            if (reboot) operation.prepareReboot(plan);
            else if (plan.state() == SelinuxPreparationState.READY_TO_ENFORCE) operation.enableEnforcement(plan);
            else if (plan.state() != SelinuxPreparationState.ENFORCEMENT_PENDING) {
                throw incomplete("Unexpected SELinux preparation checkpoint");
            }
        }
        if (reboot) waitAndEnforce(gateway, endpoint, credential, pinned);
        // A new authenticated connection is required after enforcing mode is enabled. / 启用强制模式后必须通过新连接重新认证。
        try (var session = gateway.connect(endpoint, credential.duplicate(), pinned)) {
            var operation = session.selinuxPreparation();
            var verified = operation.inspect().orElseThrow(() -> incomplete("Target distribution changed"));
            if (verified.state() != SelinuxPreparationState.ENFORCEMENT_PENDING) {
                throw incomplete("Enforcing verification did not complete; safety rollback remains available");
            }
            operation.commitEnforcement(verified);
            if (operation.inspect().orElseThrow(() -> incomplete("Target distribution changed")).state() != SelinuxPreparationState.COMPLETE) {
                throw incomplete("SELinux preparation did not reach its verified final state");
            }
        }
        return pinned;
    }

    private void waitAndEnforce(LinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
                               HostKeyEvaluator verifier) throws LinuxOperationException {
        long deadline = System.nanoTime() + timeout.toNanos();
        LinuxOperationException last = null;
        while (System.nanoTime() < deadline) {
            pause();
            try (var session = gateway.connect(endpoint, credential.duplicate(), verifier)) {
                var operation = session.selinuxPreparation();
                var plan = operation.inspect().orElseThrow(() -> incomplete("Target distribution changed"));
                if (plan.state() == SelinuxPreparationState.REBOOT_PENDING) continue;
                if (plan.state() == SelinuxPreparationState.ENFORCEMENT_PENDING) return;
                if (plan.state() != SelinuxPreparationState.READY_TO_ENFORCE) {
                    throw incomplete("Reboot returned an unexpected SELinux checkpoint");
                }
                operation.enableEnforcement(plan);
                return;
            } catch (LinuxOperationException failure) {
                String code = failure.failure().code();
                if (!code.equals(LinuxOperationFailureType.CONNECTION_FAILED.code())
                        && !code.equals(LinuxOperationFailureType.SSH_COMMAND_FAILED.code())) throw failure;
                last = failure;
            }
        }
        throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                "Timed out waiting for the approved server reboot; inspect target preparation state before resuming", last);
    }

    private void pause() throws LinuxOperationException {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "System preparation wait interrupted; approved remote reboot may still be pending", failure);
        }
    }

    private static LinuxOperationException incomplete(String detail) {
        return LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED, detail);
    }

    private static HostKeyEvaluator pinned(HostKeyEvaluator original) {
        return new HostKeyEvaluator() {
            private String fingerprint;
            @Override public HostKeyDecision verify(SshEndpoint endpoint, String observed) {
                if (fingerprint != null && !fingerprint.equals(observed)) return HostKeyDecision.REJECT;
                return original.verify(endpoint, observed);
            }
            @Override public HostKeyDecision verify(SshEndpoint endpoint, HostKeyObservation observed) {
                if (fingerprint != null && !fingerprint.equals(observed.sshSha256())) return HostKeyDecision.REJECT;
                return original.verify(endpoint, observed);
            }
            @Override public boolean authenticated(SshEndpoint endpoint, HostKeyObservation observed) {
                if (fingerprint != null && !fingerprint.equals(observed.sshSha256())) return false;
                if (!original.authenticated(endpoint, observed)) return false;
                fingerprint = observed.sshSha256();
                return true;
            }
        };
    }
}
