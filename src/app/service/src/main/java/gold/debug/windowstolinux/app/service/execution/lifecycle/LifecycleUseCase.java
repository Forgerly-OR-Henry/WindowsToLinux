package gold.debug.windowstolinux.app.service.execution.lifecycle;

import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.db.entity.CurrentRelease;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.shared.deploy.execution.lifecycle.ManagedLifecycleService;
import gold.debug.windowstolinux.shared.deploy.contract.result.lifecycle.LifecycleActionResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplicationRuntimeConfiguration;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads persisted ownership and runtime facts before controlled application lifecycle actions.
 * <p>在受控应用生命周期操作前读取持久化的归属和运行事实。
 */
public final class LifecycleUseCase {
    /**
     * Bound managed application repository collaborator for applications.
     * <p>处理应用集合的受管应用仓库协作对象。
     */
    private final ManagedApplicationRepository applications;
    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final LinuxGateway gateway;
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
     * Validates and binds the inputs required by lifecycle use case.
     * <p>校验并绑定生命周期用例所需输入。
     *
     * @param applications applications / 应用集合
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public LifecycleUseCase(ManagedApplicationRepository applications, LinuxGateway gateway,
                            ServerUseCaseFacade servers, ServerOperationLockRegistry locks) {
        this.applications = Objects.requireNonNull(applications, "applications");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Returns the values selected by {@code list}.
     *
     *  <p>返回 {@code list} 选出的值。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<ManagedApplication> list() throws SQLException {
        return applications.list();
    }

    /**
     * Builds list managed application snapshot from the supplied summaries inputs.
     * <p>根据所提供摘要集合输入构建列表受管应用快照。
     *
     * @return the operation result collection / 操作结果集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<ManagedApplicationSnapshot> summaries() throws SQLException {
        return applications.list().stream().map(application -> {
            try {
                return new ManagedApplicationSnapshot(application,
                        applications.findRelease(application.id()).map(CurrentRelease::releaseSha256),
                        applications.findRuntime(application.id()));
            } catch (SQLException exception) {
                throw ApplicationServiceException.create(ApplicationServiceFailureType.MANAGED_SUMMARY_READ_FAILED,
                        "Failed to read the managed application release or runtime configuration", exception);
            }
        }).toList();
    }

    /**
     * Executes persisted.
     * <p>执行已持久化。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public LifecycleOutcome executePersisted(String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException {
        LifecycleActionResult result = executePersistedResult(applicationId, action, masterPassword);
        return outcome(result);
    }

    /**
     * Executes persisted result.
     * <p>执行已持久化结果。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public LifecycleActionResult executePersistedResult(String applicationId, LifecycleAction action,
                                                        char[] masterPassword)
            throws SecretStoreException, SQLException {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(action, "action");
        try {
            ManagedApplication application = applications.find(applicationId).orElseThrow(
                    () -> ApplicationServiceException.create(ApplicationServiceFailureType.APPLICATION_NOT_SELECTED,
                            "No WindowsToLinux-managed application was selected"));
            ManagedApplicationRuntimeConfiguration runtime = applications.findRuntime(applicationId)
                    .orElseThrow(() -> ApplicationServiceException.create(
                            ApplicationServiceFailureType.LEGACY_RUNTIME_MISSING,
                            "Legacy managed record has no runtime configuration; redeploy before lifecycle operations"));
            ServerProfile profile = servers.find(application.server().id()).orElseThrow(
                    () -> ApplicationServiceException.create(ApplicationServiceFailureType.SERVER_PROFILE_MISSING,
                            "Server connection profile for the managed application was not found"));
            return executeResult(application, action, runtime.healthCheck(), profile,
                    profile.credentialMode(), masterPassword);
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Executes lifecycle outcome.
     * <p>执行生命周期结果。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public LifecycleOutcome execute(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck,
                                    ServerProfile profile, CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        return outcome(executeResult(application, action, healthCheck, profile, mode, masterPassword));
    }

    /**
     * Executes typed outcome produced by the delegated operation.
     * <p>执行被委派操作产生的类型化结果。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public LifecycleActionResult executeResult(ManagedApplication application, LifecycleAction action,
                                               HealthCheck healthCheck, ServerProfile profile,
                                               CredentialStorageMode mode, char[] masterPassword)
            throws SecretStoreException, SQLException {
        if (!application.server().id().equals(profile.id()) || profile.credentialMode() != mode) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.LIFECYCLE_CONTEXT_MISMATCH,
                    "Lifecycle application, server, and credential storage mode must match");
        }
        ReentrantLock lock = locks.forServer(application.server().id());
        lock.lock();
        try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
            LifecycleActionResult result = new ManagedLifecycleService().execute(
                    application, action, healthCheck, gateway, profile.endpoint(),
                    servers.loadPassword(profile, store), servers.hostKeyVerifier(profile, ignored -> false));
            if (result.observation().isPresent()) {
                try {
                    applications.saveObservation(result.observation().orElseThrow());
                } catch (SQLException failure) {
                    result = result.withNonFatalFailure(FailureDescriptor.create(
                            ApplicationServiceFailureType.LOCAL_OBSERVATION_SAVE_FAILED,
                            result.operationIdentity(), "Remote lifecycle observation was verified but local history storage failed"));
                }
            }
            return result;
        } finally {
            lock.unlock();
            clear(masterPassword);
        }
    }

    /**
     * Builds lifecycle outcome from the supplied outcome inputs.
     * <p>根据所提供结果输入构建生命周期结果。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return lifecycle outcome from the supplied outcome inputs / 根据所提供结果输入构建生命周期结果
     */
    private static LifecycleOutcome outcome(LifecycleActionResult result) {
        return new LifecycleOutcome(result.accepted(), result.message(), result.observation(),
                result.operationIdentity(), result.failure(), result.nonFatalFailures());
    }

    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
