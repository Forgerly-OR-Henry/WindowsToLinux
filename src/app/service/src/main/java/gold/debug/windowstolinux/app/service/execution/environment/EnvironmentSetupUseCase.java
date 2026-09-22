package gold.debug.windowstolinux.app.service.execution.environment;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerUseCaseFacade;
import gold.debug.windowstolinux.shared.deploy.execution.environment.EnvironmentSetupService;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Adapts desktop credentials and approvals to managed Linux environment preparation.
 * <p>将桌面凭据及批准交互适配到受管 Linux 环境准备流程。
 */
public final class EnvironmentSetupUseCase {
    /**
     * Bound environment setup service collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的环境Setup服务协作对象。
     */
    private final EnvironmentSetupService service;

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
     * Validates and binds the inputs required by environment setup use case.
     * <p>校验并绑定环境Setup用例所需输入。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public EnvironmentSetupUseCase(EnvironmentSetupService service, LinuxGateway gateway, ServerUseCaseFacade servers,
            ServerOperationLockRegistry locks) {
        this.service = Objects.requireNonNull(service, "service");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    /**
     * Prepares environment setup result.
     * <p>准备环境Setup结果。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param installationConfirmed installation confirmed / 安装已确认
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public EnvironmentSetupResult prepare(ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation, boolean installationConfirmed)
            throws SecretStoreException, SQLException, LinuxOperationException {
        return prepare(profile, mode, masterPassword, confirmation, installationConfirmed, null);
    }

    /**
     * Adds dedicated system-change consent without widening ordinary installation approval. / 增加独立系统变更批准，不扩大普通安装批准范围。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param installationConfirmed installation confirmed / 安装已确认
     * @param systemConfirmation system confirmation / 系统确认
     * @return constructed or resolved environment setup result / 构造或解析得到的环境Setup结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public EnvironmentSetupResult prepare(ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation, boolean installationConfirmed,
            Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> systemConfirmation)
            throws SecretStoreException, SQLException, LinuxOperationException {
        try {
            Objects.requireNonNull(profile, "profile");
            if (profile.credentialMode() != mode) {
                throw ApplicationServiceException.create(ApplicationServiceFailureType.STORAGE_MODE_MISMATCH,
                        "Credential storage mode does not match the saved server profile");
            }
            EnvironmentSetupApproval approval = new EnvironmentSetupApproval(profile.id(), installationConfirmed,
                    Instant.now());
            approval.requireAcceptedFor(profile.id());
            ReentrantLock lock = locks.forServer(profile.id());
            lock.lock();
            try (SecretStore store = servers.secrets().open(mode, masterPassword)) {
                if (systemConfirmation != null) {
                    return service.prepare(approval, gateway, profile.endpoint(), servers.loadPassword(profile, store),
                            servers.hostKeyVerifier(profile, confirmation), systemConfirmation);
                }
                return service.prepare(approval, gateway, profile.endpoint(), servers.loadPassword(profile, store),
                        servers.hostKeyVerifier(profile, confirmation));
            } finally {
                lock.unlock();
            }
        } finally {
            if (masterPassword != null) {
                Arrays.fill(masterPassword, '\0');
            }
        }
    }
}
