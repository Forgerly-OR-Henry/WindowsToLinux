package gold.debug.windowstolinux.app.db.mod;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.persistence.connection.*;
import gold.debug.windowstolinux.app.db.execution.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Retains original recovery sessions beyond the lifetime of their tasks. / 在任务生命期之外保留原恢复会话。 */
public final class ModRecoveryLedger {
    /** Original engine and boot identity. / 原引擎及启动身份。 */
    private final PlatformDatabase database;

    /** Serialized platform transactions. / 串行平台事务。 */
    private final RepositoryConnections connections;

    /** Existing bounded operation summaries. / 已有有界操作摘要。 */
    private final LogRepository logs;

    /** Fixed platform summary destination. / 固定平台摘要目标。 */
    private final LogRepository.Target archive;

    /** Strict original-fact decoder. / 严格原事实解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Binds recovery records to the actual platform. / 将恢复记录绑定到实际平台。
     * @param database original engine / 原引擎
     * @throws SQLException if the existing summary destination is unavailable / 已有摘要目标不可用时 */
    public ModRecoveryLedger(PlatformDatabase database) throws SQLException {
        this.database = Objects.requireNonNull(database);
        connections = new RepositoryConnections(database::openConnection);
        logs = new LogRepository(database);
        archive = logs.register("app", Optional.empty(),
                new LogCategory("mod_operation", "mod_operation", Optional.empty()));
    }

    /** Immutable original group; construction never grants transition ownership. / 不可变原组；构造不授予转换归属。
     * @param id original group identity / 原组身份
     * @param graph original complete release graph / 原完整发布图
     * @param verified whether physical cleanup was durably confirmed / 是否已持久确认物理清理
     * @param rows exact persisted operation receipts / 精确持久操作凭据 */
    public record Recovery(UUID id, TaskRecoveryGraph graph, boolean verified, List<Row> rows) {
        /** Freezes the original receipts. / 冻结原凭据。
         * @param id original group / 原组
         * @param graph original releases / 原发布
         * @param verified physical cleanup confirmation / 物理清理确认
         * @param rows original receipts / 原凭据 */
        public Recovery {
            rows = List.copyOf(rows);
        }
    }

    /** One original member receipt, including the runtime state that may be acknowledged. / 一个原成员凭据，包含允许确认的运行状态。
     * @param operation original operation / 原操作
     * @param owner original member / 原成员
     * @param archive original summary / 原摘要
     * @param revision original intent revision / 原意图修订
     * @param request exact immutable group document / 精确不可变组文档
     * @param evidence exact boot and runtime receipt / 精确启动及运行凭据 */
    public record Row(UUID operation, String owner, UUID archive, long revision, String request, String evidence) {
    }

    /** Reads complete session groups without using current version selections. / 读取完整会话组，不使用当前版本选择。
     * @return pending original groups / 待处理原组
     * @throws SQLException if a receipt is incomplete or inconsistent / 凭据不完整或不一致时 */
    public List<Recovery> pending() throws SQLException {
        return locked(this::read);
    }

    /** Records every member before any author constructor or startup is invoked. / 在调用任一作者构造或启动前记录全部成员。
     * @param graph verified original connected graph / 已核验原连通图
     * @return durable group with fixed summary identities / 具有固定摘要身份的持久组
     * @throws SQLException if code, data, runtime or another operation conflicts / 代码、数据、运行状态或其他操作冲突时 */
    public Recovery retain(TaskRecoveryGraph graph) throws SQLException {
        return locked(c -> {
            UUID group = UUID.randomUUID();
            String request = JSON.createObjectNode().put("formatVersion", 1).put("mode", "MOD_SESSION")
                    .put("group", group.toString()).set("graph", JSON.valueToTree(graph.encode())).toString();
            require(request.length() <= 1048576, "Recovery graph is too large");
            long now = System.currentTimeMillis();
            for (var release : graph.releases()) {
                validateRelease(c, release);
                try (var p = c.prepareStatement(
                        "SELECT revision,last_boot_id,last_runtime_state,actual_version FROM mod_registry WHERE mod_id=?")) {
                    p.setString(1, release.owner());
                    try (var r = p.executeQuery()) {
                        require(r.next(), "Original Mod is absent");
                        UUID oldBoot = r.getObject(2, UUID.class);
                        String actual = r.getString(4), state = r.getString(3);
                        require(!database.bootId().equals(oldBoot) && (actual == null
                                ? Set.of("STOPPED", "FAILED").contains(state)
                                : release.version().equals(actual) && oldBoot != null),
                                "Original runtime identity conflicts with recovery");
                        maintenance(c, release.owner(), null);
                        String evidence = JSON.createObjectNode().put("formatVersion", 1)
                                .put("bootId", database.bootId().toString())
                                .put("registryBootId", oldBoot == null ? "" : oldBoot.toString())
                                .put("registryState", state).put("registryVersion", actual == null ? "" : actual)
                                .toString();
                        update(c,
                                "INSERT INTO mod_operation(operation_id,mod_id,operation_type,target_version,target_schema_version,target_generation,accepted_revision,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'RECONCILE',?,?,?,?,'RUNNING','RECOVERING',?,?,?,?,?)",
                                UUID.randomUUID(), release.owner(), release.version(),
                                release.schema().map(SchemaIdentity::schemaVersion).orElse(null),
                                release.schema().map(SchemaIdentity::generation).orElse(null), r.getLong(1), request,
                                evidence, UUID.randomUUID().toString(), now, now);
                    }
                }
            }
            return read(c).stream().filter(value -> value.id().equals(group)).findFirst().orElseThrow();
        });
    }

    /** Claims only the exact unfinished group in a later boot. / 仅在后续启动取得精确未完成组。
     * @param original original read receipt / 原读取凭据
     * @return current boot receipt / 当前启动凭据
     * @throws SQLException if ownership, code or data changed / 归属、代码或数据变化时 */
    public Recovery resume(Recovery original) throws SQLException {
        return locked(c -> {
            unchanged(c, original);
            require(!original.verified(), "Verified cleanup needs only archival");
            for (var row : original.rows()) {
                var evidence = object(row.evidence());
                require(!database.bootId().toString().equals(evidence.path("bootId").textValue()),
                        "Recovery already belongs to this boot");
                validateRelease(c, original.graph().release(row.owner()));
                registry(c, row);
                maintenance(c, row.owner(), row.operation());
                ((com.fasterxml.jackson.databind.node.ObjectNode) evidence).put("bootId", database.bootId().toString());
                update(c, "UPDATE mod_operation SET state='RUNNING',recovery_json=?,updated_at=? WHERE operation_id=?",
                        evidence.toString(), System.currentTimeMillis(), row.operation());
            }
            return read(c).stream().filter(value -> value.id().equals(original.id())).findFirst().orElseThrow();
        });
    }

    /** Persists actual closure before attempting the independently retryable summary transaction. / 在尝试可独立重试的摘要事务前持久化实际关闭。
     * @param original group whose original sessions have physically closed / 原会话已物理关闭的组
     * @return durable cleanup receipt / 持久清理凭据
     * @throws SQLException if original ownership or retained resources remain / 原归属变化或保留资源残留时 */
    public Recovery released(Recovery original) throws SQLException {
        return locked(c -> {
            var acknowledged = new Recovery(original.id(), original.graph(), true, original.rows());
            if (read(c).contains(acknowledged))
                return acknowledged;
            unchanged(c, original);
            require(!original.verified(), "Cleanup is already verified");
            for (var row : original.rows()) {
                require(database.bootId().toString().equals(object(row.evidence()).path("bootId").textValue()),
                        "Recovery belongs to another boot");
                drained(c, row);
                registry(c, row);
                update(c,
                        "UPDATE mod_operation SET state='FINISHING',phase='VERIFIED',outcome='SUCCEEDED',updated_at=? WHERE operation_id=?",
                        System.currentTimeMillis(), row.operation());
            }
            return read(c).stream().filter(value -> value.id().equals(original.id())).findFirst().orElseThrow();
        });
    }

    /** Atomically archives verified groups and acknowledges only their exact old runtime states. / 原子归档已核验组，并仅确认其精确旧运行状态。
     * @param original durable physical cleanup receipt / 持久物理清理凭据
     * @throws SQLException if the summary or original receipt cannot be confirmed / 摘要或原凭据无法确认时 */
    public void archive(Recovery original) throws SQLException {
        locked(c -> {
            require(original.verified(), "Physical recovery cleanup is not verified");
            boolean absent = !original.rows().isEmpty();
            for (var row : original.rows())
                absent &= count(c, "SELECT COUNT(*) FROM mod_operation WHERE operation_id=?", row.operation()) == 0;
            if (absent)
                return null;
            unchanged(c, original);
            long now = System.currentTimeMillis();
            for (var row : original.rows()) {
                drained(c, row);
                registry(c, row);
                var release = original.graph().release(row.owner());
                logs.store(c, archive,
                        List.of(new LogRecordCodec.Record(row.archive(), now, "app", "mod_operation", LogLevel.INFO,
                                "MOD_RECOVERY_FINISHED",
                                "Original recovery resources closed; restart is required for normal activation", null,
                                row.owner(), release.version(), null, row.operation(), Outcome.SUCCEEDED, false)),
                        100);
                update(c,
                        "UPDATE mod_registry SET actual_version=NULL,last_runtime_state='STOPPED',failure_code=NULL,failure_detail=NULL,updated_at=? WHERE mod_id=?",
                        now, row.owner());
                update(c, "DELETE FROM mod_operation WHERE operation_id=?", row.operation());
            }
            return null;
        });
    }

    /** Validates exact registered code and data in the same ownership transaction. / 在同一归属事务中核验精确登记代码及数据。
     * @param c serialized connection / 串行连接
     * @param release original release / 原发布
     * @throws SQLException if original registration changed / 原登记变化时 */
    private static void validateRelease(Connection c, TaskRecoveryGraph.Release release) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT content_sha256,schema_version,state FROM mod_version WHERE mod_id=? AND version=?")) {
            p.setString(1, release.owner());
            p.setString(2, release.version());
            try (var r = p.executeQuery()) {
                require(r.next() && release.digest().equals(r.getString(1)) && "READY".equals(r.getString(3)) && Objects
                        .equals(release.schema().map(SchemaIdentity::schemaVersion).orElse(null), r.getString(2)),
                        "Original release changed");
            }
        }
        if (release.schema().isPresent())
            new SchemaCoordinates(release.schema().get()).requireRegistered(c, Set.of("READY"));
    }

    /** Reads and cross-checks every member of every persisted group. / 读取并交叉核对每个持久组的全部成员。
     * @param c serialized connection / 串行连接
     * @return complete original groups / 完整原组
     * @throws SQLException if any original receipt is inconsistent / 任一原凭据不一致时 */
    private List<Recovery> read(Connection c) throws SQLException {
        var groups = new LinkedHashMap<UUID, Recovery>();
        try (var p = c.createStatement();
                var r = p.executeQuery(
                        "SELECT * FROM mod_operation WHERE operation_type='RECONCILE' AND phase IN ('RECOVERING','VERIFIED') ORDER BY mod_id")) {
            while (r.next()) {
                String request = r.getString("request_json"), evidence = r.getString("recovery_json"),
                        owner = r.getString("mod_id");
                var document = object(request);
                var proof = object(evidence);
                require(document.size() == 4 && "MOD_SESSION".equals(document.path("mode").textValue())
                        && proof.size() == 5 && proof.path("registryState").isTextual()
                        && proof.path("registryVersion").isTextual(), "Invalid recovery receipt shape");
                UUID id = uuid(document, "group");
                uuid(proof, "bootId");
                if (!"".equals(proof.path("registryBootId").textValue()))
                    uuid(proof, "registryBootId");
                TaskRecoveryGraph graph;
                try {
                    graph = TaskRecoveryGraph.decode(document.path("graph"));
                } catch (IllegalArgumentException invalid) {
                    throw new SQLException("Invalid original recovery graph", invalid);
                }
                require(graph.releases().stream().anyMatch(value -> value.owner().equals(owner)),
                        "Unexpected recovery member");
                var release = graph.release(owner);
                boolean verified = r.getString("phase").equals("VERIFIED");
                require(release.version().equals(r.getString("target_version"))
                        && Objects.equals(release.schema().map(SchemaIdentity::schemaVersion).orElse(null),
                                r.getString("target_schema_version"))
                        && Objects.equals(release.schema().map(SchemaIdentity::generation).orElse(null),
                                r.getObject("target_generation", UUID.class))
                        && (verified
                                ? "FINISHING".equals(r.getString("state")) && "SUCCEEDED".equals(r.getString("outcome"))
                                : Set.of("RUNNING", "UNKNOWN").contains(r.getString("state"))
                                        && r.getString("outcome") == null),
                        "Recovery receipt identity changed");
                var row = new Row(r.getObject("operation_id", UUID.class), owner,
                        UUID.fromString(r.getString("archive_log_id")), r.getLong("accepted_revision"), request,
                        evidence);
                var old = groups.get(id);
                var rows = new ArrayList<Row>();
                if (old != null) {
                    require(old.graph().equals(graph) && old.verified() == verified
                            && old.rows().stream().noneMatch(value -> value.owner().equals(owner)),
                            "Recovery group conflicts");
                    rows.addAll(old.rows());
                }
                rows.add(row);
                groups.put(id, new Recovery(id, graph, verified, rows));
            }
        }
        for (var group : groups.values())
            require(group.rows().size() == group.graph().releases().size(), "Recovery group is incomplete");
        return List.copyOf(groups.values());
    }

    /** Requires exact immutable receipts before every mutation. / 在每次变更前要求精确不可变凭据。
     * @param c serialized connection / 串行连接
     * @param original expected receipt / 期望凭据
     * @throws SQLException if original ownership changed / 原归属变化时 */
    private void unchanged(Connection c, Recovery original) throws SQLException {
        require(read(c).contains(original), "Original recovery receipt changed");
    }

    /** Requires the captured old runtime slot and unchanged user intent. / 要求捕获的旧运行位置及未变更用户意图。
     * @param c serialized connection / 串行连接
     * @param row original receipt / 原凭据
     * @throws SQLException if another activation or intent owns this member / 另一启用或意图持有此成员时 */
    private static void registry(Connection c, Row row) throws SQLException {
        var proof = object(row.evidence());
        try (var p = c.prepareStatement(
                "SELECT revision,last_boot_id,last_runtime_state,actual_version FROM mod_registry WHERE mod_id=?")) {
            p.setString(1, row.owner());
            try (var r = p.executeQuery()) {
                require(r.next() && row.revision() == r.getLong(1)
                        && proof.path("registryBootId").textValue()
                                .equals(Objects.toString(r.getObject(2, UUID.class), ""))
                        && proof.path("registryState").textValue().equals(r.getString(3))
                        && proof.path("registryVersion").textValue().equals(Objects.toString(r.getString(4), "")),
                        "Original runtime receipt changed");
            }
        }
    }

    /** Requires all tasks and resource claims to disappear before acknowledgement. / 在确认前要求全部任务及资源申请消失。
     * @param c serialized connection / 串行连接
     * @param row original member / 原成员
     * @throws SQLException if retained work or another operation remains / 保留工作或其他操作残留时 */
    private static void drained(Connection c, Row row) throws SQLException {
        require(count(c,
                "SELECT (SELECT COUNT(*) FROM platform_task WHERE owner_id=?)+(SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND (owner_id=? OR resource_kind='MOD' AND resource_id=?))",
                row.owner(), row.owner(), row.owner()) == 0, "Original recovery work remains");
        maintenance(c, row.owner(), row.operation());
    }

    /** Preserves unapplied restart intent while rejecting concurrent maintenance. / 保留未应用重启意图，同时拒绝并发维护。
     * @param c original transaction / 原事务
     * @param owner original member / 原成员
     * @param operation this recovery operation, absent before retention / 本恢复操作，保留前不存在
     * @throws SQLException if another executing or inconsistent operation remains / 其他执行中或不一致操作残留时 */
    private static void maintenance(Connection c, String owner, UUID operation) throws SQLException {
        require(count(c,
                "SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND (? IS NULL OR operation_id<>?) AND NOT COALESCE(operation_type IN ('ENABLE','DISABLE','SWITCH') AND state='WAITING' AND phase='WAIT_RESTART' AND outcome IS NULL AND accepted_revision=(SELECT revision FROM mod_registry WHERE mod_id=?),FALSE)",
                owner, operation, operation, owner) == 0, "Another operation retains this Mod");
    }

    /** Decodes a bounded versioned object with strict JSON parsing. / 以严格 JSON 解析解码有界版本化对象。
     * @param encoded stored document / 存储文档
     * @return strict object / 严格对象
     * @throws SQLException if malformed or unsupported / 畸形或不受支持时 */
    private static JsonNode object(String encoded) throws SQLException {
        require(encoded != null && encoded.length() <= 1048576, "Invalid recovery document size");
        try {
            var value = JSON.readTree(encoded);
            require(value != null && value.isObject() && value.path("formatVersion").isInt()
                    && value.path("formatVersion").intValue() == 1, "Invalid recovery format");
            return value;
        } catch (java.io.IOException invalid) {
            throw new SQLException("Invalid recovery document", invalid);
        }
    }

    /** Reads a canonical required UUID. / 读取规范必需 UUID。
     * @param node original object / 原对象
     * @param field required key / 必需键
     * @return exact identity / 精确身份
     * @throws SQLException if absent or noncanonical / 缺失或不规范时 */
    private static UUID uuid(JsonNode node, String field) throws SQLException {
        try {
            String text = node.path(field).textValue();
            var value = UUID.fromString(text);
            require(value.toString().equals(text), "Noncanonical recovery identity");
            return value;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid recovery identity", invalid);
        }
    }

    /** Reads a bounded occupancy count. / 读取有界占用数量。
     * @param c original transaction / 原事务
     * @param sql fixed query / 固定查询
     * @param values bound identities / 绑定身份
     * @return count / 数量
     * @throws SQLException if query fails / 查询失败时 */
    private static long count(Connection c, String sql, Object... values) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++)
                p.setObject(i + 1, values[i]);
            try (var r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    /** Writes one exact member transition. / 写入一个精确成员转换。
     * @param c original transaction / 原事务
     * @param sql fixed mutation / 固定变更
     * @param values bound identities / 绑定身份
     * @throws SQLException if the original row is absent / 原行缺失时 */
    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++)
                p.setObject(i + 1, values[i]);
            require(p.executeUpdate() == 1, "Original recovery row changed");
        }
    }

    /** Rejects an unproved transition. / 拒绝未证明转换。
     * @param condition established fact / 已确认事实
     * @param message constant diagnosis / 常量诊断
     * @throws SQLException if unproved / 未证明时 */
    private static void require(boolean condition, String message) throws SQLException {
        if (!condition)
            throw new SQLException(message);
    }

    /** Serializes only platform work, never author callbacks. / 仅串行平台工作，不包含作者回调。
     * @param <T> result type / 结果类型
     * @param work original transaction work / 原事务工作
     * @return committed result / 已提交结果
     * @throws SQLException if transaction ownership or persistence fails / 事务归属或持久化失败时 */
    private <T> T locked(Work<T> work) throws SQLException {
        var result = new AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
                require(count(c,
                        "SELECT COUNT(*) FROM app_state WHERE singleton_id=1 AND boot_id=? AND shutdown_state IN ('STARTING','RUNNING')",
                        database.bootId()) == 1, "Recovery no longer owns this boot");
                result.set(work.run(c));
            }
        });
        return result.get();
    }

    /** Internal transaction without author code. / 不含作者代码的内部事务。
     * @param <T> result type / 结果类型 */
    @FunctionalInterface
    private interface Work<T> {
        /** Executes one bounded transaction. / 执行一个有界事务。
         * @param c original connection / 原连接
         * @return result / 结果
         * @throws SQLException if persistence fails / 持久化失败时 */
        T run(Connection c) throws SQLException;
    }
}
