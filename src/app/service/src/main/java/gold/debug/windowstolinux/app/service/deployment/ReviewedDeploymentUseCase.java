package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.ReviewedDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.SQLException;
import java.time.Instant;
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
    private final ServerUseCaseFacade servers;
    private final ServerOperationLockRegistry locks;

    /** Creates the reviewed deployment use case. / 创建经审阅部署用例。 */
    public ReviewedDeploymentUseCase(ManagedApplicationRepository applications,
                                     ApplicationSecretRepository applicationSecrets, ReviewedDeploymentService service,
                                     DeploymentLinuxGateway gateway, ServerUseCaseFacade servers, ServerOperationLockRegistry locks) {
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
                                                    gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration limits,
                                                    boolean rootBuildConfirmed, boolean containerDaemonRiskAccepted,
                                                    boolean experimentalAdapterRiskAccepted) throws SQLException {
        preparation = Objects.requireNonNull(preparation, "preparation");
        server = Objects.requireNonNull(server, "server");
        if (preparation.archive().isEmpty() || preparation.assessment().facts().isEmpty()) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.DEPLOYMENT_ANALYSIS_REQUIRED,
                    "Typed deployment requires a source that passed the selected deterministic analysis and archive preparation");
        }
        var facts = preparation.assessment().facts().orElseThrow();
        var archive = preparation.archive().orElseThrow();
        var sourceRevision = preparation.sourceRevision().orElseThrow(() -> ApplicationServiceException.create(
                ApplicationServiceFailureType.DEPLOYMENT_ANALYSIS_REQUIRED,
                "Typed deployment requires an immutable source identity bound to the reviewed archive"));
        ManagedApplication application = ManagedApplicationIdentityResolver.resolve(applications, facts.applicationId(), server);
        return new ReviewedDeploymentRequest(server, facts, sourceRevision,
                archive, configuration, secretReferences, runtime, userAccessUrl, limits,
                new DeploymentApproval(application.id(), archive.contentSha256(), server.id(), rootBuildConfirmed, Instant.now()),
                containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }

    /** Creates a request that cannot enter an experimental adapter. / 创建不能进入试验适配器的请求。 */
    public ReviewedDeploymentRequest createRequest(ReviewedSourcePreparation preparation, ServerIdentity server,
                                                    ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
                                                    gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
                                                    Optional<gold.debug.windowstolinux.shared.model.health.UserAccessUrl> userAccessUrl,
                                                    gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration limits,
                                                    boolean rootBuildConfirmed, boolean containerDaemonRiskAccepted) throws SQLException {
        return createRequest(preparation, server, configuration, secretReferences, runtime, userAccessUrl, limits,
                rootBuildConfirmed, containerDaemonRiskAccepted, false);
    }

    /**
     * Reads the saved server credential only for this bounded reviewed transaction.
     *
     * <p>仅为本次有界经审阅事务读取已保存的服务器凭据。
     */
    public DeploymentOutcome deployWithStoredPassword(ReviewedDeploymentRequest request, ServerProfile profile,
                                                      gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode,
                                                      char[] masterPassword, Predicate<String> confirmation)
            throws SecretStoreException, SQLException {
        if (profile.credentialMode() != mode) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.STORAGE_MODE_MISMATCH,
                    "Credential storage mode does not match the saved server profile");
        }
        List<ResolvedSecretRevision> resolvedSecrets = List.of();
        try {
            resolvedSecrets = resolveSecrets(request.secretReferences(), masterPassword);
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
            return deploy(request, profile.endpoint(), servers.loadPassword(profile, store),
                    servers.hostKeyVerifier(profile, confirmation), resolvedSecrets);
            }
        } finally {
            resolvedSecrets.forEach(ResolvedSecretRevision::close);
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
    public DeploymentOutcome deploy(ReviewedDeploymentRequest request, SshEndpoint endpoint,
                                   SshCredential credential, HostKeyEvaluator verifier) throws SQLException {
        if (!request.secretReferences().isEmpty()) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.APPLICATION_SECRET_REFERENCE_MISSING,
                    "Reviewed deployments with secret references require resolved stored revisions");
        }
        return deploy(request, endpoint, credential, verifier, List.of());
    }

    private DeploymentOutcome deploy(ReviewedDeploymentRequest request, SshEndpoint endpoint,
                                    SshCredential credential, HostKeyEvaluator verifier,
                                    List<ResolvedSecretRevision> resolvedSecrets) throws SQLException {
        request = Objects.requireNonNull(request, "request");
        ManagedApplication application = ManagedApplicationIdentityResolver.resolve(
                applications, request.facts().applicationId(), request.server());
        ReentrantLock lock = locks.forServer(application.server().id());
        lock.lock();
        try {
            DeploymentResult result = service.deploy(request, application, gateway, endpoint, credential, verifier,
                    resolvedSecrets);
            if (result.finalObservation().isPresent()) {
                try {
                    applications.saveObservation(result.finalObservation().orElseThrow());
                } catch (SQLException failure) {
                    result = result.withNonFatalFailure(FailureDescriptor.create(
                            ApplicationServiceFailureType.LOCAL_OBSERVATION_SAVE_FAILED,
                            result.operationIdentity(), "Remote observation was verified but local history storage failed"));
                }
            }
            if (result.status() == DeploymentStatus.SUCCEEDED) {
                try {
                    applications.recordSuccessfulDeployment(application,
                            new ManagedApplicationRuntimeConfiguration(request.runtime().healthCheck(), request.userAccessUrl()),
                            new CurrentRelease(application.id(), result.publishedReleaseSha256().orElseThrow(), Instant.now()),
                            request.secretReferences());
                } catch (SQLException failure) {
                    result = result.withNonFatalFailure(FailureDescriptor.create(
                            ApplicationServiceFailureType.DEPLOYMENT_RECORD_SAVE_FAILED,
                            result.operationIdentity(), "Remote deployment succeeded but local managed inventory storage failed"));
                }
            }
            return DeploymentOutcome.from(result, request, application);
        } finally {
            lock.unlock();
        }
    }

    private List<ResolvedSecretRevision> resolveSecrets(List<SecretReference> references, char[] masterPassword)
            throws SQLException, SecretStoreException {
        List<ResolvedSecretRevision> resolved = new java.util.ArrayList<>();
        try {
            for (SecretReference reference : references) {
                var revision = applicationSecrets.findRevision(reference)
                        .orElseThrow(() -> SecretStoreException.create(SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                                "Application secret revision metadata is missing"));
                try (SecretStore store = servers.secrets().open(revision.credentialMode(), masterPassword)) {
                    char[] value = store.read(revision.credentialKey())
                            .orElseThrow(() -> SecretStoreException.create(
                                    SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                                    "Application secret revision is unavailable from its selected platform store"));
                    try {
                        resolved.add(new ResolvedSecretRevision(reference, value));
                    } finally {
                        Arrays.fill(value, '\0');
                    }
                }
            }
            return List.copyOf(resolved);
        } catch (SQLException | SecretStoreException | RuntimeException exception) {
            resolved.forEach(ResolvedSecretRevision::close);
            throw exception;
        }
    }

}
