package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.execution.lifecycle.LifecycleOutcome;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import java.sql.SQLException;
import java.util.List;

/**
 * Narrow application operations required by the managed-applications page. / 受管应用页面所需的窄应用操作。
 */
public interface ManagedApplicationFacade {
    /**
     * Lists server profiles.
     * <p>列出服务器配置资料集合。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    List<gold.debug.windowstolinux.app.service.server.ServerProfile> listServerProfiles() throws SQLException;
    /**
     * Lists applications.
     * <p>列出应用集合。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    List<gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationSummary> listApplications() throws SQLException;
    /**
     * Persists application presentation.
     * <p>持久化应用展示。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param category category / 类别
     * @param accessUrl access url / 访问URL
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    void saveApplicationPresentation(String key, String name, String category, String accessUrl) throws SQLException;
    /**
     * Scans applications.
     * <p>扫描应用集合。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved application scan / 构造或解析得到的应用扫描
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationScan scanApplications(String serverId, char[] master,
            java.util.function.Predicate<String> confirmation) throws Exception;
    /**
     * Adopts a discovered application under the managed inventory after the required user confirmation.
     * <p>在取得所需用户确认后，将已发现应用接管到受管清单。
     *
     * @param scan scan / 扫描
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return adopt application text / 接管应用文本
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    String adoptApplication(gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationScan scan,
            gold.debug.windowstolinux.shared.model.lifecycle.DiscoveredApplication application, char[] master,
            java.util.function.Predicate<String> confirmation) throws Exception;
    /**
     * Executes application lifecycle.
     * <p>执行应用生命周期。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return constructed or resolved application lifecycle result / 构造或解析得到的应用生命周期结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationLifecycleResult executeApplicationLifecycle(String key,
            LifecycleAction action, char[] master, java.util.function.Predicate<String> confirmation) throws Exception;
    /**
     * Lists managed application summaries.
     * <p>列出受管应用摘要集合。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    List<ManagedApplicationSnapshot> listManagedApplicationSummaries() throws SQLException;

    /**
     * Executes persisted lifecycle with stored password.
     * <p>执行已持久化生命周期具有已存储密码。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return constructed or resolved lifecycle outcome / 构造或解析得到的生命周期结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    LifecycleOutcome executePersistedLifecycleWithStoredPassword(
            String applicationId, LifecycleAction action, char[] masterPassword)
            throws SecretStoreException, SQLException;
}
