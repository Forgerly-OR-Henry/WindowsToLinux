package gold.debug.windowstolinux.app.db.execution.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;

/** Initializes only the current desktop schema; existing incompatible data is never rewritten. / 仅初始化当前桌面结构，不改写不兼容数据。 */
public final class DesktopSchemaInitializer {
    /** Current fresh database format. / 当前全新数据库格式。 */
    public static final int CURRENT_SCHEMA_VERSION = 21;

    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     */
    private DesktopSchemaInitializer() {
    }

    /** Creates an empty database or verifies the exact current structure. / 建立空库或校验当前完整结构。
     * @param connections scoped connection factory / 连接工厂
     * @throws SQLException when a database is incompatible or initialization fails / 数据库不兼容或初始化失败
     */
    public static void initialize(DesktopConnectionFactory connections) throws SQLException {
        Map<String, String> expected = definitions();
        try (Connection connection = connections.open(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                int version;
                try (var rows = statement.executeQuery("PRAGMA user_version")) {
                    rows.next();
                    version = rows.getInt(1);
                }
                Map<String, String> actual = readDefinitions(connection);
                if (version == 0 && actual.isEmpty()) {
                    for (String sql : expected.values()) {
                        statement.execute(sql);
                    }
                    statement.execute("PRAGMA user_version=" + CURRENT_SCHEMA_VERSION);
                } else if (version != CURRENT_SCHEMA_VERSION || !actual.equals(expected)) {
                    throw new SQLException("unsupported desktop database structure; a fresh database is required");
                }
                try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (rows.next())
                        throw new SQLException("desktop database foreign key validation failed");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    /** Reads canonical current-schema definitions from the bundled SQL resource. / 从内置 SQL 资源读取当前结构的标准定义。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private static Map<String, String> definitions() throws SQLException {
        try (var input = DesktopSchemaInitializer.class.getResourceAsStream("/schema/desktop.sql")) {
            if (input == null)
                throw new IOException("desktop schema resource missing");
            Map<String, String> definitions = new LinkedHashMap<>();
            for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                String normalized = normalize(sql);
                if (!normalized.isEmpty())
                    definitions.put(normalized.split(" ")[2], normalized);
            }
            return definitions;
        } catch (IOException failure) {
            throw new SQLException("desktop schema resource unavailable", failure);
        }
    }

    /** Reads user-defined schema objects for exact structure validation. / 读取用户定义结构对象以进行完整结构校验。
     * @param connection connection supplied to this boundary / 传入当前边界的connection值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private static Map<String, String> readDefinitions(Connection connection) throws SQLException {
        Map<String, String> definitions = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
                var rows = statement
                        .executeQuery("SELECT name,sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'")) {
            while (rows.next())
                definitions.put(rows.getString(1), normalize(rows.getString(2)));
        }
        return definitions;
    }

    /** Normalizes SQL whitespace for schema comparison. / 归一化 SQL 空白以比较结构。
     * @param sql sql supplied to this boundary / 传入当前边界的sql值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    private static String normalize(String sql) {
        return sql == null ? "" : sql.trim().replaceAll("\\s+", " ");
    }
}
