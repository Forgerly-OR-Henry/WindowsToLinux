package gold.debug.windowstolinux.app.service.deployment;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.db.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.db.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.secret.api.SecretStore;
import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.app.service.locking.ServerOperationLocks;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCases;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.deploy.lifecycle.ManagedComponentLifecycle;
import gold.debug.windowstolinux.shared.deploy.lifecycle.MultiComponentLifecycleService;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlanner;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedReleaseIdentity;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.deploy.transaction.ReviewedComponentDeployment;
import gold.debug.windowstolinux.shared.deploy.transaction.ReviewedMultiComponentDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
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
import java.util.Set;
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
    private final MultiComponentLifecycleService lifecycleService;
    private final DeploymentLinuxGateway gateway;
    private final ServerUseCases servers;
    private final ServerOperationLocks locks;

    /** Creates the bounded whole-application use case. / 创建有界整应用用例。 */
    public MultiComponentDeploymentUseCase(
            ManagedApplicationRepository applications,
            ManagedApplicationGraphRepository graphs,
            ApplicationSecretRepository applicationSecrets,
            ReviewedMultiComponentDeploymentService deploymentService,
            MultiComponentLifecycleService lifecycleService,
            DeploymentLinuxGateway gateway,
            ServerUseCases servers,
            ServerOperationLocks locks
    ) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.applicationSecrets = Objects.requireNonNull(applicationSecrets, "applicationSecrets");
        this.deploymentService = Objects.requireNonNull(deploymentService, "deploymentService");
        this.lifecycleService = Objects.requireNonNull(lifecycleService, "lifecycleService");
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
            var application = ManagedApplicationIdentity.resolve(applications, source.facts().applicationId(), server);
            ReviewedDeploymentRequest request = new ReviewedDeploymentRequest(server, source.facts(),
                    source.sourceRevision(), source.archive(), input.configuration(), input.secretReferences(),
                    component.runtime().orElseThrow(), input.userAccessUrl(), input.limits(),
                    new DeploymentApproval(application.id(), source.archive().contentSha256(), server.id(),
                            input.limits().runAsRoot(), Instant.now()),
                    input.containerDaemonRiskAccepted(), input.experimentalAdapterRiskAccepted());
            reviewed.add(new ReviewedComponentApplication(componentId, request, application));
        }
        return new ReviewedMultiComponentApplication(plan, reviewed, applicationHealth);
    }

    /** Executes a reviewed application that has no secret references. / 执行不含秘密引用的经审阅应用。 */
    public MultiComponentDeploymentResult deploy(
            ReviewedMultiComponentApplication review,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyVerifier verifier
    ) throws SQLException {
        review = Objects.requireNonNull(review, "review");
        if (review.components().stream().anyMatch(component -> !component.request().secretReferences().isEmpty())) {
            clearCredential(credential);
            throw new LocalizedOperationException(LocalizedMessage.of("secret.applicationReferenceMissing"),
                    "Multi-component deployments with secret references require resolved stored revisions");
        }
        List<ReviewedComponentDeployment> bound = review.components().stream()
                .map(component -> new ReviewedComponentDeployment(component.componentId(), component.request(),
                        component.application(), List.of()))
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
                        component.application(), componentSecrets));
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

    /** Loads one durable graph for lifecycle work after a desktop restart. / 在桌面应用重启后加载一个用于生命周期工作的持久图。 */
    public Optional<ManagedMultiComponentApplication> findManagedApplication(String applicationId) throws SQLException {
        return graphs.find(applicationId).map(graph -> {
            Map<String, String> namespaces = new LinkedHashMap<>();
            Map<String, List<String>> dependencies = new LinkedHashMap<>();
            List<ManagedComponentLifecycle> components = new ArrayList<>();
            for (ManagedApplicationGraph.Component component : graph.components()) {
                namespaces.put(component.componentId(), component.application().id());
                dependencies.put(component.componentId(), component.dependencies());
                components.add(new ManagedComponentLifecycle(component.componentId(), component.application(),
                        component.runtimeConfiguration().healthCheck()));
            }
            MultiComponentDeploymentPlan plan = new MultiComponentDeploymentPlanner().restore(
                    graph.applicationId(), namespaces, dependencies);
            return new ManagedMultiComponentApplication(plan, components, graph.healthComponentId());
        });
    }

    /** Executes a dependency-safe application lifecycle action using authoritative remote observations. / 使用权威远端观测执行依赖安全的应用生命周期动作。 */
    public MultiComponentLifecycleResult executeLifecycleWithStoredPassword(
            String applicationId,
            Set<String> targetComponentIds,
            LifecycleAction action,
            ServerProfile profile,
            CredentialStorageMode mode,
            char[] masterPassword
    ) throws SecretStoreException, SQLException {
        try {
            ManagedMultiComponentApplication managed = findManagedApplication(applicationId)
                    .orElseThrow(() -> new LocalizedOperationException(
                            LocalizedMessage.of("lifecycle.applicationNotManaged"),
                            "The selected whole-application graph has no verified successful deployment"));
            validateProfile(managed, profile, mode);
            ReentrantLock lock = locks.forServer(profile.id());
            lock.lock();
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
                MultiComponentLifecycleResult result = lifecycleService.execute(managed.plan(), managed.components(),
                        targetComponentIds, action, gateway, profile.endpoint(), servers.loadPassword(profile, store),
                        servers.hostKeyVerifier(profile, ignored -> false));
                result.componentResults().stream().flatMap(value -> value.observation().stream())
                        .forEach(this::saveObservationQuietly);
                return result;
            } finally {
                lock.unlock();
            }
        } finally {
            clear(masterPassword);
        }
    }

    private MultiComponentDeploymentResult executeDeployment(
            ReviewedMultiComponentApplication review,
            List<ReviewedComponentDeployment> bound,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyVerifier verifier
    ) throws SQLException {
        String serverId = review.components().getFirst().request().server().id();
        ReentrantLock lock = locks.forServer(serverId);
        lock.lock();
        try {
            MultiComponentDeploymentResult result = deploymentService.deploy(review.plan(), bound,
                    review.applicationHealth(), gateway, endpoint, credential, verifier);
            result.componentResults().stream().flatMap(value -> value.observation().stream())
                    .forEach(this::saveObservationQuietly);
            if (result.status() == DeploymentStatus.SUCCEEDED) persistSuccessful(review);
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
                                ReviewedReleaseIdentity.from(component.request()), publishedAt),
                        component.request().secretReferences())).toList();
        Map<String, SuccessfulManagedDeployment> byApplication = new LinkedHashMap<>();
        deployments.forEach(deployment -> byApplication.put(deployment.application().id(), deployment));
        List<ManagedApplicationGraph.Component> components = review.components().stream().map(component ->
                new ManagedApplicationGraph.Component(component.componentId(), component.application(),
                        byApplication.get(component.application().id()).runtimeConfiguration(),
                        review.plan().dependencies().get(component.componentId()))).toList();
        graphs.recordSuccessfulApplication(new ManagedApplicationGraph(review.plan().applicationId(),
                review.applicationHealth().componentId(), components), deployments);
    }

    private List<ResolvedSecretRevision> resolveSecrets(List<gold.debug.windowstolinux.shared.config.secretref.SecretReference> references,
                                                        char[] masterPassword)
            throws SQLException, SecretStoreException {
        List<ResolvedSecretRevision> resolved = new ArrayList<>();
        try {
            for (var reference : references) {
                var revision = applicationSecrets.findRevision(reference)
                        .orElseThrow(() -> new SecretStoreException(
                                LocalizedMessage.of("secret.applicationReferenceMissing"),
                                "Application secret revision metadata is missing"));
                try (SecretStore store = servers.secrets().open(revision.credentialMode(), masterPassword)) {
                    char[] value = store.read(revision.credentialKey()).orElseThrow(() -> new SecretStoreException(
                            LocalizedMessage.of("secret.applicationReferenceMissing"),
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
            throw new LocalizedOperationException(LocalizedMessage.of("validation.lifecycleContextMismatch"),
                    "Reviewed application, server endpoint, and credential storage mode must match");
        }
    }

    private static void validateProfile(ManagedMultiComponentApplication application, ServerProfile profile,
                                        CredentialStorageMode mode) {
        Objects.requireNonNull(application, "application");
        ServerProfile selectedProfile = Objects.requireNonNull(profile, "profile");
        if (selectedProfile.credentialMode() != mode || application.components().stream().anyMatch(component ->
                !component.application().server().id().equals(selectedProfile.id())
                        || !component.application().server().host().equals(selectedProfile.endpoint().host())
                        || component.application().server().sshPort() != selectedProfile.endpoint().port())) {
            throw new LocalizedOperationException(LocalizedMessage.of("validation.lifecycleContextMismatch"),
                    "Managed application, server endpoint, and credential storage mode must match");
        }
    }

    private void saveObservationQuietly(LifecycleObservation observation) {
        try {
            applications.saveObservation(observation);
        } catch (SQLException ignored) {
            // Remote truth remains authoritative when local history recording fails. / 本地历史记录失败时，远端事实仍然具有权威性。
        }
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    private static void clearCredential(SshCredential credential) {
        if (credential instanceof SshCredential.Password password) password.clear();
    }
}
