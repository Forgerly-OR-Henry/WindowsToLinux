package gold.debug.windowstolinux.app.db.persistence.connection;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.h2.tools.RunScript;

/** Compares only the APP schema's actual structure with the same pinned-engine formal DDL. / 仅使用同一固定引擎正式DDL比较APP数据结构的实际结构。 */
final class PlatformSchemaInventory {
    /** Stable structural queries excluding data rows, owners and generated constraint names. / 排除数据行、所有者及生成约束名的稳定结构查询。 */
    private static final List<String> QUERIES = List.of(
            "SELECT table_name,table_type FROM information_schema.tables WHERE table_schema='app'",
            "SELECT table_name,column_name,ordinal_position,data_type,is_nullable,character_maximum_length,numeric_precision,numeric_scale,datetime_precision,column_default,is_identity,identity_generation,domain_schema,domain_name FROM information_schema.columns WHERE table_schema='app'",
            "SELECT t.table_name,c.check_clause FROM information_schema.table_constraints t JOIN information_schema.check_constraints c ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name WHERE t.table_schema='app'",
            "SELECT t.table_name,t.constraint_type,LISTAGG(k.column_name,',') WITHIN GROUP(ORDER BY k.ordinal_position) FROM information_schema.table_constraints t JOIN information_schema.key_column_usage k ON k.constraint_schema=t.constraint_schema AND k.constraint_name=t.constraint_name WHERE t.table_schema='app' GROUP BY t.table_name,t.constraint_type,t.constraint_name",
            "SELECT t.table_name,k.column_name,p.table_schema,p.table_name,p.column_name,r.update_rule,r.delete_rule FROM information_schema.table_constraints t JOIN information_schema.referential_constraints r ON r.constraint_schema=t.constraint_schema AND r.constraint_name=t.constraint_name JOIN information_schema.key_column_usage k ON k.constraint_schema=t.constraint_schema AND k.constraint_name=t.constraint_name JOIN information_schema.key_column_usage p ON p.constraint_schema=r.unique_constraint_schema AND p.constraint_name=r.unique_constraint_name AND p.ordinal_position=k.position_in_unique_constraint WHERE t.table_schema='app'",
            "SELECT i.table_name,i.index_type_name,LISTAGG(k.column_name||':'||k.ordering_specification,',') WITHIN GROUP(ORDER BY k.ordinal_position) FROM information_schema.indexes i JOIN information_schema.index_columns k ON k.index_schema=i.index_schema AND k.index_name=i.index_name WHERE i.table_schema='app' GROUP BY i.table_name,i.index_type_name,i.index_name");

    /** Prevents construction of this fixed structural check. / 禁止构造固定结构检查。 */
    private PlatformSchemaInventory() { }

    /** Creates a short-lived independent DDL reference without touching stored application rows. / 创建短寿命独立DDL参照，不接触已存应用行。
     * @param actual actual privileged APP connection / 实际特权APP连接
     * @param ddl formal APP DDL bytes / 正式APP DDL字节
     * @throws SQLException if actual structure differs or either inventory is unreadable / 实际结构不同或任一清单不可读时 */
    static void validate(Connection actual, byte[] ddl) throws SQLException {
        try (var reference = DriverManager.getConnection("jdbc:h2:mem:platform-schema-" + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0", "sa", "")) {
            try (var statement = reference.createStatement()) {
                statement.execute("CREATE SCHEMA app");
            }
            reference.setSchema("app");
            RunScript.execute(reference, new java.io.StringReader(new String(ddl, StandardCharsets.UTF_8)));
            for (var query : QUERIES)
                if (!rows(actual, query).equals(rows(reference, query)))
                    throw gold.debug.windowstolinux.app.db.failure.DesktopPersistenceException.create(
                            gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType.SCHEMA_INCOMPATIBLE,
                            "Actual APP structure does not match the formal platform schema", null);
        }
    }

    /** Produces a sorted, length-delimited multiset while preserving null and duplicate structural facts. / 生成排序且带长度界定的多重集合，保留null及重复结构事实。
     * @param connection original read-only metadata source / 原只读元数据来源
     * @param query fixed structural query / 固定结构查询
     * @return complete canonical query evidence / 完整规范查询依据
     * @throws SQLException if metadata cannot be read completely / 元数据无法完整读取时 */
    private static List<String> rows(Connection connection, String query) throws SQLException {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                var row = new StringBuilder();
                for (int column = 1; column <= columns; column++) {
                    var value = rows.getString(column);
                    row.append(value == null ? -1 : value.length()).append(':');
                    if (value != null)
                        row.append(value);
                }
                result.add(row.toString());
            }
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }
}
