package gold.debug.windowstolinux.app.db.schema;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** Rechecks persisted table constraints using the same restricted reader as author validation. / 使用与作者验证相同的受限读取身份复核持久表约束。 */
public final class SchemaValidation {
    /** Prevents construction of the stateless validator. / 禁止构造无状态验证器。 */
    private SchemaValidation() {
    }

    /** Checks actual rows, including constraints originally added with NOCHECK. / 检查实际行，包括最初通过 NOCHECK 添加的约束。
     * @param access operation-owned SELECT-only scope / 操作所属只读作用域
     * @return structural validation receipt, not a business-data fingerprint / 结构验证凭据，不是业务数据指纹
     * @throws SQLException if a constraint fails or validation cannot be completed / 约束失败或无法完成验证时 */
    public static String validate(SchemaAccessScope access) throws SQLException {
        try (var c = access.openConnection()) {
            String schema = c.getSchema();
            var evidence = new StringBuilder("WTL-SCHEMA-VALIDATION-1\n").append(schema).append('\n');
            try (var p = c.prepareStatement(
                    "SELECT t.table_name,c.column_name,c.is_nullable,c.data_type,c.domain_schema,c.domain_name FROM information_schema.tables t JOIN information_schema.columns c ON c.table_schema=t.table_schema AND c.table_name=t.table_name WHERE t.table_schema=? AND t.table_type='BASE TABLE' ORDER BY t.table_name,c.ordinal_position")) {
                p.setString(1, schema);
                try (var rows = p.executeQuery()) {
                    while (rows.next()) {
                        evidence.append(rows.getString(1)).append('\0').append(rows.getString(2)).append('\0')
                                .append(rows.getString(3)).append('\0').append(rows.getString(4)).append('\n');
                        if ("NO".equals(rows.getString(3)))
                            noRows(c, "SELECT 1 FROM " + qualified(schema, rows.getString(1)) + " WHERE "
                                    + SchemaAccessScope.quote(rows.getString(2)) + " IS NULL");
                        if (rows.getString(6) != null) {
                            if (!schema.equals(rows.getString(5)))
                                throw new SQLException("Column domain leaves its owner schema", "23000");
                            String domain = qualified(rows.getString(5), rows.getString(6));
                            evidence.append(domain).append('\n');
                            try (var s = c.createStatement();
                                    var values = s.executeQuery(
                                            "SELECT CAST(" + SchemaAccessScope.quote(rows.getString(2)) + " AS "
                                                    + domain + ") FROM " + qualified(schema, rows.getString(1)))) {
                                while (values.next())
                                    values.getObject(1);
                            }
                        }
                    }
                }
            }
            try (var p = c.prepareStatement(
                    "SELECT t.constraint_name,t.table_name,t.constraint_type,t.enforced,t.nulls_distinct,k.check_clause FROM information_schema.table_constraints t LEFT JOIN information_schema.check_constraints k ON k.constraint_schema=t.constraint_schema AND k.constraint_name=t.constraint_name WHERE t.table_schema=? ORDER BY t.constraint_name")) {
                p.setString(1, schema);
                try (var rows = p.executeQuery()) {
                    while (rows.next()) {
                        String name = rows.getString(1), table = qualified(schema, rows.getString(2)),
                                type = rows.getString(3);
                        if (!"YES".equals(rows.getString(4)))
                            throw new SQLException("Target contains a disabled constraint", "23000");
                        String query;
                        switch (type) {
                            case "CHECK" -> query = "SELECT 1 FROM " + table + " WHERE NOT (" + rows.getString(6) + ")";
                            case "PRIMARY KEY", "UNIQUE" -> {
                                var columns = keyColumns(c, schema, name);
                                String distinct = rows.getString(5);
                                String filter = "NO".equals(distinct) || distinct == null
                                        ? ""
                                        : " WHERE " + columns.stream().map(v -> v + " IS NOT NULL")
                                                .collect(Collectors.joining("ALL".equals(distinct) ? " OR " : " AND "));
                                query = "SELECT 1 FROM " + table + filter + " GROUP BY " + String.join(",", columns)
                                        + " HAVING COUNT(*)>1";
                            }
                            case "FOREIGN KEY", "REFERENTIAL" -> query = foreignKey(c, schema, name, table);
                            default -> throw new SQLException("Unknown persisted constraint type");
                        }
                        noRows(c, query);
                        evidence.append(name).append('\0').append(type).append('\0').append(query).append('\n');
                    }
                }
            }
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(evidence.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
            }
        }
    }

    /** Reads the exact ordered key columns from H2 metadata. / 从 H2 元数据读取精确有序键列。
     * @param c restricted reader / 受限读取连接
     * @param schema target schema / 目标数据结构
     * @param name constraint name / 约束名
     * @return individually quoted columns / 逐项引用的列
     * @throws SQLException if metadata is absent or unreadable / 元数据缺失或不可读时 */
    private static List<String> keyColumns(Connection c, String schema, String name) throws SQLException {
        var columns = new ArrayList<String>();
        try (var p = c.prepareStatement(
                "SELECT column_name FROM information_schema.key_column_usage WHERE constraint_schema=? AND constraint_name=? ORDER BY ordinal_position")) {
            p.setString(1, schema);
            p.setString(2, name);
            try (var rows = p.executeQuery()) {
                while (rows.next())
                    columns.add(SchemaAccessScope.quote(rows.getString(1)));
            }
        }
        if (columns.isEmpty())
            throw new SQLException("Persisted key has no columns");
        return columns;
    }

    /** Builds an anti-join respecting referenced key order and SQL nullable foreign keys. / 按被引用键顺序和 SQL 可空外键构建反连接。
     * @param c restricted reader / 受限读取连接
     * @param schema target schema / 目标数据结构
     * @param name original constraint / 原约束
     * @param table fully quoted source table / 完整引用的源表
     * @return read-only violation query / 只读违规查询
     * @throws SQLException if key ownership or metadata is invalid / 键归属或元数据无效时 */
    private static String foreignKey(Connection c, String schema, String name, String table) throws SQLException {
        var equalities = new ArrayList<String>();
        var present = new ArrayList<String>();
        String referenced = null;
        try (var p = c.prepareStatement(
                "SELECT k.column_name,u.table_schema,u.table_name,u.column_name FROM information_schema.referential_constraints r JOIN information_schema.key_column_usage k ON k.constraint_schema=r.constraint_schema AND k.constraint_name=r.constraint_name JOIN information_schema.key_column_usage u ON u.constraint_schema=r.unique_constraint_schema AND u.constraint_name=r.unique_constraint_name AND u.ordinal_position=k.position_in_unique_constraint WHERE r.constraint_schema=? AND r.constraint_name=? ORDER BY k.ordinal_position")) {
            p.setString(1, schema);
            p.setString(2, name);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    if (!schema.equals(rows.getString(2)))
                        throw new SQLException("Foreign key leaves its owner schema", "23000");
                    referenced = qualified(schema, rows.getString(3));
                    String source = "s." + SchemaAccessScope.quote(rows.getString(1));
                    present.add(source + " IS NOT NULL");
                    equalities.add(source + "=r." + SchemaAccessScope.quote(rows.getString(4)));
                }
            }
        }
        if (referenced == null)
            throw new SQLException("Persisted foreign key metadata is absent");
        return "SELECT 1 FROM " + table + " s WHERE " + String.join(" AND ", present) + " AND NOT EXISTS(SELECT 1 FROM "
                + referenced + " r WHERE " + String.join(" AND ", equalities) + ")";
    }

    /** Rejects actual violating rows without emitting their contents. / 拒绝实际违规行，不输出其内容。
     * @param c restricted reader / 受限读取连接
     * @param query metadata-derived SELECT / 从元数据生成的 SELECT
     * @throws SQLException if a violation exists or the query fails / 有违规或查询失败时 */
    private static void noRows(Connection c, String query) throws SQLException {
        try (var s = c.createStatement()) {
            s.setMaxRows(1);
            try (var r = s.executeQuery(query)) {
                if (r.next())
                    throw new SQLException("Target data violates a persisted constraint", "23000");
            }
        }
    }

    /** Quotes a schema and table independently. / 分别引用数据结构和表。
     * @param schema exact schema / 精确数据结构
     * @param table exact table / 精确表
     * @return qualified identifier / 完整限定标识 */
    private static String qualified(String schema, String table) {
        return SchemaAccessScope.quote(schema) + "." + SchemaAccessScope.quote(table);
    }
}
