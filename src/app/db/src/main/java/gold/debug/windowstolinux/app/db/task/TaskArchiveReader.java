package gold.debug.windowstolinux.app.db.task;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Reads only a retained host conclusion after the original task and every lease have been archived. / 仅在原任务及全部占用已归档后读取保留宿主结论。 */
final class TaskArchiveReader {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private TaskArchiveReader() {
    }

    /** Reads a bounded exact-owner archive under the ledger's state lock. / 在账本状态锁下读取有界精确归属归档。
     * @param connection original serialized platform connection / 原串行平台连接
     * @param task original UUID / 原UUID
     * @param owner exact caller owner / 精确调用方归属
     * @param version exact caller release / 精确调用方发布
     * @return known fully archived conclusion or no proof / 已知完全归档结论或无证明
     * @throws SQLException if observation or stored proof is invalid / 观察或存储证明无效时 */
    static Optional<TaskSnapshot> read(Connection connection, UUID task, String owner, String version) throws SQLException {
        for (String table : List.of("platform_task", "platform_task_resource")) {
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE task_id=?")) {
                query.setObject(1, task);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) != 0) return Optional.empty();
                }
            }
        }
        try (var query = connection.prepareStatement("SELECT CASE WHEN CHAR_LENGTH(details)<=65536 THEN details ELSE NULL END,outcome,recorded_at,truncated,event_code FROM runtime_log "
            + "WHERE owner_id='app' AND category='platform_task' AND subject_mod_id=? AND mod_version=? AND task_id=?")) {
            query.setString(1, owner);
            query.setString(2, version);
            query.setObject(3, task);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String text = rows.getString(1), result = rows.getString(2), event = rows.getString(5);
                long recorded = rows.getLong(3);
                boolean truncated = rows.getBoolean(4);
                if (rows.next()) throw new SQLException("Original task archive is not unique");
                if (truncated || text == null || text.getBytes(StandardCharsets.UTF_8).length > 65_536)
                    return Optional.empty();
                try {
                    var node = JSON.readTree(text);
                    var fields = new HashSet<String>();
                    node.fieldNames().forEachRemaining(fields::add);
                    if (!node.isObject() || !fields.equals(Set.of("taskType", "startedAt", "endedAt", "progressText", "result"))
                        || !node.path("taskType").isTextual() || node.path("taskType").textValue().isBlank()
                        || node.path("taskType").textValue().length() > 128
                        || !node.path("progressText").isTextual() || node.path("progressText").textValue().length() > 4096
                        || !node.path("result").isTextual() || !node.path("startedAt").isIntegralNumber()
                        || !node.path("startedAt").canConvertToLong() || !node.path("endedAt").isIntegralNumber()
                        || !node.path("endedAt").canConvertToLong() || node.path("endedAt").longValue() != recorded)
                        throw new SQLException("Original task archive has invalid framing");
                    var outcome = TaskResult.valueOf(result);
                    if (outcome == TaskResult.UNKNOWN || !event.equals("PLATFORM_TASK_" + outcome))
                        throw new SQLException("Original task archive has no known conclusion");
                    return Optional.of(new TaskSnapshot(task, owner, version, node.path("taskType").textValue(),
                        TaskState.ENDED, Optional.of(outcome), node.path("progressText").textValue(),
                        Instant.ofEpochMilli(node.path("startedAt").longValue()), Optional.of(Instant.ofEpochMilli(recorded)), List.of()));
                } catch (java.io.IOException | IllegalArgumentException | NullPointerException invalid) {
                    throw new SQLException("Original task archive cannot be decoded", invalid);
                }
            }
        }
    }
}
