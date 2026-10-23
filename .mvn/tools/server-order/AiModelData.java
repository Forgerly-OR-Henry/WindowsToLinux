package gold.debug.windowstolinux.app.db.init;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Exact schema-1 AI table reconstruction; no startup migration or network requests. */
final class AiModelData implements OfflineTableChange {
    static final String OLD_SHA = "c16c3a107cc70cef9237fcd79762132cb0d3504c73732ce83ab1d456c9808de6";
    private static final List<String> PURPOSES = List.of("language", "approval", "vision", "voice");
    private static final List<String> CAPABILITIES = List.of("text", "vision", "voice_file", "voice_realtime");
    private static final Set<String> REMOVED = Set.of("file_endpoint_url", "realtime_endpoint_url", "is_language_enabled",
        "is_approval_enabled", "is_vision_enabled", "is_voice_enabled");
    final byte[] oldDdl, newDdl;
    final String newSha;

    AiModelData() throws Exception {
        oldDdl = ServerOrderData.resource("/server-order-after.sql");
        newDdl = ServerOrderData.resource("/gold/debug/windowstolinux/app/db/schema/platform-v1.sql");
        newSha = ServerOrderData.sha(newDdl);
        if (!OLD_SHA.equals(ServerOrderData.sha(oldDdl))) throw new SQLException("Unrecognized prior AI schema");
        String oldText = new String(oldDdl, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String newText = new String(newDdl, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!outsideAi(oldText).equals(outsideAi(newText))) throw new SQLException("DDL changed outside the AI table");
    }

    @Override public String oldSha() { return OLD_SHA; }
    @Override public String newSha() { return newSha; }

    private static String outsideAi(String ddl) {
        int from = ddl.indexOf("CREATE TABLE ai_model\n");
        int to = ddl.indexOf("CREATE TABLE browser_recovery_task\n", from);
        if (from < 0 || to < 0) throw new IllegalArgumentException("Missing AI table boundary");
        return ddl.substring(0, from) + ddl.substring(to);
    }

    @Override public void rebuild(Connection connection, UUID generation, OfflineSchemaMaintenance.Hook hook) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(
            "SELECT database_generation,shutdown_state FROM app.app_state WHERE singleton_id=1")) {
            if (!row.next() || !generation.equals(row.getObject(1, UUID.class)) || !"CLEAN".equals(row.getString(2)))
                throw new SQLException("A clean original generation is required");
        }
        ServerOrderData.history(connection, OLD_SHA);
        PlatformSchemaList.validate(connection, oldDdl);
        var retained = ServerOrderData.snapshot(connection, Set.of("app.ai_model", "app.desktop_preference"), true);
        var old = records(connection);
        var preferences = preferences(connection);
        var expectedPreferences = new TreeMap<>(preferences);
        var expected = split(old, expectedPreferences);
        var text = new String(newDdl, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String create = text.substring(text.indexOf("CREATE TABLE ai_model\n"), text.indexOf("CREATE TABLE browser_recovery_task\n"));
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE app.ai_model RENAME TO ai_model_before");
            statement.execute(create);
        }
        insert(connection, expected);
        try (var statement = connection.createStatement()) { statement.execute("DROP TABLE app.ai_model_before"); }
        for (var key : preferences.keySet()) if (!expectedPreferences.containsKey(key)) {
            try (var delete = connection.prepareStatement("DELETE FROM app.desktop_preference WHERE preference_key=?")) {
                delete.setString(1, key);
                delete.executeUpdate();
            }
        }
        for (var entry : expectedPreferences.entrySet()) if (!Objects.equals(preferences.get(entry.getKey()), entry.getValue())) {
            try (var update = connection.prepareStatement("MERGE INTO app.desktop_preference(preference_key,preference_value) KEY(preference_key) VALUES(?,?)")) {
                update.setString(1, entry.getKey());
                update.setString(2, entry.getValue());
                update.executeUpdate();
            }
        }
        hook.at("after-alter");
        validate(connection, expected, expectedPreferences, retained);
        connection.setAutoCommit(false);
        try (var update = connection.prepareStatement("UPDATE app.app_schema_history SET script_sha256=? WHERE schema_version=1 AND state='READY' AND script_sha256=?")) {
            update.setString(1, newSha);
            update.setString(2, OLD_SHA);
            if (update.executeUpdate() != 1) throw new SQLException("Schema digest conflict");
            connection.commit();
        } catch (Exception failure) { connection.rollback(); throw failure; }
        finally { connection.setAutoCommit(true); }
        ServerOrderData.history(connection, newSha);
        validate(connection, expected, expectedPreferences, retained);
        System.out.println("VALIDATED ai_model records " + old.size() + " -> " + expected.size()
            + "; retained tables " + retained.data().size() + "; unrelated rows and Mod metadata unchanged");
    }

    private void validate(Connection connection, List<Map<String, Object>> expected, Map<String, String> preferences,
                          ServerOrderData.Snapshot retained) throws Exception {
        PlatformSchemaList.validate(connection, newDdl);
        if (!records(connection).equals(expected) || !preferences(connection).equals(preferences)
            || !retained.equals(ServerOrderData.snapshot(connection, Set.of("app.ai_model", "app.desktop_preference"), true)))
            throw new SQLException("A retained record, credential, task or schema changed");
    }

    static List<Map<String, Object>> records(Connection connection) throws SQLException {
        var values = new ArrayList<Map<String, Object>>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM app.ai_model ORDER BY display_order")) {
            while (rows.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                    row.put(rows.getMetaData().getColumnLabel(i), rows.getObject(i));
                values.add(Collections.unmodifiableMap(row));
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, String> preferences(Connection connection) throws SQLException {
        var result = new TreeMap<String, String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT preference_key,preference_value FROM app.desktop_preference")) {
            while (rows.next()) result.put(rows.getString(1), rows.getString(2));
        }
        return result;
    }

    static List<Map<String, Object>> split(List<Map<String, Object>> old, Map<String, String> preferences) {
        var result = new ArrayList<Map<String, Object>>();
        var ids = new HashSet<String>();
        old.forEach(row -> ids.add((String) row.get("model_id")));
        var endpoints = new HashMap<String, LinkedHashMap<String, Map<String, Object>>>();
        for (var source : old) {
            String original = (String) source.get("model_id");
            var addresses = new LinkedHashSet<String>();
            for (String key : List.of("endpoint_url", "file_endpoint_url", "realtime_endpoint_url")) {
                String address = endpoint(source, key);
                if (!address.isEmpty()) addresses.add(address);
            }
            if (addresses.isEmpty()) throw new IllegalArgumentException("A legacy model has no endpoint");
            var group = new LinkedHashMap<String, Map<String, Object>>();
            endpoints.put(original, group);
            int number = 0;
            for (String address : addresses) {
                String id = original;
                if (number++ != 0) {
                    int suffix = number;
                    do { id = original.substring(0, Math.min(45, original.length())) + "-endpoint-" + suffix++; }
                    while (!ids.add(id));
                }
                var row = new LinkedHashMap<>(source);
                REMOVED.forEach(row::remove);
                row.put("model_id", id);
                row.put("endpoint_url", address);
                row.put("display_order", result.size());
                PURPOSES.forEach(purpose -> row.put(purpose + "_call_order", null));
                for (String capability : CAPABILITIES) {
                    String matching = endpoint(source, switch (capability) {
                        case "voice_file" -> "file_endpoint_url";
                        case "voice_realtime" -> "realtime_endpoint_url";
                        default -> "endpoint_url";
                    });
                    if (!address.equals(matching)) {
                        row.put(capability + "_verified_revision", null);
                        row.put(capability + "_state", "UNVERIFIED");
                        row.put(capability + "_reason", "");
                        row.put(capability + "_verified_at", null);
                    }
                }
                for (String prefix : List.of("ai_model_reasoning_effort_", "ai_model_protocol_", "ai_model_output_limit_")) {
                    String value = preferences.get(prefix + original.replace('-', '_'));
                    if (value != null) preferences.put(prefix + id.replace('-', '_'), value);
                }
                String tool = "ai_model_tool_check_" + id.replace('-', '_');
                if (!address.equals(endpoint(source, "endpoint_url"))) preferences.remove(tool);
                group.put(address, row);
                result.add(row);
            }
        }
        for (String purpose : PURPOSES) {
            int order = 0;
            var sources = old.stream().filter(row -> Integer.valueOf(1).equals(row.get("is_" + purpose + "_enabled"))
                && row.get(purpose + "_call_order") != null)
                .sorted(Comparator.comparingInt(row -> ((Number) row.get(purpose + "_call_order")).intValue())).toList();
            for (var source : sources) {
                var group = endpoints.get((String) source.get("model_id"));
                var target = new LinkedHashSet<Map<String, Object>>();
                if (purpose.equals("voice")) {
                    for (String column : List.of("file_endpoint_url", "realtime_endpoint_url")) {
                        var row = group.get(endpoint(source, column));
                        if (row != null) target.add(row);
                    }
                } else {
                    var row = group.get(endpoint(source, "endpoint_url"));
                    if (row != null) target.add(row);
                }
                if (target.isEmpty()) target.add(group.values().iterator().next());
                for (var row : target) row.put(purpose + "_call_order", order++);
            }
        }
        return List.copyOf(result);
    }

    private static String endpoint(Map<String, Object> row, String column) {
        String value = (String) row.get(column);
        if (value == null || value.isEmpty()) return "";
        var uri = java.net.URI.create(value);
        if (uri.getHost() == null || !Set.of("http", "https", "ws", "wss").contains(uri.getScheme()))
            throw new IllegalArgumentException("Invalid legacy endpoint");
        // Freeze the exact URL formerly sent by the realtime transport; runtime no longer appends a query.
        if (column.equals("realtime_endpoint_url") && uri.getRawQuery() == null) return value + "?intent=transcription";
        return value;
    }

    private static void insert(Connection connection, List<Map<String, Object>> rows) throws SQLException {
        for (var row : rows) {
            String columns = String.join(",", row.keySet());
            String placeholders = String.join(",", Collections.nCopies(row.size(), "?"));
            try (var insert = connection.prepareStatement("INSERT INTO app.ai_model(" + columns + ") VALUES(" + placeholders + ")")) {
                int index = 1;
                for (var value : row.values()) insert.setObject(index++, value);
                insert.executeUpdate();
            }
        }
    }
}
