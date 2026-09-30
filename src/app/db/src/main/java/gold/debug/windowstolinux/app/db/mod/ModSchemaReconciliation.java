package gold.debug.windowstolinux.app.db.mod;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.execution.schema.*;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Reconciles original unknown data without running migration or changing user choices. / 核对原未知数据，不运行迁移或改变用户选择。 */
public final class ModSchemaReconciliation {
    /** Actual exclusively owned engine. / 实际独占引擎。 */
    private final PlatformDatabase database;

    /** Serialized platform transaction scope. / 串行平台事务作用域。 */
    private final RepositoryConnections connections;

    /** Original platform operation archive. / 原平台操作归档。 */
    private final LogRepository logs;

    /** Registered operation category. / 已登记操作类别。 */
    private final LogRepository.Target archive;

    /** Strict bounded recovery parser. / 严格有界恢复解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Binds host-only reconciliation to the current engine. / 将仅供宿主的核对绑定到当前引擎。
     * @param database actual engine / 实际引擎
     * @throws SQLException if the archive cannot be registered / 无法登记归档时 */
    public ModSchemaReconciliation(PlatformDatabase database) throws SQLException {
        this.database = database;
        connections = new RepositoryConnections(database::openConnection);
        logs = new LogRepository(database);
        archive = logs.register("app", Optional.empty(),
                new LogCategory("mod_operation", "mod_operation", Optional.empty()));
    }

    /** Immutable original operation evidence; only this ledger can construct it. / 不可变原操作证据，仅此账本可构造。 */
    public static final class Review {
        /** Original preparation identities. / 原准备身份。 */
        private final ModRestartLedger.Preparation plan;

        /** Exact original selected program. / 精确原选定程序。 */
        private final String version, manifest, digest;

        /** Original recovery inventory and execution facts. / 原恢复清单及执行事实。 */
        private final String recovery, request;

        /** Original selection revision. / 原选择修订。 */
        private final long revision;

        /** Original preparation boot. / 原准备启动身份。 */
        private final UUID boot;
        /** Constructs a validated original review. / 构造已验证的原审阅。
         * @param plan original identities / 原身份
         * @param version original version / 原版本
         * @param manifest original declaration / 原声明
         * @param digest original build digest / 原构建摘要
         * @param recovery original recovery text / 原恢复文本
         * @param request original parent request / 原父请求
         * @param revision original accepted revision / 原接纳修订
         * @param boot original boot / 原启动 */
        private Review(ModRestartLedger.Preparation plan, String version, String manifest, String digest,
                String recovery, String request, long revision, UUID boot) {
            this.plan = plan;
            this.version = version;
            this.manifest = manifest;
            this.digest = digest;
            this.recovery = recovery;
            this.request = request;
            this.revision = revision;
            this.boot = boot;
        }

        /** Returns the original immutable identities. / 返回原不可变身份。
         * @return original preparation / 原准备 */
        public ModRestartLedger.Preparation plan() {
            return plan;
        }

        /** Returns the exact original program version. / 返回精确原程序版本。
         * @return original version / 原版本 */
        public String version() {
            return version;
        }

        /** Returns the exact verified declaration. / 返回精确已验证声明。
         * @return original declaration / 原声明 */
        public String manifest() {
            return manifest;
        }

        /** Returns the exact verified content digest. / 返回精确已验证内容摘要。
         * @return original content digest / 原内容摘要 */
        public String digest() {
            return digest;
        }

        /** Returns the source fingerprint recorded before author migration. / 返回作者迁移前记录的来源指纹。
         * @return original fingerprint, absent for initialization / 原指纹，初始化为空
         * @throws SQLException if recovery is malformed / 恢复格式错误时 */
        public Optional<String> sourceDigest() throws SQLException {
            String value = object(recovery).path("facts").path("sourceBefore").textValue();
            return Optional.ofNullable(value);
        }

        /** Proves a standalone retry stopped before reset acknowledgement and before any author execution. / 证明独立重试停在重置确认之前且尚未执行作者代码。
         * @return whether only the original failed target can remain / 是否只可能残留原失败目标
         * @throws SQLException if recovery cannot be read / 无法读取恢复记录时 */
        public boolean beforeRetryExecution() throws SQLException {
            var facts = object(recovery).path("facts");
            return plan.intent().equals(plan.operation()) && !facts.path("retryResetComplete").asBoolean()
                    && !facts.has("created");
        }
    }

    /** One explicit retained validation attempt, separate from original author migration. / 一次明确保留的验证尝试，与原作者迁移分开。
     * @param review original evidence / 原证据
     * @param operation original reconciliation operation / 原核对操作
     * @param reader tracked validation reader / 受跟踪验证读取身份
     * @param archive fixed reconciliation summary / 固定核对摘要 */
    public record Attempt(Review review, UUID operation, UUID reader, UUID archive) {
    }

    /** Reads exact original identities without class loading or data changes. / 读取精确原身份，不加载类或改变数据。
     * @param operation original MIGRATE operation / 原 MIGRATE 操作
     * @return immutable review / 不可变审阅
     * @throws SQLException if original unknown ownership cannot be established / 无法确定原未知归属时 */
    public Review review(UUID operation) throws SQLException {
        return locked(c -> read(c, operation));
    }

    /** Claims explicit read-only reconciliation, preserving the original unknown target. / 取得明确只读核对，保留原未知目标。
     * @param review previously inspected original evidence / 此前审阅原证据
     * @return original durable attempt / 原持久尝试
     * @throws SQLException if evidence, occupancy or program changed / 证据、占用或程序变化时 */
    public Attempt begin(Review review) throws SQLException {
        return locked(c -> {
            unchanged(c, review);
            require(!database.bootId().equals(review.boot),
                    "Original preparation must be closed by a later engine boot");
            drained(c, review, null);
            UUID id = UUID.randomUUID(), reader = UUID.randomUUID(), log = UUID.randomUUID();
            long created = System.currentTimeMillis();
            try (var p = c.prepareStatement(
                    "SELECT operation_id,recovery_json,archive_log_id,state,created_at FROM mod_operation WHERE mod_id=? AND operation_type='RECONCILE'")) {
                p.setString(1, review.plan.target().ownerId());
                try (var r = p.executeQuery()) {
                    if (r.next()) {
                        require("UNKNOWN".equals(r.getString(4)), "Original reconciliation is still active");
                        var old = object(r.getString(2));
                        require(review.plan.operation().toString().equals(old.path("preparation").asText()),
                                "Another reconciliation owns this Mod");
                        id = r.getObject(1, UUID.class);
                        reader = uuid(old, "reader");
                        log = UUID.fromString(r.getString(3));
                        created = r.getLong(5);
                        require(!r.next(), "Multiple reconciliations are unresolved");
                    }
                }
            }
            require(database.openConnections(reader.toString()) == 0, "Original reconciliation connections remain");
            long now = System.currentTimeMillis();
            String recovery = JSON.createObjectNode().put("formatVersion", 1)
                    .put("preparation", review.plan.operation().toString()).put("reader", reader.toString())
                    .put("bootId", database.bootId().toString()).toString();
            try (var p = c.prepareStatement(
                    "MERGE INTO mod_operation(operation_id,mod_id,operation_type,target_version,target_schema_version,target_generation,accepted_revision,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) KEY(operation_id) VALUES(?,?,'RECONCILE',?,?,?,?,'RUNNING','VALIDATING',?,?,?, ?,?)")) {
                p.setObject(1, id);
                p.setString(2, review.plan.target().ownerId());
                p.setString(3, review.version);
                p.setString(4, review.plan.target().schemaVersion());
                p.setObject(5, review.plan.target().generation());
                p.setLong(6, review.revision);
                p.setString(7, review.request);
                p.setString(8, recovery);
                p.setString(9, log.toString());
                p.setLong(10, created);
                p.setLong(11, now);
                p.executeUpdate();
            }
            return new Attempt(review, id, reader, log);
        });
    }

    /** Releases only recorded restricted identities and proven created intermediates. / 仅释放已记录受限身份及已证明创建的中间目标。
     * @param attempt current original validation / 当前原验证
     * @param intermediates whether known validation permits intermediate cleanup / 已知验证是否允许中间清理
     * @throws SQLException if ownership or physical release is uncertain / 归属或物理释放不确定时 */
    public void cleanup(Attempt attempt, boolean intermediates) throws SQLException {
        locked(c -> {
            active(c, attempt);
            return null;
        });
        var review = attempt.review();
        var plan = review.plan;
        var readers = new LinkedHashMap<UUID, SchemaCoordinates>();
        readers.put(plan.reader(), new SchemaCoordinates(plan.target()));
        readers.put(attempt.reader(), new SchemaCoordinates(plan.target()));
        for (var step : plan.steps()) {
            readers.put(step.sourceReader(), ModRestartLedger.sourceCoordinates(plan, step));
            readers.put(step.reader(), ModRestartLedger.targetCoordinates(plan, step));
        }
        for (var entry : readers.entrySet())
            new SchemaProvisioner(database).releaseRecordedReader(entry.getKey(), entry.getValue());
        if (intermediates) {
            var created = object(review.recovery).path("facts").path("created");
            for (var step : plan.steps().subList(0, Math.max(0, plan.steps().size() - 1))) {
                var target = ModRestartLedger.targetCoordinates(plan, step);
                new SchemaProvisioner(database).dropReconciledIntermediate(target,
                        step.target().generation().toString().equals(created.path(target.physicalName()).asText()));
            }
        }
    }

    /** Checks whether a new target needs validation or evidence already proves preparation incomplete. / 检查新目标是否需要验证，或证据是否已证明准备未完成。
     * @param attempt current attempt / 当前尝试
     * @return whether a newly created target needs author validation; false proves incomplete preparation / 新创建目标是否需要作者验证，假证明准备未完成
     * @throws SQLException if a collision, partial object or inventory mismatch remains / 仍有冲突、部分对象或清单不匹配时 */
    public boolean targetNeedsValidation(Attempt attempt) throws SQLException {
        return locked(c -> {
            active(c, attempt);
            var target = new SchemaCoordinates(attempt.review.plan.target());
            try (var p = c.prepareStatement(
                    "SELECT (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?),(SELECT COUNT(*) FROM information_schema.users WHERE user_name=?)")) {
                p.setString(1, target.physicalName());
                p.setString(2, target.databaseUser());
                try (var r = p.executeQuery()) {
                    r.next();
                    if (r.getLong(1) == 0 && r.getLong(2) == 0)
                        return false;
                    if (attempt.review.beforeRetryExecution()) {
                        if (r.getLong(1) != 0)
                            target.requirePhysicalOwnership(c);
                        try (var q = c.prepareStatement(
                                "SELECT (SELECT COUNT(*) FROM information_schema.users WHERE user_name=? AND is_admin)+(SELECT COUNT(*) FROM information_schema.schemata WHERE schema_owner=? AND schema_name<>?)+(SELECT COUNT(*) FROM information_schema.sessions WHERE LOWER(user_name)=?)")) {
                            q.setString(1, target.databaseUser());
                            q.setString(2, target.databaseUser());
                            q.setString(3, target.physicalName());
                            q.setString(4, target.databaseUser());
                            try (var owned = q.executeQuery()) {
                                owned.next();
                                require(owned.getLong(1) == 0, "Original failed target ownership changed during reset");
                            }
                        }
                        return false;
                    }
                    require(r.getLong(1) == 1 && r.getLong(2) == 1,
                            "Original target has uncertain partial physical objects");
                }
            }
            var created = object(attempt.review.recovery).path("facts").path("created");
            require(target.identity().generation().toString().equals(created.path(target.physicalName()).asText()),
                    "Physical target lacks original creation acknowledgement");
            target.requirePhysicalOwnership(c);
            return true;
        });
    }

    /** Atomically records proven data state and archives original preparation without starting business. / 原子记录已证明数据状态并归档原准备，不启动业务。
     * @param attempt retained validation attempt / 保留验证尝试
     * @param outcome proven success, failure, or unknown / 已证明成功、失败或未知
     * @param digest successful author and structural receipt / 成功作者及结构凭据
     * @throws SQLException if cleanup, identity or archive remains unconfirmed / 清理、身份或归档仍未确认时 */
    public void finish(Attempt attempt, Outcome outcome, String digest) throws SQLException {
        require(Set.of(Outcome.SUCCEEDED, Outcome.FAILED, Outcome.UNKNOWN).contains(outcome),
                "Invalid reconciliation result");
        require(outcome != Outcome.SUCCEEDED || digest != null && digest.matches("[0-9a-f]{64}"),
                "Successful validation receipt missing");
        locked(c -> {
            if (finished(c, attempt, outcome, digest))
                return null;
            active(c, attempt);
            var review = attempt.review;
            var plan = review.plan;
            require(database.openConnections(attempt.reader.toString()) == 0, "Reconciliation connections remain");
            var readers = new HashSet<>(List.of(plan.reader(), attempt.reader()));
            plan.steps().forEach(s -> readers.addAll(List.of(s.sourceReader(), s.reader())));
            for (var reader : readers) {
                try (var p = c.prepareStatement("SELECT COUNT(*) FROM information_schema.users WHERE user_name=?")) {
                    p.setString(1, SchemaAccessScope.readerUser(reader));
                    try (var r = p.executeQuery()) {
                        r.next();
                        require(r.getLong(1) == 0, "Original temporary reader cleanup remains");
                    }
                }
            }
            if (outcome == Outcome.UNKNOWN) {
                try (var p = c.prepareStatement(
                        "UPDATE mod_operation SET state='UNKNOWN',failure_code='MOD_SCHEMA_RECONCILIATION_UNKNOWN',updated_at=? WHERE operation_id=?")) {
                    p.setLong(1, System.currentTimeMillis());
                    p.setObject(2, attempt.operation);
                    p.executeUpdate();
                }
                return null;
            }
            for (var step : plan.steps().subList(0, Math.max(0, plan.steps().size() - 1))) {
                var target = ModRestartLedger.targetCoordinates(plan, step);
                try (var p = c.prepareStatement(
                        "SELECT (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?)+(SELECT COUNT(*) FROM information_schema.users WHERE user_name=?)")) {
                    p.setString(1, target.physicalName());
                    p.setString(2, target.databaseUser());
                    try (var r = p.executeQuery()) {
                        r.next();
                        require(r.getLong(1) == 0, "Intermediate cleanup remains");
                    }
                }
            }
            long now = System.currentTimeMillis();
            try (var p = c.prepareStatement(
                    "UPDATE mod_schema SET state=?,validated_at=?,validation_digest=? WHERE generation=? AND state='UNKNOWN' AND NOT ever_used")) {
                p.setString(1, outcome == Outcome.SUCCEEDED ? "READY" : "FAILED");
                p.setObject(2, outcome == Outcome.SUCCEEDED ? now : null);
                p.setString(3, digest);
                p.setObject(4, plan.target().generation());
                require(p.executeUpdate() == 1, "Original target changed");
            }
            String event = outcome == Outcome.SUCCEEDED
                    ? "MOD_SCHEMA_RECONCILED_READY"
                    : "MOD_SCHEMA_RECONCILED_FAILED";
            logs.store(c, archive,
                    List.of(new LogRecordCodec.Record(plan.archive(), now, "app", "mod_operation", LogLevel.INFO, event,
                            "Original data preparation was explicitly reconciled", null, plan.target().ownerId(),
                            review.version, null, plan.operation(), outcome, false),
                            new LogRecordCodec.Record(attempt.archive, now, "app", "mod_operation", LogLevel.INFO,
                                    event, "Read-only reconciliation and original cleanup completed", null,
                                    plan.target().ownerId(), review.version, null, attempt.operation, outcome, false)),
                    100);
            if (!plan.intent().equals(plan.operation()))
                try (var p = c.prepareStatement("SELECT archive_log_id FROM mod_operation WHERE operation_id=?")) {
                    p.setObject(1, plan.intent());
                    try (var r = p.executeQuery()) {
                        require(r.next(), "Original activation intent disappeared");
                        logs.store(c, archive, List.of(new LogRecordCodec.Record(UUID.fromString(r.getString(1)), now,
                                "app", "mod_operation", LogLevel.WARN, "MOD_ACTIVATION_INTERRUPTED",
                                "Original startup was interrupted; data reconciliation does not start business", null,
                                plan.target().ownerId(), review.version, null, plan.intent(), Outcome.FAILED, false)),
                                100);
                    }
                }
            try (var p = c.prepareStatement(
                    "UPDATE mod_registry SET last_runtime_state='FAILED',actual_version=NULL,last_boot_id=?,failure_code=?,failure_detail=NULL WHERE mod_id=? AND revision=?")) {
                p.setObject(1, database.bootId());
                p.setString(2,
                        outcome == Outcome.SUCCEEDED
                                ? "MOD_DATA_READY_RESTART_REQUIRED"
                                : "MOD_SCHEMA_MIGRATION_FAILED");
                p.setString(3, plan.target().ownerId());
                p.setLong(4, review.revision);
                require(p.executeUpdate() == 1, "Original selection changed");
            }
            try (var p = c.prepareStatement("DELETE FROM mod_migration_step WHERE operation_id=?")) {
                p.setObject(1, plan.operation());
                p.executeUpdate();
            }
            try (var p = c.prepareStatement("DELETE FROM mod_operation WHERE operation_id IN (?,?,?)")) {
                p.setObject(1, plan.operation());
                p.setObject(2, plan.intent());
                p.setObject(3, attempt.operation);
                require(p.executeUpdate() == (plan.intent().equals(plan.operation()) ? 2 : 3),
                        "Original operation ownership changed");
            }
            return null;
        });
    }

    /** Reads and validates original inventory, program and never-used target. / 读取并验证原清单、程序及从未使用的目标。
     * @param c serialized connection / 串行连接
     * @param operation original preparation / 原准备
     * @return exact original review / 精确原审阅
     * @throws SQLException if recovery cannot be trusted / 无法信任恢复记录时 */
    private Review read(Connection c, UUID operation) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT o.mod_id,o.target_version,o.target_schema_version,o.target_generation,o.accepted_revision,o.recovery_json,o.archive_log_id,v.manifest_json,v.content_sha256,o.phase FROM mod_operation o JOIN mod_version v ON v.mod_id=o.mod_id AND v.version=o.target_version WHERE o.operation_id=? AND o.operation_type='MIGRATE' AND o.state='UNKNOWN' AND o.outcome IS NULL AND v.state='READY'")) {
            p.setObject(1, operation);
            try (var r = p.executeQuery()) {
                require(r.next(), "Original unknown preparation is unavailable");
                String owner = r.getString(1), version = r.getString(2), recovery = r.getString(6);
                var node = object(recovery);
                require(node.path("formatVersion").asInt() == 1 && node.path("facts").isObject()
                        && node.path("steps").isArray(), "Unknown preparation inventory format");
                require(fields(node).equals(node.has("source")
                        ? Set.of("formatVersion", "bootId", "intent", "writer", "reader", "physicalSchema",
                                "databaseUser", "facts", "source", "steps")
                        : Set.of("formatVersion", "bootId", "intent", "writer", "reader", "physicalSchema",
                                "databaseUser", "facts", "steps")),
                        "Unknown preparation inventory fields");
                var target = new SchemaIdentity(owner, r.getString(3), r.getObject(4, UUID.class));
                var coords = new SchemaCoordinates(target);
                var manifest = object(r.getString(8));
                require(owner.equals(manifest.path("id").textValue())
                        && version.equals(manifest.path("version").textValue())
                        && target.schemaVersion().equals(manifest.path("schemaVersion").textValue()),
                        "Original program and target disagree");
                require(coords.physicalName().equals(node.path("physicalSchema").asText())
                        && coords.databaseUser().equals(node.path("databaseUser").asText()),
                        "Original final coordinates changed");
                coords.requireRegistered(c, Set.of("UNKNOWN"));
                try (var q = c.prepareStatement(
                        "SELECT ever_used,validated_at,validation_digest FROM mod_schema WHERE generation=?")) {
                    q.setObject(1, target.generation());
                    try (var data = q.executeQuery()) {
                        require(data.next() && !data.getBoolean(1) && data.getObject(2) == null
                                && data.getObject(3) == null,
                                "Previously usable data cannot be reconciled as first preparation");
                    }
                }
                Optional<SchemaIdentity> source = node.has("source")
                        ? Optional.of(new SchemaIdentity(owner, node.path("source").path("schemaVersion").asText(),
                                uuid(node.path("source"), "generation")))
                        : Optional.empty();
                if (source.isPresent())
                    new SchemaCoordinates(source.get()).requireRegistered(c, Set.of("READY"));
                var steps = new ArrayList<ModRestartLedger.MigrationStep>();
                for (var step : node.path("steps")) {
                    require(fields(step).equals(Set.of("from", "to", "className", "generation", "physicalSchema",
                            "databaseUser", "sourceReader", "writer", "reader")), "Unknown migration inventory fields");
                    var identity = new SchemaIdentity(owner, step.path("to").asText(), uuid(step, "generation"));
                    steps.add(new ModRestartLedger.MigrationStep(steps.size(), step.path("from").asText(),
                            step.path("to").asText(), step.path("className").asText(), identity,
                            uuid(step, "sourceReader"), uuid(step, "writer"), uuid(step, "reader")));
                }
                require(source.isPresent() == !steps.isEmpty() && steps.size() <= 256,
                        "Original chain inventory changed");
                var plan = new ModRestartLedger.Preparation(uuid(node, "intent"), operation, target,
                        uuid(node, "writer"), uuid(node, "reader"), UUID.fromString(r.getString(7)), source, steps);
                verifyMigrationInventory(c, plan, node);
                String request = originalRequest(c, plan, r.getLong(5));
                var parent = object(request);
                if (plan.intent().equals(plan.operation())) {
                    require(fields(parent)
                            .equals(Set.of("formatVersion", "mode", "ownerId", "previousGeneration", "manifest",
                                    "contentDigest", "target", "data"))
                            && "RETRY".equals(parent.path("mode").asText())
                            && !target.generation().equals(uuid(parent, "previousGeneration"))
                            && r.getString(8).equals(parent.path("manifest").asText())
                            && r.getString(9).equals(parent.path("contentDigest").asText()),
                            "Original retry evidence changed");
                    require(!node.path("facts").has("created")
                            || node.path("facts").path("retryResetComplete").asBoolean(),
                            "Retry created data before proving reset");
                    if (!node.path("facts").path("retryResetComplete").asBoolean()) {
                        require(Set.of("SOURCE_CHECKED", "MIGRATING").contains(r.getString(10)),
                                "Retry reset evidence contradicts its original phase");
                        try (var q = c.prepareStatement(
                                "SELECT COUNT(*) FROM mod_migration_step WHERE operation_id=? AND state<>'PENDING'")) {
                            q.setObject(1, operation);
                            try (var pending = q.executeQuery()) {
                                pending.next();
                                require(pending.getLong(1) == 0, "Migration ran before reset acknowledgement");
                            }
                        }
                    }
                }
                verifyOriginalChain(plan, parent, version, manifest.path("migrations"));
                return new Review(plan, version, r.getString(8), r.getString(9), recovery, request, r.getLong(5),
                        uuid(node, "bootId"));
            }
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Invalid original reconciliation identity");
        }
    }

    /** Rejects any change since the original inspection. / 拒绝原审阅之后的任何依据变化。
     * @param c serialized connection / 串行连接
     * @param review original evidence / 原证据
     * @throws SQLException if evidence changed / 证据变化时 */
    private void unchanged(Connection c, Review review) throws SQLException {
        var current = read(c, review.plan.operation());
        require(current.plan.equals(review.plan) && current.version.equals(review.version)
                && current.manifest.equals(review.manifest) && current.digest.equals(review.digest)
                && current.recovery.equals(review.recovery) && current.request.equals(review.request)
                && current.revision == review.revision && current.boot.equals(review.boot),
                "Reconciliation review is stale");
    }

    /** Checks retained own work, cross-owner occupancy and other maintenance. / 检查保留自有工作、跨所有者占用及其他维护。
     * @param c serialized connection / 串行连接
     * @param review original evidence / 原证据
     * @param attempt original attempt, absent before claim / 原尝试，取得前为空
     * @throws SQLException if unresolved work exists / 存在未解决工作时 */
    private void drained(Connection c, Review review, UUID attempt) throws SQLException {
        String owner = review.plan.target().ownerId();
        try (var p = c.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM platform_task WHERE owner_id=? AND state<>'ENDED')+(SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND (owner_id=? OR resource_kind='MOD' AND resource_id=?))+(SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND operation_id NOT IN (?,?) AND operation_type<>'RECONCILE' AND state<>'ENDED')")) {
            p.setString(1, owner);
            p.setString(2, owner);
            p.setString(3, owner);
            p.setString(4, owner);
            p.setObject(5, review.plan.operation());
            p.setObject(6, review.plan.intent());
            try (var r = p.executeQuery()) {
                r.next();
                require(r.getLong(1) == 0, "Original Mod work or maintenance remains");
            }
        }
        if (attempt != null)
            try (var p = c.prepareStatement(
                    "SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND operation_type='RECONCILE' AND operation_id<>?")) {
                p.setString(1, owner);
                p.setObject(2, attempt);
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 0, "Another reconciliation remains");
                }
            }
        var scopes = new HashSet<>(List.of(review.plan.writer(), review.plan.reader()));
        review.plan.steps().forEach(s -> scopes.addAll(List.of(s.sourceReader(), s.writer(), s.reader())));
        for (var scope : scopes)
            require(database.openConnections(scope.toString()) == 0, "Original preparation connection remains");
    }

    /** Verifies the current explicit attempt without granting author writes. / 验证当前明确尝试，不授予作者写权限。
     * @param c serialized connection / 串行连接
     * @param attempt original attempt / 原尝试
     * @throws SQLException if identity or occupancy changed / 身份或占用变化时 */
    private void active(Connection c, Attempt attempt) throws SQLException {
        unchanged(c, attempt.review);
        drained(c, attempt.review, attempt.operation);
        try (var p = c.prepareStatement(
                "SELECT recovery_json FROM mod_operation WHERE operation_id=? AND operation_type='RECONCILE' AND state='RUNNING' AND phase='VALIDATING' AND target_generation=? AND archive_log_id=?")) {
            p.setObject(1, attempt.operation);
            p.setObject(2, attempt.review.plan.target().generation());
            p.setString(3, attempt.archive.toString());
            try (var r = p.executeQuery()) {
                require(r.next(), "Original reconciliation is not active");
                var node = object(r.getString(1));
                require(database.bootId().equals(uuid(node, "bootId")) && attempt.reader.equals(uuid(node, "reader"))
                        && attempt.review.plan.operation().equals(uuid(node, "preparation")),
                        "Original reconciliation ownership changed");
            }
        }
    }

    /** Recognizes a completed original commit without recreating purged history. / 识别已完成原提交，不重建已清除历史。
     * @param c serialized connection / 串行连接
     * @param attempt original attempt / 原尝试
     * @param outcome original result / 原结果
     * @param digest original validation receipt / 原验证凭据
     * @return whether the exact terminal state is already present / 精确终态是否已经存在
     * @throws SQLException if evidence cannot be read / 无法读取证据时 */
    private boolean finished(Connection c, Attempt attempt, Outcome outcome, String digest) throws SQLException {
        if (outcome == Outcome.UNKNOWN) {
            try (var p = c.prepareStatement(
                    "SELECT recovery_json FROM mod_operation WHERE operation_id=? AND state='UNKNOWN' AND archive_log_id=? AND target_generation=?")) {
                p.setObject(1, attempt.operation);
                p.setString(2, attempt.archive.toString());
                p.setObject(3, attempt.review.plan.target().generation());
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        return false;
                    var node = object(r.getString(1));
                    return database.bootId().equals(uuid(node, "bootId")) && attempt.reader.equals(uuid(node, "reader"))
                            && attempt.review.plan.operation().equals(uuid(node, "preparation"));
                }
            }
        }
        try (var p = c.prepareStatement(
                "SELECT s.state,s.validation_digest,(SELECT COUNT(*) FROM mod_operation WHERE operation_id IN (?,?,?)) FROM mod_schema s WHERE s.generation=?")) {
            p.setObject(1, attempt.operation);
            p.setObject(2, attempt.review.plan.operation());
            p.setObject(3, attempt.review.plan.intent());
            p.setObject(4, attempt.review.plan.target().generation());
            try (var r = p.executeQuery()) {
                return r.next() && (outcome == Outcome.SUCCEEDED ? "READY" : "FAILED").equals(r.getString(1))
                        && Objects.equals(digest, r.getString(2)) && r.getLong(3) == 0;
            }
        }
    }

    /** Parses only bounded versioned recovery objects. / 仅解析有界版本化恢复对象。
     * @param text original JSON / 原 JSON
     * @return strict object / 严格对象
     * @throws SQLException if malformed / 格式错误时 */
    private static JsonNode object(String text) throws SQLException {
        try {
            require(text != null && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1048576,
                    "Recovery envelope exceeds its bound");
            var node = JSON.readTree(text);
            require(node != null && node.isObject(), "Recovery object required");
            return node;
        } catch (java.io.IOException invalid) {
            throw new SQLException("Invalid reconciliation recovery data");
        }
    }

    /** Reads one canonical UUID without guessing missing identities. / 读取一个规范 UUID，不猜测缺失身份。
     * @param node original object / 原对象
     * @param field identity field / 身份字段
     * @return exact UUID / 精确 UUID
     * @throws SQLException if invalid / 无效时 */
    private static UUID uuid(JsonNode node, String field) throws SQLException {
        try {
            String text = node.path(field).textValue();
            var id = UUID.fromString(text);
            require(id.toString().equals(text), "Noncanonical recovery UUID");
            return id;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid recovery UUID");
        }
    }

    /** Returns exact persisted field names for strict inventory validation. / 返回精确持久字段名以严格验证清单。
     * @param node original JSON object / 原 JSON 对象
     * @return immutable field set / 不可变字段集合 */
    private static Set<String> fields(JsonNode node) {
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    /** Rejects an unproved transition. / 拒绝未证明转换。
     * @param proven checked predicate / 已核验条件
     * @param message constant diagnosis / 常量诊断
     * @throws SQLException when unproved / 未证明时 */
    private static void require(boolean proven, String message) throws SQLException {
        if (!proven)
            throw new SQLException(message);
    }

    /** Serializes a short host transition against shutdown and task admission. / 将短宿主转换与关闭及任务准入串行。
     * @param <T> result type / 结果类型
     * @param work bounded platform work / 有界平台工作
     * @return committed result / 已提交结果
     * @throws SQLException if transaction fails / 事务失败时 */
    private <T> T locked(Work<T> work) throws SQLException {
        var result = new AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
                try (var s = c.createStatement();
                        var r = s.executeQuery("SELECT boot_id,shutdown_state FROM app_state WHERE singleton_id=1")) {
                    require(r.next() && database.bootId().equals(r.getObject(1, UUID.class))
                            && Set.of("STARTING", "RUNNING").contains(r.getString(2)),
                            "Reconciliation no longer owns the active boot");
                }
                result.set(work.run(c));
            }
        });
        return result.get();
    }
    /** Internal transaction callback without author code. / 不含作者代码的内部事务回调。
     * @param <T> result type / 结果类型 */
    @FunctionalInterface
    private interface Work<T> {
        /** Runs one platform transition. / 运行一次平台转换。
         * @param connection original transaction / 原事务
         * @return result / 结果
         * @throws SQLException if transition fails / 转换失败时 */
        T run(Connection connection) throws SQLException;
    }

    /** Cross-checks each original migration edge against its persisted physical coordinates. / 按持久物理坐标交叉检查每条原迁移边。
     * @param c original serialized connection / 原串行连接
     * @param plan original preparation / 原准备
     * @param node original recovery inventory / 原恢复清单
     * @throws SQLException if an edge or coordinate changed / 边或坐标变化时 */
    private void verifyMigrationInventory(Connection c, ModRestartLedger.Preparation plan, JsonNode node)
            throws SQLException {
        var steps = plan.steps();
        UUID operation = plan.operation();
        int index = 0;
        try (var q = c.prepareStatement(
                "SELECT step_number,from_version,to_version,migration_class,source_schema,target_schema FROM mod_migration_step WHERE operation_id=? ORDER BY step_number")) {
            q.setObject(1, operation);
            try (var data = q.executeQuery()) {
                while (data.next()) {
                    require(index < steps.size(), "Unexpected migration step");
                    var step = steps.get(index++);
                    var declared = node.path("steps").path(step.index());
                    var physical = ModRestartLedger.targetCoordinates(plan, step);
                    require(data.getInt(1) == step.index() && data.getString(2).equals(step.from())
                            && data.getString(3).equals(step.to()) && data.getString(4).equals(step.className())
                            && data.getString(5).equals(ModRestartLedger.sourceCoordinates(plan, step).physicalName())
                            && data.getString(6).equals(physical.physicalName())
                            && physical.physicalName().equals(declared.path("physicalSchema").asText())
                            && physical.databaseUser().equals(declared.path("databaseUser").asText()),
                            "Migration inventory changed");
                }
            }
        }
        require(index == steps.size(), "Migration steps disappeared");
    }

    /** Reads the original parent intent under its unchanged registry revision and closed runtime. / 按未改变登记修订及已关闭运行态读取原父意图。
     * @param c original serialized connection / 原串行连接
     * @param plan original preparation / 原准备
     * @param revision original accepted revision / 原接纳修订
     * @return exact original request text / 精确原请求文本
     * @throws SQLException if the intent or runtime changed / 意图或运行态变化时 */
    private String originalRequest(Connection c, ModRestartLedger.Preparation plan, long revision) throws SQLException {
        String owner = plan.target().ownerId();
        UUID operation = plan.operation();
        var target = plan.target();
        try (var q = c.prepareStatement(
                "SELECT o.request_json FROM mod_operation o JOIN mod_registry m ON m.mod_id=o.mod_id WHERE o.operation_id=? AND o.mod_id=? AND o.state='UNKNOWN' AND (o.operation_type IN ('ENABLE','SWITCH') OR o.operation_type='MIGRATE' AND o.operation_id=?) AND o.target_generation=? AND o.accepted_revision=? AND m.revision=o.accepted_revision AND m.enabled AND m.selected_version=o.target_version AND m.actual_version IS NULL AND m.last_runtime_state IN ('STOPPED','FAILED')")) {
            q.setObject(1, plan.intent());
            q.setString(2, owner);
            q.setObject(3, operation);
            q.setObject(4, target.generation());
            q.setLong(5, revision);
            try (var parent = q.executeQuery()) {
                require(parent.next(), "Original intent or closed runtime changed");
                return parent.getString(1);
            }
        }
    }

    /** Verifies the original request and declared increasing chain without running migration. / 不运行迁移地核验原请求及已声明递增链。
     * @param plan original preparation / 原准备
     * @param parent exact original parent request / 精确原父请求
     * @param version original selected program version / 原选定程序版本
     * @param declarations original manifest migration edges / 原清单迁移边
     * @throws SQLException if the confirmed source or chain changed / 确认来源或链变化时 */
    private void verifyOriginalChain(ModRestartLedger.Preparation plan, JsonNode parent, String version,
            JsonNode declarations) throws SQLException {
        var target = plan.target();
        String owner = target.ownerId();
        var source = plan.source();
        var steps = plan.steps();
        var data = parent.path("data");
        require(parent.path("formatVersion").asInt() == 1 && owner.equals(parent.path("ownerId").textValue())
                && parent.path("target").path("enabled").asBoolean()
                && version.equals(parent.path("target").path("version").textValue())
                && target.schemaVersion().equals(data.path("target").textValue()), "Original request identity changed");
        if (source.isPresent()) {
            require("MIGRATE".equals(data.path("mode").textValue())
                    && source.get().schemaVersion().equals(data.path("source").textValue())
                    && source.get().generation().equals(uuid(data, "generation"))
                    && data.path("steps").size() == steps.size(), "Original confirmed source changed");
            String previous = source.get().schemaVersion();
            for (var step : steps) {
                var edge = data.path("steps").path(step.index());
                boolean declared = false;
                for (var declaration : declarations)
                    if (step.from().equals(declaration.path("from").asText())
                            && step.to().equals(declaration.path("to").asText())
                            && step.className().equals(declaration.path("class").asText()))
                        declared = true;
                require(previous.equals(step.from())
                        && new java.math.BigInteger(step.from()).compareTo(new java.math.BigInteger(step.to())) < 0
                        && step.from().equals(edge.path("from").textValue())
                        && step.to().equals(edge.path("to").textValue())
                        && step.className().equals(edge.path("className").textValue()) && declared,
                        "Original confirmed chain changed");
                previous = step.to();
            }
            require(previous.equals(target.schemaVersion()) && steps.getLast().target().equals(target)
                    && steps.getLast().writer().equals(plan.writer()) && steps.getLast().reader().equals(plan.reader()),
                    "Original final migration identities changed");
        } else
            require("INITIALIZE".equals(data.path("mode").textValue()), "Original initialization mode changed");
    }

}
