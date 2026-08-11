package gold.debug.windowstolinux.app.service;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.app.secret.api.SecretStore;
import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.app.service.ai.AiAnalysisOutcome;
import gold.debug.windowstolinux.app.service.ai.AiProfile;
import gold.debug.windowstolinux.app.service.ai.AiUseCases;
import gold.debug.windowstolinux.app.service.concurrency.ServerOperationLocks;
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
import gold.debug.windowstolinux.app.windows.workspace.WindowsSourceWorkspace;
import gold.debug.windowstolinux.shared.analyze.core.StaticProjectAnalyzer;
import gold.debug.windowstolinux.shared.deploy.environment.PhaseOneEnvironmentPreparationService;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.LifecycleActionResult;
import gold.debug.windowstolinux.shared.deploy.transaction.PhaseOneDeploymentService;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.PhaseOneLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.deployment.PhaseOneEnvironmentPreparationResult;
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
    public DesktopApplicationService(DesktopDatabase database, Path workDirectory, PhaseOneLinuxGateway linuxGateway) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(linuxGateway, "linuxGateway");
        ServerOperationLocks locks = new ServerOperationLocks();
        DesktopSecretStores secrets = new DesktopSecretStores(database);
        this.servers = new ServerUseCases(database, secrets, linuxGateway);
        this.source = new SourcePreparationUseCase(new StaticProjectAnalyzer(), new WindowsSourceWorkspace(workDirectory));
        this.ai = new AiUseCases(database, secrets);
        this.environment = new EnvironmentPreparationUseCase(
                new PhaseOneEnvironmentPreparationService(), linuxGateway, servers, locks);
        this.deployment = new DeploymentUseCase(
                database, new PhaseOneDeploymentService(), linuxGateway, servers, locks);
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
     * Performs the {@code preparePhaseOneEnvironmentWithStoredPassword} operation.
     *
     * <p>执行 {@code preparePhaseOneEnvironmentWithStoredPassword} 操作。
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
    public PhaseOneEnvironmentPreparationResult preparePhaseOneEnvironmentWithStoredPassword(
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
