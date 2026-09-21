package gold.debug.windowstolinux.shared.deploy.execution.environment;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;

import java.util.Objects;

/**
 * Runs one explicitly approved environment-preparation operation. This is intentionally separate from deployment so installation cannot happen as an implicit side effect of uploading a project.
 *
 *  <p>运行一个经过显式批准的环境准备操作。它被有意与部署分离，以防安装作为上传项目的隐式副作用发生。
 */
public final class EnvironmentSetupService {
    /**
     * Verifies the approved host identity, prepares the supported environment and rechecks capabilities before returning.
     * <p>验证已批准主机身份、准备受支持环境，并在返回前复核能力。
     *
     * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @param systemConfirmation system confirmation / 系统确认
     * @return constructed or resolved environment setup result / 构造或解析得到的环境Setup结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public EnvironmentSetupResult prepare(EnvironmentSetupApproval approval, LinuxGateway gateway,
            SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator verifier,
            java.util.function.Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> systemConfirmation)
            throws LinuxOperationException {
        Objects.requireNonNull(credential, "credential");
        try {
            Objects.requireNonNull(approval, "approval").requireAcceptedFor(endpoint.serverId());
            Objects.requireNonNull(systemConfirmation, "systemConfirmation");
            HostKeyEvaluator pinned = new SelinuxPreparationService().prepare(gateway, endpoint, credential,
                    verifier, systemConfirmation);
            return prepare(approval, gateway, endpoint, credential.duplicate(), pinned);
        } finally {
            credential.clear();
        }
    }

    /**
     * Verifies the approved host identity, prepares the supported environment and rechecks capabilities before returning.
     * <p>验证已批准主机身份、准备受支持环境，并在返回前复核能力。
     *
     * @param approval the per-source, per-server approval / 按源码、服务器绑定的批准
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public EnvironmentSetupResult prepare(
            EnvironmentSetupApproval approval,
            LinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyEvaluator hostKeyVerifier
    ) throws LinuxOperationException {
        Objects.requireNonNull(credential, "credential");
        try {
            Objects.requireNonNull(approval, "approval").requireAcceptedFor(Objects.requireNonNull(endpoint, "endpoint").serverId());
            Objects.requireNonNull(gateway, "gateway");
            Objects.requireNonNull(hostKeyVerifier, "hostKeyVerifier");
            EnvironmentSetupResult installed;
            LinuxRemoteSession connected;
            try { connected = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier); }
            catch (LinuxOperationException failure) { throw LinuxOperationException.beforeEnvironmentPreparation(failure); }
            try (LinuxRemoteSession session = connected) { installed = session.prepareEnvironment(approval); }
            try (LinuxRemoteSession verified = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
                return new EnvironmentSetupResult(verified.collectCapabilities(), installed.evidence()
                        + "\nVerified capabilities through a fresh authenticated SSH connection after preparation.");
            } catch (LinuxOperationException failure) {
                throw LinuxOperationException.afterEnvironmentPreparation(installed, failure);
            }
        } finally {
            credential.clear();
        }
    }

}
