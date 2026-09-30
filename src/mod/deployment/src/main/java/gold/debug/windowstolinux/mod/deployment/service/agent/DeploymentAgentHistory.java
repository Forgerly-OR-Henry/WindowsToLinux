package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper;

/** Reads existing formal agent evidence and explicitly inspects only its original candidates. / 读取既有正式自主依据并明确仅检查其原候选。 */
public final class DeploymentAgentHistory {
    /** Actual owner capabilities, never an old task context. / 实际所有者能力，绝非旧任务上下文。 */ private final ModContext context;
    /** Original formal event reader. / 原正式事件读取器。 */ private final DeploymentAgentJournal journal;
    /** One selected original business row. / 一项所选原业务行。
     * @param id original task UUID / 原任务UUID
     * @param server original selected server / 原所选服务器
     * @param state actual Mod-local persisted state / 实际Mod内部持久状态
     * @param updatedAt last original update / 最近原更新 */
    public record Entry(UUID id, ServerRef server, String state, Instant updatedAt) {}
    /** Complete bounded detail from the existing business ledger. / 来自既有业务账本的完整有界详情。
     * @param entry selected original row / 所选原行
     * @param snapshot original immutable target and policy / 原不可变目标及策略
     * @param events original ordered bounded event stream / 原有序有界事件流 */
    public record Detail(Entry entry, DeploymentAgentJournal.Snapshot snapshot, List<DeploymentAgentJournal.Event> events) {
        /** Copies original event references without reconstructing work. / 复制原事件引用且不重建工作。
         * @param entry original row / 原行
         * @param snapshot original identity / 原身份
         * @param events bounded events / 有界事件 */
        public Detail { events = List.copyOf(events); }
    }

    /** Binds one actual activation. / 绑定一次实际启用。
     * @param context actual owner / 实际所有者 */
    public DeploymentAgentHistory(ModContext context) {
        this.context = Objects.requireNonNull(context); journal = new DeploymentAgentJournal(context.db());
    }

    /** Reads at most two hundred existing tasks; no duplicate history store is created. / 最多读取二百项既有任务，不创建重复历史存储。
     * @param task actual read task / 实际读取任务
     * @return newest existing business records / 最近既有业务记录
     * @throws Exception if original records cannot be read / 无法读取原记录时 */
    public List<Entry> recent(TaskContext task) throws Exception {
        var result = new ArrayList<Entry>();
        try (var connection = context.db().openConnection(task);
                var query = connection.prepareStatement("SELECT task_id,server_profile_id,task_state,updated_at "
                        + "FROM deployment_agent_task ORDER BY updated_at DESC,task_id FETCH FIRST 200 ROWS ONLY");
                var rows = query.executeQuery()) {
            while (rows.next()) result.add(new Entry(UUID.fromString(rows.getString(1)), new ServerRef(rows.getString(2)),
                    rows.getString(3), Instant.ofEpochMilli(rows.getLong(4))));
        }
        return List.copyOf(result);
    }

    /** Reads a selected original stream under an actual current query. / 在实际当前查询下读取所选原事件流。
     * @param task actual read task / 实际读取任务
     * @param selected original selected UUID / 原所选UUID
     * @return exact bounded original evidence / 精确有界原依据
     * @throws Exception if identity or semantics cannot be read / 无法读取身份或语义时 */
    public Detail detail(TaskContext task, UUID selected) throws Exception {
        var snapshot = journal.find(task, selected).orElseThrow(() -> new SQLException("Original agent task missing"));
        try (var connection = context.db().openConnection(task);
                var query = connection.prepareStatement("SELECT updated_at FROM deployment_agent_task WHERE task_id=?")) {
            query.setString(1, selected.toString());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Original task disappeared");
                var entry = new Entry(selected, new ServerRef(snapshot.identity().server()), snapshot.state(),
                        Instant.ofEpochMilli(rows.getLong(1)));
                return new Detail(entry, snapshot, journal.events(task, selected));
            }
        }
    }

    /** Requests inspection metadata only; the APP must enter the original retained callback. / 仅请求检查元数据；APP必须进入原留存回调。
     * @param task actual explicit request task / 实际显式请求任务
     * @param selected exact retained original UUID / 精确留存原UUID
     * @return original pending request identity / 原待处理请求身份
     * @throws Exception if ownership, original retention or metadata differs / 所有权、原留存或元数据不符时 */
    public long request(TaskContext task, UUID selected) throws Exception {
        requireRetained(selected);
        try (var connection = context.db().openConnection(task)) {
            connection.setAutoCommit(false);
            try {
                DeploymentAgentEvidenceLimit.require(connection, selected, true);
                var events = journal.events(task, selected);
                var pending = pending(events);
                if (pending.isPresent()) { connection.rollback(); return pending.orElseThrow().id(); }
                if (events.size() >= 10000) throw new SQLException("Original inspection evidence exceeds bound");
                try (var insert = connection.prepareStatement("INSERT INTO deployment_agent_event("
                        + "task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,'INSPECT_REQUESTED',?,?)")) {
                    insert.setString(1, selected.toString()); insert.setString(2, selected.toString());
                    insert.setString(3, task.id().toString()); insert.setLong(4, Instant.now().toEpochMilli()); insert.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
        return pending(journal.events(task, selected)).orElseThrow().id();
    }

    /** Observes exact original candidates without cleanup, publication or result mutation. / 观察精确原候选，不清理、发布或改变结果。
     * @param task actual query or original retained callback / 实际查询或原留存回调
     * @param detail exact original evidence / 精确原依据
     * @return actual candidate facts, including verified absence / 实际候选事实，包括已核实不存在
     * @throws Exception if permission, profile, fingerprint or candidate facts differ / 权限、资料、指纹或候选事实不符时 */
    public Map<String, String> inspect(TaskContext task, Detail detail) throws Exception {
        String profile = detail.events().stream().filter(event -> event.type().equals("TARGET_PROFILE"))
                .reduce((before, after) -> after).orElseThrow(() -> new SQLException("Original endpoint binding missing")).detail();
        var server = detail.entry().server();
        if (!profile.equals(DeploymentAgentWorkflow.targetProfile(context.servers().get(server, task))))
            throw new SQLException("Original inspection endpoint changed");
        var workspaces = DeploymentAgentRecovery.originalWorkspaces(detail.events()).orElseThrow(
                () -> new SQLException("Original candidate identities are incomplete"));
        var result = new LinkedHashMap<String, String>();
        try (var session = context.linux().connect(server, task)) {
            if (!detail.snapshot().identity().targetDigest().equals(DeploymentAgentWorkflow.targetDigest(server, session)))
                throw new SQLException("Original inspection fingerprint changed");
            String revision = detail.snapshot().identity().configurationDigest();
            if (new DeploymentManagedHelper(session, context.interaction(), server, task, revision).probe().outcome() != Outcome.SUCCEEDED)
                throw new SQLException("Original inspection helper is unconfirmed");
            var channel = new DeploymentAgentProjectChannel(session, context.interaction(), server, task, revision, () -> revision);
            for (var workspace : workspaces) {
                if (!profile.equals(DeploymentAgentWorkflow.targetProfile(context.servers().get(server, task))))
                    throw new SQLException("Original inspection endpoint changed before query");
                var status = channel.candidateStatus(detail.entry().id(), workspace);
                result.put(workspace.candidateId(), status.buildActive() ? "BUILD_ACTIVE" : status.present() ? "PRESENT" : "ABSENT");
            }
        }
        return Map.copyOf(result);
    }

    /** Finds only an unprocessed exact request; a later explicit recovery can then run normally. / 仅查找未处理精确请求，随后明确恢复可以正常运行。
     * @param events original bounded stream / 原有界事件流
     * @return pending exact request or absence / 待处理精确请求或不存在 */
    static Optional<DeploymentAgentJournal.Event> pending(List<DeploymentAgentJournal.Event> events) {
        var request = events.stream().filter(event -> event.type().equals("INSPECT_REQUESTED")).reduce((a, b) -> b);
        if (request.isEmpty()) return Optional.empty();
        String id = Long.toString(request.orElseThrow().id());
        return events.stream().anyMatch(event -> event.id() > request.orElseThrow().id()
                && event.type().equals("INSPECT_RESULT") && event.action().equals(id)) ? Optional.empty() : request;
    }

    /** Requires actual owner-listed UNKNOWN retention before any inspection request write. / 任一检查请求写入前要求实际所有者列表中的UNKNOWN留存。
     * @param selected original task UUID / 原任务UUID
     * @throws ApiException if original occupancy cannot be observed / 原占用无法观察时 */
    private void requireRetained(UUID selected) throws ApiException {
        Optional<String> cursor = Optional.empty();
        int count = 0;
        do {
            var page = context.tasks().list(cursor, 200);
            for (var row : page.items()) {
                if (row.id().equals(selected) && row.type().equals(DeploymentAgentWorkflow.TASK_TYPE)
                        && row.state() == TaskState.UNKNOWN) return;
                if (++count == 10000) break;
            }
            cursor = page.nextCursor();
        } while (cursor.isPresent() && count < 10000);
        throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_INSPECT_NOT_RETAINED",
                "Inspection requires the actual retained original task", Map.of(), Optional.empty()));
    }
}
