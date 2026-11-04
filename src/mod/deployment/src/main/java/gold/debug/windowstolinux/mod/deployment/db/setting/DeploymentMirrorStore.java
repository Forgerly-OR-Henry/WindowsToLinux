package gold.debug.windowstolinux.mod.deployment.db.setting;

import java.sql.*;
import java.util.Objects;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.db.transaction.DeploymentStorageTransaction;
import gold.debug.windowstolinux.mod.deployment.service.mirror.MirrorSettings;

/** One atomic JSON preference in a separate namespace, on the original task/database capability. */
public final class DeploymentMirrorStore {
    public static final String KEY = "deployment.mirrors.settings.v1";
    private final ModDatabase database;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public DeploymentMirrorStore(ModDatabase database) { this.database = Objects.requireNonNull(database); }
    public MirrorSettings read(TaskContext task) throws SQLException, ApiException {
        DeploymentSchemaIdentity.requireIdentity(database.identity());
        var result = new MirrorSettings[1];
        var connection = database.openConnection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            var stored = load(connection);
            result[0] = stored == null ? MirrorSettings.defaults() : stored;
        });
        return result[0];
    }
    public void save(TaskContext task, MirrorSettings settings) throws SQLException, ApiException {
        Objects.requireNonNull(settings);
        String json = encode(settings);
        DeploymentSchemaIdentity.requireIdentity(database.identity());
        var connection = database.openConnection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            lock(connection);
            try (var statement = connection.prepareStatement(
                "MERGE INTO preference(preference_key,preference_value) KEY(preference_key) VALUES(?,?)")) {
                statement.setString(1, KEY); statement.setString(2, json); statement.executeUpdate();
            }
        });
    }

    /** Compare-and-save consent without overwriting a newer UI selection. */
    public boolean saveConsent(TaskContext task, MirrorSettings expected, MirrorSettings consented)
            throws SQLException, ApiException {
        Objects.requireNonNull(expected); Objects.requireNonNull(consented);
        String json = encode(consented);
        DeploymentSchemaIdentity.requireIdentity(database.identity());
        var saved = new boolean[1];
        var connection = database.openConnection(task);
        DeploymentStorageTransaction.execute(connection, () -> {
            lock(connection);
            var stored = load(connection);
            if (!(stored == null ? MirrorSettings.defaults() : stored).equals(expected)) return;
            String sql = stored == null
                ? "INSERT INTO preference(preference_key,preference_value) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM preference WHERE preference_key=?)"
                : "UPDATE preference SET preference_value=? WHERE preference_key=?";
            try (var statement = connection.prepareStatement(sql)) {
                if (stored == null) {
                    statement.setString(1, KEY); statement.setString(2, json); statement.setString(3, KEY);
                } else { statement.setString(1, json); statement.setString(2, KEY); }
                saved[0] = statement.executeUpdate() == 1;
            }
        });
        return saved[0];
    }

    private static String encode(MirrorSettings settings) throws SQLException {
        try {
            String json = JSON.writeValueAsString(settings);
            if (json.length() > 262_144) throw new SQLException("Mirror preference exceeds size limit");
            return json;
        } catch (JsonProcessingException e) { throw new SQLException("Cannot encode mirror preference", e); }
    }

    private static MirrorSettings load(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT preference_value FROM preference WHERE preference_key=?")) {
            statement.setString(1, KEY);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                var json = rows.getString(1);
                if (json.length() > 262_144) throw new SQLException("Mirror preference exceeds size limit");
                try { return JSON.readValue(json, MirrorSettings.class); }
                catch (JsonProcessingException | IllegalArgumentException e) { throw new SQLException("Invalid stored mirror preference", e); }
            }
        }
    }

    private static void lock(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
            "MERGE INTO preference(preference_key,preference_value) KEY(preference_key) VALUES('deployment.mirrors.guard.v1','1')")) {
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement(
            "SELECT preference_value FROM preference WHERE preference_key='deployment.mirrors.guard.v1' FOR UPDATE");
             var rows = statement.executeQuery()) {
            if (!rows.next() || !rows.getString(1).equals("1")) throw new SQLException("Mirror preference lock unavailable");
        }
    }
}
