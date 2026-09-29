package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/** Durable agent-only state and action evidence, independent of standard deployment. / 独立于标准部署的持久自主任务状态与动作依据。 */
public final class DeploymentAgentJournal {
    /** Original owner-bound database. / 原所有者绑定的数据库。 */
    private final ModDatabase database;

    /** Binds the real active Schema. / 绑定真实活动 Schema。
     * @param database owned JDBC capability / 所属 JDBC 能力 */
    public DeploymentAgentJournal(ModDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Original frozen task identity. / 原冻结任务身份。
     * @param server server profile / 服务器资料
     * @param targetDigest authenticated target digest / 已认证目标摘要
     * @param configurationDigest original model configuration digest / 原模型配置摘要
     * @param approvalMode selected review policy / 所选审核策略 */
    public record Identity(String server, String targetDigest, String configurationDigest, String approvalMode) {
        /** Checks immutable persisted fields. / 校验不可变持久字段。
         * @param server server identity / 服务器身份
         * @param targetDigest target digest / 目标摘要
         * @param configurationDigest model configuration digest / 模型配置摘要
         * @param approvalMode review policy / 审核策略 */
        public Identity {
            Objects.requireNonNull(server);
            if (!targetDigest.matches("[0-9a-f]{64}") || !configurationDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid agent identity digest");
            if (!Set.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL").contains(approvalMode))
                throw new IllegalArgumentException("Invalid agent approval mode");
        }
    }

    /** One retained nonsecret action observation. / 一项保留的不含秘密动作观察。
     * @param id ordered event identity / 有序事件身份
     * @param type controlled event type / 受控事件类型
     * @param action exact action identity / 精确动作身份
     * @param detail bounded digest or status / 有界摘要或状态 */
    public record Event(long id, String type, String action, String detail) {
    }

    /** Original dedicated durable task row. / 原专有持久任务行。
     * @param identity frozen target, configuration and policy / 冻结目标、配置与策略
     * @param state last durable state / 最近持久状态 */
    public record Snapshot(Identity identity, String state) {
    }

    /** Persists identity before any model invocation or remote effect. / 在模型调用或远端影响之前持久化身份。
     * @param task original task / 原任务
     * @param identity reviewed identity / 已审阅身份
     * @throws Exception on unavailable persistence / 持久化不可用时 */
    public void create(TaskContext task, Identity identity) throws Exception {
        try (Connection connection = database.openConnection(task);
                var statement = connection.prepareStatement("INSERT INTO deployment_agent_task("
                        + "task_id,server_profile_id,target_digest,configuration_digest,automation_mode,approval_mode,"
                        + "deployment_skill_id,approval_skill_id,execution_semantics_version,task_state,started_at,updated_at) "
                        + "VALUES(?,?,?,?,'AGENT',?,'autonomous-deployment-v2','deployment-approval-v2',2,'RUNNING',?,?)")) {
            long now = Instant.now().toEpochMilli();
            statement.setString(1, task.id().toString());
            statement.setString(2, identity.server());
            statement.setString(3, identity.targetDigest());
            statement.setString(4, identity.configurationDigest());
            statement.setString(5, identity.approvalMode());
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    /** Appends action intent before a side effect or a verified result afterward. / 副作用前追加动作意图，之后追加经核实结果。
     * @param task original task / 原任务
     * @param type controlled event type / 受控事件类型
     * @param action action binding / 动作绑定
     * @param detail nonsecret digest or bounded status / 不含秘密的摘要或有界状态
     * @throws Exception on failed persistence / 持久化失败时 */
    public void event(TaskContext task, String type, String action, String detail) throws Exception {
        if (!type.matches("[A-Z_]{1,48}") || action.length() > 128 || detail.length() > 4096
                || detail.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid agent event");
        try (Connection connection = database.openConnection(task);
                var statement = connection.prepareStatement("INSERT INTO deployment_agent_event("
                        + "task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
            statement.setString(1, task.id().toString());
            statement.setString(2, action);
            statement.setString(3, type);
            statement.setString(4, detail);
            statement.setLong(5, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    /** Changes task state and records matching evidence in one transaction. / 在同一事务中改变任务状态并记录相应依据。
     * @param task original task / 原任务
     * @param state known lifecycle state / 已知生命周期状态
     * @throws Exception on uncertain write / 写入不明时 */
    public void state(TaskContext task, String state) throws Exception {
        if (!Set.of("RUNNING", "WAITING", "FAILED", "CANCELLED", "SUCCEEDED", "UNKNOWN", "INTERRUPTED").contains(state))
            throw new IllegalArgumentException("Invalid agent state");
        try (Connection connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                long now = Instant.now().toEpochMilli();
                try (var update = connection.prepareStatement(
                        "UPDATE deployment_agent_task SET task_state=?,updated_at=? WHERE task_id=?")) {
                    update.setString(1, state);
                    update.setLong(2, now);
                    update.setString(3, task.id().toString());
                    if (update.executeUpdate() != 1)
                        throw new SQLException("Agent task missing");
                }
                try (var event = connection.prepareStatement("INSERT INTO deployment_agent_event("
                        + "task_id,action_id,event_type,event_detail,created_at) VALUES(?,'','STATE',?,?)")) {
                    event.setString(1, task.id().toString());
                    event.setString(2, state);
                    event.setLong(3, now);
                    event.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Reads original ordered action evidence without reconstructing execution closures. / 读取原有序动作依据，不重建执行闭包。
     * @param task original task or recovery / 原任务或恢复
     * @return chronological evidence / 按时间排序的依据
     * @throws Exception on missing or unavailable evidence / 依据缺失或不可用时 */
    public List<Event> events(TaskContext task) throws Exception {
        var events = new ArrayList<Event>();
        try (Connection connection = database.openConnection(task);
                var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail "
                        + "FROM deployment_agent_event WHERE task_id=? ORDER BY event_id")) {
            query.setString(1, task.id().toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (events.size() == 10000)
                        throw new SQLException("Agent event history exceeds recovery budget");
                    events.add(new Event(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
        }
        return List.copyOf(events);
    }

    /** Reads the original identity and state without recreating task work. / 读取原身份与状态而不重建任务工作。
     * @param task original execution or recovery context / 原执行或恢复上下文
     * @return existing row or absence / 既有行或不存在
     * @throws Exception if retained evidence is unavailable / 留存依据不可用时 */
    public Optional<Snapshot> find(TaskContext task) throws Exception {
        try (Connection connection = database.openConnection(task);
                var query = connection.prepareStatement("SELECT server_profile_id,target_digest,configuration_digest,"
                        + "approval_mode,task_state,deployment_skill_id,approval_skill_id,execution_semantics_version "
                        + "FROM deployment_agent_task WHERE task_id=?")) {
            query.setString(1, task.id().toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next())
                    return Optional.empty();
                if (!"autonomous-deployment-v2".equals(rows.getString(6))
                        || !"deployment-approval-v2".equals(rows.getString(7)) || rows.getInt(8) != 2)
                    throw new SQLException("Agent persisted semantics changed");
                var snapshot = new Snapshot(
                        new Identity(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)),
                        rows.getString(5));
                if (rows.next())
                    throw new SQLException("Duplicate agent task");
                return Optional.of(snapshot);
            }
        }
    }

    /** Requires matching completion for every possibly started external effect. / 要求每项可能已开始的外部影响都有匹配的完成依据。
     * @param events original event stream / 原事件流
     * @return true only if no started effect lacks a trusted result / 仅无未结清的已启动影响时为真 */
    public static boolean effectsSettled(List<Event> events) {
        var pending = new HashSet<String>();
        for (var event : events) {
            if (Set.of("PREPARE_EXECUTING", "COMMAND_EXECUTING", "PATCH_EXECUTING", "SEAL_EXECUTING",
                    "DELIVERY_EXECUTING").contains(event.type()))
                pending.add(event.action());
            if (Set.of("PREPARE_RESULT", "COMMAND_RESULT", "PATCH_RESULT", "SEAL_RESULT", "DELIVERY_RESULT")
                    .contains(event.type()))
                pending.remove(event.action());
        }
        return pending.isEmpty();
    }
}
