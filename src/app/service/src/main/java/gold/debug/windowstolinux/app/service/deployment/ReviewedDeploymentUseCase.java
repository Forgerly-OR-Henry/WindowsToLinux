package gold.debug.windowstolinux.app.service.deployment;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.shared.config.resource.ManagedComponentResourceBindings;
import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseBinding;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.standard.deploy.contract.DeploymentApproval;
import gold.debug.windowstolinux.shared.standard.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.standard.deploy.execution.transaction.ReviewedDeploymentService;

/**
 * Persists successful reviewed deployments in the same managed-application inventory as existing deployments.
 *
 *  <p>将成功的经审阅部署持久化到与现有部署相同的受管应用清单中。
 */
public final class ReviewedDeploymentUseCase {
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
     * Bound reviewed deployment service collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的已审阅部署服务协作对象。
     */
    private final ReviewedDeploymentService service;

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
     * Creates the reviewed deployment use case. / 创建经审阅部署用例。
     *
     * @param applications applications / 应用集合
     * @param graphs graphs / 图集合
     * @param applicationSecrets application secrets / 应用秘密集合
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReviewedDeploymentUseCase(ManagedApplicationRepository applications,
            ManagedApplicationGraphRepository graphs, ApplicationSecretRepository applicationSecrets,
            ReviewedDeploymentService service, DeploymentLinuxGateway gateway, ServerUseCaseFacade servers,
            ServerOperationLockRegistry locks) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.applicationSecrets = Objects.requireNonNull(applicationSecrets, "applicationSecrets");
        this.service = Objects.requireNonNull(service, "service");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Creates a fully bound request from a planning-ready typed source without inferring an executable command.
     *
     *  <p>从可计划的类型化源码创建完整绑定请求，不推断可执行命令。
     *
     * @param preparation preparation / 准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param rootBuildConfirmed root build confirmed / 根目录构建已确认
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     * @return a fully bound request from a planning-ready typed source without inferring an executable command / 从可计划的类型化源码创建完整绑定请求，不推断可执行命令
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReviewedDeploymentRequest createRequest(ReviewedSourcePreparation preparation, ServerIdentity server,
            ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
            Optional<List<ManagedDatabaseBinding>> databaseBindings,
            gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
            Optional<gold.debug.windowstolinux.shared.model.health.UserAccessUrl> userAccessUrl,
            gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration limits,
            boolean rootBuildConfirmed, boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted)
            throws SQLException {
        preparation = Objects.requireNonNull(preparation, "preparation");
        server = Objects.requireNonNull(server, "server");
        if (preparation.archive().isEmpty() || preparation.assessment().facts().isEmpty()) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.DEPLOYMENT_ANALYSIS_REQUIRED,
                    "Typed deployment requires a source that passed the selected deterministic analysis and archive preparation");
        }
        var facts = preparation.assessment().facts().orElseThrow();
        var archive = preparation.archive().orElseThrow();
        var sourceRevision = preparation.sourceRevision()
                .orElseThrow(() -> ApplicationServiceException.create(
                        ApplicationServiceFailureType.DEPLOYMENT_ANALYSIS_REQUIRED,
                        "Typed deployment requires an immutable source identity bound to the reviewed archive"));
        ManagedApplication application = ManagedApplicationIdentityResolver.resolve(applications, facts.applicationId(),
                server);
        var storage = gold.debug.windowstolinux.shared.standard.deploy.input.ManagedStoragePreparation
                .prepare(facts.sourceRoot(), facts.applicationId(), configuration, runtime, List.of());
        return new ReviewedDeploymentRequest(
                server, facts, sourceRevision, archive, storage.configuration(), secretReferences, databaseBindings,
                storage.files(), runtime, userAccessUrl, limits, new DeploymentApproval(application.id(),
                        archive.contentSha256(), server.id(), rootBuildConfirmed, Instant.now()),
                containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }

    /**
     * Creates a request whose database scope has not yet been reviewed. / 创建数据库范围尚未审阅的请求。
     *
     * @param preparation preparation / 准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param rootBuildConfirmed root build confirmed / 根目录构建已确认
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @param experimentalAdapterRiskAccepted the fresh experimental-adapter test-environment approval / 本次试验适配器测试环境批准
     * @return a request whose database scope has not yet been reviewed / 数据库范围尚未审阅的请求
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public ReviewedDeploymentRequest createRequest(ReviewedSourcePreparation preparation, ServerIdentity server,
            ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
            gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
            Optional<gold.debug.windowstolinux.shared.model.health.UserAccessUrl> userAccessUrl,
            gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration limits,
            boolean rootBuildConfirmed, boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted)
            throws SQLException {
        return createRequest(preparation, server, configuration, secretReferences, Optional.empty(), runtime,
                userAccessUrl, limits, rootBuildConfirmed, containerDaemonRiskAccepted,
                experimentalAdapterRiskAccepted);
    }

    /**
     * Creates a request that cannot enter an experimental adapter. / 创建不能进入试验适配器的请求。
     *
     * @param preparation preparation / 准备
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param userAccessUrl the optional user-facing HTTP URL / 可选的用户访问 HTTP URL
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param rootBuildConfirmed root build confirmed / 根目录构建已确认
     * @param containerDaemonRiskAccepted the explicit Docker daemon risk approval / 显式 Docker 守护进程风险批准
     * @return a request that cannot enter an experimental adapter / 不能进入试验适配器的请求
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
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
     *  <p>仅为本次有界经审阅事务读取已保存的服务器凭据。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return the saved server credential only for this bounded reviewed transaction / 仅为本次有界经审阅事务读取已保存的服务器凭据
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public DeploymentOutcome deployWithStoredPassword(ReviewedDeploymentRequest request, ServerProfile profile,
            gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation) throws SecretStoreException, SQLException {
        return deployWithStoredPassword(request, profile, mode, masterPassword, confirmation, ignored -> {
        });
    }

    /**
     * Publishes actual execution events without changing the reviewed request. / 发布实际执行事件，不改变已审阅请求。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param progress progress / 进度
     * @return constructed or resolved deployment outcome / 构造或解析得到的部署结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public DeploymentOutcome deployWithStoredPassword(ReviewedDeploymentRequest request, ServerProfile profile,
            gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent> progress)
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
                        servers.hostKeyVerifier(profile, confirmation), resolvedSecrets, progress);
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
     *  <p>部署经审阅请求，并在成功后记录精确选定的健康契约。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @return constructed or resolved deployment outcome / 构造或解析得到的部署结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public DeploymentOutcome deploy(ReviewedDeploymentRequest request, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator verifier) throws SQLException {
        if (!request.secretReferences().isEmpty()) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.APPLICATION_SECRET_REFERENCE_MISSING,
                    "Reviewed deployments with secret references require resolved stored revisions");
        }
        return deploy(request, endpoint, credential, verifier, List.of(), ignored -> {
        });
    }

    /**
     * Deploys deployment outcome.
     * <p>部署部署结果。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param verifier verifier / 验证器
     * @param resolvedSecrets resolved secrets / 已解析秘密集合
     * @param progress progress / 进度
     * @return constructed or resolved deployment outcome / 构造或解析得到的部署结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private DeploymentOutcome deploy(ReviewedDeploymentRequest request, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator verifier, List<ResolvedSecretRevision> resolvedSecrets,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent> progress)
            throws SQLException {
        request = Objects.requireNonNull(request, "request");
        ManagedApplication application = ManagedApplicationIdentityResolver.resolve(applications,
                request.facts().applicationId(), request.server());
        ReentrantLock lock = locks.forServer(application.server().id());
        lock.lock();
        try {
            DeploymentResult result = service.deploy(request, application, gateway, endpoint, credential, verifier,
                    resolvedSecrets, progress);
            if (result.status() == DeploymentStatus.SUCCEEDED) {
                try {
                    recordSuccessful(graphs, application,
                            new ManagedApplicationRuntimeConfiguration(request.runtime().healthCheck(),
                                    request.userAccessUrl(), request.runtime().identityPolicy(),
                                    request.runtime().workload()),
                            new CurrentRelease(application.id(), result.publishedReleaseSha256().orElseThrow(),
                                    Instant.now()),
                            request.runtime(), request.configuration(), request.secretReferences(),
                            request.databaseBindings(), request.fileBindings());
                } catch (SQLException failure) {
                    result = result.withNonFatalFailure(FailureDescriptor.create(
                            ApplicationServiceFailureType.DEPLOYMENT_RECORD_SAVE_FAILED, result.operationIdentity(),
                            "Remote deployment succeeded but local managed inventory storage failed"));
                }
            }
            if (result.finalObservation().isPresent()) {
                try {
                    if (applications.find(application.id()).isPresent()) {
                        applications.saveObservation(result.finalObservation().orElseThrow());
                    }
                } catch (SQLException failure) {
                    result = result.withNonFatalFailure(FailureDescriptor.create(
                            ApplicationServiceFailureType.LOCAL_OBSERVATION_SAVE_FAILED, result.operationIdentity(),
                            "Remote observation was verified but local history storage failed"));
                }
            }
            return DeploymentOutcome.from(result, request, application);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically saves every exact non-secret input needed by a later single-component backup. / 原子保存后续单组件备份所需的全部精确非秘密输入。
     *
     * @param graphs graphs / 图集合
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtimeConfiguration runtime configuration / 运行时配置
     * @param release release / 发布
     * @param reviewedRuntime reviewed runtime / 已审阅运行时
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @param fileBindings file bindings / 文件绑定集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static void recordSuccessful(ManagedApplicationGraphRepository graphs, ManagedApplication application,
            ManagedApplicationRuntimeConfiguration runtimeConfiguration, CurrentRelease release,
            gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification reviewedRuntime,
            ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
            Optional<List<ManagedDatabaseBinding>> databaseBindings,
            List<gold.debug.windowstolinux.shared.config.resource.ManagedFileBinding> fileBindings)
            throws SQLException {
        Objects.requireNonNull(graphs, "graphs");
        application = Objects.requireNonNull(application, "application");
        runtimeConfiguration = Objects.requireNonNull(runtimeConfiguration, "runtimeConfiguration");
        reviewedRuntime = Objects.requireNonNull(reviewedRuntime, "reviewedRuntime");
        SuccessfulManagedDeployment deployment = new SuccessfulManagedDeployment(application, runtimeConfiguration,
                release, configuration, secretReferences);
        ManagedApplicationGraph.Component component = new ManagedApplicationGraph.Component(application.id(),
                application, runtimeConfiguration, List.of(), Optional.of(reviewedRuntime), Optional.of(List.of()),
                Optional.of(new ManagedComponentResourceBindings(fileBindings, databaseBindings)));
        graphs.recordSuccessfulApplication(new ManagedApplicationGraph(application.id(), application.id(),
                Optional.of(reviewedRuntime.healthCheck()), List.of(component)), List.of(deployment));
    }

    /**
     * Records successful.
     * <p>记录成功。
     *
     * @param graphs graphs / 图集合
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param runtimeConfiguration runtime configuration / 运行时配置
     * @param release release / 发布
     * @param reviewedRuntime reviewed runtime / 已审阅运行时
     * @param configuration reviewed configuration snapshot or settings / 已审阅配置快照或设置
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param databaseBindings the reviewed database scope, or empty when it was not reviewed / 经审阅数据库范围；未审阅时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static void recordSuccessful(ManagedApplicationGraphRepository graphs, ManagedApplication application,
            ManagedApplicationRuntimeConfiguration runtimeConfiguration, CurrentRelease release,
            gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification reviewedRuntime,
            ConfigurationSnapshot configuration, List<SecretReference> secretReferences,
            Optional<List<ManagedDatabaseBinding>> databaseBindings) throws SQLException {
        recordSuccessful(graphs, application, runtimeConfiguration, release, reviewedRuntime, configuration,
                secretReferences, databaseBindings, List.of());
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
    private List<ResolvedSecretRevision> resolveSecrets(List<SecretReference> references, char[] masterPassword)
            throws SQLException, SecretStoreException {
        List<ResolvedSecretRevision> resolved = new java.util.ArrayList<>();
        try {
            for (SecretReference reference : references) {
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
