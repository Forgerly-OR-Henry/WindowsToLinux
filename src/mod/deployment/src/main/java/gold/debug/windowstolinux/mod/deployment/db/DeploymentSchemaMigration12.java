package gold.debug.windowstolinux.mod.deployment.db;

import java.io.InputStream;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.SchemaAccess;
import gold.debug.windowstolinux.appapi.db.SchemaMigration;

/** Copies validated schema-1 business rows to a separate schema-2 generation without inventing historical secret purposes. / 将结构1业务行复制到独立结构2代次，不臆造历史秘密用途。 */
public final class DeploymentSchemaMigration12 implements SchemaMigration {
    /** Creates the explicit one-step author migration. / 创建显式单步作者迁移。 */
    public DeploymentSchemaMigration12() {
    }

    /** Builds only a fresh target and leaves the read-only source untouched. / 只建立全新目标且不改只读来源。
     * @param source protected version-1 source / 受保护的版本1来源
     * @param target fresh version-2 target / 全新版本2目标
     * @throws Exception if source inventory, copy or constraints fail / 来源清单、复制或约束失败时 */
    @Override
    public void migrate(SchemaAccess source, SchemaAccess target) throws Exception {
        if (!DeploymentSchema.OWNER.equals(source.identity().ownerId())
                || !"1".equals(source.identity().schemaVersion()))
            throw new SQLException("Deployment migration requires original schema version 1");
        DeploymentSchema.requireIdentity(target.identity());
        try (var from = source.openConnection(); var to = target.openConnection()) {
            if (!tables(to).isEmpty())
                throw new SQLException("Deployment migration target must be empty");
            var inventory = inventory(from);
            try (var statement = to.createStatement()) {
                for (var command : DeploymentSchema.resource("schema-v2.sql").split(";"))
                    if (!command.isBlank())
                        statement.executeUpdate(command);
                for (var table : inventory)
                    statement.execute("ALTER TABLE " + table + " SET REFERENTIAL_INTEGRITY FALSE");
            }
            for (var table : inventory)
                copy(from, to, table);
            try (var statement = to.createStatement()) {
                for (var table : inventory)
                    statement.execute("ALTER TABLE " + table + " SET REFERENTIAL_INTEGRITY TRUE CHECK");
                statement.execute("ALTER TABLE deployment_agent_event ALTER COLUMN event_id RESTART WITH "
                        + Math.max(nextEvent(to), sourceNextEvent(from)));
            }
            if (!tables(to).equals(Set.copyOf(inventory)))
                throw new SQLException("Deployment migration target inventory differs");
        }
    }

    /** Validates the frozen source-1 columns before any target copy. / 在任何目标复制前校验冻结的来源1列。
     * @param from original read-only connection / 原只读连接
     * @return exact original table inventory / 精确原表清单
     * @throws Exception if the source is not the documented version / 来源并非文档版本时 */
    private static List<String> inventory(Connection from) throws Exception {
        var names = new ArrayList<String>();
        for (var query : DeploymentSchema.resource("validation-v1.sql").split(";")) {
            if (query.isBlank())
                continue;
            var table = query.substring(query.indexOf(" FROM ") + 6, query.indexOf(" WHERE "));
            try (var statement = from.createStatement(); var rows = statement.executeQuery(query)) {
                var declared = rows.getMetaData();
                try (var actualStatement = from.createStatement();
                        var actualRows = actualStatement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
                    var actual = actualRows.getMetaData();
                    if (declared.getColumnCount() != actual.getColumnCount())
                        throw new SQLException("Migration source columns differ: " + table);
                    for (int index = 1; index <= declared.getColumnCount(); index++)
                        if (!declared.getColumnName(index).equalsIgnoreCase(actual.getColumnName(index)))
                            throw new SQLException("Migration source column order differs: " + table);
                }
            }
            names.add(table);
        }
        if (!tables(from).equals(Set.copyOf(names)))
            throw new SQLException("Migration source table inventory differs");
        return List.copyOf(names);
    }

    /** Copies every original column and leaves only the newly added purpose NULL. / 复制全部原列，只将新加用途保留为NULL。
     * @param from protected source / 受保护来源
     * @param to independent target / 独立目标
     * @param table validated fixed table / 已验证固定表
     * @throws SQLException if any original row cannot be copied / 任一原行无法复制时 */
    private static void copy(Connection from, Connection to, String table) throws SQLException {
        try (var read = from.createStatement(); var rows = read.executeQuery("SELECT * FROM " + table)) {
            var metadata = rows.getMetaData();
            var columns = new ArrayList<String>();
            for (int index = 1; index <= metadata.getColumnCount(); index++)
                columns.add(metadata.getColumnName(index));
            var insert = "INSERT INTO " + table + "(" + String.join(",", columns) + ") VALUES("
                    + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
            try (var write = to.prepareStatement(insert)) {
                while (rows.next()) {
                    for (int index = 1; index <= columns.size(); index++)
                        if (metadata.getColumnType(index) == Types.BLOB) {
                            InputStream content = rows.getBinaryStream(index);
                            if (content == null)
                                write.setNull(index, Types.BLOB);
                            else
                                write.setBinaryStream(index, content);
                        } else
                            write.setObject(index, rows.getObject(index));
                    write.executeUpdate();
                }
            }
        }
    }

    /** Reads exact ordinary tables in the connection's own generation. / 读取连接自身代次的精确普通表。
     * @param connection owned generation / 所属代次
     * @return table names / 表名
     * @throws SQLException if metadata is unavailable / 元数据不可用时 */
    private static Set<String> tables(Connection connection) throws SQLException {
        var result = new HashSet<String>();
        try (var statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE'")) {
            statement.setString(1, connection.getSchema());
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    result.add(rows.getString(1));
            }
        }
        return result;
    }

    /** Restarts the sole generated identity above copied original events. / 将唯一自增身份重启到已复制原事件之后。
     * @param connection copied target / 已复制目标
     * @return next positive identity / 下一正身份
     * @throws SQLException if event state is unavailable / 事件状态不可用时 */
    private static long nextEvent(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COALESCE(MAX(event_id),0)+1 FROM deployment_agent_event")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Preserves the old generated sequence even when its highest row was deleted. / 即使最高旧行已删除，仍保留原自增序列进度。
     * @param source protected original generation / 受保护原代次
     * @return next original event identity / 原下一事件身份
     * @throws SQLException if identity metadata is unavailable / 身份元数据不可用时 */
    private static long sourceNextEvent(Connection source) throws SQLException {
        try (var statement = source.prepareStatement(
                "SELECT identity_base FROM information_schema.columns WHERE UPPER(table_schema)=UPPER(?) AND UPPER(table_name)='DEPLOYMENT_AGENT_EVENT' AND UPPER(column_name)='EVENT_ID'")) {
            statement.setString(1, source.getSchema());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject(1) == null)
                    throw new SQLException("Original deployment event identity is unavailable");
                return rows.getLong(1);
            }
        }
    }
}
