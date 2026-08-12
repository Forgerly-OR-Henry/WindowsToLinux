package gold.debug.windowstolinux.app.service.lifecycle;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.secret.api.SecretStore;
import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.app.service.concurrency.ServerOperationLocks;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCases;
import gold.debug.windowstolinux.shared.deploy.lifecycle.ManagedLifecycleService;
import gold.debug.windowstolinux.shared.deploy.result.LifecycleActionResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides the {@code LifecycleUseCase} implementation.
 *
 * <p>提供 {@code LifecycleUseCase} 实现。
 */
public final class LifecycleUseCase {
    private final DesktopDatabase database;
    private final LinuxGateway gateway;
    private final ServerUseCases servers;
    private final ServerOperationLocks locks;

    /**
     * Creates a {@code LifecycleUseCase} instance.
     *
     * <p>创建 {@code LifecycleUseCase} 实例。
     *
     * @param database the {@code database} value / {@code database} 值
     * @param gateway the {@code gateway} value / {@code gateway} 值
     * @param servers the {@code servers} value / {@code servers} 值
     * @param locks the {@code locks} value / {@code locks} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public LifecycleUseCase(DesktopDatabase database, LinuxGateway gateway,
                            ServerUseCases servers, ServerOperationLocks locks) {
        this.database = Objects.requireNonNull(database, "database");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Returns the values selected by {@code list}.
     *
     * <p>返回 {@code list} 选出的值。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public List<ManagedApplication> list() throws SQLException {
        return database.listManagedApplications();
    }

    /**
     * Performs the {@code summaries} operation.
     *
     * <p>执行 {@code summaries} 操作。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public List<ManagedApplicationSummary> summaries() throws SQLException {
        return database.listManagedApplications().stream().map(application -> {
            try {
                return new ManagedApplicationSummary(application,
                        database.findCurrentRelease(application.id()).map(CurrentRelease::artifactSha256),
                        database.findManagedApplicationRuntimeConfiguration(application.id()));
            } catch (SQLException exception) {
                throw new LocalizedOperationException(LocalizedMessage.of("applications.summaryReadFailed"),
                        "Failed to read the managed application release or runtime configuration", exception);
            }
        }).toList();
    }

    /**
     * Performs the {@code executePersisted} operation.
     *
     * <p>执行 {@code executePersisted} 操作。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param action the {@code action} value / {@code action} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public LifecycleOutcome executePersisted(String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException {
        LifecycleActionResult result = executePersistedResult(applicationId, action, masterPassword);
        return outcome(result);
    }

    /**
     * Performs the {@code executePersistedResult} operation.
     *
     * <p>执行 {@code executePersistedResult} 操作。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param action the {@code action} value / {@code action} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public LifecycleActionResult executePersistedResult(String applicationId, LifecycleAction action,
                                                        char[] masterPassword)
            throws SecretStoreException, SQLException {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(action, "action");
        try {
            ManagedApplication application = database.findManagedApplication(applicationId).orElseThrow(
                    () -> new LocalizedOperationException(LocalizedMessage.of("lifecycle.selectApplication"),
                            "No WindowsToLinux-managed application was selected"));
            ManagedApplicationRuntimeConfiguration runtime = database
                    .findManagedApplicationRuntimeConfiguration(applicationId)
                    .orElseThrow(() -> new LocalizedOperationException(
                            LocalizedMessage.of("applications.legacyRuntime"),
                            "Legacy managed record has no runtime configuration; redeploy before lifecycle operations"));
            ServerProfile profile = servers.find(application.server().id()).orElseThrow(
                    () -> new LocalizedOperationException(LocalizedMessage.of("lifecycle.serverProfileMissing"),
                            "Server connection profile for the managed application was not found"));
            return executeResult(application, action, runtime.healthCheck(), profile,
                    profile.credentialMode(), masterPassword);
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Performs the {@code execute} operation.
     *
     * <p>执行 {@code execute} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param action the {@code action} value / {@code action} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public LifecycleOutcome execute(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck,
                                    ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return outcome(executeResult(application, action, healthCheck, profile, mode, masterPassword));
    }

    /**
     * Performs the {@code executeResult} operation.
     *
     * <p>执行 {@code executeResult} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param action the {@code action} value / {@code action} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public LifecycleActionResult executeResult(ManagedApplication application, LifecycleAction action,
                                               HealthCheck healthCheck, ServerProfile profile,
                                               CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        if (!application.server().id().equals(profile.id()) || profile.credentialMode() != mode) {
            throw new LocalizedOperationException(LocalizedMessage.of("validation.lifecycleContextMismatch"),
                    "Lifecycle application, server, and credential storage mode must match");
        }
        ReentrantLock lock = locks.forServer(application.server().id());
        lock.lock();
        try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
            LifecycleActionResult result = new ManagedLifecycleService().execute(
                    application, action, healthCheck, gateway, profile.endpoint(),
                    servers.loadPassword(profile, store), servers.hostKeyVerifier(profile, ignored -> false));
            result.observation().ifPresent(this::saveObservationQuietly);
            return result;
        } finally {
            lock.unlock();
            clear(masterPassword);
        }
    }

    private static LifecycleOutcome outcome(LifecycleActionResult result) {
        return new LifecycleOutcome(result.accepted(), result.message(), result.observation());
    }

    private void saveObservationQuietly(LifecycleObservation observation) {
        try {
            database.saveLastObservation(observation);
        } catch (SQLException ignored) {
            // Remote truth remains authoritative when local history recording fails. / 本地历史记录失败时，远端事实仍然具有权威性。
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
