package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.secret.api.SecretStore;
import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.app.service.concurrency.ServerOperationLocks;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCases;
import gold.debug.windowstolinux.app.service.source.SourcePreparation;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.transaction.PhaseOneDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.PhaseOneLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Provides the {@code DeploymentUseCase} implementation.
 *
 * <p>提供 {@code DeploymentUseCase} 实现。
 */
public final class DeploymentUseCase {
    private final DesktopDatabase database;
    private final PhaseOneDeploymentService service;
    private final PhaseOneLinuxGateway gateway;
    private final ServerUseCases servers;
    private final ServerOperationLocks locks;

    /**
     * Creates a {@code DeploymentUseCase} instance.
     *
     * <p>创建 {@code DeploymentUseCase} 实例。
     *
     * @param database the {@code database} value / {@code database} 值
     * @param service the {@code service} value / {@code service} 值
     * @param gateway the {@code gateway} value / {@code gateway} 值
     * @param servers the {@code servers} value / {@code servers} 值
     * @param locks the {@code locks} value / {@code locks} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DeploymentUseCase(DesktopDatabase database, PhaseOneDeploymentService service,
                             PhaseOneLinuxGateway gateway, ServerUseCases servers, ServerOperationLocks locks) {
        this.database = Objects.requireNonNull(database, "database");
        this.service = Objects.requireNonNull(service, "service");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Creates a value through {@code createRequest}.
     *
     * <p>通过 {@code createRequest} 创建值。
     *
     * @param preparation the {@code preparation} value / {@code preparation} 值
     * @param server the {@code server} value / {@code server} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @param userAccessUrl the {@code userAccessUrl} value / {@code userAccessUrl} 值
     * @param limits the {@code limits} value / {@code limits} 值
     * @param rootBuildConfirmed the {@code rootBuildConfirmed} value / {@code rootBuildConfirmed} 值
     * @return the operation result / 操作结果
     */
    public DeploymentRequest createRequest(SourcePreparation preparation, ServerIdentity server, HealthCheck healthCheck,
                                           Optional<UserAccessUrl> userAccessUrl, BuildLimits limits,
                                           boolean rootBuildConfirmed) {
        if (preparation.archive().isEmpty() || preparation.assessment().facts().isEmpty()) {
            throw new LocalizedOperationException(LocalizedMessage.of("deployment.analyzeFirst"),
                    "Deployment request requires a project that passed static analysis and archive preparation");
        }
        var archive = preparation.archive().orElseThrow();
        var facts = preparation.assessment().facts().orElseThrow();
        ManagedApplication application = resolveApplication(facts.applicationName(), server);
        DeploymentApproval approval = new DeploymentApproval(
                application.id(), archive.contentSha256(), server.id(), rootBuildConfirmed, Instant.now());
        return new DeploymentRequest(application, facts, archive,
                new ManagedApplicationRuntimeConfiguration(healthCheck, userAccessUrl), limits, approval);
    }

    /**
     * Performs the {@code deployWithStoredPassword} operation.
     *
     * <p>执行 {@code deployWithStoredPassword} 操作。
     *
     * @param request the {@code request} value / {@code request} 值
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param confirmation the {@code confirmation} value / {@code confirmation} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public DeploymentOutcome deployWithStoredPassword(DeploymentRequest request, ServerProfile profile,
                                                       CredentialStorageMode mode, char[] masterPassword,
                                                       Predicate<String> confirmation)
            throws SecretStoreException, SQLException {
        return DeploymentOutcome.from(deployResultWithStoredPassword(
                request, profile, mode, masterPassword, confirmation), request);
    }

    /**
     * Performs the {@code deployResultWithStoredPassword} operation.
     *
     * <p>执行 {@code deployResultWithStoredPassword} 操作。
     *
     * @param request the {@code request} value / {@code request} 值
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param confirmation the {@code confirmation} value / {@code confirmation} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public DeploymentResult deployResultWithStoredPassword(DeploymentRequest request, ServerProfile profile,
                                                            CredentialStorageMode mode, char[] masterPassword,
                                                            Predicate<String> confirmation)
            throws SecretStoreException, SQLException {
        if (profile.credentialMode() != mode) {
            throw new LocalizedOperationException(LocalizedMessage.of("validation.storageModeMismatch"),
                    "Credential storage mode does not match the saved server profile");
        }
        try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
            return deploy(request, gateway, profile.endpoint(), servers.loadPassword(profile, store),
                    servers.hostKeyVerifier(profile, confirmation));
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Performs the {@code deploy} operation.
     *
     * <p>执行 {@code deploy} 操作。
     *
     * @param request the {@code request} value / {@code request} 值
     * @param requestedGateway the {@code requestedGateway} value / {@code requestedGateway} 值
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param credential the {@code credential} value / {@code credential} 值
     * @param verifier the {@code verifier} value / {@code verifier} 值
     * @return the operation result / 操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public DeploymentResult deploy(DeploymentRequest request, PhaseOneLinuxGateway requestedGateway, SshEndpoint endpoint,
                                   SshCredential credential, HostKeyVerifier verifier) throws SQLException {
        ReentrantLock lock = locks.forServer(request.application().server().id());
        lock.lock();
        try {
            DeploymentResult result = service.deploy(request, requestedGateway, endpoint, credential, verifier);
            result.finalObservation().ifPresent(observation -> {
                try {
                    database.saveLastObservation(observation);
                } catch (SQLException ignored) {
                    // Remote truth remains authoritative when local history recording fails. / 本地历史记录失败时，远端事实仍然具有权威性。
                }
            });
            if (result.status() == DeploymentStatus.SUCCEEDED) {
                database.recordSuccessfulDeployment(request.application(), request.runtimeConfiguration(),
                        new CurrentRelease(request.application().id(), result.publishedArtifactSha256().orElseThrow(),
                                Instant.now()));
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private ManagedApplication resolveApplication(String applicationId, ServerIdentity server) {
        try {
            Optional<ManagedApplication> saved = database.findManagedApplication(applicationId);
            if (saved.isEmpty()) {
                return ManagedApplication.forPhaseOne(applicationId, server, randomDigest());
            }
            ManagedApplication existing = saved.orElseThrow();
            if (!existing.server().equals(server)) {
                throw new LocalizedOperationException(LocalizedMessage.of("deployment.applicationServerConflict",
                        "application", applicationId),
                        "Managed application " + applicationId
                                + " is bound to a different server identity and cannot be reclaimed");
            }
            ManagedApplication canonical = ManagedApplication.forPhaseOne(
                    applicationId, server, existing.ownershipManifestSha256());
            if (!existing.equals(canonical)) {
                throw new LocalizedOperationException(LocalizedMessage.of("deployment.applicationIdentityInvalid",
                        "application", applicationId),
                        "Saved identity for managed application " + applicationId
                                + " violates phase-one rules and cannot be overwritten or reclaimed");
            }
            return existing;
        } catch (SQLException exception) {
            throw new LocalizedOperationException(LocalizedMessage.of("deployment.applicationIdentityReadFailed"),
                    "Failed to read the saved managed application identity; deployment request was rejected",
                    exception);
        }
    }

    private static String randomDigest() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
