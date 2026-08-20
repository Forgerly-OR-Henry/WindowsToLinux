package gold.debug.windowstolinux.shared.deploy.execution.lifecycle;

import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.LifecycleActionResult;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Optional;

/**
 * Applies lifecycle actions only after a live ownership verification.
 *
 * <p>仅在实时验证资源归属后应用生命周期动作。
 */
public final class ManagedLifecycleService {
    /**
     * Performs the {@code execute} operation.
     *
     * <p>执行 {@code execute} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param action the {@code action} value / {@code action} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @param gateway the {@code gateway} value / {@code gateway} 值
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param credential the {@code credential} value / {@code credential} 值
     * @param hostKeyVerifier the {@code hostKeyVerifier} value / {@code hostKeyVerifier} 值
     * @return the operation result / 操作结果
     */
    public LifecycleActionResult execute(
            ManagedApplication application,
            LifecycleAction action,
            HealthCheck healthCheck,
            LinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyEvaluator hostKeyVerifier
    ) {
        try (LinuxRemoteSession session = gateway.connect(endpoint, credential, hostKeyVerifier)) {
            LifecycleObservation before = session.observe(application);
            if (!before.ownershipVerified()) {
                return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.ownershipUnverified"), Optional.of(before));
            }
            if (action == LifecycleAction.REFRESH_STATUS) {
                return new LifecycleActionResult(true, LocalizedMessage.of("lifecycle.statusFetched"), Optional.of(before));
            }
            if (before.runtimeState() == gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.UNKNOWN
                    || before.autostartState() == gold.debug.windowstolinux.shared.model.lifecycle.AutostartState.UNKNOWN) {
                return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.remoteStateUnverified"), Optional.of(before));
            }
            if (action == LifecycleAction.START && before.runtimeState() != gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.STOPPED) {
                return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.startOnlyStopped"), Optional.of(before));
            }
            LifecycleObservation after = session.executeLifecycle(application, action, healthCheck);
            return new LifecycleActionResult(after.ownershipVerified(), LocalizedMessage.of("lifecycle.remoteResultVerified"), Optional.of(after));
        } catch (LinuxOperationException exception) {
            return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.connectionFailed",
                    java.util.Map.of("detail", safeMessage(exception))), Optional.empty());
        }
    }

    private static String safeMessage(LinuxOperationException exception) {
        return exception.getMessage() == null ? "Controlled operation failed" : exception.getMessage();
    }
}
