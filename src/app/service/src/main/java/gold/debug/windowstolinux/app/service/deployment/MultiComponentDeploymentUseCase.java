package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.deployment.multi.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedComponentApplication;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlanner;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedReleaseIdentityResolver;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.ReviewedComponentDeployment;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.ReviewedMultiComponentDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the desktop product boundary for reviewed whole-application deployment and lifecycle.
 *
 * <p>负责经审阅整应用部署与生命周期的桌面产品边界。
 */
public final class MultiComponentDeploymentUseCase {
    private final ManagedApplicationRepository applications;
    private final ManagedApplicationGraphRepository graphs;
    private final ApplicationSecretRepository applicationSecrets;
    private final ReviewedMultiComponentDeploymentService deploymentService;
    private final DeploymentLinuxGateway gateway;
    private final ServerUseCaseFacade servers;
    private final ServerOperationLockRegistry locks;

    /** Creates the bounded whole-application use case. / 创建有界整应用用例。 */
    public MultiComponentDeploymentUseCase(
            ManagedApplicationRepository applications,
            ManagedApplicationGraphRepository graphs,
            ApplicationSecretRepository applicationSecrets,
            ReviewedMultiComponentDeploymentService deploymentService,
            DeploymentLinuxGateway gateway,
            ServerUseCaseFacade servers,
            ServerOperationLockRegistry locks
    ) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.applicationSecrets = Objects.requireNonNull(applicationSecrets, "applicationSecrets");
        this.deploymentService = Objects.requireNonNull(deploymentService, "deploymentService");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /** Creates one complete secret-free review bound to stable managed identities. / 创建绑定稳定受管身份的完整无秘密审阅。 */
    public ReviewedMultiComponentApplication createReview(
            PreparedMultiComponentSource prepared,
            ServerIdentity server,
            List<MultiComponentReviewInput> inputs,
            ApplicationHealthGate applicationHealth
    ) throws SQLException {
        prepared = Objects.requireNonNull(prepared, "prepared");
        server = Objects.requireNonNull(server, "server");
        MultiComponentDeploymentPlan plan = new MultiComponentDeploymentPlanner().plan(prepared.assessment());
        Map<String, MultiComponentReviewInput> indexedInputs = inputs(inputs);
        if (!indexedInputs.keySet().equals(plan.candidateNamespaces().keySet())) {
            throw new IllegalArgumentException("component reviews must exactly cover the admitted application graph");
        }
        Map<String, gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent> components =
                new LinkedHashMap<>();
        prepared.assessment().components().forEach(component -> components.put(component.componentId(), component));
        List<ReviewedComponentApplication> reviewed = new ArrayList<>();
        for (String componentId : plan.startOrder()) {
            var source = prepared.components().get(componentId);
            var component = components.get(componentId);
            var input = indexedInputs.get(componentId);
            if (!source.facts().equals(component.facts())) {
                throw new IllegalArgumentException("prepared component facts differ from the admitted graph");
            }
            var application = ManagedApplicationIdentityResolver.resolve(applications, source.facts().applicationId(), server);
            ReviewedDeploymentRequest request = new ReviewedDeploymentRequest(server, source.facts(),
                    source.sourceRevision(), source.archive(), input.configuration(), input.secretReferences(),
                    input.databaseBindings(),
                    component.runtime().orElseThrow(), input.userAccessUrl(), input.limits(),
                    new DeploymentApproval(application.id(), source.archive().contentSha256(), server.id(),
                            input.limits().runAsRoot(), Instant.now()),
                    input.containerDaemonRiskAccepted(), input.experimentalAdapterRiskAccepted());
            reviewed.add(new ReviewedComponentApplication(componentId, request, application,
                    ReviewedComponentApplication.resourceBindings(componentId, component.dataPaths(),
                            input.databaseBindings())));
        }
        return new ReviewedMultiComponentApplication(plan, reviewed, applicationHealth);
    }

    /** Executes a reviewed application that has no secret references. / 执行不含秘密引用的经审阅应用。 */
    public MultiComponentDeploymentResult deploy(
            ReviewedMultiComponentApplication review,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyEvaluator verifier
    ) throws SQLException {
        review = Objects.requireNonNull(review, "review");
        if (review.components().stream().anyMatch(component -> !component.request().secretReferences().isEmpty())) {
            credential.clear();
            throw ApplicationServiceException.create(ApplicationServiceFailureType.APPLICATION_SECRET_REFERENCE_MISSING,
                    "Multi-component deployments with secret references require resolved stored revisions");
        }
        List<ReviewedComponentDeployment> bound = review.components().stream()
                .map(component -> new ReviewedComponentDeployment(component.componentId(), component.request(),
                        component.application(), List.of(), component.resourceBindings()))
                .toList();
        return executeDeployment(review, bound, endpoint, credential, verifier);
    }

    /** Executes a reviewed application with the selected saved server credential. / 使用选定的已保存服务器凭据执行经审阅应用。 */
    public MultiComponentDeploymentResult deployWithStoredPassword(
            ReviewedMultiComponentApplication review,
            ServerProfile profile,
            CredentialStorageMode mode,
            char[] masterPassword,
            Predicate<String> confirmation
    ) throws SecretStoreException, SQLException {
        List<ResolvedSecretRevision> resolved = new ArrayList<>();
        try {
            validateProfile(review, profile, mode);
            List<ReviewedComponentDeployment> bound = new ArrayList<>();
            for (ReviewedComponentApplication component : review.components()) {
                List<ResolvedSecretRevision> componentSecrets = resolveSecrets(
                        component.request().secretReferences(), masterPassword);
                resolved.addAll(componentSecrets);
                bound.add(new ReviewedComponentDeployment(component.componentId(), component.request(),
                        component.application(), componentSecrets, component.resourceBindings()));
            }
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
                return executeDeployment(review, bound, profile.endpoint(), servers.loadPassword(profile, store),
                        servers.hostKeyVerifier(profile, confirmation));
            }
        } finally {
            resolved.forEach(ResolvedSecretRevision::close);
            clear(masterPassword);
        }
    }


    private MultiComponentDeploymentResult executeDeployment(
            ReviewedMultiComponentApplication review,
            List<ReviewedComponentDeployment> bound,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyEvaluator verifier
    ) throws SQLException {
        String serverId = review.components().getFirst().request().server().id();
        ReentrantLock lock = locks.forServer(serverId);
        lock.lock();
        try {
            MultiComponentDeploymentResult result = deploymentService.deploy(review.plan(), bound,
                    review.applicationHealth(), gateway, endpoint, credential, verifier);
            boolean observationSaveFailed = false;
            for (var observation : result.componentResults().stream()
                    .flatMap(value -> value.observation().stream()).toList()) {
                try {
                    applications.saveObservation(observation);
                } catch (SQLException failure) {
                    observationSaveFailed = true;
                }
            }
            if (observationSaveFailed) {
                result = result.withNonFatalFailure(FailureDescriptor.create(
                        ApplicationServiceFailureType.LOCAL_OBSERVATION_SAVE_FAILED,
                        result.operationIdentity(), "At least one remote component observation could not be stored locally"));
            }
            if (result.status() == DeploymentStatus.SUCCEEDED) {
                try {
                    persistSuccessful(review);
                } catch (SQLException failure) {
                    result = result.withNonFatalFailure(FailureDescriptor.create(
                            ApplicationServiceFailureType.DEPLOYMENT_RECORD_SAVE_FAILED,
                            result.operationIdentity(), "Remote multi-component deployment succeeded but local topology storage failed"));
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    private void persistSuccessful(ReviewedMultiComponentApplication review) throws SQLException {
        Instant publishedAt = Instant.now();
        List<SuccessfulManagedDeployment> deployments = review.components().stream().map(component ->
                new SuccessfulManagedDeployment(component.application(),
                        new ManagedApplicationRuntimeConfiguration(component.request().runtime().healthCheck(),
                                component.request().userAccessUrl()),
                        new CurrentRelease(component.application().id(),
                                ReviewedReleaseIdentityResolver.from(component.request()), publishedAt),
                        component.request().configuration(), component.request().secretReferences())).toList();
        Map<String, SuccessfulManagedDeployment> byApplication = new LinkedHashMap<>();
        deployments.forEach(deployment -> byApplication.put(deployment.application().id(), deployment));
        List<ManagedApplicationGraph.Component> components = review.components().stream().map(component ->
                new ManagedApplicationGraph.Component(component.componentId(), component.application(),
                        byApplication.get(component.application().id()).runtimeConfiguration(),
                        review.plan().dependencies().get(component.componentId()),
                        Optional.of(component.request().runtime()),
                        Optional.of(component.resourceBindings().fileBindings().stream()
                                .map(binding -> binding.dataPath()).toList()),
                        Optional.of(component.resourceBindings()))).toList();
        graphs.recordSuccessfulApplication(new ManagedApplicationGraph(review.plan().applicationId(),
                review.applicationHealth().componentId(), Optional.of(review.applicationHealth().healthCheck()),
                components), deployments);
    }

    private List<ResolvedSecretRevision> resolveSecrets(List<gold.debug.windowstolinux.shared.config.secretref.SecretReference> references,
                                                        char[] masterPassword)
            throws SQLException, SecretStoreException {
        List<ResolvedSecretRevision> resolved = new ArrayList<>();
        try {
            for (var reference : references) {
                var revision = applicationSecrets.findRevision(reference)
                        .orElseThrow(() -> SecretStoreException.create(
                                SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                                "Application secret revision metadata is missing"));
                try (SecretStore store = servers.secrets().open(revision.credentialMode(), masterPassword)) {
                    char[] value = store.read(revision.credentialKey()).orElseThrow(() -> SecretStoreException.create(
                            SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                            "Application secret revision is unavailable from its selected platform store"));
                    try {
                        resolved.add(new ResolvedSecretRevision(reference, value));
                    } finally {
                        clear(value);
                    }
                }
            }
            return List.copyOf(resolved);
        } catch (SQLException | SecretStoreException | RuntimeException exception) {
            resolved.forEach(ResolvedSecretRevision::close);
            throw exception;
        }
    }

    private static Map<String, MultiComponentReviewInput> inputs(List<MultiComponentReviewInput> inputs) {
        LinkedHashMap<String, MultiComponentReviewInput> indexed = new LinkedHashMap<>();
        Objects.requireNonNull(inputs, "inputs").stream()
                .sorted(java.util.Comparator.comparing(MultiComponentReviewInput::componentId))
                .forEach(input -> {
                    if (indexed.putIfAbsent(input.componentId(), input) != null) {
                        throw new IllegalArgumentException("component reviews must be unique");
                    }
                });
        return indexed;
    }

    private static void validateProfile(ReviewedMultiComponentApplication review, ServerProfile profile,
                                        CredentialStorageMode mode) {
        Objects.requireNonNull(review, "review");
        ServerProfile selectedProfile = Objects.requireNonNull(profile, "profile");
        if (selectedProfile.credentialMode() != mode || review.components().stream().anyMatch(component ->
                !component.request().server().id().equals(selectedProfile.id())
                        || !component.request().server().host().equals(selectedProfile.endpoint().host())
                        || component.request().server().sshPort() != selectedProfile.endpoint().port())) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.LIFECYCLE_CONTEXT_MISMATCH,
                    "Reviewed application, server endpoint, and credential storage mode must match");
        }
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

}
