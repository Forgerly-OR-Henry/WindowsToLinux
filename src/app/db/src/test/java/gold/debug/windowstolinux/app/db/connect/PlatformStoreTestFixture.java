package gold.debug.windowstolinux.app.db.connect;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.security.StoredSecret;
import gold.debug.windowstolinux.app.db.security.StoredSecurityProfile;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;

/** Supplies only H2 engine, credential rows and complete database-unit task admission for these repository tests. / 仅为这些仓库测试提供H2引擎、凭据行及完整数据库单元任务准入。 */
public final class PlatformStoreTestFixture {
    /** Deterministic test protection, without claiming Windows DPAPI verification. / 确定性测试保护，不声称验证Windows DPAPI。 */
    private static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        /** Copies and masks fixture bytes. / 复制并遮蔽夹具字节。
         * @param clear original bytes / 原字节
         * @return independently owned fixture protection / 独立持有的夹具保护 */
        @Override
        public byte[] protect(byte[] clear) {
            var protectedBytes = clear.clone();
            for (int i = 0; i < protectedBytes.length; i++)
                protectedBytes[i] ^= 0x35;
            return protectedBytes;
        }

        /** Recovers an independent fixture copy. / 恢复独立夹具副本。
         * @param encrypted fixture-protected bytes / 夹具保护的字节
         * @return independent recovered bytes / 独立恢复的字节 */
        @Override
        public byte[] unprotect(byte[] encrypted) {
            return protect(encrypted);
        }
    };

    /** Prevents construction of fixture utilities. / 禁止构造夹具工具。 */
    private PlatformStoreTestFixture() {
    }

    /** Opens the actual owned platform engine without bypassing schema initialization. / 打开实际所属平台引擎，不绕过Schema初始化。
     * @param directory isolated data directory / 隔离数据目录
     * @return persistence owning the engine / 持有引擎的持久层
     * @throws Exception if bootstrap fails / 引导失败时 */
    public static PlatformStorage open(Path directory) throws Exception {
        return new PlatformStorage(PlatformDatabase.open(directory, PROTECTION));
    }

    /** Creates only valid database metadata needed by foreign-key references. / 仅创建外键引用所需的有效数据库元数据。
     * @param persistence original platform repositories / 原平台仓库
     * @param id exact credential reference / 精确凭据引用
     * @throws SQLException if repository initialization fails / 仓库初始化失败时 */
    public static void credential(PlatformStorage persistence, String id) throws SQLException {
        if (persistence.security().profile().isEmpty())
            persistence.security().initialize(new StoredSecurityProfile(false, "fixture-verifier", new byte[48],
                "AES256_GCM_V1", new byte[12], "ARGON2ID_V1", new byte[16], "fixture-parameters", 1));
        if (!persistence.security().credentialIds().contains(id))
            try (var credential = new StoredSecret(id, "PLAIN_V1", new byte[]{42}, null)) {
                persistence.security().save(credential, false);
            }
    }

    /** Enqueues a complete original root task; the test must explicitly start or reconcile it. / 入队完整原根任务，测试必须明确启动或核对它。
     * @param persistence original platform engine / 原平台引擎
     * @param owner exact root owner / 精确根所有者
     * @param type actual host protocol / 实际宿主协议
     * @param server requested saved server / 请求的已存服务器
     * @return original durable identity / 原持久身份
     * @throws SQLException if complete admission fails / 完整准入失败时 */
    public static UUID queue(PlatformStorage persistence, String owner, String type, String server) throws SQLException {
        return new TaskLedger(persistence.database()).enqueue(UUID.randomUUID(), owner, "1.0.0",
            new TaskRequest(type, "Repository fixture", List.of(server), true),
            List.of(new TaskResource("MOD", owner, owner, false),
                new TaskResource("SERVER", server, owner, true)));
    }
}
