package gold.debug.windowstolinux.app.db.init;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** One pinned schema change with complete row preservation and independent formal metadata checks. */
final class ServerOrderData implements OfflineTableChange {
    static final String OLD_SHA = "b11e78af458ee53f029d5815b6990573a898e2957fe913b245025419890c96e0";

    static final String INSERTION = "    display_order             INTEGER           NOT NULL UNIQUE CHECK (display_order >= 0),\n";

    private static final Map<String, String> MOD_METADATA = Map.of(
        "tables", "table_schema,table_name,table_type",
        "columns", "table_schema,table_name,column_name,ordinal_position,data_type,is_nullable,character_maximum_length,numeric_precision,numeric_scale,datetime_precision,column_default,is_identity,identity_generation,domain_schema,domain_name",
        "table_constraints", "constraint_schema,constraint_name,constraint_type,table_schema,table_name",
        "check_constraints", "constraint_schema,constraint_name,check_clause",
        "key_column_usage", "constraint_schema,constraint_name,table_schema,table_name,column_name,ordinal_position,position_in_unique_constraint",
        "referential_constraints", "constraint_schema,constraint_name,unique_constraint_schema,unique_constraint_name,match_option,update_rule,delete_rule",
        "indexes", "table_schema,table_name,index_schema,index_name,index_type_name",
        "index_columns", "table_schema,table_name,index_schema,index_name,column_name,ordinal_position,ordering_specification");

    final byte[] oldDdl;

    final byte[] newDdl;

    final String newSha;

    ServerOrderData() throws Exception {
        oldDdl = resource("/server-order-before.sql");
        newDdl = resource("/server-order-after.sql");
        newSha = sha(newDdl);
        var oldText = new String(oldDdl, StandardCharsets.UTF_8).replace("\r\n", "\n");
        var newText = new String(newDdl, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String marker = "    host                      CHARACTER VARYING NOT NULL,\n";
        if (!sha(oldDdl).equals(OLD_SHA) || oldText.indexOf(marker) != oldText.lastIndexOf(marker)
            || !newText.equals(oldText.replace(marker, INSERTION + marker)))
            throw new IOException("The pinned old and new formal DDL differ outside the approved server column");
        if (!org.h2.engine.Constants.VERSION.equals("2.5.250"))
            throw new IOException("Only the pinned H2 2.5.250 engine is supported");
    }

    @Override public String oldSha() { return OLD_SHA; }
    @Override public String newSha() { return newSha; }
    @Override public void rebuild(Connection connection, UUID generation, OfflineSchemaMaintenance.Hook hook) throws Exception {
        preflight(connection, generation);
        var before = snapshot(connection);
        var ids = ids(connection);
        alter(connection, ids);
        hook.at("after-alter");
        validateNew(connection, ids, before);
        commitDigest(connection);
        history(connection, newSha);
        validateNew(connection, ids, before);
    }

    static byte[] resource(String name) throws IOException {
        try (var input = ServerOrderData.class.getResourceAsStream(name)) {
            if (input == null)
                throw new IOException("Required formal schema reference is absent");
            return input.readAllBytes();
        }
    }

    static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    static Connection open(java.nio.file.Path root, DatabaseBootstrap material) throws SQLException {
        var connection = DriverManager.getConnection("jdbc:h2:" + root.resolve("windowstolinux")
            + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;LOCK_TIMEOUT=5000;WRITE_DELAY=0"
            + ";TRACE_LEVEL_FILE=0;IFEXISTS=TRUE", "app_internal", material.password());
        try {
            connection.setSchema("app");
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    void preflight(Connection connection, UUID generation) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT database_generation,shutdown_state FROM app.app_state WHERE singleton_id=1")) {
            if (!rows.next() || !generation.equals(rows.getObject(1, UUID.class)) || !"CLEAN".equals(rows.getString(2)))
                throw new SQLException("The original generation must be cleanly shut down before maintenance");
        }
        history(connection, OLD_SHA);
        PlatformSchemaList.validate(connection, oldDdl);
    }

    static void history(Connection connection, String digest) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT schema_version,script_sha256,state FROM app.app_schema_history")) {
            if (!rows.next() || rows.getInt(1) != 1 || !digest.equals(rows.getString(2))
                || !"READY".equals(rows.getString(3)) || rows.next())
                throw new SQLException("Only the exact pinned READY schema-1 history is accepted");
        }
    }

    static List<String> ids(Connection connection) throws SQLException {
        var ids = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT server_profile_id FROM app.server_profile ORDER BY server_profile_id")) {
            while (rows.next()) {
                var id = rows.getString(1);
                if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,62}"))
                    throw new SQLException("An existing server identity is outside the stored profile contract");
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    static void alter(Connection connection, List<String> ids) throws SQLException {
        var expression = new StringBuilder(ids.isEmpty() ? "0" : "CASE server_profile_id");
        for (int index = 0; index < ids.size(); index++)
            expression.append(" WHEN '").append(ids.get(index)).append("' THEN ").append(index);
        if (!ids.isEmpty())
            expression.append(" ELSE NULL END");
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE app.server_profile ADD COLUMN display_order INTEGER NOT NULL UNIQUE "
                + "CHECK (display_order >= 0) USING " + expression + " BEFORE host");
        }
    }

    void validateNew(Connection connection, List<String> expectedIds, Snapshot before) throws Exception {
        PlatformSchemaList.validate(connection, newDdl);
        var actual = new ArrayList<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT server_profile_id,display_order FROM app.server_profile ORDER BY display_order")) {
            while (rows.next()) {
                if (rows.getInt(2) != actual.size())
                    throw new SQLException("The rebuilt order is not contiguous");
                actual.add(rows.getString(1));
            }
        }
        if (!actual.equals(expectedIds) || !before.equals(snapshot(connection)))
            throw new SQLException("Rebuilding changed a retained row or a Mod schema definition");
    }

    void commitDigest(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("UPDATE app.app_schema_history SET script_sha256=? "
            + "WHERE schema_version=1 AND state='READY' AND script_sha256=?")) {
            statement.setString(1, newSha);
            statement.setString(2, OLD_SHA);
            if (statement.executeUpdate() != 1)
                throw new SQLException("The formal schema digest compare-and-set failed");
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static Snapshot snapshot(Connection connection) throws Exception {
        return snapshot(connection, Set.of(), false);
    }

    static Snapshot snapshot(Connection connection, Set<String> excluded, boolean retainServerOrder) throws Exception {
        var data = new TreeMap<String, List<String>>();
        try (var statement = connection.createStatement(); var tables = statement.executeQuery(
            "SELECT table_schema,table_name FROM information_schema.tables WHERE table_type='BASE TABLE' "
                + "AND table_schema<>'information_schema' ORDER BY table_schema,table_name")) {
            while (tables.next()) {
                String schema = tables.getString(1), table = tables.getString(2);
                if (excluded.contains(schema + "." + table)) continue;
                var columns = new ArrayList<String>();
                try (var metadata = connection.prepareStatement("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name=? ORDER BY ordinal_position")) {
                    metadata.setString(1, schema);
                    metadata.setString(2, table);
                    try (var names = metadata.executeQuery()) {
                        while (names.next()) {
                            var name = names.getString(1);
                            if (!(schema.equals("app") && (!retainServerOrder && table.equals("server_profile") && name.equals("display_order")
                                || table.equals("app_schema_history") && name.equals("script_sha256"))))
                                columns.add(quote(name));
                        }
                    }
                }
                data.put(schema + "." + table, rows(connection,
                    "SELECT " + String.join(",", columns) + " FROM " + quote(schema) + "." + quote(table)));
            }
        }
        var metadata = new TreeMap<String, List<String>>();
        for (var table : MOD_METADATA.keySet()) {
            String schemaColumn = switch (table) {
                case "check_constraints", "key_column_usage", "referential_constraints", "table_constraints" ->
                    "constraint_schema";
                case "indexes", "index_columns" -> "index_schema";
                default -> "table_schema";
            };
            metadata.put(table, rows(connection, "SELECT " + MOD_METADATA.get(table) + " FROM information_schema." + table + " WHERE "
                + schemaColumn + " NOT IN ('app','information_schema')"));
        }
        var principals = new ArrayList<String>();
        try (var statement = connection.createStatement(); var script = statement.executeQuery("SCRIPT NODATA")) {
            while (script.next()) {
                var line = script.getString(1);
                if (line.startsWith("CREATE USER ") || line.startsWith("CREATE ROLE ") || line.startsWith("GRANT ")) {
                    var bytes = line.getBytes(StandardCharsets.UTF_8);
                    principals.add(sha(bytes));
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
        principals.sort(String::compareTo);
        metadata.put("principals-and-grants", List.copyOf(principals));
        return new Snapshot(Map.copyOf(data), Map.copyOf(metadata));
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static List<String> rows(Connection connection, String query) throws Exception {
        var result = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            int count = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                var digest = MessageDigest.getInstance("SHA-256");
                for (int column = 1; column <= count; column++) {
                    var type = rows.getMetaData().getColumnType(column);
                    byte[] value = switch (type) {
                        case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> rows.getBytes(column);
                        default -> {
                            var text = rows.getString(column);
                            yield text == null ? null : text.getBytes(StandardCharsets.UTF_8);
                        }
                    };
                    digest.update(java.nio.ByteBuffer.allocate(8).putLong(value == null ? -1 : value.length).array());
                    if (value != null) {
                        digest.update(value);
                        Arrays.fill(value, (byte) 0);
                    }
                }
                result.add(HexFormat.of().formatHex(digest.digest()));
            }
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    record Snapshot(Map<String, List<String>> data, Map<String, List<String>> modMetadata) {
    }
}
