package gold.debug.windowstolinux.app.db.mod;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Applies restart intentions and explicit data retries against original durable facts. / 根据原持久事实应用重启意图和明确数据重试。 */
public final class ModRestartLedger {
    /** Existing-state responsibility collaborator. / 既有状态职责协作者。 */
    private final SetupLedger preparation = new SetupLedger();

    /** Exact physical database boot. / 精确物理数据库启动身份。 */
    private final UUID boot;

    /** Physical preparation connection tracker. / 物理准备连接跟踪器。 */
    private final PlatformDatabase database;

    /** Platform transactions shared with task admission. / 与任务准入共用的平台事务。 */
    private final StoreConnections connections;

    /** Existing standard log writer. / 既有标准日志写入器。 */
    private final LogStore logs;

    /** Registered platform operation category. / 已登记的平台操作类别。 */
    private final LogStore.Target archive;

    /** Strict durable request parser. / 严格持久请求解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Binds the execution ledger without running author code. / 绑定执行账本，不运行作者代码。
     * @param database exclusively owned platform engine / 独占的平台引擎
     * @throws SQLException if the original archive category cannot be registered / 无法登记原归档类别时 */
    public ModRestartLedger(PlatformDatabase database) throws SQLException {
        this.database = database;
        boot = database.bootId();
        connections = new StoreConnections(database::openConnection);
        logs = new LogStore(database);
        archive = logs.register("app", Optional.empty(),
            new LogCategory("mod_operation", "mod_operation", Optional.empty()));
    }

    /** Prepared activation identity; it does not prove author startup succeeded. / 已准备的启用身份，不证明作者启动成功。
     * @param operation original execution identity / 原执行身份
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param enabled requested switch / 请求开关
     * @param version selected release, if any / 选定版本，可不存在
     * @param schema exact ready data generation, if any / 精确就绪数据代次，可不存在 */
    public record Application(UUID operation, String owner, boolean enabled, Optional<String> version,
                              Optional<SchemaIdentity> schema) {
    }

    /** Claims one earlier-boot request only after prior work and required data are ready. / 仅在旧工作收尾且所需数据就绪后取得前次启动请求。
     * @param id original operation identity / 原操作身份
     * @return prepared identity to pass into the actual lifecycle / 传入实际生命周期的已准备身份
     * @throws SQLException if boot, intent, resources or data cannot be proven / 无法证明启动、意图、资源或数据时 */
    public Application begin(UUID id) throws SQLException {
        return locked(c -> {
            var row = IntentEvidence.required(c, id);
            require(row.state().equals("WAITING") && row.phase().equals("WAIT_RESTART") && row.outcome() == null,
                "Original intent is not waiting for restart");
            require(!boot.equals(row.requestBoot()), "An intent cannot apply in the boot that accepted it");
            var state = registry(c, row);
            require(!boot.equals(state.boot()), "This Mod already had an activation in the current boot");
            requireClosed(state);
            requireDrained(c, row);
            var schema = prepared(c, row);
            var recovery = JSON.createObjectNode().put("formatVersion", 1).put("applicationBootId", boot.toString());
            try (var p = c.prepareStatement(
                "UPDATE mod_operation SET state='RUNNING',phase='APPLYING',recovery_json=?,updated_at=? WHERE operation_id=?")) {
                p.setString(1, recovery.toString());
                p.setLong(2, System.currentTimeMillis());
                p.setObject(3, id);
                require(p.executeUpdate() == 1, "Original intent disappeared");
            }
            return new Application(id, row.owner(), row.enabled(), Optional.ofNullable(row.version()), schema);
        });
    }

    /** Identifies an untouched request needing data preparation without claiming it. / 识别需要数据准备的未执行请求，不取得它。
     * @param id retained intent / 保留意图
     * @return whether initialization or migration remains necessary / 是否仍需初始化或迁移
     * @throws SQLException if the original request is invalid / 原请求无效时 */
    public boolean needsPreparation(UUID id) throws SQLException {
        return locked(c -> {
            var row = IntentEvidence.required(c, id);
            return row.state().equals("WAITING") && row.phase().equals("WAIT_RESTART") && row.outcome() == null
                && Set.of("INITIALIZE", "MIGRATE").contains(row.data().path("mode").asText());
        });
    }

    /** Original identities for one physical data preparation attempt. / 一次物理数据准备尝试的原身份。
     * @param intent original enabled choice, or the standalone retry operation / 原启用选择，或独立重试操作
     * @param operation independent preparation operation / 独立准备操作
     * @param target newly registered generation / 新登记代次
     * @param writer tracked initialization scope / 受跟踪初始化作用域
     * @param reader recoverable SELECT-only scope / 可恢复只读作用域
     * @param archive fixed summary identity / 固定摘要身份
     * @param source confirmed original source, if migrating / 迁移时已确认的原来源
     * @param steps original ordered migration chain / 原有序迁移链 */
    public record Setup(UUID intent, UUID operation, SchemaIdentity target, UUID writer, UUID reader,
                        UUID archive, Optional<SchemaIdentity> source, List<MigrationStep> steps) {
        /** Copies the operation-owned ordered steps. / 复制操作所属有序步骤。
         * @param intent original intent, or the standalone retry operation / 原意图，或独立重试操作
         * @param operation preparation operation / 准备操作
         * @param target final target / 最终目标
         * @param writer final writer scope / 最终写入作用域
         * @param reader final reader scope / 最终读取作用域
         * @param archive original summary / 原摘要
         * @param source confirmed source, if migrating / 迁移时的已确认来源
         * @param steps ordered original chain / 有序原链 */
        public Setup {
            source = Objects.requireNonNull(source);
            steps = List.copyOf(steps);
        }
    }

    /** Persisted ordered migration edge and its tracked scopes. / 已持久的有序迁移边及其受跟踪作用域。
     * @param index zero-based original step / 从零开始的原步骤
     * @param from source logical version / 来源逻辑版本
     * @param to target logical version / 目标逻辑版本
     * @param className explicitly declared migration / 明确声明的迁移
     * @param target unique target generation / 唯一目标代次
     * @param sourceReader original source reader scope / 原来源读取作用域
     * @param writer original target writer scope / 原目标写入作用域
     * @param reader original target validation scope / 原目标验证作用域 */
    public record MigrationStep(int index, String from, String to, String className, SchemaIdentity target,
                                UUID sourceReader, UUID writer, UUID reader) {
    }

    /** Resolves permanent or intermediate physical target ownership. / 解析正式或中间物理目标归属。
     * @param plan original operation / 原操作
     * @param step original edge / 原迁移边
     * @return original target coordinates / 原目标坐标 */
    public static SchemaCoordinates targetCoordinates(Setup plan, MigrationStep step) {
        return step.index() == plan.steps().size() - 1
            ? new SchemaCoordinates(plan.target())
            : new SchemaCoordinates(step.target(), plan.operation(), step.index());
    }

    /** Resolves the exact confirmed source or preceding operation-owned intermediate. / 解析精确已确认来源或前一步操作所属中间目标。
     * @param plan original operation / 原操作
     * @param step original edge / 原迁移边
     * @return exact original source / 精确原来源 */
    public static SchemaCoordinates sourceCoordinates(Setup plan, MigrationStep step) {
        return step.index() == 0
            ? new SchemaCoordinates(plan.source().orElseThrow())
            : targetCoordinates(plan, plan.steps().get(step.index() - 1));
    }

    /** Claims the original initialization or migration mode without changing its data plan. / 取得原初始化或迁移模式，不改变其数据计划。
     * @param id original intent / 原意图
     * @param manifest verified selected declaration / 已验证选定声明
     * @param digest verified program bytes / 已验证程序字节摘要
     * @return committed preparation / 已提交准备
     * @throws SQLException if the original plan cannot execute / 原计划无法执行时 */
    public Setup beginPreparation(UUID id, String manifest, String digest) throws SQLException {
        return locked(c -> "MIGRATE".equals(IntentEvidence.required(c, id).data().path("mode").asText())
            ? beginMigration(id, manifest, digest)
            : beginInitialization(id, manifest, digest));
    }

    /** Claims one explicitly reviewed failed target inside the caller's platform transaction. / 在调用者的平台事务中取得一个明确审阅的失败目标。
     * @param c caller-owned serialized transaction / 调用者所属串行事务
     * @param previous exact failed and unused generation / 精确失败未使用代次
     * @param version unchanged selected program / 未改变选定程序
     * @param revision unchanged selection revision / 未改变选择修订
     * @param manifest verified target declaration / 已验证目标声明
     * @param digest verified target content / 已验证目标内容摘要
     * @param dataJson explicitly confirmed data plan / 明确确认数据计划
     * @return registered original preparation, whose intent equals its standalone operation / 已登记原准备，其意图等于独立操作
     * @throws SQLException if source, target, references or occupancy changed / 来源、目标、引用或占用变化时 */
    public Setup beginRetry(Connection c, SchemaIdentity previous, String version, long revision, String manifest,
                            String digest, String dataJson) throws SQLException {
        var data = preparation.retryAdmission(c, previous, version, revision, manifest, digest, dataJson);
        var target = new SchemaIdentity(previous.ownerId(), previous.schemaVersion(), UUID.randomUUID());
        Optional<SchemaIdentity> source = Optional.empty();
        List<MigrationStep> steps = List.of();
        if ("MIGRATE".equals(data.path("mode").textValue())) {
            require(IntentEvidence.fields(data).equals(Set.of("mode", "target", "source", "generation", "steps")),
                "Invalid retry source plan");
            source = Optional.of(new SchemaIdentity(previous.ownerId(), data.path("source").asText(),
                IntentEvidence.uuid(data, "generation")));
            new SchemaCoordinates(source.get()).requireRegistered(c, Set.of("READY"));
            steps = SetupLedger.migrationSteps(source.get(), target, data, manifest);
        } else {
            require("INITIALIZE".equals(data.path("mode").textValue())
                    && IntentEvidence.fields(data).equals(Set.of("mode", "target")),
                "Retry requires initialization or confirmed migration");
            try (var p = c.prepareStatement("SELECT COUNT(*) FROM mod_schema WHERE mod_id=? AND generation<>?")) {
                p.setString(1, previous.ownerId());
                p.setObject(2, previous.generation());
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 0, "Existing sources require explicit confirmation");
                }
            }
        }
        UUID operation = UUID.randomUUID();
        var plan = new Setup(operation, operation, target,
            steps.isEmpty() ? UUID.randomUUID() : steps.getLast().writer(),
            steps.isEmpty() ? UUID.randomUUID() : steps.getLast().reader(), UUID.randomUUID(), source, steps);
        long now = System.currentTimeMillis();
        var request = JSON.createObjectNode().put("formatVersion", 1).put("mode", "RETRY")
            .put("ownerId", previous.ownerId()).put("previousGeneration", previous.generation().toString())
            .put("manifest", manifest).put("contentDigest", digest);
        request.putObject("target").put("enabled", true).put("version", version);
        request.set("data", data);
        try (var p = c.prepareStatement(
            "UPDATE mod_schema SET generation=?,state='PREPARING',created_at=?,validated_at=NULL,validation_digest=NULL WHERE generation=? AND state='FAILED' AND NOT ever_used")) {
            p.setObject(1, target.generation());
            p.setLong(2, now);
            p.setObject(3, previous.generation());
            require(p.executeUpdate() == 1, "Original failed target changed");
        }
        try (var p = c.prepareStatement(
            "INSERT INTO mod_operation(operation_id,mod_id,operation_type,target_version,source_schema_version,target_schema_version,target_generation,accepted_revision,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'MIGRATE',?,?,?,?,?,'RUNNING','SOURCE_CHECKED',?,?,?,?,?)")) {
            p.setObject(1, operation);
            p.setString(2, target.ownerId());
            p.setString(3, version);
            p.setString(4, source.map(SchemaIdentity::schemaVersion).orElse(null));
            p.setString(5, target.schemaVersion());
            p.setObject(6, target.generation());
            p.setLong(7, revision);
            p.setString(8, request.toString());
            p.setString(9, preparation.preparationEvidence(plan));
            p.setString(10, plan.archive().toString());
            p.setLong(11, now);
            p.setLong(12, now);
            p.executeUpdate();
        }
        SetupLedger.registerSteps(c, plan);
        return plan;
    }

    /** Confirms physical removal before any retried author code may create new data. / 在重试作者代码创建新数据前确认物理移除。
     * @param plan original explicit retry / 原明确重试
     * @throws SQLException if original target removal remains uncertain / 原目标移除仍不确定时 */
    public void resetCompleted(Setup plan) throws SQLException {
        locked(c -> {
            require(plan.intent().equals(plan.operation())
                && "MIGRATING".equals(preparation.requirePreparation(c, plan)), "Not an active retry reset");
            var target = new SchemaCoordinates(plan.target());
            try (var p = c.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?)+(SELECT COUNT(*) FROM information_schema.users WHERE user_name=?)")) {
                p.setString(1, target.physicalName());
                p.setString(2, target.databaseUser());
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 0, "Failed target removal is incomplete");
                }
            }
            var recovery = SetupLedger.preparationRecovery(c, plan);
            ((com.fasterxml.jackson.databind.node.ObjectNode) recovery.path("facts")).put("retryResetComplete", true);
            SetupLedger.saveRecovery(c, plan, recovery);
            return null;
        });
    }

    /** Registers the previously confirmed chain without choosing another source or path. / 登记此前已确认链，不另选来源或路径。
     * @param id original restart intent / 原重启意图
     * @param manifest verified selected declaration / 已验证选定声明
     * @param digest verified selected bytes / 已验证选定字节摘要
     * @return committed original preparation / 已提交原准备
     * @throws SQLException if source, declaration, chain or occupancy changed / 来源、声明、迁移链或占用变化时 */
    public Setup beginMigration(UUID id, String manifest, String digest) throws SQLException {
        return locked(c -> {
            var row = IntentEvidence.required(c, id);
            var data = row.data();
            require(row.enabled() && row.targetSchema() != null && row.generation() == null
                && IntentEvidence.fields(data).equals(Set.of("mode", "target", "source", "generation", "steps"))
                && "MIGRATE".equals(data.path("mode").textValue())
                && row.targetSchema().equals(data.path("target").textValue()), "Original migration plan changed");
            var source = new SchemaIdentity(row.owner(), data.path("source").asText(),
                IntentEvidence.uuid(data, "generation"));
            new SchemaCoordinates(source).requireRegistered(c, Set.of("READY"));
            try (var p = c.prepareStatement("SELECT source_schema_version FROM mod_operation WHERE operation_id=?")) {
                p.setObject(1, id);
                try (var r = p.executeQuery()) {
                    require(r.next() && source.schemaVersion().equals(r.getString(1)),
                        "Original source version identity changed");
                }
            }
            try (var p = c.prepareStatement(
                "SELECT COUNT(*) FROM mod_version WHERE mod_id=? AND version=? AND schema_version=? AND manifest_json=? AND content_sha256=? AND state='READY'")) {
                p.setString(1, row.owner());
                p.setString(2, row.version());
                p.setString(3, row.targetSchema());
                p.setString(4, manifest);
                p.setString(5, digest);
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 1, "Original migration program changed");
                }
            }
            try (var p = c.prepareStatement("SELECT COUNT(*) FROM mod_schema WHERE mod_id=? AND schema_version=?")) {
                p.setString(1, row.owner());
                p.setString(2, row.targetSchema());
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 0, "Target data already exists and requires reconciliation");
                }
            }
            var target = new SchemaIdentity(row.owner(), row.targetSchema(), UUID.randomUUID());
            var steps = SetupLedger.migrationSteps(source, target, data, manifest);
            preparation.claimUnprepared(c, row);
            var last = steps.getLast();
            var plan = new Setup(id, UUID.randomUUID(), target, last.writer(), last.reader(), UUID.randomUUID(),
                Optional.of(source), steps);
            preparation.registerPreparation(c, row, plan);
            return plan;
        });
    }

    /** Registers the target and all scope identities before any DDL or author callback. / 在任一 DDL 或作者回调前登记目标及全部作用域身份。
     * @param id original restart intent / 原重启意图
     * @param manifest verified selected declaration / 已验证选定声明
     * @param digest verified selected content digest / 已验证选定内容摘要
     * @return committed preparation identities / 已提交准备身份
     * @throws SQLException if the original choice, source or exclusivity changed / 原选择、来源或独占发生变化时 */
    public Setup beginInitialization(UUID id, String manifest, String digest) throws SQLException {
        return locked(c -> {
            var row = IntentEvidence.required(c, id);
            require(row.enabled() && row.targetSchema() != null && row.generation() == null
                    && IntentEvidence.fields(row.data()).equals(Set.of("mode", "target"))
                    && "INITIALIZE".equals(row.data().path("mode").textValue())
                    && row.targetSchema().equals(row.data().path("target").textValue()),
                "Original first-use plan changed");
            try (var p = c.prepareStatement("SELECT COUNT(*) FROM mod_schema WHERE mod_id=?")) {
                p.setString(1, row.owner());
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 0, "First-use data already exists; reconciliation is required");
                }
            }
            try (var p = c.prepareStatement(
                "SELECT COUNT(*) FROM mod_version WHERE mod_id=? AND version=? AND schema_version=? AND manifest_json=? AND content_sha256=? AND state='READY'")) {
                p.setString(1, row.owner());
                p.setString(2, row.version());
                p.setString(3, row.targetSchema());
                p.setString(4, manifest);
                p.setString(5, digest);
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 1, "Selected initialization source changed");
                }
            }
            preparation.claimUnprepared(c, row);
            var plan = new Setup(id, UUID.randomUUID(),
                new SchemaIdentity(row.owner(), row.targetSchema(), UUID.randomUUID()), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), List.of());
            preparation.registerPreparation(c, row, plan);
            return plan;
        });
    }

    /** Claims only an untouched request rejected before physical preparation. / 仅取得物理准备前已被拒绝的未执行请求。
     * @param id original request / 原请求
     * @throws SQLException if prior execution or work remains / 已有执行或工作残留时 */
    public void rejectPreparation(UUID id) throws SQLException {
        locked(c -> {
            var row = IntentEvidence.required(c, id);
            if (row.state().equals("WAITING")) {
                require(Set.of("INITIALIZE", "MIGRATE").contains(row.data().path("mode").asText()),
                    "Not a preparation request");
                preparation.claimUnprepared(c, row);
            }
            return null;
        });
    }

    /** Advances one fixed preparation phase before its corresponding side effect. / 在对应副作用前推进一个固定准备阶段。
     * @param plan original attempt / 原尝试
     * @param validating true for validation, false for initialization / 真表示验证，假表示初始化
     * @throws SQLException if original ownership or phase is stale / 原归属或阶段已过期时 */
    public void preparationPhase(Setup plan, boolean validating) throws SQLException {
        locked(c -> {
            String phase = preparation.requirePreparation(c, plan);
            require(phase.equals(validating ? "MIGRATING" : "SOURCE_CHECKED"), "Setup phase changed");
            try (var p = c.prepareStatement("UPDATE mod_operation SET phase=?,updated_at=? WHERE operation_id=?")) {
                p.setString(1, validating ? "VALIDATING" : "MIGRATING");
                p.setLong(2, System.currentTimeMillis());
                p.setObject(3, plan.operation());
                require(p.executeUpdate() == 1, "Setup disappeared");
            }
            return null;
        });
    }

    /** Records physical creation only after the original create returned successfully. / 仅在原物理创建成功返回后记录归属。
     * @param plan original preparation / 原准备
     * @param target original permanent or intermediate target / 原正式或中间目标
     * @throws SQLException if original identity or acknowledgement changed / 原身份或确认变化时 */
    public void created(Setup plan, SchemaCoordinates target) throws SQLException {
        locked(c -> {
            preparation.requirePreparation(c, plan);
            boolean owned = new SchemaCoordinates(plan.target()).physicalName().equals(target.physicalName())
                || plan.steps().stream().anyMatch(
                step -> targetCoordinates(plan, step).physicalName().equals(target.physicalName()));
            require(owned, "Created target is not in the original inventory");
            target.requireRegistered(c, Set.of("PREPARING"));
            target.requirePhysicalOwnership(c);
            var recovery = SetupLedger.preparationRecovery(c, plan);
            ((com.fasterxml.jackson.databind.node.ObjectNode) recovery.path("facts")).withObject("/created")
                .put(target.physicalName(), target.identity().generation().toString());
            SetupLedger.saveRecovery(c, plan, recovery);
            return null;
        });
    }

    /** Persists the original source fingerprint before migration and checks it after cleanup. / 在迁移前持久化原来源指纹，并在清理后核对。
     * @param plan original migration / 原迁移
     * @param digest physical source fingerprint / 物理来源指纹
     * @param after whether this is the final observation / 是否最终观测
     * @return whether the observed source still matches its original / 观测来源是否仍匹配原值
     * @throws SQLException if source evidence cannot be recorded / 无法记录来源证据时 */
    public boolean sourceDigest(Setup plan, String digest, boolean after) throws SQLException {
        require(digest != null && digest.matches("[0-9a-f]{64}") && plan.source().isPresent(), "Invalid source proof");
        return locked(c -> {
            String phase = preparation.requirePreparation(c, plan);
            require(after || phase.equals("SOURCE_CHECKED"), "Source proof must precede migration");
            var recovery = SetupLedger.preparationRecovery(c, plan);
            var facts = (com.fasterxml.jackson.databind.node.ObjectNode) recovery.path("facts");
            String field = after ? "sourceAfter" : "sourceBefore";
            require(after || !facts.has(field) || digest.equals(facts.path(field).asText()),
                "Original source proof changed");
            facts.put(field, digest);
            SetupLedger.saveRecovery(c, plan, recovery);
            return !after || digest.equals(facts.path("sourceBefore").asText());
        });
    }

    /** Starts one original edge only after all preceding steps completed. / 仅在此前步骤全部完成后开始一条原迁移边。
     * @param plan original migration / 原迁移
     * @param step original ordered edge / 原有序迁移边
     * @param before source fingerprint before this callback / 此回调前来源指纹
     * @throws SQLException if phase or previous steps changed / 阶段或前序步骤变化时 */
    public void beginStep(Setup plan, MigrationStep step, String before) throws SQLException {
        require(before != null && before.matches("[0-9a-f]{64}"), "Invalid original step source proof");
        locked(c -> {
            require(preparation.requirePreparation(c, plan).equals("MIGRATING"), "Migration is not executing");
            var facts = SetupLedger.preparationRecovery(c, plan).path("facts");
            require(facts.path("sourceBefore").asText().matches("[0-9a-f]{64}"),
                "Original source has not been checked");
            try (var p = c.prepareStatement(
                "SELECT COUNT(*) FROM mod_migration_step WHERE operation_id=? AND step_number<? AND state<>'COMPLETE'")) {
                p.setObject(1, plan.operation());
                p.setInt(2, step.index());
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 0, "Previous migration step is incomplete");
                }
            }
            try (var p = c.prepareStatement(
                "UPDATE mod_migration_step SET state='RUNNING',started_at=? WHERE operation_id=? AND step_number=? AND from_version=? AND to_version=? AND migration_class=? AND source_schema=? AND target_schema=? AND state='PENDING'")) {
                p.setLong(1, System.currentTimeMillis());
                p.setObject(2, plan.operation());
                p.setInt(3, step.index());
                p.setString(4, step.from());
                p.setString(5, step.to());
                p.setString(6, step.className());
                p.setString(7, sourceCoordinates(plan, step).physicalName());
                p.setString(8, targetCoordinates(plan, step).physicalName());
                require(p.executeUpdate() == 1, "Original migration step changed or was already attempted");
            }
            var recovery = SetupLedger.preparationRecovery(c, plan);
            ((com.fasterxml.jackson.databind.node.ObjectNode) recovery.path("facts")).withObject("/steps")
                .putObject(Integer.toString(step.index())).put("sourceBefore", before);
            SetupLedger.saveRecovery(c, plan, recovery);
            return null;
        });
    }

    /** Records a completed edge only after physical scope release and unchanged source proof. / 仅在物理作用域释放且来源未变后记录完成的迁移边。
     * @param plan original migration / 原迁移
     * @param step original edge / 原迁移边
     * @param before source snapshot before its callback / 回调前来源快照
     * @param after source snapshot after its callback / 回调后来源快照
     * @param targetDigest target validation receipt / 目标验证凭据
     * @throws SQLException if physical release, proof or write fails / 物理释放、证明或写入失败时 */
    public void completeStep(Setup plan, MigrationStep step, String before, String after, String targetDigest)
        throws SQLException {
        require(before != null && before.matches("[0-9a-f]{64}") && before.equals(after) && targetDigest != null
            && targetDigest.matches("[0-9a-f]{64}"), "Migration source changed or validation is absent");
        for (var scope : List.of(step.sourceReader(), step.writer(), step.reader()))
            require(database.openConnections(scope.toString()) == 0, "Original migration step connections remain");
        locked(c -> {
            require(Set.of("MIGRATING", "VALIDATING").contains(preparation.requirePreparation(c, plan)),
                "Original migration phase changed");
            var recovery = SetupLedger.preparationRecovery(c, plan);
            var facts = (com.fasterxml.jackson.databind.node.ObjectNode) recovery.path("facts");
            var original = facts.path("steps").path(Integer.toString(step.index()));
            require(before.equals(original.path("sourceBefore").asText()), "Original step source proof changed");
            try (var p = c.prepareStatement(
                "UPDATE mod_migration_step SET state='COMPLETE',ended_at=? WHERE operation_id=? AND step_number=? AND state='RUNNING'")) {
                p.setLong(1, System.currentTimeMillis());
                p.setObject(2, plan.operation());
                p.setInt(3, step.index());
                if (p.executeUpdate() != 1) {
                    try (var q = c.prepareStatement(
                        "SELECT state FROM mod_migration_step WHERE operation_id=? AND step_number=?")) {
                        q.setObject(1, plan.operation());
                        q.setInt(2, step.index());
                        try (var r = q.executeQuery()) {
                            require(r.next() && "COMPLETE".equals(r.getString(1))
                                    && after.equals(original.path("sourceAfter").asText())
                                    && targetDigest.equals(original.path("targetDigest").asText()),
                                "Original step completion is unconfirmed");
                            return null;
                        }
                    }
                }
            }
            facts.withObject("/steps").putObject(Integer.toString(step.index())).put("sourceBefore", before)
                .put("sourceAfter", after).put("targetDigest", targetDigest);
            SetupLedger.saveRecovery(c, plan, recovery);
            return null;
        });
    }

    /** Confirms closed preparation and archives its known outcome in one transaction. / 在同一事务确认已关闭准备并归档已知结果。
     * @param plan original attempt / 原尝试
     * @param outcome known result, or UNKNOWN for retained reconciliation / 已知结果，UNKNOWN 保留核对
     * @param digest validation receipt for success, otherwise null / 成功验证凭据，否则为空
     * @throws SQLException if release, identity or atomic archival cannot be confirmed / 无法确认释放、身份或原子归档时 */
    public void finishPreparation(Setup plan, TaskResult outcome, String digest) throws SQLException {
        require(Set.of(TaskResult.SUCCEEDED, TaskResult.FAILED, TaskResult.UNKNOWN).contains(outcome),
            "Invalid preparation result");
        var scopes = new HashSet<>(List.of(plan.writer(), plan.reader()));
        plan.steps().forEach(step -> scopes.addAll(List.of(step.sourceReader(), step.writer(), step.reader())));
        for (var scope : scopes)
            require(database.openConnections(scope.toString()) == 0, "Setup connections remain");
        locked(c -> {
            if (preparation.preparationFinished(c, plan, outcome, digest))
                return null;
            String phase = preparation.requirePreparation(c, plan);
            preparation.verifyCompletion(c, plan, outcome, digest, phase, scopes);
            long now = System.currentTimeMillis();
            try (var p = c.prepareStatement(
                "UPDATE mod_schema SET state=?,validated_at=?,validation_digest=? WHERE generation=? AND state='PREPARING' AND NOT ever_used")) {
                p.setString(1, outcome == TaskResult.SUCCEEDED ? "READY" : outcome.name());
                p.setObject(2, outcome == TaskResult.SUCCEEDED ? now : null);
                p.setString(3, outcome == TaskResult.SUCCEEDED ? digest : null);
                p.setObject(4, plan.target().generation());
                require(p.executeUpdate() == 1, "Original target cannot change state");
            }
            if (outcome == TaskResult.UNKNOWN) {
                try (var p = c.prepareStatement(
                    "UPDATE mod_migration_step SET state='UNKNOWN' WHERE operation_id=? AND state='RUNNING'")) {
                    p.setObject(1, plan.operation());
                    p.executeUpdate();
                }
                try (var p = c.prepareStatement(
                    "UPDATE mod_operation SET state='UNKNOWN',failure_code='MOD_SCHEMA_PREPARATION_UNKNOWN',updated_at=? WHERE operation_id IN (?,?)")) {
                    p.setLong(1, now);
                    p.setObject(2, plan.operation());
                    p.setObject(3, plan.intent());
                    require(p.executeUpdate() == (plan.intent().equals(plan.operation()) ? 1 : 2),
                        "Original preparation identities changed");
                }
            } else {
                String owner = plan.target().ownerId();
                String version = plan.intent().equals(plan.operation())
                    ? preparation.retryRequest(c, plan).path("target").path("version").asText()
                    : IntentEvidence.required(c, plan.intent()).version();
                boolean migrating = plan.source().isPresent();
                logs.store(c, archive, List.of(new LogRecordCodec.Record(plan.archive(), now, "app", "mod_operation",
                    outcome == TaskResult.SUCCEEDED ? LogLevel.INFO : LogLevel.ERROR,
                    migrating
                        ? outcome == TaskResult.SUCCEEDED ? "MOD_SCHEMA_MIGRATED" : "MOD_SCHEMA_MIGRATION_FAILED"
                        : outcome == TaskResult.SUCCEEDED
                        ? "MOD_SCHEMA_INITIALIZED"
                        : "MOD_SCHEMA_INITIALIZATION_FAILED",
                    outcome == TaskResult.SUCCEEDED
                        ? "Data validation and original resource cleanup succeeded"
                        : "Data preparation failed and original resources are closed",
                    null, owner, version, null, plan.operation(), outcome, false)), 100);
                if (plan.intent().equals(plan.operation())) {
                    try (var p = c.prepareStatement(
                        "UPDATE mod_registry SET last_runtime_state='FAILED',actual_version=NULL,last_boot_id=?,failure_code=?,failure_detail=NULL WHERE mod_id=?")) {
                        p.setObject(1, boot);
                        p.setString(2,
                            outcome == TaskResult.SUCCEEDED
                                ? "MOD_DATA_READY_RESTART_REQUIRED"
                                : "MOD_SCHEMA_PREPARATION_FAILED");
                        p.setString(3, owner);
                        require(p.executeUpdate() == 1, "Retry owner disappeared");
                    }
                }
                try (var p = c.prepareStatement("DELETE FROM mod_migration_step WHERE operation_id=?")) {
                    p.setObject(1, plan.operation());
                    p.executeUpdate();
                }
                try (var p = c.prepareStatement("DELETE FROM mod_operation WHERE operation_id=?")) {
                    p.setObject(1, plan.operation());
                    require(p.executeUpdate() == 1, "Original preparation disappeared");
                }
            }
            return null;
        });
    }

    /** Original disabled registry identity observed at runtime creation. / 创建运行时时观察到的原禁用登记身份。
     * @param revision original desired revision / 原期望修订
     * @param updatedAt original registry update stamp / 原登记更新时间
     * @param previousBoot original lifecycle boot if any / 原生命周期启动，如有 */
    public record Disabled(long revision, long updatedAt, Optional<UUID> previousBoot) {
    }

    /** Captures already disabled revisions before the runtime is exposed to new user actions. / 运行时暴露给新用户操作前捕获已禁用修订。
     * @param database original newly opened platform / 原新打开平台
     * @return exact previously stopped revisions / 精确前次已停止修订
     * @throws SQLException if the initial inventory is unavailable / 初始清单不可用时 */
    public static Map<String, Disabled> initiallyDisabled(PlatformDatabase database) throws SQLException {
        var result = new HashMap<String, Disabled>();
        try (var c = database.openConnection();
             var s = c.createStatement();
             var r = s.executeQuery(
                 "SELECT mod_id,revision,updated_at,last_boot_id FROM mod_registry WHERE NOT enabled AND actual_version IS NULL AND last_runtime_state='STOPPED'")) {
            while (r.next())
                result.put(r.getString(1),
                    new Disabled(r.getLong(2), r.getLong(3), Optional.ofNullable(r.getObject(4, UUID.class))));
        }
        return Map.copyOf(result);
    }

    /** Acknowledges a never-loaded disabled revision observed before this runtime opened to actions. / 确认本运行时开放操作前观察到的未加载禁用修订。
     * @param owner original disabled owner / 原禁用所有者
     * @param original original boot snapshot / 原启动快照
     * @throws SQLException if choice, prior state or retained ownership changed / 选择、前次状态或保留归属变化时 */
    public void acknowledgeIdleDisabled(String owner, Disabled original) throws SQLException {
        locked(c -> {
            ModProgramMaintenance.drained(c, owner, null);
            try (var p = c.prepareStatement(
                "UPDATE mod_registry SET last_boot_id=?,disable_ack_revision=revision,updated_at=? WHERE mod_id=? AND NOT enabled AND revision=? AND actual_version IS NULL AND last_runtime_state='STOPPED' AND (last_boot_id IS NULL OR last_boot_id<>?) AND updated_at=? AND last_boot_id IS NOT DISTINCT FROM ?")) {
                p.setObject(1, boot);
                p.setLong(2, System.currentTimeMillis());
                p.setString(3, owner);
                p.setLong(4, original.revision());
                p.setObject(5, boot);
                p.setLong(6, original.updatedAt());
                p.setObject(7, original.previousBoot().orElse(null));
                require(p.executeUpdate() == 1, "Original disabled revision or prior lifecycle changed");
            }
            return null;
        });
    }

    /** Archives actual success, or confirms a never-loaded disabled Mod in this new boot. / 归档实际成功，或确认本次新启动未加载的关闭 Mod。
     * @param id original operation identity / 原操作身份
     * @return true if archived now; false if no execution remains, without reconstructing its history / 本次归档返回真，无执行行则返回假且不重建历史
     * @throws SQLException if actual success, cleanup or the archive transaction is unconfirmed / 实际成功、清理或归档事务未确认时 */
    public boolean applied(UUID id) throws SQLException {
        return finish(id, false);
    }

    /** Archives only a known failed activation after its original resources closed. / 仅在原资源关闭后归档已知启用失败。
     * @param id original operation identity / 原操作身份
     * @return whether the retained execution was archived / 是否归档了保留执行
     * @throws SQLException if failure or cleanup remains unconfirmed / 失败或清理仍未确认时 */
    public boolean failed(UUID id) throws SQLException {
        return finish(id, true);
    }

    /** Verifies actual lifecycle evidence and commits summary plus execution removal atomically. / 核验实际生命周期证据并原子提交摘要及执行移除。
     * @param id original operation identity / 原操作身份
     * @param failed whether a known startup failure is being archived / 是否归档已知启动失败
     * @return whether an original execution existed / 是否存在原执行
     * @throws SQLException if proof or any transactional write fails / 证明或任一事务写入失败时 */
    private boolean finish(UUID id, boolean failed) throws SQLException {
        return locked(c -> {
            var row = IntentEvidence.read(c, id);
            if (row == null)
                return false;
            require(row.state().equals("RUNNING") && row.phase().equals("APPLYING") && row.outcome() == null,
                "Original intent is not an active application");
            var recovery = IntentEvidence.object(row.recovery());
            require(IntentEvidence.fields(recovery).equals(Set.of("formatVersion", "applicationBootId"))
                    && recovery.path("formatVersion").isInt() && recovery.path("formatVersion").intValue() == 1
                    && boot.equals(IntentEvidence.uuid(recovery, "applicationBootId")),
                "Application belongs to another boot");
            var state = registry(c, row);
            requireDrained(c, row);
            if (failed) {
                require(row.enabled() && boot.equals(state.boot()) && state.state().equals("FAILED")
                        && state.actual() == null && state.failure() != null && !state.failure().isBlank(),
                    "Original activation has not recorded a known, fully closed failure");
            } else if (row.enabled()) {
                require(boot.equals(state.boot()) && state.state().equals("RUNNING")
                        && row.version().equals(state.actual()) && state.failure() == null,
                    "The requested release is not running in this boot");
                prepared(c, row);
            } else {
                require(!boot.equals(state.boot()),
                    "A Mod loaded during this boot cannot receive restart-disable acknowledgement");
                requireClosed(state);
                try (var p = c.prepareStatement(
                    "UPDATE mod_registry SET last_boot_id=?,last_runtime_state='STOPPED',actual_version=NULL,disable_ack_revision=revision,failure_code=NULL,failure_detail=NULL,updated_at=? WHERE mod_id=?")) {
                    p.setObject(1, boot);
                    p.setLong(2, System.currentTimeMillis());
                    p.setString(3, row.owner());
                    require(p.executeUpdate() == 1, "Original Mod disappeared");
                }
            }
            long now = System.currentTimeMillis();
            var outcome = failed ? TaskResult.FAILED : TaskResult.SUCCEEDED;
            try (var p = c.prepareStatement(
                "UPDATE mod_operation SET state='FINISHING',phase='VERIFIED',outcome=?,updated_at=? WHERE operation_id=?")) {
                p.setString(1, outcome.name());
                p.setLong(2, now);
                p.setObject(3, id);
                require(p.executeUpdate() == 1, "Original intent disappeared");
            }
            logs.store(c, archive, List.of(new LogRecordCodec.Record(row.archive(), now, "app", "mod_operation",
                failed ? LogLevel.ERROR : LogLevel.INFO, failed ? "MOD_INTENT_FAILED" : "MOD_INTENT_APPLIED",
                failed
                    ? "Mod startup failed and its original resources are closed"
                    : "Mod restart change was verified",
                null, row.owner(), row.version(), null, id, outcome, false)), 100);
            try (var p = c.prepareStatement("DELETE FROM mod_operation WHERE operation_id=?")) {
                p.setObject(1, id);
                require(p.executeUpdate() == 1, "Original execution cleanup was not confirmed");
            }
            return true;
        });
    }

    /** Checks the unchanged accepted choice without trusting a prior RUNNING marker. / 检查未变的接纳选择，不信任前次 RUNNING 标记。
     * @param c locked transaction / 已锁定事务
     * @param row original execution / 原执行
     * @return current actual evidence / 当前实际证据
     * @throws SQLException if intent no longer matches / 意图不再匹配时 */
    private RuntimeState registry(Connection c, Intent row) throws SQLException {
        try (var p = c.prepareStatement(
            "SELECT enabled,selected_version,revision,last_boot_id,last_runtime_state,actual_version,failure_code FROM mod_registry WHERE mod_id=?")) {
            p.setString(1, row.owner());
            try (var r = p.executeQuery()) {
                require(r.next() && r.getBoolean(1) == row.enabled() && Objects.equals(r.getString(2), row.version())
                    && r.getLong(3) == row.revision(), "Accepted Mod choice changed before application");
                return new RuntimeState(r.getObject(4, UUID.class), r.getString(5), r.getString(6), r.getString(7));
            }
        }
    }

    /** Requires prior author cleanup to have been explicitly confirmed. / 要求旧作者清理已经明确确认。
     * @param state retained runtime evidence / 保留运行证据
     * @throws SQLException if original resources might remain / 原资源可能残留时 */
    private static void requireClosed(RuntimeState state) throws SQLException {
        require(Set.of("STOPPED", "FAILED").contains(state.state()) && state.actual() == null,
            "Previous Mod activation still requires reconciliation");
    }

    /** Checks owned tasks, cross-Mod resources and other maintenance in the same transaction. / 在同一事务检查自有任务、跨 Mod 资源及其他维护。
     * @param c locked transaction / 已锁定事务
     * @param row original execution / 原执行
     * @throws SQLException if unresolved work or migration steps remain / 未解决工作或迁移步骤残留时 */
    private static void requireDrained(Connection c, Intent row) throws SQLException {
        try (var p = c.prepareStatement(
            "SELECT (SELECT COUNT(*) FROM platform_task WHERE owner_id=? AND state<>'ENDED')+(SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND (owner_id=? OR resource_kind='MOD' AND resource_id=?))+(SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND operation_id<>? AND state<>'ENDED')+(SELECT COUNT(*) FROM mod_migration_step WHERE operation_id=?)")) {
            p.setString(1, row.owner());
            p.setString(2, row.owner());
            p.setString(3, row.owner());
            p.setString(4, row.owner());
            p.setObject(5, row.id());
            p.setObject(6, row.id());
            try (var r = p.executeQuery()) {
                r.next();
                require(r.getLong(1) == 0, "Original work or maintenance has not finished cleanup");
            }
        }
    }

    /** Proves data-free startup or the exact reviewed READY generation; it never creates data. / 证明无数据启动或精确已审阅 READY 代次，不创建数据。
     * @param c locked transaction / 已锁定事务
     * @param row original execution / 原执行
     * @return verified ready identity, if used / 已核验就绪身份，如使用
     * @throws SQLException if initialization, migration or generation verification is still required / 仍需初始化、迁移或代次核验时 */
    private static Optional<SchemaIdentity> prepared(Connection c, Intent row) throws SQLException {
        if (!row.enabled())
            return Optional.empty();
        String schema;
        try (var p = c.prepareStatement("SELECT schema_version,state FROM mod_version WHERE mod_id=? AND version=?")) {
            p.setString(1, row.owner());
            p.setString(2, row.version());
            try (var r = p.executeQuery()) {
                require(r.next() && "READY".equals(r.getString(2)), "Selected release is not ready");
                schema = r.getString(1);
            }
        }
        require(Objects.equals(schema, row.targetSchema()),
            "Selected data version no longer matches the accepted request");
        var data = row.data();
        if (schema == null) {
            require(IntentEvidence.fields(data).equals(Set.of("mode")) && "NONE".equals(data.path("mode").textValue())
                && row.generation() == null, "Stateless startup has invalid data evidence");
            return Optional.empty();
        }
        boolean initialized = (IntentEvidence.fields(data).equals(Set.of("mode", "target"))
            && "INITIALIZE".equals(data.path("mode").textValue())
            || IntentEvidence.fields(data).equals(Set.of("mode", "target", "source", "generation", "steps"))
            && "MIGRATE".equals(data.path("mode").textValue()))
            && row.state().equals("RUNNING") && row.phase().equals("APPLYING") && row.generation() != null;
        require((initialized || IntentEvidence.fields(data).equals(Set.of("mode", "target", "generation"))
                && "REUSE".equals(data.path("mode").textValue())) && schema.equals(data.path("target").textValue()),
            "Original data preparation must be completed before activation");
        var identity = new SchemaIdentity(row.owner(), schema,
            initialized ? row.generation() : IntentEvidence.uuid(data, "generation"));
        require(identity.generation().equals(row.generation()), "Accepted target generation changed");
        var coordinates = new SchemaCoordinates(identity);
        try (var p = c.prepareStatement(
            "SELECT COUNT(*) FROM mod_schema WHERE mod_id=? AND schema_version=? AND generation=? AND state='READY' AND physical_name=? AND database_user=?")) {
            p.setString(1, row.owner());
            p.setString(2, schema);
            p.setObject(3, identity.generation());
            p.setString(4, coordinates.physicalName());
            p.setString(5, coordinates.databaseUser());
            try (var r = p.executeQuery()) {
                r.next();
                require(r.getLong(1) == 1, "Reviewed target generation is no longer ready");
            }
        }
        return Optional.of(identity);
    }

    /** Rejects unproven transitions before any author callback. / 在任一作者回调前拒绝未证明的转换。
     * @param proven required condition / 所需条件
     * @param message constant internal reason / 常量内部原因
     * @throws SQLException if the condition is unproven / 条件未证明时 */
    private static void require(boolean proven, String message) throws SQLException {
        if (!proven)
            throw new SQLException(message);
    }

    /** Serializes a bounded transition against the actual boot and shutdown gate. / 按实际启动及关闭门串行执行有界转换。
     * @param <T> result type / 结果类型
     * @param work internal platform work / 内部平台工作
     * @return committed result / 已提交结果
     * @throws SQLException if transaction or boot ownership fails / 事务或启动归属失败时 */
    private <T> T locked(Work<T> work) throws SQLException {
        var value = new AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                StoreConnections.lockPlatformState(c);
                try (var s = c.createStatement();
                     var r = s.executeQuery("SELECT boot_id,shutdown_state FROM app_state WHERE singleton_id=1")) {
                    require(r.next() && boot.equals(r.getObject(1, UUID.class))
                            && Set.of("STARTING", "RUNNING").contains(r.getString(2)),
                        "Restart execution no longer owns the active platform boot");
                }
                value.set(work.run(c));
            }
        });
        return value.get();
    }

    /** Transaction callback that never invokes author code. / 不调用作者代码的事务回调。
     * @param <T> result type / 结果类型 */
    @FunctionalInterface
    private interface Work<T> {
        /** Executes one internal transition. / 执行一个内部转换。
         * @param c owning transaction / 所属事务
         * @return internal result / 内部结果
         * @throws SQLException if transition fails / 转换失败时 */
        T run(Connection c) throws SQLException;
    }

    /** Durable actual state independent of desired choice. / 独立于期望选择的持久实际状态。
     * @param boot activation boot / 启用启动身份
     * @param state actual lifecycle state / 实际生命周期状态
     * @param actual still-owned release / 仍持有版本
     * @param failure retained safe event / 保留安全事件 */
    private record RuntimeState(UUID boot, String state, String actual, String failure) {
    }

    /** Original operation evidence retained until atomic archival. / 保留至原子归档的原操作证据。
     * @param id operation identity / 操作身份
     * @param owner Mod owner / Mod 所有者
     * @param requestBoot accepting boot / 接纳启动身份
     * @param enabled desired switch / 期望开关
     * @param version desired release / 期望版本
     * @param revision accepted revision / 接纳修订
     * @param state execution state / 执行状态
     * @param phase execution phase / 执行阶段
     * @param outcome known terminal result / 已知终态结果
     * @param data original preparation plan / 原准备计划
     * @param targetSchema selected data version / 选定数据版本
     * @param generation selected data generation / 选定数据代次
     * @param recovery application evidence / 应用证据
     * @param archive fixed archive identity / 固定归档身份 */
    private record Intent(UUID id, String owner, UUID requestBoot, boolean enabled, String version, long revision,
                          String state, String phase, String outcome, JsonNode data, String targetSchema,
                          UUID generation,
                          String recovery, UUID archive) {
    }

    /** Parses the exact retained intent and canonical recovery identities. / 解析精确保留意图及规范恢复身份。 */
    private static final class IntentEvidence {
        /** Binds the existing ledger state only. / 仅绑定既有账本状态。 */
        private IntentEvidence() {
        }

        /** Reads and validates the exact retained operation. / 读取并校验精确保留操作。
         * @param c locked transaction / 已锁定事务
         * @param id original identity / 原身份
         * @return retained intent / 保留意图
         * @throws SQLException if absent or invalid / 不存在或无效时 */
        private static Intent required(Connection c, UUID id) throws SQLException {
            var row = read(c, id);
            require(row != null, "Original restart request no longer exists");
            return row;
        }

        /** Parses an original request while cross-checking its relational identity. / 解析原请求并交叉检查其关系身份。
         * @param c locked transaction / 已锁定事务
         * @param id original identity / 原身份
         * @return retained intent, or null when already removed / 保留意图，已移除则为空
         * @throws SQLException if storage or retained evidence is invalid / 存储或保留证据无效时 */
        private static Intent read(Connection c, UUID id) throws SQLException {
            try (var p = c.prepareStatement("SELECT * FROM mod_operation WHERE operation_id=?")) {
                p.setObject(1, Objects.requireNonNull(id));
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        return null;
                    var request = object(r.getString("request_json"));
                    require(fields(request)
                            .equals(Set.of("formatVersion", "requestId", "bootId", "ownerId", "base", "target", "data",
                                "affected"))
                            && request.path("formatVersion").isInt() && request.path("formatVersion").intValue() == 1,
                        "Unsupported restart request");
                    uuid(request, "requestId");
                    var requestBoot = uuid(request, "bootId");
                    String owner = r.getString("mod_id");
                    require(owner.equals(request.path("ownerId").textValue()), "Restart owner changed");
                    var base = choice(request.path("base"));
                    var target = choice(request.path("target"));
                    String type = base.path("enabled").booleanValue() != target.path("enabled").booleanValue()
                        ? target.path("enabled").booleanValue() ? "ENABLE" : "DISABLE"
                        : "SWITCH";
                    require(type.equals(r.getString("operation_type")) && !base.equals(target)
                            && Objects.equals(base.path("version").textValue(), r.getString("source_version"))
                            && Objects.equals(target.path("version").textValue(), r.getString("target_version"))
                            && r.getObject("accepted_revision") != null && r.getLong("accepted_revision") > 0,
                        "Restart request identity changed");
                    var affected = request.path("affected");
                    require(affected.isArray() && !affected.isEmpty(), "Restart impact is missing");
                    var owners = new HashSet<String>();
                    for (var item : affected)
                        require(item.isTextual() && !item.textValue().isBlank() && item.textValue().length() <= 512
                            && owners.add(item.textValue()), "Restart impact is invalid");
                    require(owners.contains(owner) && request.path("data").isObject(),
                        "Restart impact or data evidence is invalid");
                    return new Intent(id, owner, requestBoot, target.path("enabled").booleanValue(),
                        r.getString("target_version"), r.getLong("accepted_revision"), r.getString("state"),
                        r.getString("phase"), r.getString("outcome"), request.path("data"),
                        r.getString("target_schema_version"), r.getObject("target_generation", UUID.class),
                        r.getString("recovery_json"), canonicalUuid(r.getString("archive_log_id")));
                }
            }
        }

        /** Validates the original switch and optional selection. / 校验原开关及可选单选。
         * @param node original choice / 原选择
         * @return validated object / 已校验对象
         * @throws SQLException if the choice is malformed / 选择格式错误时 */
        private static JsonNode choice(JsonNode node) throws SQLException {
            require(node.isObject() && fields(node).equals(Set.of("enabled", "version"))
                    && node.path("enabled").isBoolean()
                    && (node.path("version").isNull()
                    || node.path("version").isTextual() && !node.path("version").textValue().isBlank()
                    && node.path("version").textValue().length() <= 512)
                    && (!node.path("enabled").booleanValue() || node.path("version").isTextual()),
                "Invalid Mod choice");
            return node;
        }

        /** Decodes bounded JSON without duplicate keys or trailing input. / 解码有界 JSON，不接受重复键或尾随输入。
         * @param value retained JSON / 保留 JSON
         * @return parsed object / 解析对象
         * @throws SQLException if malformed / 格式错误时 */
        private static JsonNode object(String value) throws SQLException {
            require(value != null && value.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024,
                "Invalid retained JSON size");
            try {
                var node = JSON.readTree(value);
                require(node != null && node.isObject(), "Retained evidence is not an object");
                return node;
            } catch (java.io.IOException invalid) {
                throw new SQLException("Retained Mod evidence is invalid", invalid);
            }
        }

        /** Lists exact field names for strict protocol validation. / 列出精确字段名以严格校验协议。
         * @param node protocol object / 协议对象
         * @return field names / 字段名 */
        private static Set<String> fields(JsonNode node) {
            var names = new HashSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            return names;
        }

        /** Reads one canonical persisted UUID. / 读取一个规范持久 UUID。
         * @param node original object / 原对象
         * @param name exact field / 精确字段
         * @return canonical identity / 规范身份
         * @throws SQLException if missing or malformed / 缺失或格式错误时 */
        private static UUID uuid(JsonNode node, String name) throws SQLException {
            return canonicalUuid(node.path(name).textValue());
        }

        /** Rejects Java UUID shorthand and noncanonical retained identities. / 拒绝 Java UUID 缩写及非规范保留身份。
         * @param value retained identity / 保留身份
         * @return parsed UUID / 解析 UUID
         * @throws SQLException if noncanonical / 非规范时 */
        private static UUID canonicalUuid(String value) throws SQLException {
            try {
                var id = UUID.fromString(Objects.requireNonNull(value));
                require(id.toString().equals(value), "Noncanonical retained identity");
                return id;
            } catch (IllegalArgumentException | NullPointerException invalid) {
                throw new SQLException("Invalid retained identity", invalid);
            }
        }
    }

    /** Registers and verifies the original physical preparation and its recovery facts. / 登记并核验原物理准备及其恢复事实。 */
    private final class SetupLedger {
        /** Binds the existing ledger state only. / 仅绑定既有账本状态。 */
        private SetupLedger() {
        }

        /** Verifies the original failed target before a retry changes any registration. / 重试改变任何登记前核验原失败目标。
         * @param c original caller transaction / 原调用者事务
         * @param previous original failed generation / 原失败代次
         * @param version unchanged selected version / 未改变选定版本
         * @param revision unchanged selection revision / 未改变选择修订
         * @param manifest original declaration / 原声明
         * @param digest original content digest / 原内容摘要
         * @param dataJson explicitly reviewed data plan / 明确审阅数据计划
         * @return exact parsed data plan / 精确解析数据计划
         * @throws SQLException if admission or evidence changed / 准入或证据变化时 */
        private JsonNode retryAdmission(Connection c, SchemaIdentity previous, String version, long revision,
                                        String manifest, String digest, String dataJson) throws SQLException {
            require(!c.getAutoCommit(), "Explicit retry requires the owning platform transaction");
            StoreConnections.lockPlatformState(c);
            try (var s = c.createStatement();
                 var r = s.executeQuery("SELECT boot_id,shutdown_state FROM app_state WHERE singleton_id=1")) {
                require(r.next() && boot.equals(r.getObject(1, UUID.class)) && "RUNNING".equals(r.getString(2)),
                    "Retry no longer owns the running platform boot");
            }
            var old = new SchemaCoordinates(previous);
            old.requireRegistered(c, Set.of("FAILED"));
            var declaration = IntentEvidence.object(manifest);
            var data = IntentEvidence.object(dataJson);
            require(previous.ownerId().equals(declaration.path("id").textValue())
                    && version.equals(declaration.path("version").textValue())
                    && previous.schemaVersion().equals(declaration.path("schemaVersion").textValue())
                    && previous.schemaVersion().equals(data.path("target").textValue()),
                "Retry target declaration changed");
            try (var p = c.prepareStatement(
                "SELECT COUNT(*) FROM mod_schema s JOIN mod_registry r ON r.mod_id=s.mod_id JOIN mod_version v ON v.mod_id=r.mod_id AND v.version=r.selected_version WHERE s.generation=? AND s.state='FAILED' AND NOT s.ever_used AND s.validated_at IS NULL AND s.validation_digest IS NULL AND r.enabled AND r.selected_version=? AND r.revision=? AND r.actual_version IS NULL AND r.last_runtime_state IN ('STOPPED','FAILED') AND v.state='READY' AND v.manifest_json=? AND v.content_sha256=?")) {
                p.setObject(1, previous.generation());
                p.setString(2, version);
                p.setLong(3, revision);
                p.setString(4, manifest);
                p.setString(5, digest);
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 1, "Only the selected failed unused target may be retried");
                }
            }
            try (var p = c.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND state<>'ENDED')+(SELECT COUNT(*) FROM platform_task WHERE owner_id=? AND state<>'ENDED')+(SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND (owner_id=? OR resource_kind='MOD' AND resource_id=?))+(SELECT COUNT(*) FROM mod_server_reference WHERE schema_generation=?)+(SELECT COUNT(*) FROM mod_credential_reference WHERE schema_generation=?)+(SELECT COUNT(*) FROM log_category WHERE schema_generation=?)")) {
                for (int i = 1; i <= 4; i++)
                    p.setString(i, previous.ownerId());
                for (int i = 5; i <= 7; i++)
                    p.setObject(i, previous.generation());
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 0, "Failed target still has work, references or maintenance");
                }
            }
            try (var p = c
                .prepareStatement("SELECT COUNT(*) FROM information_schema.sessions WHERE LOWER(user_name)=?")) {
                p.setString(1, old.databaseUser());
                try (var r = p.executeQuery()) {
                    r.next();
                    require(r.getLong(1) == 0, "Original target still owns a physical session");
                }
            }
            return data;
        }

        /** Reuses the exact declared ordered chain for initial and explicitly retried preparation. / 为首次及明确重试准备复用精确声明有序链。
         * @param source confirmed retained source / 已确认保留来源
         * @param target new exact target generation / 新精确目标代次
         * @param data reviewed data plan / 已审阅数据计划
         * @param manifest verified selected declaration / 已验证选定声明
         * @return original ordered step identities / 原有序步骤身份
         * @throws SQLException if the confirmed chain no longer matches / 已确认链不再匹配时 */
        private static List<MigrationStep> migrationSteps(SchemaIdentity source, SchemaIdentity target, JsonNode data,
                                                          String manifest) throws SQLException {
            var declared = IntentEvidence.object(manifest).path("migrations");
            var edges = data.path("steps");
            require(edges.isArray() && !edges.isEmpty() && edges.size() <= 256, "Original migration chain is invalid");
            var steps = new ArrayList<MigrationStep>();
            String previous = source.schemaVersion();
            for (var edge : edges) {
                require(IntentEvidence.fields(edge).equals(Set.of("from", "to", "className"))
                        && previous.equals(edge.path("from").textValue())
                        && edge.path("to").asText().matches("[1-9][0-9]{0,63}")
                        && new java.math.BigInteger(previous)
                        .compareTo(new java.math.BigInteger(edge.path("to").asText())) < 0,
                    "Original migration chain is discontinuous");
                boolean found = false;
                for (var candidate : declared)
                    if (edge.path("from").equals(candidate.path("from")) && edge.path("to").equals(candidate.path("to"))
                        && edge.path("className").asText().equals(candidate.path("class").asText()))
                        found = true;
                require(found, "Original migration class is no longer declared");
                previous = edge.path("to").asText();
                var identity = steps.size() == edges.size() - 1
                    ? target
                    : new SchemaIdentity(target.ownerId(), previous, UUID.randomUUID());
                steps.add(new MigrationStep(steps.size(), edge.path("from").asText(), previous,
                    edge.path("className").asText(), identity, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID()));
            }
            require(previous.equals(target.schemaVersion()), "Original chain does not reach its requested target");
            return List.copyOf(steps);
        }

        /** Registers one exclusive preparation before physical creation. / 在物理创建前登记一次独占准备。
         * @param c original transaction / 原事务
         * @param row original intent / 原意图
         * @param plan original physical plan / 原物理计划
         * @throws SQLException if registration fails / 登记失败时 */
        private void registerPreparation(Connection c, Intent row, Setup plan) throws SQLException {
            var coordinates = new SchemaCoordinates(plan.target());
            long now = System.currentTimeMillis();
            try (var p = c.prepareStatement(
                "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,?,?,?,?,'PREPARING',?)")) {
                p.setString(1, row.owner());
                p.setString(2, row.targetSchema());
                p.setObject(3, plan.target().generation());
                p.setString(4, coordinates.physicalName());
                p.setString(5, coordinates.databaseUser());
                p.setLong(6, now);
                p.executeUpdate();
            }
            try (var p = c.prepareStatement("UPDATE mod_operation SET target_generation=? WHERE operation_id=?")) {
                p.setObject(1, plan.target().generation());
                p.setObject(2, plan.intent());
                require(p.executeUpdate() == 1, "Original intent disappeared");
            }
            try (var p = c.prepareStatement(
                "INSERT INTO mod_operation(operation_id,mod_id,operation_type,target_version,target_schema_version,target_generation,accepted_revision,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'MIGRATE',?,?,?,?,'RUNNING','SOURCE_CHECKED',?,?,?,?,?)")) {
                p.setObject(1, plan.operation());
                p.setString(2, row.owner());
                p.setString(3, row.version());
                p.setString(4, row.targetSchema());
                p.setObject(5, plan.target().generation());
                p.setLong(6, row.revision());
                p.setString(7,
                    JSON.createObjectNode().put("formatVersion", 1)
                        .put("mode", plan.source().isPresent() ? "MIGRATE" : "INITIALIZE")
                        .put("intent", plan.intent().toString()).toString());
                p.setString(8, preparationEvidence(plan));
                p.setString(9, plan.archive().toString());
                p.setLong(10, now);
                p.setLong(11, now);
                p.executeUpdate();
            }
            if (plan.source().isPresent())
                try (var p = c
                    .prepareStatement("UPDATE mod_operation SET source_schema_version=? WHERE operation_id=?")) {
                    p.setString(1, plan.source().get().schemaVersion());
                    p.setObject(2, plan.operation());
                    require(p.executeUpdate() == 1, "Original migration disappeared");
                }
            registerSteps(c, plan);
        }

        /** Persists every original edge before any author execution. / 在任意作者执行前持久化每条原迁移边。
         * @param c original transaction / 原事务
         * @param plan original preparation / 原准备
         * @throws SQLException if an edge cannot be registered / 无法登记迁移边时 */
        private static void registerSteps(Connection c, Setup plan) throws SQLException {
            for (var step : plan.steps()) {
                try (var p = c.prepareStatement(
                    "INSERT INTO mod_migration_step(operation_id,step_number,from_version,to_version,migration_class,source_schema,target_schema,state) VALUES(?,?,?,?,?,?,?,'PENDING')")) {
                    p.setObject(1, plan.operation());
                    p.setInt(2, step.index());
                    p.setString(3, step.from());
                    p.setString(4, step.to());
                    p.setString(5, step.className());
                    p.setString(6, sourceCoordinates(plan, step).physicalName());
                    p.setString(7, targetCoordinates(plan, step).physicalName());
                    p.executeUpdate();
                }
            }
        }

        /** Claims a drained earlier-boot request without pretending its data is ready. / 取得已排空的前次启动请求，不假称数据就绪。
         * @param c serialized transaction / 串行事务
         * @param row original request / 原请求
         * @throws SQLException if the original request cannot be claimed / 无法取得原请求时 */
        private void claimUnprepared(Connection c, Intent row) throws SQLException {
            require(row.state().equals("WAITING") && row.phase().equals("WAIT_RESTART") && row.outcome() == null
                && !boot.equals(row.requestBoot()), "Original request is not awaiting a new boot");
            var state = registry(c, row);
            require(!boot.equals(state.boot()), "This Mod already had an activation in this boot");
            requireClosed(state);
            requireDrained(c, row);
            try (var p = c.prepareStatement(
                "UPDATE mod_operation SET state='RUNNING',phase='APPLYING',recovery_json=?,updated_at=? WHERE operation_id=?")) {
                p.setString(1, JSON.createObjectNode().put("formatVersion", 1).put("applicationBootId", boot.toString())
                    .toString());
                p.setLong(2, System.currentTimeMillis());
                p.setObject(3, row.id());
                require(p.executeUpdate() == 1, "Original request disappeared");
            }
        }

        /** Reads the bounded recovery object already checked against its immutable inventory. / 读取已按不可变清单核验的有界恢复对象。
         * @param c platform transaction / 平台事务
         * @param plan original operation / 原操作
         * @return mutable copy of original recovery facts / 原恢复事实的可变副本
         * @throws SQLException if original recovery is absent / 原恢复不存在时 */
        private static com.fasterxml.jackson.databind.node.ObjectNode preparationRecovery(Connection c,
                                                                                          Setup plan) throws SQLException {
            try (var p = c.prepareStatement("SELECT recovery_json FROM mod_operation WHERE operation_id=?")) {
                p.setObject(1, plan.operation());
                try (var r = p.executeQuery()) {
                    require(r.next(), "Original preparation disappeared");
                    return (com.fasterxml.jackson.databind.node.ObjectNode) IntentEvidence.object(r.getString(1));
                }
            }
        }

        /** Saves evidence without changing the original preparation inventory. / 保存证据而不改变原准备清单。
         * @param c platform transaction / 平台事务
         * @param plan original operation / 原操作
         * @param recovery complete original envelope / 完整原封套
         * @throws SQLException if the original write cannot be confirmed / 无法确认原写入时 */
        private static void saveRecovery(Connection c, Setup plan, JsonNode recovery) throws SQLException {
            try (var p = c
                .prepareStatement("UPDATE mod_operation SET recovery_json=?,updated_at=? WHERE operation_id=?")) {
                p.setString(1, recovery.toString());
                p.setLong(2, System.currentTimeMillis());
                p.setObject(3, plan.operation());
                require(p.executeUpdate() == 1, "Original preparation disappeared");
            }
        }

        /** Verifies temporary-user release and original migration cleanup before recording a result. / 记录结果前核验临时用户释放及原迁移清理。
         * @param c original serialized transaction / 原串行事务
         * @param plan original preparation / 原准备
         * @param outcome observed outcome / 已观察结果
         * @param digest original validation receipt / 原验证凭据
         * @param phase original confirmed phase / 原确认阶段
         * @param scopes exact original preparation scopes / 精确原准备作用域
         * @throws SQLException if closure or validation evidence is absent / 关闭或验证证据缺失时 */
        private void verifyCompletion(Connection c, Setup plan, TaskResult outcome, String digest, String phase,
                                      Set<UUID> scopes) throws SQLException {
            for (var scope : scopes) {
                try (var p = c.prepareStatement("SELECT COUNT(*) FROM information_schema.users WHERE user_name=?")) {
                    p.setString(1, SchemaAccessScope.readerUser(scope));
                    try (var r = p.executeQuery()) {
                        r.next();
                        require(outcome == TaskResult.UNKNOWN || r.getLong(1) == 0, "Temporary preparation user remains");
                    }
                }
            }
            require(outcome != TaskResult.SUCCEEDED
                    || phase.equals("VALIDATING") && digest != null && digest.matches("[0-9a-f]{64}"),
                "Successful validation evidence is absent");
            if (plan.source().isPresent() && outcome != TaskResult.UNKNOWN) {
                var facts = SetupLedger.preparationRecovery(c, plan).path("facts");
                require(!facts.has("sourceBefore") && outcome == TaskResult.FAILED
                        || facts.path("sourceBefore").asText().matches("[0-9a-f]{64}")
                        && facts.path("sourceBefore").equals(facts.path("sourceAfter")),
                    "Original source is not confirmed unchanged");
                try (var p = c.prepareStatement(
                    "SELECT COUNT(*) FROM mod_migration_step WHERE operation_id=? AND state<>'COMPLETE'")) {
                    p.setObject(1, plan.operation());
                    try (var r = p.executeQuery()) {
                        r.next();
                        require(outcome != TaskResult.SUCCEEDED || r.getLong(1) == 0,
                            "Original migration chain is incomplete");
                    }
                }
                for (var step : plan.steps().subList(0, plan.steps().size() - 1)) {
                    var coordinates = targetCoordinates(plan, step);
                    try (var p = c.prepareStatement(
                        "SELECT (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?)+(SELECT COUNT(*) FROM information_schema.users WHERE user_name=?)")) {
                        p.setString(1, coordinates.physicalName());
                        p.setString(2, coordinates.databaseUser());
                        try (var r = p.executeQuery()) {
                            r.next();
                            require(r.getLong(1) == 0, "Original intermediate cleanup remains");
                        }
                    }
                }
            }
        }

        /** Recognizes an already committed original result without rebuilding a removed summary. / 识别已提交的原结果，不重建已移除摘要。
         * @param c serialized transaction / 串行事务
         * @param plan original in-memory attempt / 原内存尝试
         * @param outcome originally observed result / 原已观察结果
         * @param digest original validation receipt / 原验证凭据
         * @return whether the exact original terminal transaction is already visible / 是否已经可见精确原终态事务
         * @throws SQLException if retained evidence cannot be read / 无法读取保留证据时 */
        private boolean preparationFinished(Connection c, Setup plan, TaskResult outcome, String digest)
            throws SQLException {
            if (plan.intent().equals(plan.operation())) {
                String state = outcome == TaskResult.SUCCEEDED ? "READY" : outcome.name();
                try (var p = c.prepareStatement("SELECT state,validation_digest FROM mod_schema WHERE generation=?")) {
                    p.setObject(1, plan.target().generation());
                    try (var r = p.executeQuery()) {
                        if (!r.next() || !state.equals(r.getString(1)) || !Objects.equals(digest, r.getString(2)))
                            return false;
                    }
                }
                new SchemaCoordinates(plan.target()).requireRegistered(c, Set.of(state));
                try (var p = c.prepareStatement("SELECT state,recovery_json FROM mod_operation WHERE operation_id=?")) {
                    p.setObject(1, plan.operation());
                    try (var r = p.executeQuery()) {
                        boolean exists = r.next();
                        return outcome == TaskResult.UNKNOWN
                            ? exists && "UNKNOWN".equals(r.getString(1)) && sameInventory(r.getString(2), plan)
                            : !exists;
                    }
                }
            }
            var row = IntentEvidence.required(c, plan.intent());
            require(plan.target().ownerId().equals(row.owner()) && plan.target().generation().equals(row.generation())
                    && plan.target().schemaVersion().equals(row.targetSchema())
                    && boot.equals(IntentEvidence.uuid(IntentEvidence.object(row.recovery()), "applicationBootId")),
                "Original terminal identity changed");
            registry(c, row);
            String state = outcome == TaskResult.SUCCEEDED ? "READY" : outcome.name();
            try (var p = c.prepareStatement("SELECT state,validation_digest FROM mod_schema WHERE generation=?")) {
                p.setObject(1, plan.target().generation());
                try (var r = p.executeQuery()) {
                    if (!r.next() || !state.equals(r.getString(1)) || !Objects.equals(digest, r.getString(2)))
                        return false;
                }
            }
            new SchemaCoordinates(plan.target()).requireRegistered(c, Set.of(state));
            try (var p = c.prepareStatement("SELECT state,recovery_json FROM mod_operation WHERE operation_id=?")) {
                p.setObject(1, plan.operation());
                try (var r = p.executeQuery()) {
                    boolean exists = r.next();
                    return outcome == TaskResult.UNKNOWN
                        ? exists && row.state().equals("UNKNOWN") && "UNKNOWN".equals(r.getString(1))
                        && sameInventory(r.getString(2), plan)
                        : !exists && row.state().equals("RUNNING") && row.phase().equals("APPLYING");
                }
            }
        }

        /** Verifies both original operations and the untouched target generation. / 核验两个原操作及未交付的目标代次。
         * @param c platform transaction / 平台事务
         * @param plan original preparation / 原准备
         * @return current physical preparation phase / 当前物理准备阶段
         * @throws SQLException if ownership or registry evidence changed / 归属或登记证据变化时 */
        private String requirePreparation(Connection c, Setup plan) throws SQLException {
            if (plan.intent().equals(plan.operation()))
                retryRequest(c, plan);
            else {
                var row = IntentEvidence.required(c, plan.intent());
                require(row.owner().equals(plan.target().ownerId())
                        && Objects.equals(row.targetSchema(), plan.target().schemaVersion())
                        && plan.target().generation().equals(row.generation()) && row.state().equals("RUNNING")
                        && row.phase().equals("APPLYING")
                        && boot.equals(IntentEvidence.uuid(IntentEvidence.object(row.recovery()), "applicationBootId")),
                    "Original preparation intent changed");
                requireClosed(registry(c, row));
            }
            new SchemaCoordinates(plan.target()).requireRegistered(c, Set.of("PREPARING"));
            if (plan.source().isPresent())
                new SchemaCoordinates(plan.source().get()).requireRegistered(c, Set.of("READY"));
            try (var p = c.prepareStatement(
                "SELECT phase,recovery_json FROM mod_operation WHERE operation_id=? AND mod_id=? AND operation_type='MIGRATE' AND target_generation=? AND state='RUNNING' AND outcome IS NULL AND archive_log_id=?")) {
                p.setObject(1, plan.operation());
                p.setString(2, plan.target().ownerId());
                p.setObject(3, plan.target().generation());
                p.setString(4, plan.archive().toString());
                try (var r = p.executeQuery()) {
                    require(r.next() && sameInventory(r.getString(2), plan), "Original preparation ownership changed");
                    return r.getString(1);
                }
            }
        }

        /** Validates an explicit standalone retry without inventing an activation intent. / 验证明示独立重试，不虚构启用意图。
         * @param c original transaction / 原事务
         * @param plan original preparation / 原准备
         * @return exact retry request / 精确重试请求
         * @throws SQLException if original program, choice or ownership changed / 原程序、选择或归属变化时 */
        private JsonNode retryRequest(Connection c, Setup plan) throws SQLException {
            try (var p = c.prepareStatement(
                "SELECT o.request_json,o.target_version FROM mod_operation o JOIN mod_registry r ON r.mod_id=o.mod_id JOIN mod_version v ON v.mod_id=o.mod_id AND v.version=o.target_version WHERE o.operation_id=? AND o.mod_id=? AND o.operation_type='MIGRATE' AND o.state='RUNNING' AND o.outcome IS NULL AND o.target_generation=? AND r.enabled AND r.selected_version=o.target_version AND r.revision=o.accepted_revision AND r.actual_version IS NULL AND r.last_runtime_state IN ('STOPPED','FAILED') AND v.state='READY' AND v.schema_version=o.target_schema_version")) {
                p.setObject(1, plan.operation());
                p.setString(2, plan.target().ownerId());
                p.setObject(3, plan.target().generation());
                try (var r = p.executeQuery()) {
                    require(r.next(), "Original explicit retry is no longer owned");
                    var request = IntentEvidence.object(r.getString(1));
                    require(IntentEvidence.fields(request)
                            .equals(Set.of("formatVersion", "mode", "ownerId", "previousGeneration", "manifest",
                                "contentDigest", "target", "data"))
                            && request.path("formatVersion").asInt() == 1
                            && "RETRY".equals(request.path("mode").asText())
                            && plan.target().ownerId().equals(request.path("ownerId").asText())
                            && request.path("target").path("enabled").asBoolean()
                            && r.getString(2).equals(request.path("target").path("version").asText())
                            && !plan.target().generation().equals(IntentEvidence.uuid(request, "previousGeneration")),
                        "Original retry request changed");
                    try (var q = c.prepareStatement(
                        "SELECT COUNT(*) FROM mod_version WHERE mod_id=? AND version=? AND manifest_json=? AND content_sha256=?")) {
                        q.setString(1, plan.target().ownerId());
                        q.setString(2, r.getString(2));
                        q.setString(3, request.path("manifest").asText());
                        q.setString(4, request.path("contentDigest").asText());
                        try (var v = q.executeQuery()) {
                            v.next();
                            require(v.getLong(1) == 1, "Original retry program changed");
                        }
                    }
                    return request;
                }
            }
        }

        /** Encodes recoverable physical identities without passwords or author data. / 编码可恢复物理身份，不含口令或作者数据。
         * @param plan original preparation / 原准备
         * @return canonical recovery envelope / 规范恢复封套 */
        private String preparationEvidence(Setup plan) {
            var coordinates = new SchemaCoordinates(plan.target());
            var recovery = JSON.createObjectNode().put("formatVersion", 1).put("bootId", boot.toString())
                .put("intent", plan.intent().toString()).put("writer", plan.writer().toString())
                .put("reader", plan.reader().toString()).put("physicalSchema", coordinates.physicalName())
                .put("databaseUser", coordinates.databaseUser());
            recovery.putObject("facts");
            if (plan.source().isPresent()) {
                var source = plan.source().get();
                recovery.putObject("source").put("schemaVersion", source.schemaVersion()).put("generation",
                    source.generation().toString());
            }
            var steps = recovery.putArray("steps");
            for (var step : plan.steps()) {
                var target = targetCoordinates(plan, step);
                steps.addObject().put("from", step.from()).put("to", step.to()).put("className", step.className())
                    .put("generation", step.target().generation().toString())
                    .put("physicalSchema", target.physicalName()).put("databaseUser", target.databaseUser())
                    .put("sourceReader", step.sourceReader().toString()).put("writer", step.writer().toString())
                    .put("reader", step.reader().toString());
            }
            return recovery.toString();
        }

        /** Compares immutable original inventory while retaining separately recorded runtime facts. / 比较不可变原清单，同时保留单独记录的运行事实。
         * @param encoded original recovery envelope / 原恢复封套
         * @param plan original in-memory operation / 原内存操作
         * @return whether original inventory and facts shape match / 原清单和事实形状是否匹配
         * @throws SQLException if recovery is malformed / 恢复格式错误时 */
        private boolean sameInventory(String encoded, Setup plan) throws SQLException {
            var actual = (com.fasterxml.jackson.databind.node.ObjectNode) IntentEvidence.object(encoded);
            var expected = (com.fasterxml.jackson.databind.node.ObjectNode) IntentEvidence
                .object(preparationEvidence(plan));
            require(actual.path("facts").isObject(), "Original preparation facts are invalid");
            actual.remove("facts");
            expected.remove("facts");
            return actual.equals(expected);
        }
    }
}
