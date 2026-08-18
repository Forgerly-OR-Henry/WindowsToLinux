package gold.debug.windowstolinux.shared.deploy.environment;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;

import java.util.Objects;

/**
 * Runs the one fixed Ubuntu 24.04 environment-preparation operation. This is intentionally separate from deployment so installation cannot happen as an implicit side effect of uploading a project.
 *
 * <p>运行唯一固定的 Ubuntu 24.04 环境准备操作。它被有意与部署分离，以防安装作为上传项目的隐式副作用发生。
 */
public final class EnvironmentSetupService {
    /**
     * Performs the {@code prepare} operation.
     *
     * <p>执行 {@code prepare} 操作。
     *
     * @param approval the {@code approval} value / {@code approval} 值
     * @param gateway the {@code gateway} value / {@code gateway} 值
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param credential the {@code credential} value / {@code credential} 值
     * @param hostKeyVerifier the {@code hostKeyVerifier} value / {@code hostKeyVerifier} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public EnvironmentSetupResult prepare(
            EnvironmentSetupApproval approval,
            LinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyVerifier hostKeyVerifier
    ) throws LinuxOperationException {
        Objects.requireNonNull(credential, "credential");
        try {
            Objects.requireNonNull(approval, "approval").requireAcceptedFor(Objects.requireNonNull(endpoint, "endpoint").serverId());
            Objects.requireNonNull(gateway, "gateway");
            Objects.requireNonNull(hostKeyVerifier, "hostKeyVerifier");
            try (LinuxRemoteSession session = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
                return session.prepareEnvironment(approval);
            }
        } finally {
            credential.clear();
        }
    }

}
