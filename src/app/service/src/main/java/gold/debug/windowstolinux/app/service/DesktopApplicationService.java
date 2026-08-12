package gold.debug.windowstolinux.app.service;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.app.secret.api.SecretStore;
import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.app.service.ai.AiAnalysisOutcome;
import gold.debug.windowstolinux.app.service.ai.AiProfile;
import gold.debug.windowstolinux.app.service.ai.AiProviderProfile;
import gold.debug.windowstolinux.app.service.ai.AiUseCases;
import gold.debug.windowstolinux.app.service.ai.ReadOnlyDeploymentAgentTools;
import gold.debug.windowstolinux.app.service.concurrency.ServerOperationLocks;
import gold.debug.windowstolinux.app.service.config.DeploymentConfigurationUseCase;
import gold.debug.windowstolinux.app.service.deployment.DeploymentOutcome;
import gold.debug.windowstolinux.app.service.deployment.DeploymentUseCase;
import gold.debug.windowstolinux.app.service.environment.EnvironmentPreparationUseCase;
import gold.debug.windowstolinux.app.service.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.lifecycle.LifecycleUseCase;
import gold.debug.windowstolinux.app.service.lifecycle.ManagedApplicationSummary;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStores;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCases;
import gold.debug.windowstolinux.app.service.source.SourcePreparation;
import gold.debug.windowstolinux.app.service.source.SourcePreparationUseCase;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourceWorkspace;
import gold.debug.windowstolinux.shared.analyze.core.StaticProjectAnalyzer;
import gold.debug.windowstolinux.shared.deploy.environment.EnvironmentPreparationService;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.LifecycleActionResult;
import gold.debug.windowstolinux.shared.deploy.transaction.ManagedDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentProjectAssessment;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.health.UserAccessUrl;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Stable desktop facade. Package-specific use cases own all implementation details.
 *
 * <p>稳定的桌面门面。各包专属用例持有全部实现细节。
 */
public final class DesktopApplicationService {
    private final SourcePreparationUseCase source;
    private final ServerUseCases servers;
    private final AiUseCases ai;
    private final ReadOnlyDeploymentAgentTools deploymentAgentTools;
    private final DeploymentConfigurationUseCase deploymentConfiguration;
    private final EnvironmentPreparationUseCase environment;
    private final DeploymentUseCase deployment;
    private final LifecycleUseCase lifecycle;

    /**
     * Creates a {@code DesktopApplicationService} instance.
     *
     * <p>创建 {@code DesktopApplicationService} 实例。
     *
     * @param database the {@code database} value / {@code database} 值
     * @param workDirectory the {@code workDirectory} value / {@code workDirectory} 值
     * @param linuxGateway the {@code linuxGateway} value / {@code linuxGateway} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopApplicationService(DesktopDatabase database, Path workDirectory, LinuxGateway linuxGateway) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(linuxGateway, "linuxGateway");
        ServerOperationLocks locks = new ServerOperationLocks();
        DesktopSecretStores secrets = new DesktopSecretStores(database);
        this.servers = new ServerUseCases(database, secrets, linuxGateway);
        this.source = new SourcePreparationUseCase(new StaticProjectAnalyzer(), new WindowsSourceWorkspace(workDirectory));
        this.ai = new AiUseCases(database, secrets);
        this.deploymentAgentTools = new ReadOnlyDeploymentAgentTools();
        this.deploymentConfiguration = new DeploymentConfigurationUseCase(database, secrets);
        this.environment = new EnvironmentPreparationUseCase(
                new EnvironmentPreparationService(), linuxGateway, servers, locks);
        this.deployment = new DeploymentUseCase(
                database, new ManagedDeploymentService(), linuxGateway, servers, locks);
        this.lifecycle = new LifecycleUseCase(database, linuxGateway, servers, locks);
    }

    /**
     * Performs the {@code prepareSource} operation.
     *
     * <p>执行 {@code prepareSource} 操作。
     *
     * @param sourceDirectory the {@code sourceDirectory} value / {@code sourceDirectory} 值
     * @return the operation result / 操作结果
     * @throws IOException if the operation cannot be completed / 无法完成操作时
     */
    public SourcePreparation prepareSource(Path sourceDirectory) throws IOException {
        return source.prepare(sourceDirectory);
    }

    /**
     * Performs the typed deployment bounded static inspection made available to the optional read-only agent.
     *
     * <p>执行供可选只读 Agent 使用的部署有界静态检查。
     */
    public DeploymentProjectAssessment analyzeDeploymentSource(Path sourceDirectory, DeploymentProjectType projectType) {
        return deploymentAgentTools.analyze(sourceDirectory, projectType);
    }

    /**
     * Renders a fully validated typed deployment plan without opening SSH, invoking a build, or reading a secret.
     *
     * <p>渲染完整校验的部署计划，不打开 SSH、不调用构建，也不读取秘密。
     */
    public ReviewedDeploymentPlan planDeploymentDeployment(ReviewedDeploymentRequest request) {
        return deploymentAgentTools.plan(request);
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
    public AiAnalysisOutcome requestAiExplanation(SourcePreparation preparation, AiProfile profile,
                                                   CredentialStorageMode mode, char[] masterPassword,
                                                   String languageTag) {
        return ai.explain(preparation, profile, mode, masterPassword, languageTag);
    }

    /**
     * Requests an explanation from exactly one persisted named provider.
     *
     * <p>从恰好一个已持久化的命名提供者请求解释。
     */
    public AiAnalysisOutcome requestAiExplanationFromProvider(SourcePreparation preparation, String providerId,
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
    public EnvironmentPreparationResult prepareEnvironmentWithStoredPassword(
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
     * Creates a value through {@code createDeploymentRequest}.
     *
     * <p>通过 {@code createDeploymentRequest} 创建值。
     *
     * @param preparation the {@code preparation} value / {@code preparation} 值
     * @param server the {@code server} value / {@code server} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @param userAccessUrl the {@code userAccessUrl} value / {@code userAccessUrl} 值
     * @param limits the {@code limits} value / {@code limits} 值
     * @param rootBuildConfirmed the {@code rootBuildConfirmed} value / {@code rootBuildConfirmed} 值
     * @return the operation result / 操作结果
     */
    public DeploymentRequest createDeploymentRequest(SourcePreparation preparation, ServerIdentity server,
                                                     HealthCheck healthCheck, Optional<UserAccessUrl> userAccessUrl,
                                                     BuildLimits limits, boolean rootBuildConfirmed) {
        return deployment.createRequest(preparation, server, healthCheck, userAccessUrl, limits, rootBuildConfirmed);
    }

    /**
     * Creates a value through {@code createDeploymentRequest}.
     *
     * <p>通过 {@code createDeploymentRequest} 创建值。
     *
     * @param preparation the {@code preparation} value / {@code preparation} 值
     * @param server the {@code server} value / {@code server} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @param limits the {@code limits} value / {@code limits} 值
     * @param rootBuildConfirmed the {@code rootBuildConfirmed} value / {@code rootBuildConfirmed} 值
     * @return the operation result / 操作结果
     */
    public DeploymentRequest createDeploymentRequest(SourcePreparation preparation, ServerIdentity server,
                                                     HealthCheck healthCheck, BuildLimits limits,
                                                     boolean rootBuildConfirmed) {
        return createDeploymentRequest(preparation, server, healthCheck, Optional.empty(), limits, rootBuildConfirmed);
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
        return deployment.deployWithStoredPassword(request, profile, mode, masterPassword, confirmation);
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
        return deployment.deployResultWithStoredPassword(request, profile, mode, masterPassword, confirmation);
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
