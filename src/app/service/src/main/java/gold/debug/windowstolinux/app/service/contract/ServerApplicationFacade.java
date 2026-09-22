package gold.debug.windowstolinux.app.service.contract;

import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Narrow application operations required by server management. / 服务器管理所需的窄应用操作。
 */
public interface ServerApplicationFacade {
    /**
     * Rechecks a read-only connection after a user-guided console rescue. / 用户通过控制台救援后重做只读连接检查。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved server capability facts / 构造或解析得到的服务器能力事实
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    default ServerCapabilityFacts verifyServerRecovering(ServerProfile profile, CredentialStorageMode mode,
            char[] master, Predicate<String> fingerprint,
            gold.debug.windowstolinux.app.service.contract.DesktopRecoveryInteraction interaction) throws Exception {
        return verifyServer(profile, mode, master, fingerprint);
    }

    /**
     * Keeps completed installation evidence when its reconnect requires rescue. / 重连需要救援时保留已完成安装的证据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param confirmed confirmed / 已确认
     * @param system system / 系统
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved environment setup result / 构造或解析得到的环境Setup结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    default EnvironmentSetupResult prepareEnvironmentRecovering(ServerProfile profile, CredentialStorageMode mode,
            char[] master, Predicate<String> fingerprint, boolean confirmed,
            Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> system,
            gold.debug.windowstolinux.app.service.contract.DesktopRecoveryInteraction interaction) throws Exception {
        return prepareEnvironmentWithStoredPassword(profile, mode, master, fingerprint, confirmed, system);
    }

    /**
     * Lists server summaries.
     * <p>列出服务器摘要集合。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    java.util.List<gold.debug.windowstolinux.app.service.server.ServerSummary> listServerSummaries()
            throws SQLException;

    /**
     * Lists server profiles.
     * <p>列出服务器配置资料集合。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    java.util.List<ServerProfile> listServerProfiles() throws SQLException;

    /**
     * Persists server profile.
     * <p>持久化服务器配置资料。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    void saveServerProfile(ServerProfile profile, CredentialStorageMode mode, char[] masterPassword, char[] password)
            throws SQLException, SecretStoreException;

    /**
     * Finds server profile.
     * <p>查找服务器配置资料。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    Optional<ServerProfile> findServerProfile(String serverId) throws SQLException;

    /**
     * Verifies server identity or selected server configuration.
     * <p>验证服务器身份或所选服务器配置。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved server capability facts / 构造或解析得到的服务器能力事实
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    ServerCapabilityFacts verifyServer(ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation) throws SecretStoreException, SQLException, LinuxOperationException;

    /**
     * Prepares environment with stored password.
     * <p>准备环境具有已存储密码。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @param installationConfirmed installation confirmed / 安装已确认
     * @return constructed or resolved environment setup result / 构造或解析得到的环境Setup结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    EnvironmentSetupResult prepareEnvironmentWithStoredPassword(ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword, Predicate<String> confirmation, boolean installationConfirmed)
            throws SecretStoreException, SQLException, LinuxOperationException;

    /**
     * Prepares environment with stored password.
     * <p>准备环境具有已存储密码。
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
     */
    EnvironmentSetupResult prepareEnvironmentWithStoredPassword(ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword, Predicate<String> confirmation, boolean installationConfirmed,
            Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> systemConfirmation)
            throws SecretStoreException, SQLException, LinuxOperationException;
}
