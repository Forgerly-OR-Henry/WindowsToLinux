package gold.debug.windowstolinux.app.service.deployment;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.db.entity.ManagedApplicationGraph;
import gold.debug.windowstolinux.app.db.entity.SuccessfulManagedDeployment;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.service.contract.definition.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedComponentApplication;
import gold.debug.windowstolinux.app.service.deployment.multi.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.standard.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.standard.deploy.execution.transaction.ReviewedComponentDeployment;
import gold.debug.windowstolinux.shared.standard.deploy.execution.transaction.ReviewedMultiComponentDeploymentService;
import gold.debug.windowstolinux.shared.standard.deploy.plan.MultiComponentDeploymentPlanner;
import gold.debug.windowstolinux.shared.standard.deploy.plan.ReviewedReleaseIdentityResolver;

/**
 * Owns the desktop product boundary for reviewed whole-application deployment and lifecycle.
 *
 *  <p>负责经审阅整应用部署与生命周期的桌面产品边界。
 */
public final class MultiComponentDeploymentUseCase {
    /**
     * Bound managed application repository collaborator for applications.
     * <p>处理应用集合的受管应用仓库协作对象。
     */
    private final ManagedApplicationRepository applications;

    /**
     * Bound managed application graph repository collaborator for graphs.
     * <p>处理图集合的受管应用图仓库协作对象。
     */
    private final ManagedApplicationGraphRepository graphs;

    /**
     * Bound application secret repository collaborator for application secrets.
     * <p>处理应用秘密集合的应用秘密仓库协作对象。
     */
    private final ApplicationSecretRepository applicationSecrets;

    /**
     * Bound reviewed multi component deployment service collaborator for deployment service.
     * <p>处理部署服务的已审阅多组件部署服务协作对象。
     */
    private final ReviewedMultiComponentDeploymentService deploymentService;

    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final DeploymentLinuxGateway gateway;

    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;

    /**
     * Shared operation locks indexed by target identity.
     * <p>按目标身份索引的共享操作锁。
     */
    private final ServerOperationLockRegistry locks;

    /**
     * Creates the bounded whole-application use case. / 创建有界整应用用例。
     *
     * @param applications applications / 应用集合
     * @param graphs graphs / 图集合
     * @param applicationSecrets application secrets / 应用秘密集合
     * @param deploymentService deployment service / 部署服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentDeploymentUseCase(ManagedApplicationRepository applications,
            ManagedApplicationGraphRepository graphs, ApplicationSecretRepository applicationSecrets,
            ReviewedMultiComponentDeploymentService deploymentService, DeploymentLinuxGateway gateway,
            ServerUseCaseFacade servers, ServerOperationLockRegistry locks) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.applicationSecrets = Objects.requireNonNull(applicationSecrets, "applicationSecrets");
        this.deploymentService = Objects.requireNonNull(deploymentService, "deploymentService");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Creates one complete secret-free review bound to stable managed identities. / 创建绑定稳定受管身份的完整无秘密审阅。
     *
     * @param prepared prepared / 已准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @param applicationHealth caller-supplied whole-application health contract / 调用方提供的整应用健康契约
     * @return one complete secret-free review bound to stable managed identities / 绑定稳定受管身份的完整无秘密审阅
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReviewedMultiComponentApplication createReview(PreparedMultiComponentSource prepared, ServerIdentity server,
            List<MultiComponentReviewInput> inputs, ApplicationHealthGate applicationHealth) throws SQLException {
        prepared = Objects.requireNonNull(prepared, "prepared");
        server = Objects.requireNonNull(server, "server");
        MultiComponentDeploymentPlan plan = new MultiComponentDeploymentPlanner().plan(prepared.assessment());
        Map<String, MultiComponentReviewInput> indexedInputs = inputs(inputs);
        if (!indexedInputs.keySet().equals(plan.candidateNamespaces().keySet())) {
            throw new IllegalArgumentException("component reviews must exactly cover the admitted application graph");
        }
        Map<String, gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent> components = new LinkedHashMap<>();
        prepared.assessment().components().forEach(component -> components.put(component.componentId(), component));
        List<ReviewedComponentApplication> reviewed = new ArrayList<>();
        for (String componentId : plan.startOrder()) {
            var source = prepared.components().get(componentId);
            var component = components.get(componentId);
            var input = indexedInputs.get(componentId);
            if (!source.facts().equals(component.facts())) {
                throw new IllegalArgumentException("prepared component facts differ from the admitted graph");
            }
            var application = ManagedApplicationIdentityResolver.resolve(applications, source.facts().applicationId(),
                    server);
            var initialResources = ReviewedComponentApplication.resourceBindings(componentId, component.dataPaths(),
                    input.databaseBindings());
            var storage = gold.debug.windowstolinux.shared.standard.deploy.input.ManagedStoragePreparation.prepare(
                    source.facts().sourceRoot(), application.id(), input.configuration(),
                    component.runtime().orElseThrow(), initialResources.fileBindings());
            ReviewedDeploymentRequest request = new ReviewedDeploymentRequest(server, source.facts(),
                    source.sourceRevision(), source.archive(), storage.configuration(), input.secretReferences(),
                    input.databaseBindings(), storage.files(), component.runtime().orElseThrow(), input.userAccessUrl(),
                    input.limits(),
                    new DeploymentApproval(application.id(), source.archive().contentSha256(), server.id(),
                            input.limits().runAsRoot(), Instant.now()),
                    input.containerDaemonRiskAccepted(), input.experimentalAdapterRiskAccepted());
            reviewed.add(new ReviewedComponentApplication(componentId, request, application,
                    new gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings(
                            storage.files(), input.databaseBindings())));
        }
        return new ReviewedMultiComponentApplication(plan, reviewed, applicationHealth);
    }

    /**
     * Executes a reviewed application that has no secret references. / 执行不含秘密引用的经审阅应用。
     *
     * @param review review / 审阅
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MultiComponentDeploymentResult deploy(ReviewedMultiComponentApplication review, SshEndpoint endpoint,
            SshCredential credential, HostKeyEvaluator verifier) throws SQLException {
        review = Objects.requireNonNull(review, "review");
        if (review.components().stream().anyMatch(component -> !component.request().secretReferences().isEmpty())) {
            credential.clear();
            throw ApplicationServiceException.create(ApplicationServiceFailureType.APPLICATION_SECRET_REFERENCE_MISSING,
                    "Multi-component deployments with secret references require resolved stored revisions");
        }
        List<ReviewedComponentDeployment> bound = review
                .components().stream().map(component -> new ReviewedComponentDeployment(component.componentId(),
                        component.request(), component.application(), List.of(), component.resourceBindings()))
                .toList();
        return executeDeployment(review, bound, endpoint, credential, verifier, ignored -> {
        });
    }

    /**
     * Executes a reviewed application with the selected saved server credential. / 使用选定的已保存服务器凭据执行经审阅应用。
     *
     * @param review review / 审阅
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public MultiComponentDeploymentResult deployWithStoredPassword(ReviewedMultiComponentApplication review,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword, Predicate<String> confirmation)
            throws SecretStoreException, SQLException {
        return deployWithStoredPassword(review, profile, mode, masterPassword, confirmation, ignored -> {
        });
    }

    /**
     * Publishes application and component events while the transaction runs. / 在事务运行期间发布应用和组件事件。
     *
     * @param review review / 审阅
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param progress progress / 进度
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public MultiComponentDeploymentResult deployWithStoredPassword(ReviewedMultiComponentApplication review,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword, Predicate<String> confirmation,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent> progress)
            throws SecretStoreException, SQLException {
        List<ResolvedSecretRevision> resolved = new ArrayList<>();
        try {
            validateProfile(review, profile, mode);
            List<ReviewedComponentDeployment> bound = new ArrayList<>();
            for (ReviewedComponentApplication component : review.components()) {
                List<ResolvedSecretRevision> componentSecrets = resolveSecrets(component.request().secretReferences(),
                        masterPassword);
                resolved.addAll(componentSecrets);
                bound.add(new ReviewedComponentDeployment(component.componentId(), component.request(),
                        component.application(), componentSecrets, component.resourceBindings()));
            }
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
                return executeDeployment(review, bound, profile.endpoint(), servers.loadPassword(profile, store),
                        servers.hostKeyVerifier(profile, confirmation), progress);
            }
        } finally {
            resolved.forEach(ResolvedSecretRevision::close);
            clear(masterPassword);
        }
    }

    /**
     * Runs the reviewed shared deployment transaction and persists only its verified successful application state.
     * <p>运行已审阅共享部署事务，并仅持久化已验证成功应用状态。
     *
     * @param review review / 审阅
     * @param bound bound / 边界
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @param progress progress / 进度
     * @return constructed or resolved multi component deployment result / 构造或解析得到的多组件部署结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private MultiComponentDeploymentResult executeDeployment(ReviewedMultiComponentApplication review,
            List<ReviewedComponentDeployment> bound, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator verifier,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent> progress)
            throws SQLException {
        String serverId = review.components().getFirst().request().server().id();
        ReentrantLock lock = locks.forServer(serverId);
        lock.lock();
        try {
            MultiComponentDeploymentResult result = deploymentService.deploy(review.plan(), bound,
                    review.applicationHealth(), gateway, endpoint, credential, verifier, progress);
            boolean observationSaveFailed = false;
            for (var observation : result.componentResults().stream().flatMap(value -> value.observation().stream())
                    .toList()) {
                try {
                    applications.saveObservation(observation);
                } catch (SQLException failure) {
                    observationSaveFailed = true;
                }
            }
            if (observationSaveFailed) {
                result = result.withNonFatalFailure(FailureDescriptor.create(
                        ApplicationServiceFailureType.LOCAL_OBSERVATION_SAVE_FAILED, result.operationIdentity(),
                        "At least one remote component observation could not be stored locally"));
            }
            if (result.status() == DeploymentStatus.SUCCEEDED) {
                try {
                    persistSuccessful(review);
                } catch (SQLException failure) {
                    result = result.withNonFatalFailure(FailureDescriptor.create(
                            ApplicationServiceFailureType.DEPLOYMENT_RECORD_SAVE_FAILED, result.operationIdentity(),
                            "Remote multi-component deployment succeeded but local topology storage failed"));
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Persists the successfully reviewed component deployments with their publication time.
     * <p>持久化成功审阅的组件部署及其发布时间。
     *
     * @param review review / 审阅
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private void persistSuccessful(ReviewedMultiComponentApplication review) throws SQLException {
        Instant publishedAt = Instant.now();
        List<SuccessfulManagedDeployment> deployments = review.components().stream()
                .map(component -> new SuccessfulManagedDeployment(component.application(),
                        new ManagedApplicationRuntimeConfiguration(component.request().runtime().healthCheck(),
                                component.request().userAccessUrl(), component.request().runtime().identityPolicy(),
                                component.request().runtime().workload()),
                        new CurrentRelease(component.application().id(),
                                ReviewedReleaseIdentityResolver.from(component.request()), publishedAt),
                        component.request().configuration(), component.request().secretReferences()))
                .toList();
        Map<String, SuccessfulManagedDeployment> byApplication = new LinkedHashMap<>();
        deployments.forEach(deployment -> byApplication.put(deployment.application().id(), deployment));
        List<ManagedApplicationGraph.Component> components = review.components().stream()
                .map(component -> new ManagedApplicationGraph.Component(component.componentId(),
                        component.application(), byApplication.get(component.application().id()).runtimeConfiguration(),
                        review.plan().dependencies().get(component.componentId()),
                        Optional.of(component.request().runtime()),
                        Optional.of(component.resourceBindings().fileBindings().stream()
                                .map(binding -> binding.dataPath()).toList()),
                        Optional.of(component.resourceBindings())))
                .toList();
        graphs.recordSuccessfulApplication(
                new ManagedApplicationGraph(review.plan().applicationId(), review.applicationHealth().componentId(),
                        Optional.of(review.applicationHealth().healthCheck()), components),
                deployments);
    }

    /**
     * Resolves credential references or scoped secret-access service.
     * <p>解析凭据引用或限定作用域的秘密访问服务。
     *
     * @param references references / 引用集合
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    private List<ResolvedSecretRevision> resolveSecrets(
            List<gold.debug.windowstolinux.shared.config.secretref.SecretReference> references, char[] masterPassword)
            throws SQLException, SecretStoreException {
        List<ResolvedSecretRevision> resolved = new ArrayList<>();
        try {
            for (var reference : references) {
                var revision = applicationSecrets.findRevision(reference).orElseThrow(
                        () -> SecretStoreException.create(SecretStoreFailureType.APPLICATION_REFERENCE_MISSING,
                                "Application secret revision metadata is missing"));
                try (SecretStore store = servers.secrets().open(revision.credentialMode(), masterPassword)) {
                    char[] value = store.read(revision.credentialKey())
                            .orElseThrow(() -> SecretStoreException.create(
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

    /**
     * Indexes component review inputs deterministically and rejects duplicate component identifiers.
     * <p>以确定顺序索引组件审阅输入，并拒绝重复组件标识。
     *
     * @param inputs reviewed non-secret deployment input fields / 已审阅的非秘密部署输入字段
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static Map<String, MultiComponentReviewInput> inputs(List<MultiComponentReviewInput> inputs) {
        LinkedHashMap<String, MultiComponentReviewInput> indexed = new LinkedHashMap<>();
        Objects.requireNonNull(inputs, "inputs").stream()
                .sorted(java.util.Comparator.comparing(MultiComponentReviewInput::componentId)).forEach(input -> {
                    if (indexed.putIfAbsent(input.componentId(), input) != null) {
                        throw new IllegalArgumentException("component reviews must be unique");
                    }
                });
        return indexed;
    }

    /**
     * Validates connection or provider settings supplied to the operation.
     * <p>校验提供给操作的连接或提供者设置。
     *
     * @param review review / 审阅
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static void validateProfile(ReviewedMultiComponentApplication review, ServerProfile profile,
            CredentialStorageMode mode) {
        Objects.requireNonNull(review, "review");
        ServerProfile selectedProfile = Objects.requireNonNull(profile, "profile");
        if (selectedProfile.credentialMode() != mode || review.components().stream()
                .anyMatch(component -> !component.request().server().id().equals(selectedProfile.id())
                        || !component.request().server().host().equals(selectedProfile.endpoint().host())
                        || component.request().server().sshPort() != selectedProfile.endpoint().port())) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.LIFECYCLE_CONTEXT_MISMATCH,
                    "Reviewed application, server endpoint, and credential storage mode must match");
        }
    }

    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void clear(char[] value) {
        if (value != null)
            Arrays.fill(value, '\0');
    }

}
