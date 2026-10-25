package gold.debug.windowstolinux.mod.deployment.db;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseOrigin;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.db.transaction.DeploymentStorageTransaction;

/** Completes additive publication structures without replacing retained business data.
 * / 补齐发布新增结构，不替换留存业务数据。 */
public final class DeploymentPublicationUpgrade {
    private static final String KEY = "deployment-release-origin-upgrade";
    private static final List<String> PREVIOUS = List.of("application_id", "release_id", "execution_id",
        "release_sha256", "runtime_payload", "health_payload", "state", "created_at");

    private static final List<String> RECEIPT_COLUMNS = List.of("operation_id", "platform_task_id", "server_profile_id",
        "mode", "outcome", "application_id", "release_id", "release_sha256", "settlement_payload", "settled_at");

    private DeploymentPublicationUpgrade() {
    }

    /** Preserves existing releases and rejects missing provenance rather than inventing it.
     * / 保留已有发布，来源依据缺失时拒绝，绝不补造。
     * @param context original owned schema / 原所属结构
     * @param task authentic publication or recovery / 真实发布或恢复任务
     * @param recovery whether this is the platform's original reconciliation / 是否为平台原任务核对
     * @throws SQLException if structure or creating evidence differs / 结构或原创建依据不符时
     * @throws ApiException if checkpoint ownership is unavailable / 检查点归属不可用时
     * @throws java.io.IOException if the fixed schema resource is unavailable / 固定结构资源缺失时 */
    public static synchronized void prepare(ModContext context, TaskContext task, boolean recovery)
        throws SQLException, ApiException, java.io.IOException {
        try (var connection = context.db().openConnection(task)) {
            boolean receipts = receiptsPresent(connection);
            var columns = new ArrayList<String>();
            boolean present;
            boolean complete = false;
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT * FROM deployment_release WHERE 1=0")) {
                var metadata = rows.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    String column = metadata.getColumnName(index);
                    columns.add(column);
                    if (column.equals("origin_payload"))
                        complete = metadata.isNullable(index) == ResultSetMetaData.columnNoNulls;
                }
            }
            if (columns.contains("origin_payload") && columns.indexOf("origin_payload") != 3)
                throw new SQLException("Release origin column has an unexpected position");
            present = columns.remove("origin_payload");
            if (!columns.equals(PREVIOUS)) throw new SQLException("Release column inventory differs from the supported origin upgrade");
            var pending = context.tasks().findCheckpoint(task, KEY);
            if (pending.isEmpty() && recovery) pending = context.tasks().findRecoveryCheckpoint(task, KEY);
            if (complete && receipts && pending.isEmpty()) return;
            if (pending.isPresent()) {
                var snapshot = pending.orElseThrow().snapshot();
                var value = gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.parse(snapshot.payload(), 8192);
                gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.fields(value,
                    java.util.Set.of("formatVersion", "taskId", "phase"));
                if (!value.path("formatVersion").isInt() || value.path("formatVersion").intValue() != 1
                    || !task.id().toString().equals(value.path("taskId").asText())
                    || !java.util.Set.of("PREPARED", "COMPLETE").contains(value.path("phase").asText())
                    || snapshot.released() && (!complete || !receipts))
                    throw new SQLException("Release origin upgrade checkpoint conflicts with the current structure");
            }
            var origins = new LinkedHashMap<DeploymentReleaseRecord, String>();
            if (!complete) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM deployment_release")) {
                    while (rows.next()) {
                        var release = DeploymentReleaseStore.row(rows);
                        var execution = DeploymentExecutionStore.read(connection, release.executionId(), false)
                            .orElseThrow(() -> new SQLException("Release origin upgrade requires its original creating execution"));
                        if (!execution.businessCommitted() || execution.outcome().filter(TaskResult.SUCCEEDED::equals).isEmpty())
                            throw new SQLException("Release origin upgrade requires a committed successful creating execution");
                        String origin = DeploymentReleaseOrigin.encode(execution.identity(), release);
                        if (present && rows.getBytes("origin_payload") != null
                            && !DeploymentReleaseOrigin.read(connection, release).equals(execution.identity()))
                            throw new SQLException("Existing release origin conflicts with its creating execution");
                        origins.put(release, origin);
                    }
                }
            }
            var checkpoint = pending.isPresent() ? pending.orElseThrow()
                : recovery ? context.tasks().beginRecoveryCheckpoint(task, KEY, payload(task, "PREPARED"))
                : context.tasks().beginCheckpoint(task, KEY, payload(task, "PREPARED"));
            if (!present)
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE deployment_release ADD COLUMN origin_payload BINARY LARGE OBJECT BEFORE release_sha256");
                }
            if (!complete) {
                backfill(context.db().openConnection(task), origins);
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE deployment_release ALTER COLUMN origin_payload SET NOT NULL");
                }
            }
            if (!receipts) {
                var commands = java.util.Arrays.stream(DeploymentSchema.resource("schema-v1.sql").split(";"))
                    .map(String::trim).filter(command -> command.matches(
                        "(?s)CREATE\\s+TABLE\\s+deployment_operation_receipt\\s*\\(.*")).toList();
                if (commands.size() != 1) throw new SQLException("Publication receipt declaration is not unique");
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(commands.getFirst());
                }
                if (!receiptsPresent(connection)) throw new SQLException("Publication receipt creation was not confirmed");
            }
            var snapshot = checkpoint.snapshot();
            if (!snapshot.released()) {
                checkpoint.advance(snapshot, payload(task, "COMPLETE"));
                checkpoint.close();
            }
        }
    }

    private static boolean receiptsPresent(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT table_name FROM information_schema.tables "
            + "WHERE table_schema=? AND table_name='deployment_operation_receipt' AND table_type='BASE TABLE'")) {
            query.setString(1, connection.getSchema());
            try (var tables = query.executeQuery()) {
                if (!tables.next()) return false;
                if (tables.next()) throw new SQLException("Publication receipt table identity is ambiguous");
            }
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM deployment_operation_receipt WHERE 1=0")) {
            var metadata = rows.getMetaData();
            var columns = new ArrayList<String>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) columns.add(metadata.getColumnName(index));
            if (!columns.equals(RECEIPT_COLUMNS)) throw new SQLException("Publication receipt column inventory differs");
        }
        return true;
    }

    private static void backfill(Connection connection, LinkedHashMap<DeploymentReleaseRecord, String> origins) throws SQLException, ApiException {
        DeploymentStorageTransaction.execute(connection, () -> {
            try (var update = connection.prepareStatement(
                "UPDATE deployment_release SET origin_payload=? WHERE application_id=? AND release_id=? AND origin_payload IS NULL")) {
                for (var entry : origins.entrySet()) {
                    update.setBytes(1, entry.getValue().getBytes(StandardCharsets.UTF_8));
                    update.setString(2, entry.getKey().applicationId());
                    update.setObject(3, entry.getKey().identity().id());
                    update.executeUpdate();
                }
            }
        });
    }

    private static String payload(TaskContext task, String phase) {
        return "{\"formatVersion\":1,\"taskId\":\"" + task.id() + "\",\"phase\":\"" + phase + "\"}";
    }
}
