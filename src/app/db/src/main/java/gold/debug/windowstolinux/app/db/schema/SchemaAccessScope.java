package gold.debug.windowstolinux.app.db.schema;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.appapi.db.SchemaAccess;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;

/** Owns preparation connections and optional SELECT-only validation credentials. / 持有准备阶段连接及可选的只读验证凭据。 */
public final class SchemaAccessScope implements SchemaAccess, AutoCloseable {
    /** Engine tracking actual physical connection ownership. / 跟踪实际物理连接归属的引擎。 */
    private final PlatformDatabase database;

    /** Exact physical and logical identity accepted by the operation. / 操作接纳的精确物理及逻辑身份。 */
    private final SchemaCoordinates coordinates;

    /** Operation-registered scope identity used to recover temporary users. / 操作登记的作用域身份，用于恢复临时用户。 */
    private final UUID scopeId;

    /** Whether a temporary SELECT-only identity owns this scope. / 此作用域是否持有临时只读身份。 */
    private final boolean readOnly;

    /** Prepared internal database identity, never returned through SchemaAccess. / 已准备内部数据库身份，不经 SchemaAccess 返回。 */
    private final String user;

    /** Private driver credential discarded on successful close. / 成功关闭后丢弃的私有驱动凭据。 */
    private String password;

    /** Whether normal release completed. / 正常释放是否完成。 */
    private boolean closed;

    /** Rejects new connections once cleanup starts, even while old connections remain. / 清理开始后拒绝新连接，即使旧连接仍残留。 */
    private boolean sealing;

    /**
     * Binds a scope after the caller has persisted its operation identity. / 调用方持久化操作身份后绑定作用域。
     * @param database owned engine / 所属引擎
     * @param identity registered logical generation / 已登记逻辑代次
     * @param scopeId caller-persisted unique scope identity / 调用方持久化的唯一作用域身份
     * @param readOnly whether to create a SELECT-only temporary user / 是否创建临时只读用户
     * @throws Exception if registration, credential or privilege preparation fails / 登记、凭据或权限准备失败时
     */
    public SchemaAccessScope(PlatformDatabase database, SchemaIdentity identity, UUID scopeId, boolean readOnly)
            throws Exception {
        this(database, new SchemaCoordinates(identity), scopeId, readOnly);
    }

    /** Binds a permanent or operation-registered intermediate scope. / 绑定正式或由操作登记的中间作用域。
     * @param database original engine / 原引擎
     * @param coordinates registered physical ownership / 已登记物理归属
     * @param scopeId persisted scope identity / 已持久作用域身份
     * @param readOnly whether only SELECT is granted / 是否只授予 SELECT
     * @throws Exception if the original target or access cannot be verified / 无法核验原目标或访问时 */
    public SchemaAccessScope(PlatformDatabase database, SchemaCoordinates coordinates, UUID scopeId, boolean readOnly)
            throws Exception {
        this.database = Objects.requireNonNull(database);
        this.coordinates = Objects.requireNonNull(coordinates);
        this.scopeId = Objects.requireNonNull(scopeId);
        this.readOnly = readOnly;
        verifyRegistration();
        user = readOnly ? readerUser(scopeId) : coordinates.databaseUser();
        if (readOnly) {
            var bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getEncoder().encodeToString(bytes);
            Arrays.fill(bytes, (byte) 0);
            prepareReader();
        } else
            password = database.schemaPassword(coordinates);
    }

    /**
     * Derives a recoverable temporary user name from a persisted scope identity. / 从已持久化作用域身份推导可恢复临时用户名。
     * @param scopeId persisted resource identity / 已持久化资源身份
     * @return exact temporary reader user / 精确临时读取用户
     */
    public static String readerUser(UUID scopeId) {
        return "r_" + scopeId.toString().replace("-", "");
    }

    /**
     * Validates generation and physical ownership before every new connection. / 每次新连接前验证代次和物理归属。
     * @throws SQLException if a stale, failed or mismatched identity is observed / 观察到过期、失败或不匹配身份时
     */
    private void verifyRegistration() throws SQLException {
        try (var c = database.openConnection()) {
            coordinates.requireRegistered(c, Set.of("PREPARING", "READY"));
            coordinates.requirePhysicalOwnership(c);
        }
    }

    /**
     * Creates an operation-scoped user with SELECT on existing tables and views only. / 创建操作作用域用户，仅授予已有表和视图的 SELECT 权限。
     * @throws SQLException if user creation or grants fail / 创建用户或授权失败时
     */
    private void prepareReader() throws SQLException {
        try (var c = database.openConnection()) {
            boolean created = false;
            try {
                try (var p = c.prepareStatement("CREATE USER " + quote(user) + " PASSWORD ?")) {
                    p.setString(1, password);
                    p.execute();
                    created = true;
                }
                var names = new ArrayList<String>();
                try (var p = c.prepareStatement(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type IN ('BASE TABLE','VIEW')")) {
                    p.setString(1, coordinates.physicalName());
                    try (var r = p.executeQuery()) {
                        while (r.next())
                            names.add(r.getString(1));
                    }
                }
                try (var statement = c.createStatement()) {
                    for (var table : names)
                        statement.execute("GRANT SELECT ON " + quote(coordinates.physicalName()) + "." + quote(table)
                                + " TO " + quote(user));
                }
            } catch (SQLException failure) {
                var sanitized = new SQLException("Read-only schema identity preparation failed", failure.getSQLState(),
                        failure.getErrorCode());
                if (created)
                    try (var s = c.createStatement()) {
                        s.execute("DROP USER " + quote(user));
                    } catch (SQLException cleanup) {
                        sanitized.addSuppressed(new SQLException("Temporary reader cleanup is uncertain",
                                cleanup.getSQLState(), cleanup.getErrorCode()));
                    }
                password = null;
                throw sanitized;
            }
        }
    }

    /**
     * Quotes one trusted identifier without interpreting embedded separators. / 引用一个受信标识符，不解释其中的分隔符。
     * @param identifier exact database identifier / 精确数据库标识符
     * @return safely quoted SQL identifier / 安全引用的 SQL 标识符
     */
    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Returns the immutable logical generation accepted by this operation. / 返回此操作接纳的不可变逻辑代次。
     * @return exact schema identity / 精确数据身份 */
    @Override
    public SchemaIdentity identity() {
        return coordinates.identity();
    }

    /**
     * Opens a non-administrator tracked connection after generation validation. / 验证代次后打开非管理员跟踪连接。
     * @return independent connection that the author closes / 由作者关闭的独立连接
     * @throws SQLException if the scope closed or registration/driver checks fail / 作用域已关闭或登记、驱动检查失败时
     */
    @Override
    public synchronized Connection openConnection() throws SQLException {
        if (sealing)
            throw new SQLException("Schema access scope is closed");
        verifyRegistration();
        return database.openInternalConnection(coordinates.physicalName(), user, password, scopeId.toString());
    }

    /** Reports actual connection occupancy for this operation scope. / 报告此操作作用域的实际连接占用。
     * @return number of connections not physically closed / 尚未物理关闭的连接数 */
    public int openConnections() {
        return database.openConnections(scopeId.toString());
    }

    /**
     * Revokes temporary rights only after all owned connections actually close. / 仅在所有所属连接实际关闭后撤销临时权限。
     * @throws SQLException if occupancy remains or rights revocation fails / 仍有占用或撤销权限失败时
     */
    @Override
    public synchronized void close() throws SQLException {
        if (closed)
            return;
        sealing = true;
        if (openConnections() != 0)
            throw new SQLException("Schema access still owns open connections");
        if (readOnly)
            try (var c = database.openConnection(); var s = c.createStatement()) {
                s.execute("DROP USER IF EXISTS " + quote(user));
            }
        password = null;
        closed = true;
    }
}
