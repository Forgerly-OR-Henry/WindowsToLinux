package gold.debug.windowstolinux.mod.deployment.service.agent;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.ModDatabase;

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
        create(task, identity, Optional.empty());
    }

    /** Serializes original admission and its precise non-secret endpoint proof. / 串行执行原准入及其精确非秘密端点证明。
     * @param task authentic original task / 真实原任务
     * @param identity original target and policy / 原目标及策略
     * @param endpoint exact canonical endpoint, absent for older evidence / 精确规范端点，旧依据可为空
     * @throws Exception if another unresolved original task or persistence blocks admission / 其他未结原任务或持久性阻止准入时 */
    public void create(TaskContext task, Identity identity, Optional<String> endpoint) throws Exception {
        if (endpoint.isPresent() && !endpoint.orElseThrow().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid canonical agent endpoint");
        try (Connection connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("MERGE INTO preference(preference_key,preference_value) KEY(preference_key) VALUES(?,?)")) {
                    lock.setString(1, "deployment.agent.admission.v2");
                    lock.setString(2, "2");
                    lock.executeUpdate();
                }
                requireAvailable(connection, task, identity, endpoint);
                insert(connection, task, identity);
                if (endpoint.isPresent()) {
                    try (var target = connection.prepareStatement("INSERT INTO deployment_agent_event(task_id,action_id,event_type,event_detail,created_at) VALUES(?,'','REMOTE_TARGET',?,?)")) {
                        target.setString(1, task.id().toString());
                        target.setString(2, endpoint.orElseThrow());
                        target.setLong(3, Instant.now().toEpochMilli());
                        target.executeUpdate();
                    }
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

    /** Inserts original identity under the existing serialized admission transaction. / 在既有串行准入事务下插入原身份。
     * @param connection original transaction / 原事务
     * @param task authentic original task / 真实原任务
     * @param identity immutable original metadata / 不可变原元数据
     * @throws SQLException if the original insert cannot be confirmed / 原插入无法确认时 */
    private static void insert(Connection connection, TaskContext task, Identity identity) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO deployment_agent_task("
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

    /** Refuses unresolved effects even when a terminal label or another profile would hide them. / 即使终态标签或另一资料会隐藏未结影响也拒绝准入。
     * @param connection original serialized transaction / 原串行事务
     * @param task actual new original task / 实际新原任务
     * @param identity actual selected profile / 实际所选资料
     * @param endpoint actual canonical endpoint / 实际规范端点
     * @throws SQLException if retained evidence cannot be read completely / 留存依据无法完整读取时
     * @throws ApiException if original unresolved ownership blocks admission / 原未结归属阻止准入时 */
    private static void requireAvailable(Connection connection, TaskContext task, Identity identity,
                                         Optional<String> endpoint) throws SQLException, ApiException {
        try (var query = connection.prepareStatement("SELECT task_id,server_profile_id,task_state FROM deployment_agent_task ORDER BY started_at LIMIT 10001");
             var rows = query.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                if (++count > 10000) throw blocked(task, task.id(), "DEPLOYMENT_AGENT_TARGET_EVIDENCE_LIMIT");
                UUID original = UUID.fromString(rows.getString(1));
                if (original.equals(task.id()))
                    throw blocked(task, original, "DEPLOYMENT_AGENT_ORIGINAL_ALREADY_EXISTS");
                var events = events(connection, original);
                boolean terminal = Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(rows.getString(3));
                if (terminal && effectsSettled(events)) continue;
                var targets = events.stream().filter(event -> event.type().equals("REMOTE_TARGET")).map(Event::detail).distinct().toList();
                boolean known = targets.size() == 1 && targets.getFirst().matches("[0-9a-f]{64}");
                if (identity.server().equals(rows.getString(2)) || !known || endpoint.isEmpty()
                    || targets.getFirst().equals(endpoint.orElseThrow()))
                    throw blocked(task, original, known ? "DEPLOYMENT_AGENT_TARGET_BUSY" : "DEPLOYMENT_AGENT_TARGET_UNCONFIRMED");
            }
        }
    }

    /** Reads all bounded original events in the same admission transaction. / 在同一准入事务中读取全部有界原事件。
     * @param connection original serialized transaction / 原串行事务
     * @param original selected original identity / 所选原身份
     * @return complete ordered events / 完整有序事件
     * @throws SQLException if evidence exceeds its bound or is unavailable / 依据超界或不可用时 */
    private static List<Event> events(Connection connection, UUID original) throws SQLException {
        var result = new ArrayList<Event>();
        try (var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail FROM deployment_agent_event WHERE task_id=? ORDER BY event_id LIMIT 10001")) {
            query.setString(1, original.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == 10000) throw new SQLException("Original agent evidence exceeds its bound");
                    result.add(new Event(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Identifies the original blocker without exposing private connection or cause details. / 标识原阻塞者，不暴露私有连接或原因细节。
     * @param task actual rejected task / 实际被拒任务
     * @param original unresolved original UUID / 未结原UUID
     * @param event public classified reason / 公开分类原因
     * @return original-bound public rejection / 绑定原身份的公开拒绝 */
    private static ApiException blocked(TaskContext task, UUID original, String event) {
        return new ApiException(new Problem(ProblemCode.ACCESS_DENIED, event, "Reconcile the unresolved original agent task before starting this target",
            Map.of("task", task.id().toString(), "originalTask", original.toString()), Optional.of(original)));
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
        try (Connection connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                DeploymentAgentEvidenceLimit.require(connection, task.id(), Set.of("PAUSED", "RESUMED",
                    "MODEL_REFRESH_REJECTED", "INSPECT_REQUESTED").contains(type));
                try (var statement = connection.prepareStatement("INSERT INTO deployment_agent_event("
                    + "task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?)")) {
                    statement.setString(1, task.id().toString());
                    statement.setString(2, action);
                    statement.setString(3, type);
                    statement.setString(4, detail);
                    statement.setLong(5, Instant.now().toEpochMilli());
                    statement.executeUpdate();
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
                DeploymentAgentEvidenceLimit.require(connection, task.id(), state.equals("WAITING"));
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

    /** Replaces only a verified paused route and records old and new identities atomically. / 仅替换已核实暂停路由并原子记录旧新身份。
     * @param task authentic original worker / 真实原工作线程
     * @param previous originally adopted digest / 原采用摘要
     * @param selected explicitly reviewed replacement / 明确审阅替换
     * @throws Exception if original state, identity or commit differs / 原状态、身份或提交不符时 */
    public void replaceConfiguration(TaskContext task, String previous, String selected) throws Exception {
        if (!previous.matches("[0-9a-f]{64}") || !selected.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid agent model revision");
        try (var connection = database.openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                DeploymentAgentEvidenceLimit.require(connection, task.id(), true);
                try (var update = connection.prepareStatement("UPDATE deployment_agent_task SET configuration_digest=?,"
                    + "updated_at=? WHERE task_id=? AND configuration_digest=? AND task_state='WAITING'")) {
                    update.setString(1, selected);
                    update.setLong(2, Instant.now().toEpochMilli());
                    update.setString(3, task.id().toString());
                    update.setString(4, previous);
                    if (update.executeUpdate() != 1) throw new SQLException("Original paused configuration changed");
                }
                try (var event = connection.prepareStatement("INSERT INTO deployment_agent_event("
                    + "task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,'CONFIGURATION_REPLACED',?,?)")) {
                    event.setString(1, task.id().toString());
                    event.setString(2, previous);
                    event.setString(3, selected);
                    event.setLong(4, Instant.now().toEpochMilli());
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
        return events(task, task.id());
    }

    /** Reads a selected owned business stream using the actual query task. / 使用实际查询任务读取所选所属业务事件流。
     * @param actual actual authenticated query task / 实际已认证查询任务
     * @param selected selected original UUID / 所选原UUID
     * @return bounded original ordered evidence / 有界原有序依据
     * @throws Exception if owned evidence is unavailable / 所属依据不可用时 */
    public List<Event> events(TaskContext actual, UUID selected) throws Exception {
        var events = new ArrayList<Event>();
        try (Connection connection = database.openConnection(actual);
             var query = connection.prepareStatement("SELECT event_id,event_type,action_id,event_detail "
                 + "FROM deployment_agent_event WHERE task_id=? ORDER BY event_id")) {
            query.setString(1, selected.toString());
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
        return find(task, task.id());
    }

    /** Reads selected owned task evidence without impersonating its ended context. / 不冒充已结束上下文地读取所选所属任务依据。
     * @param actual actual query or original task / 实际查询或原任务
     * @param selected exact owned original UUID / 精确所属原UUID
     * @return existing immutable evidence or absence / 既有不可变依据或不存在
     * @throws Exception if original semantics or persistence differs / 原语义或持久化不符时 */
    public Optional<Snapshot> find(TaskContext actual, UUID selected) throws Exception {
        try (Connection connection = database.openConnection(actual);
             var query = connection.prepareStatement("SELECT server_profile_id,target_digest,configuration_digest,"
                 + "approval_mode,task_state,deployment_skill_id,approval_skill_id,execution_semantics_version "
                 + "FROM deployment_agent_task WHERE task_id=?")) {
            query.setString(1, selected.toString());
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
        var pending = new HashMap<List<String>, Integer>();
        for (var event : events) {
            if (Set.of("PREPARE_EXECUTING", "COMMAND_EXECUTING", "PATCH_EXECUTING", "SEAL_EXECUTING",
                "DELIVERY_EXECUTING").contains(event.type()))
                pending.merge(List.of(event.type().substring(0, event.type().length() - "_EXECUTING".length()), event.action()), 1, Integer::sum);
            if (Set.of("PREPARE_RESULT", "COMMAND_RESULT", "PATCH_RESULT", "SEAL_RESULT", "DELIVERY_RESULT")
                .contains(event.type()))
                pending.computeIfPresent(List.of(event.type().substring(0, event.type().length() - "_RESULT".length()), event.action()),
                    (key, count) -> count == 1 ? null : count - 1);
        }
        return pending.isEmpty();
    }
}
