package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.secret.api.SecretStore;
import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.app.service.concurrency.ServerOperationLocks;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCases;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.transaction.ReviewedDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Arrays;
import java.util.function.Predicate;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists successful reviewed deployments in the same managed-application inventory as existing deployments.
 *
 * <p>将成功的经审阅部署持久化到与现有部署相同的受管应用清单中。
 */
public final class ReviewedDeploymentUseCase {
    private final ManagedApplicationRepository applications;
    private final ApplicationSecretRepository applicationSecrets;
    private final ReviewedDeploymentService service;
    private final DeploymentLinuxGateway gateway;
    private final ServerUseCases servers;
    private final ServerOperationLocks locks;

    /** Creates the reviewed deployment use case. / 创建经审阅部署用例。 */
    public ReviewedDeploymentUseCase(ManagedApplicationRepository applications,
                                     ApplicationSecretRepository applicationSecrets, ReviewedDeploymentService service,
                                     DeploymentLinuxGateway gateway, ServerUseCases servers, ServerOperationLocks locks) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.applicationSecrets = Objects.requireNonNull(applicationSecrets, "applicationSecrets");
        this.service = Objects.requireNonNull(service, "service");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Creates a fully bound request from a planning-ready typed source without inferring an executable command.
     *
     * <p>从可计划的类型化源码创建完整绑定请求，不推断可执行命令。
     */
    public ReviewedDeploymentRequest createRequest(ReviewedSourcePreparation preparation, ServerIdentity server,
                                                    ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
                                                    gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
                                                    Optional<gold.debug.windowstolinux.shared.model.health.UserAccessUrl> userAccessUrl,
                                                    gold.debug.windowstolinux.shared.model.deployment.BuildLimits limits,
                                                    boolean rootBuildConfirmed, boolean containerDaemonRiskAccepted) throws SQLException {
        preparation = Objects.requireNonNull(preparation, "preparation");
        server = Objects.requireNonNull(server, "server");
        if (preparation.archive().isEmpty() || preparation.assessment().facts().isEmpty()) {
            throw new LocalizedOperationException(LocalizedMessage.of("deployment.analyzeFirst"),
                    "Typed deployment requires a source that passed the selected deterministic analysis and archive preparation");
        }
        var facts = preparation.assessment().facts().orElseThrow();
        var archive = preparation.archive().orElseThrow();
        var sourceRevision = preparation.sourceRevision().orElseThrow(() -> new LocalizedOperationException(
                LocalizedMessage.of("deployment.analyzeFirst"),
                "Typed deployment requires an immutable source identity bound to the reviewed archive"));
        ManagedApplication application = resolveApplication(facts.applicationId(), server);
        return new ReviewedDeploymentRequest(server, facts, sourceRevision,
                archive, configuration, secretReferences, runtime, userAccessUrl, limits,
                new DeploymentApproval(application.id(), archive.contentSha256(), server.id(), rootBuildConfirmed, Instant.now()),
                containerDaemonRiskAccepted);
    }

    /**
     * Reads the saved server credential only for this bounded reviewed transaction.
     *
     * <p>仅为本次有界经审阅事务读取已保存的服务器凭据。
     */
    public DeploymentResult deployWithStoredPassword(ReviewedDeploymentRequest request, ServerProfile profile,
                                                      gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode,
                                                      char[] masterPassword, Predicate<String> confirmation)
            throws SecretStoreException, SQLException {
        if (profile.credentialMode() != mode) {
            throw new LocalizedOperationException(LocalizedMessage.of("validation.storageModeMismatch"),
                    "Credential storage mode does not match the saved server profile");
        }
        try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
            return deploy(request, profile.endpoint(), servers.loadPassword(profile, store),
                    servers.hostKeyVerifier(profile, confirmation));
        } finally {
            if (masterPassword != null) {
                Arrays.fill(masterPassword, '\0');
            }
        }
    }

    /**
     * Deploys a reviewed request and records the exact selected health contract on success.
     *
     * <p>部署经审阅请求，并在成功后记录精确选定的健康契约。
     */
    public DeploymentResult deploy(ReviewedDeploymentRequest request, SshEndpoint endpoint,
                                   SshCredential credential, HostKeyVerifier verifier) throws SQLException {
        request = Objects.requireNonNull(request, "request");
        ManagedApplication application = resolveApplication(request.facts().applicationId(), request.server());
        ReentrantLock lock = locks.forServer(application.server().id());
        lock.lock();
        try {
            DeploymentResult result = service.deploy(request, application, gateway, endpoint, credential, verifier);
            result.finalObservation().ifPresent(observation -> {
                try {
                    applications.saveObservation(observation);
                } catch (SQLException ignored) {
                    // Remote truth remains authoritative when local history recording fails. / 本地历史记录失败时，远端事实仍然具有权威性。
                }
            });
            if (result.status() == DeploymentStatus.SUCCEEDED) {
                applications.recordSuccessfulDeployment(application,
                        new ManagedApplicationRuntimeConfiguration(request.runtime().healthCheck(), request.userAccessUrl()),
                        new CurrentRelease(application.id(), result.publishedArtifactSha256().orElseThrow(), Instant.now()));
                applicationSecrets.bindRelease(application.id(), result.publishedArtifactSha256().orElseThrow(),
                        request.secretReferences());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private ManagedApplication resolveApplication(String applicationId, ServerIdentity server) throws SQLException {
        Optional<ManagedApplication> saved = applications.find(applicationId);
        if (saved.isEmpty()) {
            return ManagedApplication.forManaged(applicationId, server, randomDigest());
        }
        ManagedApplication existing = saved.orElseThrow();
        if (!existing.server().equals(server)) {
            throw new LocalizedOperationException(LocalizedMessage.of("deployment.applicationServerConflict", "application", applicationId),
                    "Managed application " + applicationId + " is bound to a different server identity");
        }
        ManagedApplication canonical = ManagedApplication.forManaged(applicationId, server, existing.ownershipManifestSha256());
        if (!existing.equals(canonical)) {
            throw new LocalizedOperationException(LocalizedMessage.of("deployment.applicationIdentityInvalid", "application", applicationId),
                    "Saved managed application identity violates managed-deployment rules");
        }
        return existing;
    }

    private static String randomDigest() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
