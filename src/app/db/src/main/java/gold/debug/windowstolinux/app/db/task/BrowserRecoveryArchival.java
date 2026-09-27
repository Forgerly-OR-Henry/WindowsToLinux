package gold.debug.windowstolinux.app.db.task;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Archives rescue state only inside its original central task transaction. / 仅在原中央任务事务内归档救援状态。 */
public final class BrowserRecoveryArchival {
    /** Exact host protocol owning the rescue journal. / 持有救援记录的精确宿主协议。 */
    public static final String TASK_TYPE = "platform.ssh.rescue.v1";

    /** Prevents utility construction. / 禁止构造工具实例。 */
    private BrowserRecoveryArchival() {
    }

    /** Identifies the host task allowed to own a rescue journal. / 识别允许持有救援记录的宿主任务。
     * @param task original task observation / 原任务观察
     * @return whether this is the exact host protocol / 是否为精确宿主协议 */
    static boolean owns(TaskSnapshot task) {
        return task.ownerId().equals("@platform") && task.type().equals(TASK_TYPE);
    }

    /** Rejects late journal writes and unrelated task/server identities under the platform lock. / 持有平台锁时拒绝迟到写入及无关任务和服务器身份。
     * @param c original owning transaction / 原所属事务
     * @param id accepted original task / 已接纳原任务
     * @param server originally held server / 原持有服务器
     * @throws SQLException if the original active ownership cannot be proved / 无法证明原活动归属时 */
    public static void requireActive(Connection c, UUID id, String server) throws SQLException {
        if (c.getAutoCommit())
            throw new SQLException("Rescue journal admission requires an owning transaction");
        try (var p = c.prepareStatement(
                "SELECT COUNT(*) FROM platform_task t JOIN platform_task_resource r ON t.task_id=r.task_id WHERE t.task_id=? AND t.owner_id='@platform' AND t.task_type=? AND t.state IN ('RUNNING','CANCEL_REQUESTED','UNKNOWN') AND t.ended_at IS NULL AND r.resource_kind='SERVER' AND r.resource_id=? AND r.owner_id='@platform' AND r.released_at IS NULL")) {
            p.setObject(1, id);
            p.setString(2, TASK_TYPE);
            p.setString(3, server);
            try (var rows = p.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 1)
                    throw new SQLException("Original rescue task and server are no longer active");
            }
        }
    }

    /** Saves one fixed rescue summary before deleting its events and state in the task transaction. / 在任务事务内先保存固定救援摘要，再移除其事件及状态。
     * @param database original database generation / 原数据库代次
     * @param c original serialized transaction / 原串行事务
     * @param target rescue category, absent for unrelated task types / 救援类别，无关任务类型为空
     * @param archive original central archive identity / 原中央归档身份
     * @param task original fully drained known result / 原完全排空的已知结果
     * @throws SQLException if ownership, summary or cleanup cannot be confirmed / 归属、摘要或清理无法确认时 */
    static void archive(PlatformDatabase database, Connection c, LogRepository.Target target, UUID archive,
            TaskSnapshot task) throws SQLException {
        if (c.getAutoCommit() || task.state() != TaskState.ENDED)
            throw new SQLException("Rescue archival requires the original terminal transaction");
        try (var p = c.prepareStatement(
                "SELECT server_profile_id,operation_id,task_state,last_action_digest,last_event_code,updated_at FROM browser_recovery_task WHERE task_id=? FOR UPDATE")) {
            p.setString(1, task.id().toString());
            try (var row = p.executeQuery()) {
                if (!row.next())
                    return;
                if (!owns(task) || target == null || !TASK_TYPE.equals(row.getString(2)))
                    throw new SQLException("Rescue journal belongs to a different task protocol");
                String server = row.getString(1), state = row.getString(3),
                        digest = Objects.requireNonNullElse(row.getString(4), ""), event = row.getString(5);
                long updated = row.getLong(6);
                if (!state.matches("[A-Z_]{1,40}") || !digest.matches("(?:[a-f0-9]{64})?")
                        || !event.matches("[a-z-]{1,80}") || updated < 0)
                    throw new SQLException("Original rescue journal metadata is invalid");
                try (var claim = c.prepareStatement(
                        "SELECT COUNT(*) FROM platform_task_resource WHERE task_id=? AND resource_kind='SERVER' AND resource_id=? AND owner_id='@platform'")) {
                    claim.setObject(1, task.id());
                    claim.setString(2, server);
                    try (var held = claim.executeQuery()) {
                        held.next();
                        if (held.getLong(1) != 1)
                            throw new SQLException("Original rescue server ownership differs");
                    }
                }
                long events, submitted;
                try (var query = c.prepareStatement(
                        "SELECT COUNT(*),COALESCE(SUM(CASE WHEN event_code='submitted' THEN 1 ELSE 0 END),0) FROM browser_recovery_event WHERE task_id=?")) {
                    query.setString(1, task.id().toString());
                    try (var counts = query.executeQuery()) {
                        counts.next();
                        events = counts.getLong(1);
                        submitted = counts.getLong(2);
                    }
                }
                if (events == 0)
                    throw new SQLException("Original rescue has no journal evidence");
                try {
                    String details = new ObjectMapper()
                            .writeValueAsString(Map.of("protocol", TASK_TYPE, "serverId", LogRecordCodec.redact(server),
                                    "lastObservedState", state, "lastEvent", event, "lastActionSha256", digest,
                                    "updatedAt", updated, "eventCount", events, "submittedCount", submitted));
                    var outcome = task.outcome().orElseThrow();
                    var fixed = UUID
                            .nameUUIDFromBytes(("WTL-BROWSER-ARCHIVE-1:" + archive).getBytes(StandardCharsets.UTF_8));
                    var record = LogRecordCodec.createSummary(target, task.ownerVersion(),
                            new LogEvent(outcome == Outcome.FAILED ? LogLevel.ERROR : LogLevel.INFO,
                                    "BROWSER_RECOVERY_" + outcome, "Browser recovery task finished: " + outcome,
                                    Optional.of(details), Optional.of(task.id()), Optional.empty(),
                                    Optional.of(outcome)),
                            fixed, task.endedAt().orElseThrow().toEpochMilli(), null);
                    new LogRepository(database).store(c, target, List.of(record), 100);
                } catch (java.io.IOException | IllegalArgumentException invalid) {
                    throw new SQLException("Original rescue summary cannot be encoded", invalid);
                }
                try (var remove = c.prepareStatement("DELETE FROM browser_recovery_event WHERE task_id=?")) {
                    remove.setString(1, task.id().toString());
                    if (remove.executeLargeUpdate() != events)
                        throw new SQLException("Original rescue events changed");
                }
                try (var remove = c.prepareStatement("DELETE FROM browser_recovery_task WHERE task_id=?")) {
                    remove.setString(1, task.id().toString());
                    if (remove.executeUpdate() != 1)
                        throw new SQLException("Original rescue journal disappeared");
                }
            }
        }
    }
}
