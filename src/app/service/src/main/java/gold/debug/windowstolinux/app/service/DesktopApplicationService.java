package gold.debug.windowstolinux.app.service;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.ai.AiAnalysisOutcome;
import gold.debug.windowstolinux.app.service.ai.AiProfile;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiRoleAssignment;
import gold.debug.windowstolinux.app.service.ai.AiUseCases;
import gold.debug.windowstolinux.app.service.ai.ReadOnlyDeploymentAgentTools;
import gold.debug.windowstolinux.app.service.locking.ServerOperationLocks;
import gold.debug.windowstolinux.app.service.config.DeploymentConfigurationUseCase;
import gold.debug.windowstolinux.app.service.deployment.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.deployment.ReviewedDeploymentUseCase;
import gold.debug.windowstolinux.app.service.deployment.MultiComponentDeploymentUseCase;
import gold.debug.windowstolinux.app.service.deployment.MultiComponentLifecycleUseCase;
import gold.debug.windowstolinux.app.service.deployment.ManagedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.deployment.MultiComponentReviewInput;
import gold.debug.windowstolinux.app.service.deployment.ReviewedMultiComponentApplication;
import gold.debug.windowstolinux.app.service.environment.EnvironmentSetupUseCase;
import gold.debug.windowstolinux.app.service.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.lifecycle.LifecycleUseCase;
import gold.debug.windowstolinux.app.service.lifecycle.ManagedApplicationSummary;
import gold.debug.windowstolinux.app.service.port.AiApplicationPort;
import gold.debug.windowstolinux.app.service.port.DeploymentApplicationPort;
import gold.debug.windowstolinux.app.service.port.ManagedApplicationPort;
import gold.debug.windowstolinux.app.service.port.MultiComponentApplicationPort;
import gold.debug.windowstolinux.app.service.port.ServerApplicationPort;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStores;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCases;
import gold.debug.windowstolinux.app.service.source.ReviewedSourcePreparation;
import gold.debug.windowstolinux.app.service.source.SourcePreparationUseCase;
import gold.debug.windowstolinux.app.service.source.PreparedMultiComponentSource;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourceWorkspace;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.ai.collaboration.AiRoleContext;
import gold.debug.windowstolinux.shared.ai.collaboration.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.deploy.environment.EnvironmentSetupService;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.LifecycleActionResult;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.MultiComponentLifecycleResult;
import gold.debug.windowstolinux.shared.deploy.lifecycle.MultiComponentLifecycleService;
import gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate;
import gold.debug.windowstolinux.shared.deploy.transaction.ReviewedMultiComponentDeploymentService;
import gold.debug.windowstolinux.shared.deploy.transaction.ReviewedDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.assessment.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.analyze.component.ComponentAnalysisRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.Set;

/**
 * Stable desktop facade. Package-specific use cases own all implementation details.
 *
 * <p>稳定的桌面门面。各包专属用例持有全部实现细节。
 */
public final class DesktopApplicationService implements AiApplicationPort, DeploymentApplicationPort,
        MultiComponentApplicationPort, ServerApplicationPort, ManagedApplicationPort {
    private final SourcePreparationUseCase source;
    private final ServerUseCases servers;
    private final AiUseCases ai;
    private final ReadOnlyDeploymentAgentTools deploymentAgentTools;
    private final DeploymentConfigurationUseCase deploymentConfiguration;
    private final EnvironmentSetupUseCase environment;
    private final ReviewedDeploymentUseCase reviewedDeployment;
    private final MultiComponentDeploymentUseCase multiComponentDeployment;
    private final MultiComponentLifecycleUseCase multiComponentLifecycle;
    private final LifecycleUseCase lifecycle;

    /**
     * Creates a {@code DesktopApplicationService} instance.
     *
     * <p>创建 {@code DesktopApplicationService} 实例。
     *
     * @param persistence the {@code persistence} value / {@code persistence} 值
     * @param workDirectory the {@code workDirectory} value / {@code workDirectory} 值
     * @param linuxGateway the {@code linuxGateway} value / {@code linuxGateway} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopApplicationService(DesktopPersistence persistence, Path workDirectory, DeploymentLinuxGateway linuxGateway) {
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(linuxGateway, "linuxGateway");
        ServerOperationLocks locks = new ServerOperationLocks();
        DesktopSecretStores secrets = new DesktopSecretStores(persistence.encryptedSecrets());
        this.servers = new ServerUseCases(persistence.servers(), secrets, linuxGateway);
        this.source = new SourcePreparationUseCase(new DeploymentAnalysisCoordinator(), new WindowsSourceWorkspace(workDirectory));
        this.ai = new AiUseCases(persistence.aiProfiles(), secrets);
        this.deploymentAgentTools = new ReadOnlyDeploymentAgentTools();
        this.deploymentConfiguration = new DeploymentConfigurationUseCase(
                persistence.configurations(), persistence.applicationSecrets(), secrets);
        this.environment = new EnvironmentSetupUseCase(
                new EnvironmentSetupService(), linuxGateway, servers, locks);
        this.reviewedDeployment = new ReviewedDeploymentUseCase(persistence.managedApplications(),
                persistence.applicationSecrets(), new ReviewedDeploymentService(), linuxGateway, servers, locks);
        this.multiComponentDeployment = new MultiComponentDeploymentUseCase(persistence.managedApplications(),
                persistence.managedApplicationGraphs(),
                persistence.applicationSecrets(), new ReviewedMultiComponentDeploymentService(),
                linuxGateway, servers, locks);
        this.multiComponentLifecycle = new MultiComponentLifecycleUseCase(persistence.managedApplications(),
                persistence.managedApplicationGraphs(), new MultiComponentLifecycleService(), linuxGateway, servers, locks);
        this.lifecycle = new LifecycleUseCase(persistence.managedApplications(), linuxGateway, servers, locks);
    }

    /**
     * Performs the typed deployment bounded static inspection made available to the optional read-only agent.
     *
     * <p>执行供可选只读 Agent 使用的部署有界静态检查。
     */
    public DeploymentProjectAssessment analyzeDeploymentSource(Path sourceDirectory, DeploymentProjectType projectType) {
        return deploymentAgentTools.analyze(sourceDirectory, projectType);
    }

    /** Prepares a selected typed source and safe archive without invoking project code. / 在不调用项目代码的情况下准备选定类型的源码和安全归档。 */
    public ReviewedSourcePreparation prepareReviewedSource(Path sourceDirectory, DeploymentProjectType projectType) throws IOException {
        return source.prepare(sourceDirectory, projectType);
    }

    /** Prepares an explicit component graph with one independent safe archive per component. / 使用每组件独立安全归档准备显式组件图。 */
    public PreparedMultiComponentSource prepareReviewedMultiComponentSource(
            Path applicationRoot, String applicationId, List<ComponentAnalysisRequest> components) throws IOException {
        return source.prepareMultiComponent(applicationRoot, applicationId, components);
    }

    /** Creates the complete secret-free review object for a whole-application transaction. / 创建整应用事务的完整无秘密审阅对象。 */
    public ReviewedMultiComponentApplication createReviewedMultiComponentApplication(
            PreparedMultiComponentSource prepared, ServerIdentity server, List<MultiComponentReviewInput> inputs,
            ApplicationHealthGate applicationHealth) throws SQLException {
        return multiComponentDeployment.createReview(prepared, server, inputs, applicationHealth);
    }

    /** Executes a reviewed whole-application transaction without stored application secrets. / 执行不含已存应用秘密的经审阅整应用事务。 */
    public MultiComponentDeploymentResult deployReviewedMultiComponent(
            ReviewedMultiComponentApplication review, SshEndpoint endpoint, SshCredential credential,
            HostKeyVerifier verifier) throws SQLException {
        return multiComponentDeployment.deploy(review, endpoint, credential, verifier);
    }

    /** Executes a reviewed whole-application transaction with the selected saved credential. / 使用选定已保存凭据执行经审阅整应用事务。 */
    public MultiComponentDeploymentResult deployReviewedMultiComponentWithStoredPassword(
            ReviewedMultiComponentApplication review, ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword, Predicate<String> confirmation) throws SecretStoreException, SQLException {
        return multiComponentDeployment.deployWithStoredPassword(review, profile, mode, masterPassword, confirmation);
    }

    /** Loads a durable secret-free whole-application graph after a desktop restart. / 在桌面应用重启后加载持久且不含秘密的整应用图。 */
    public Optional<ManagedMultiComponentApplication> findManagedMultiComponentApplication(String applicationId)
            throws SQLException {
        return multiComponentLifecycle.findManagedApplication(applicationId);
    }

    /** Executes a dependency-safe lifecycle action for a durably managed application graph. / 对持久受管应用图执行依赖安全生命周期动作。 */
    public MultiComponentLifecycleResult executeManagedMultiComponentLifecycleWithStoredPassword(
            String applicationId, Set<String> targetComponentIds, LifecycleAction action,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return multiComponentLifecycle.executeLifecycleWithStoredPassword(applicationId, targetComponentIds, action,
                profile, mode, masterPassword);
    }

    /**
     * Prepares one explicitly selected credential-free Git source by pinning it to a detached commit before analysis.
     *
     * <p>在分析前将一个显式选择且不含凭据的 Git 源固定为分离 Commit 后再准备。
     */
    public ReviewedSourcePreparation prepareReviewedGitSource(GitSourceRequest request, DeploymentProjectType projectType)
            throws GitSnapshotException {
        return source.prepareGit(request, projectType);
    }

    /**
     * Renders a fully validated typed deployment plan without opening SSH, invoking a build, or reading a secret.
     *
     * <p>渲染完整校验的部署计划，不打开 SSH、不调用构建，也不读取秘密。
     */
    public ReviewedDeploymentPlan planDeployment(ReviewedDeploymentRequest request) {
        return deploymentAgentTools.plan(request);
    }

    /**
     * Executes one previously reviewed type-specific deployment through the configured bounded gateway.
     *
     * <p>通过已配置的有界网关执行一个先前审阅的类型专属部署。
     */
    public DeploymentOutcome deployReviewed(ReviewedDeploymentRequest request, SshEndpoint endpoint,
                                           SshCredential credential, HostKeyVerifier verifier) throws SQLException {
        return reviewedDeployment.deploy(request, endpoint, credential, verifier);
    }

    /** Creates a reviewed request bound to the desktop-managed application identity. / 创建绑定到桌面受管应用身份的经审阅请求。 */
    public ReviewedDeploymentRequest createReviewedDeploymentRequest(
            ReviewedSourcePreparation preparation, ServerIdentity server, ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences, gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimits limits, boolean rootBuildConfirmed,
            boolean containerDaemonRiskAccepted, boolean experimentalAdapterRiskAccepted
    ) throws SQLException {
        return reviewedDeployment.createRequest(preparation, server, configuration, secretReferences, runtime, userAccessUrl, limits,
                        rootBuildConfirmed, containerDaemonRiskAccepted, experimentalAdapterRiskAccepted);
    }

    /** Creates a request that cannot enter an experimental adapter. / 创建不能进入试验适配器的请求。 */
    public ReviewedDeploymentRequest createReviewedDeploymentRequest(
            ReviewedSourcePreparation preparation, ServerIdentity server, ConfigurationSnapshot configuration,
            List<SecretReference> secretReferences, gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification runtime,
            Optional<UserAccessUrl> userAccessUrl, BuildLimits limits, boolean rootBuildConfirmed,
            boolean containerDaemonRiskAccepted
    ) throws SQLException {
        return createReviewedDeploymentRequest(preparation, server, configuration, secretReferences, runtime, userAccessUrl,
                limits, rootBuildConfirmed, containerDaemonRiskAccepted, false);
    }

    /** Executes a reviewed request using the selected saved server credential. / 使用选定的已保存服务器凭据执行经审阅请求。 */
    public DeploymentOutcome deployReviewedWithStoredPassword(ReviewedDeploymentRequest request, ServerProfile profile,
                                                              CredentialStorageMode mode, char[] masterPassword,
                                                              Predicate<String> confirmation)
            throws SecretStoreException, SQLException {
        return reviewedDeployment.deployWithStoredPassword(request, profile, mode, masterPassword, confirmation);
    }

    /**
     * Stores data through {@code saveServerProfile}.
     *
     * <p>通过 {@code saveServerProfile} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param store the {@code store} value / {@code store} 值
     * @param password the {@code password} value / {@code password} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    public void saveServerProfile(ServerProfile profile, SecretStore store, char[] password)
            throws SQLException, SecretStoreException {
        servers.save(profile, store, password);
    }

    /**
     * Stores data through {@code saveServerProfile}.
     *
     * <p>通过 {@code saveServerProfile} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param password the {@code password} value / {@code password} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    public void saveServerProfile(ServerProfile profile, CredentialStorageMode mode,
                                  char[] masterPassword, char[] password)
            throws SQLException, SecretStoreException {
        servers.save(profile, mode, masterPassword, password);
    }

    /**
     * Returns the value produced by {@code findServerProfile}.
     *
     * <p>返回 {@code findServerProfile} 生成的值。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ServerProfile> findServerProfile(String serverId) throws SQLException {
        return servers.find(serverId);
    }

    /**
     * Stores data through {@code saveAiProfile}.
     *
     * <p>通过 {@code saveAiProfile} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param apiKey the {@code apiKey} value / {@code apiKey} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    public void saveAiProfile(AiProfile profile, CredentialStorageMode mode,
                              char[] masterPassword, char[] apiKey)
            throws SQLException, SecretStoreException {
        ai.save(profile, mode, masterPassword, apiKey);
    }

    /**
     * Returns the value produced by {@code findAiProfile}.
     *
     * <p>返回 {@code findAiProfile} 生成的值。
     *
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<AiProfile> findAiProfile() throws SQLException {
        return ai.find();
    }

    /**
     * Stores an explicitly named AI provider without replacing the default profile.
     *
     * <p>保存一个显式命名的 AI 提供者，而不替换默认配置。
     */
    public void saveAiProviderProfile(AiProviderProfile profile, char[] masterPassword, char[] apiKey)
            throws SQLException, SecretStoreException {
        ai.saveNamed(profile, masterPassword, apiKey);
    }

    /**
     * Lists configured AI providers without returning any API key.
     *
     * <p>列出已配置的 AI 提供者，而不返回任何 API Key。
     */
    public List<AiProviderProfile> listAiProviderProfiles() throws SQLException {
        return ai.listNamed();
    }

    /** Assigns one fixed AI collaboration role to one existing named provider. / 将一个固定 AI 协作角色分配给一个已有命名提供者。 */
    public void assignAiRole(AiRoleAssignment assignment) throws SQLException {
        ai.assignRole(assignment);
    }

    /** Lists the three independently configurable AI role bindings. / 列出三个可独立配置的 AI 角色绑定。 */
    public List<AiRoleAssignment> listAiRoleAssignments() throws SQLException {
        return ai.listRoleAssignments();
    }

    /** Invokes exactly the provider assigned to the supplied minimal role context. / 精确调用分配给所提供最小角色上下文的提供者。 */
    public Optional<AiRoleInvocationResult> invokeAiRole(AiRoleContext context, char[] masterPassword)
            throws SQLException, SecretStoreException {
        return ai.invokeRole(context, masterPassword);
    }

    /**
     * Stores an immutable non-secret typed deployment configuration snapshot.
     *
     * <p>保存一个不可变的非秘密部署配置快照。
     */
    public void saveDeploymentConfigurationSnapshot(ConfigurationSnapshot snapshot) throws SQLException {
        deploymentConfiguration.saveSnapshot(snapshot);
    }

    /**
     * Stores one immutable application-secret revision without exposing its plaintext after this call.
     *
     * <p>保存一个不可变应用秘密修订；此调用后不再暴露其明文。
     */
    public void saveDeploymentSecretRevision(StoredApplicationSecretRevision revision, SecretStore store, char[] value)
            throws SQLException, SecretStoreException {
        deploymentConfiguration.saveSecretRevision(revision, store, value);
    }

    /** Stores one immutable application-secret revision through the selected desktop secret store. / 通过选定的桌面秘密存储保存一个不可变应用秘密修订。 */
    public void saveDeploymentSecretRevision(StoredApplicationSecretRevision revision, CredentialStorageMode mode,
                                             char[] masterPassword, char[] value)
            throws SQLException, SecretStoreException {
        deploymentConfiguration.saveSecretRevision(revision, mode, masterPassword, value);
    }

    /**
     * Verifies and binds immutable secret revisions to a release identity before it may be used for deployment or rollback.
     *
     * <p>在可用于部署或回滚前，验证并将不可变秘密修订绑定到发布标识。
     */
    public void bindDeploymentReleaseSecrets(String applicationId, String releaseIdentity, List<SecretReference> references,
                                            char[] masterPassword) throws SQLException, SecretStoreException {
        deploymentConfiguration.bindReleaseSecrets(applicationId, releaseIdentity, references, masterPassword);
    }

    /**
     * Performs the {@code requestAiExplanation} operation.
     *
     * <p>执行 {@code requestAiExplanation} 操作。
     *
     * @param preparation the {@code preparation} value / {@code preparation} 值
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param languageTag the {@code languageTag} value / {@code languageTag} 值
     * @return the operation result / 操作结果
     */
    /** Requests an optional AI explanation for a selected typed source inspection. / 为选定的类型化源码检查请求可选 AI 说明。 */
    public AiAnalysisOutcome requestAiExplanation(ReviewedSourcePreparation preparation, AiProfile profile,
                                                   CredentialStorageMode mode, char[] masterPassword,
                                                   String languageTag) {
        return ai.explain(preparation, profile, mode, masterPassword, languageTag);
    }

    /**
     * Requests an explanation from exactly one persisted named provider.
     *
     * <p>从恰好一个已持久化的命名提供者请求解释。
     */
    public AiAnalysisOutcome requestAiExplanationFromProvider(ReviewedSourcePreparation preparation, String providerId,
                                                               char[] masterPassword, String languageTag) {
        return ai.explainNamed(preparation, providerId, masterPassword, languageTag);
    }

    /**
     * Validates the input through {@code verifyServer}.
     *
     * <p>通过 {@code verifyServer} 验证输入。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param confirmation the {@code confirmation} value / {@code confirmation} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public ServerCapabilities verifyServer(ServerProfile profile, CredentialStorageMode mode,
                                           char[] masterPassword, Predicate<String> confirmation)
            throws SecretStoreException, SQLException, LinuxOperationException {
        return servers.verify(profile, mode, masterPassword, confirmation);
    }

    /** Collects exact non-secret deployment capabilities without mutating the selected server. / 在不修改所选服务器的情况下采集精确且不含秘密的部署能力。 */
    public LinuxCapabilities inspectDeploymentCapabilitiesWithStoredPassword(
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation) throws SecretStoreException, LinuxOperationException {
        return servers.inspectDeploymentCapabilities(profile, mode, masterPassword, confirmation);
    }

    /**
     * Performs the {@code prepareEnvironmentWithStoredPassword} operation.
     *
     * <p>执行 {@code prepareEnvironmentWithStoredPassword} 操作。
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
     */
    public EnvironmentSetupResult prepareEnvironmentWithStoredPassword(
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation, boolean installationConfirmed)
            throws SecretStoreException, SQLException, LinuxOperationException {
        return environment.prepare(profile, mode, masterPassword, confirmation, installationConfirmed);
    }

    /**
     * Returns the value produced by {@code findTrustedServer}.
     *
     * <p>返回 {@code findTrustedServer} 生成的值。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ServerIdentity> findTrustedServer(String serverId) throws SQLException {
        return servers.findTrusted(serverId);
    }

    /**
     * Returns the values selected by {@code listManagedApplications}.
     *
     * <p>返回 {@code listManagedApplications} 选出的值。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public List<ManagedApplication> listManagedApplications() throws SQLException {
        return lifecycle.list();
    }

    /**
     * Returns the values selected by {@code listManagedApplicationSummaries}.
     *
     * <p>返回 {@code listManagedApplicationSummaries} 选出的值。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public List<ManagedApplicationSummary> listManagedApplicationSummaries() throws SQLException {
        return lifecycle.summaries();
    }

    /**
     * Performs the {@code executePersistedLifecycleWithStoredPassword} operation.
     *
     * <p>执行 {@code executePersistedLifecycleWithStoredPassword} 操作。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param action the {@code action} value / {@code action} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public LifecycleOutcome executePersistedLifecycleWithStoredPassword(
            String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return lifecycle.executePersisted(applicationId, action, masterPassword);
    }

    /**
     * Performs the {@code executePersistedLifecycleResultWithStoredPassword} operation.
     *
     * <p>执行 {@code executePersistedLifecycleResultWithStoredPassword} 操作。
     *
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param action the {@code action} value / {@code action} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public LifecycleActionResult executePersistedLifecycleResultWithStoredPassword(
            String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return lifecycle.executePersistedResult(applicationId, action, masterPassword);
    }

    /**
     * Performs the {@code executeLifecycleWithStoredPassword} operation.
     *
     * <p>执行 {@code executeLifecycleWithStoredPassword} 操作。
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
    public LifecycleOutcome executeLifecycleWithStoredPassword(
            ManagedApplication application, LifecycleAction action, HealthCheck healthCheck,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return lifecycle.execute(application, action, healthCheck, profile, mode, masterPassword);
    }

    /**
     * Performs the {@code executeLifecycleResultWithStoredPassword} operation.
     *
     * <p>执行 {@code executeLifecycleResultWithStoredPassword} 操作。
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
    public LifecycleActionResult executeLifecycleResultWithStoredPassword(
            ManagedApplication application, LifecycleAction action, HealthCheck healthCheck,
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return lifecycle.executeResult(application, action, healthCheck, profile, mode, masterPassword);
    }

    HostKeyVerifier hostKeyVerifier(ServerProfile profile, Predicate<String> confirmation) {
        return servers.hostKeyVerifier(profile, confirmation);
    }

    SshCredential.Password loadPasswordCredential(ServerProfile profile, SecretStore store)
            throws SecretStoreException {
        return servers.loadPassword(profile, store);
    }
}
