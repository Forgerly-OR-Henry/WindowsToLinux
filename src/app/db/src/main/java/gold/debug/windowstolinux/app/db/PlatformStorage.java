package gold.debug.windowstolinux.app.db;

import java.sql.SQLException;
import java.util.Objects;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.ai.AiModelStore;
import gold.debug.windowstolinux.app.db.security.SecretRefStore;
import gold.debug.windowstolinux.app.db.security.SecurityStore;
import gold.debug.windowstolinux.app.db.server.ServerProfileStore;
import gold.debug.windowstolinux.app.db.server.ServerRefStore;
import gold.debug.windowstolinux.app.db.setting.DesktopPreferenceStore;
import gold.debug.windowstolinux.app.db.sshrecover.BrowserRecoveryStore;

/** Composes platform-owned configuration repositories over the protected H2 engine. / 在受保护 H2 引擎上装配平台所属配置仓库。 */
public final class PlatformStorage implements AutoCloseable {
    /** Engine owned until all caller connections close. / 调用方连接全部关闭前持续持有的引擎。 */
    private final PlatformDatabase database;

    /** Common scope for atomic credential and configuration writes. / 凭据和配置原子写入使用的共同作用域。 */
    private final StoreConnections connections;

    /** Master-password profile and protected credentials. / 主密码配置及受保护凭据。 */
    private final SecurityStore security;

    /** Server configuration and authenticated host trust. / 服务器配置及已认证主机信任。 */
    private final ServerProfileStore servers;

    /** Generation-owned business references to shared servers. / 共享服务器的代次所属业务引用。 */
    private final ServerRefStore serverReferences;

    /** Generation-owned business references to protected credentials. / 受保护凭据的代次所属业务引用。 */
    private final SecretRefStore credentialReferences;

    /** Model configuration and revision-bound capability evidence. / 模型配置及绑定修订的能力证据。 */
    private final AiModelStore aiProfiles;

    /** Small platform appearance and installation preferences. / 小型平台外观和安装偏好。 */
    private final DesktopPreferenceStore preferences;

    /** Independent rescue session journal. / 独立救援会话日志。 */
    private final BrowserRecoveryStore recovery;

    /**
     * Takes ownership of an already bootstrapped platform database. / 接管已完成引导的平台数据库。
     * @param database initialized engine whose lifetime is transferred here / 生命周期转交给此对象的已初始化引擎
     */
    public PlatformStorage(PlatformDatabase database) {
        this.database = Objects.requireNonNull(database);
        connections = new StoreConnections(database::openConnection);
        security = new SecurityStore(connections);
        servers = new ServerProfileStore(connections);
        serverReferences = new ServerRefStore(connections);
        credentialReferences = new SecretRefStore(connections);
        aiProfiles = new AiModelStore(connections);
        preferences = new DesktopPreferenceStore(connections);
        recovery = new BrowserRecoveryStore(connections);
    }

    /** Returns the internal engine owner for platform lifecycle coordination. / 返回供平台生命周期协调使用的内部引擎所有者。
     * @return owned platform database / 所属平台数据库 */
    public PlatformDatabase database() {
        return database;
    }

    /** Returns the platform security repository. / 返回平台安全仓库。
     * @return master-password and credential records / 主密码和凭据记录 */
    public SecurityStore security() {
        return security;
    }

    /** Returns server configuration without credential values. / 返回不含凭据值的服务器配置。
     * @return server repository / 服务器仓库 */
    public ServerProfileStore servers() {
        return servers;
    }

    /** Returns authoritative server reference transactions. / 返回权威服务器引用事务。
     * @return generation-owned reference repository / 代次所属引用仓库 */
    public ServerRefStore serverReferences() {
        return serverReferences;
    }

    /** Returns authoritative protected-credential association transactions. / 返回权威受保护凭据关联事务。
     * @return generation-owned credential references / 代次所属凭据引用 */
    public SecretRefStore credentialReferences() {
        return credentialReferences;
    }

    /** Returns model configuration and capability evidence. / 返回模型配置和能力证据。
     * @return model repository / 模型仓库 */
    public AiModelStore aiProfiles() {
        return aiProfiles;
    }

    /** Returns small platform preferences. / 返回小型平台偏好。
     * @return preference repository / 偏好仓库 */
    public DesktopPreferenceStore preferences() {
        return preferences;
    }

    /** Returns the existing transaction scope for entrance preferences. / 返回入口偏好使用的已有事务作用域。
     * @return navigation repository / 导航仓库 */
    public gold.debug.windowstolinux.app.db.setting.NavigationPreferenceStore navigation() {
        return new gold.debug.windowstolinux.app.db.setting.NavigationPreferenceStore(connections);
    }

    /** Returns the independent rescue journal. / 返回独立救援日志。
     * @return rescue repository / 救援仓库 */
    public BrowserRecoveryStore recovery() {
        return recovery;
    }

    /**
     * Commits related configuration and credential changes together. / 一并提交关联的配置和凭据变更。
     * @param work platform writes belonging to one operation / 属于同一操作的平台写入
     * @throws SQLException if work or transaction completion fails / 写入或事务完成失败时
     */
    public void transaction(StoreConnections.Work work) throws SQLException {
        connections.transaction(work);
    }

    /**
     * Closes the engine after every retained connection is actually released. / 在所有保留连接实际释放后关闭引擎。
     * @throws Exception if occupancy remains or engine shutdown fails / 仍有占用或引擎关闭失败时
     */
    @Override
    public void close() throws Exception {
        database.close();
    }
}
