package gold.debug.windowstolinux.shared.deploy.execution.lifecycle;

import java.util.Optional;

import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.LifecycleActionResult;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.session.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Applies lifecycle actions only after a live ownership verification.
 *
 *  <p>仅在实时验证资源归属后应用生命周期动作。
 */
public final class ManagedLifecycleService {
    /**
     * Executes lifecycle action result.
     * <p>执行生命周期动作结果。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return the operation result / 操作结果
     */
    public LifecycleActionResult execute(ManagedApplication application, LifecycleAction action,
            HealthCheck healthCheck, LinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator hostKeyVerifier) {
        try (LinuxRemoteSession session = gateway.connect(endpoint, credential, hostKeyVerifier)) {
            LifecycleObservation before = session.observe(application);
            if (!before.ownershipVerified()) {
                return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.ownershipUnverified"),
                        Optional.of(before));
            }
            if (action == LifecycleAction.REFRESH_STATUS) {
                return new LifecycleActionResult(true, LocalizedMessage.of("lifecycle.statusFetched"),
                        Optional.of(before));
            }
            if (before.runtimeState() == gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.INSTALLED)
                return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.onDemandCommandRequired"),
                        Optional.of(before));
            if (before.runtimeState() == gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.UNKNOWN || before
                    .autostartState() == gold.debug.windowstolinux.shared.model.lifecycle.AutostartState.UNKNOWN) {
                return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.remoteStateUnverified"),
                        Optional.of(before));
            }
            if (action == LifecycleAction.START
                    && before.runtimeState() != gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.STOPPED) {
                return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.startOnlyStopped"),
                        Optional.of(before));
            }
            if (before.runtimeState() == gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.ERROR
                    && action != LifecycleAction.STOP && action != LifecycleAction.DISABLE_AUTOSTART) {
                return new LifecycleActionResult(false, LocalizedMessage.of("lifecycle.errorRequiresStop"),
                        Optional.of(before));
            }
            LifecycleObservation after = session.executeLifecycle(application, action, healthCheck);
            return new LifecycleActionResult(after.ownershipVerified(),
                    LocalizedMessage.of("lifecycle.remoteResultVerified"), Optional.of(after));
        } catch (LinuxOperationException exception) {
            return LifecycleActionResult.failed(exception.failure());
        }
    }
}
