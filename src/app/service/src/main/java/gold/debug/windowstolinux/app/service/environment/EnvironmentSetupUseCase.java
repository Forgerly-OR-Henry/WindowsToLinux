package gold.debug.windowstolinux.app.service.environment;

import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.shared.deploy.environment.EnvironmentSetupService;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Provides the {@code EnvironmentSetupUseCase} implementation.
 *
 * <p>提供 {@code EnvironmentSetupUseCase} 实现。
 */
public final class EnvironmentSetupUseCase {
    private final EnvironmentSetupService service;
    private final LinuxGateway gateway;
    private final ServerUseCaseFacade servers;
    private final ServerOperationLockRegistry locks;

    /**
     * Creates a {@code EnvironmentSetupUseCase} instance.
     *
     * <p>创建 {@code EnvironmentSetupUseCase} 实例。
     *
     * @param service the {@code service} value / {@code service} 值
     * @param gateway the {@code gateway} value / {@code gateway} 值
     * @param servers the {@code servers} value / {@code servers} 值
     * @param locks the {@code locks} value / {@code locks} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public EnvironmentSetupUseCase(EnvironmentSetupService service, LinuxGateway gateway,
                                         ServerUseCaseFacade servers, ServerOperationLockRegistry locks) {
        this.service = Objects.requireNonNull(service, "service");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Performs the {@code prepare} operation.
     *
     * <p>执行 {@code prepare} 操作。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param confirmation the {@code confirmation} value / {@code confirmation} 值
     * @param installationConfirmed the {@code installationConfirmed} value / {@code installationConfirmed} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public EnvironmentSetupResult prepare(ServerProfile profile, CredentialStorageMode mode,
                                                        char[] masterPassword, Predicate<String> confirmation,
                                                        boolean installationConfirmed)
            throws SecretStoreException, SQLException, LinuxOperationException {
        try {
            Objects.requireNonNull(profile, "profile");
            if (profile.credentialMode() != mode) {
                throw new LocalizedOperationException(LocalizedMessage.of("validation.storageModeMismatch"),
                        "Credential storage mode does not match the saved server profile");
            }
            EnvironmentSetupApproval approval = new EnvironmentSetupApproval(
                    profile.id(), installationConfirmed, Instant.now());
            approval.requireAcceptedFor(profile.id());
            ReentrantLock lock = locks.forServer(profile.id());
            lock.lock();
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
                return service.prepare(approval, gateway, profile.endpoint(), servers.loadPassword(profile, store),
                        servers.hostKeyVerifier(profile, confirmation));
            } finally {
                lock.unlock();
            }
        } finally {
            if (masterPassword != null) {
                Arrays.fill(masterPassword, '\0');
            }
        }
    }
}
