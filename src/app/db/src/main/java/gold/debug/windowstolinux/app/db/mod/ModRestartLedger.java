package gold.debug.windowstolinux.app.db.mod;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Applies a prepared restart intent against durable facts from the original boot. / 根据原启动的持久事实应用已准备的重启意图。 */
public final class ModRestartLedger {
    /** Exact physical database boot. / 精确物理数据库启动身份。 */
    private final UUID boot;

    /** Physical preparation connection tracker. / 物理准备连接跟踪器。 */
    private final PlatformDatabase database;

    /** Platform transactions shared with task admission. / 与任务准入共用的平台事务。 */
    private final RepositoryConnections connections;

    /** Existing standard log writer. / 既有标准日志写入器。 */
    private final LogRepository logs;

    /** Registered platform operation category. / 已登记的平台操作类别。 */
    private final LogRepository.Target archive;

    /** Strict durable request parser. / 严格持久请求解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Binds the execution ledger without running author code. / 绑定执行账本，不运行作者代码。
     * @param database exclusively owned platform engine / 独占的平台引擎
     * @throws SQLException if the original archive category cannot be registered / 无法登记原归档类别时 */
    public ModRestartLedger(PlatformDatabase database) throws SQLException {
        this.database = database;
        boot = database.bootId();
        connections = new RepositoryConnections(database::openConnection);
        logs = new LogRepository(database);
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
            var row = required(c, id);
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

    /** Identifies an untouched first-use request without claiming or executing it. / 识别未执行的首次使用请求，不取得或执行它。
     * @param id retained intent / 保留意图
     * @return whether first initialization remains necessary / 是否仍需首次初始化
     * @throws SQLException if the original request is invalid / 原请求无效时 */
    public boolean needsInitialization(UUID id) throws SQLException {
        return locked(c -> {
            var row = required(c, id);
            return row.state().equals("WAITING") && row.phase().equals("WAIT_RESTART") && row.outcome() == null
                    && "INITIALIZE".equals(row.data().path("mode").textValue());
        });
    }

    /** Original identities for a single physical first-use attempt. / 一次物理首次使用尝试的原身份。
     * @param intent original enabled choice / 原启用选择
     * @param operation independent preparation operation / 独立准备操作
     * @param target newly registered generation / 新登记代次
     * @param writer tracked initialization scope / 受跟踪初始化作用域
     * @param reader recoverable SELECT-only scope / 可恢复只读作用域
     * @param archive fixed summary identity / 固定摘要身份 */
    public record Initialization(UUID intent, UUID operation, SchemaIdentity target, UUID writer, UUID reader,
            UUID archive) {
    }

    /** Registers the target and all scope identities before any DDL or author callback. / 在任一 DDL 或作者回调前登记目标及全部作用域身份。
     * @param id original restart intent / 原重启意图
     * @param manifest verified selected declaration / 已验证选定声明
     * @param digest verified selected content digest / 已验证选定内容摘要
     * @return committed preparation identities / 已提交准备身份
     * @throws SQLException if the original choice, source or exclusivity changed / 原选择、来源或独占发生变化时 */
    public Initialization beginInitialization(UUID id, String manifest, String digest) throws SQLException {
        return locked(c -> {
            var row = required(c, id);
            require(row.enabled() && row.targetSchema() != null && row.generation() == null
                    && fields(row.data()).equals(Set.of("mode", "target"))
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
            claimUnprepared(c, row);
            var plan = new Initialization(id, UUID.randomUUID(),
                    new SchemaIdentity(row.owner(), row.targetSchema(), UUID.randomUUID()), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID());
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
                p.setObject(2, id);
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
                p.setString(7, JSON.createObjectNode().put("formatVersion", 1).put("mode", "INITIALIZE")
                        .put("intent", id.toString()).toString());
                p.setString(8, initializationEvidence(plan));
                p.setString(9, plan.archive().toString());
                p.setLong(10, now);
                p.setLong(11, now);
                p.executeUpdate();
            }
            return plan;
        });
    }

    /** Claims only an untouched request rejected before physical preparation. / 仅取得物理准备前已被拒绝的未执行请求。
     * @param id original request / 原请求
     * @throws SQLException if prior execution or work remains / 已有执行或工作残留时 */
    public void rejectInitialization(UUID id) throws SQLException {
        locked(c -> {
            var row = required(c, id);
            if (row.state().equals("WAITING")) {
                require("INITIALIZE".equals(row.data().path("mode").textValue()), "Not an initialization request");
                claimUnprepared(c, row);
            }
            return null;
        });
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

    /** Advances one fixed preparation phase before its corresponding side effect. / 在对应副作用前推进一个固定准备阶段。
     * @param plan original attempt / 原尝试
     * @param validating true for validation, false for initialization / 真表示验证，假表示初始化
     * @throws SQLException if original ownership or phase is stale / 原归属或阶段已过期时 */
    public void initializationPhase(Initialization plan, boolean validating) throws SQLException {
        locked(c -> {
            String phase = requireInitialization(c, plan);
            require(phase.equals(validating ? "MIGRATING" : "SOURCE_CHECKED"), "Preparation phase changed");
            try (var p = c.prepareStatement("UPDATE mod_operation SET phase=?,updated_at=? WHERE operation_id=?")) {
                p.setString(1, validating ? "VALIDATING" : "MIGRATING");
                p.setLong(2, System.currentTimeMillis());
                p.setObject(3, plan.operation());
                require(p.executeUpdate() == 1, "Preparation disappeared");
            }
            return null;
        });
    }

    /** Confirms closed preparation and archives its known outcome in one transaction. / 在同一事务确认已关闭准备并归档已知结果。
     * @param plan original attempt / 原尝试
     * @param outcome known result, or UNKNOWN for retained reconciliation / 已知结果，UNKNOWN 保留核对
     * @param digest validation receipt for success, otherwise null / 成功验证凭据，否则为空
     * @throws SQLException if release, identity or atomic archival cannot be confirmed / 无法确认释放、身份或原子归档时 */
    public void finishInitialization(Initialization plan, Outcome outcome, String digest) throws SQLException {
        require(Set.of(Outcome.SUCCEEDED, Outcome.FAILED, Outcome.UNKNOWN).contains(outcome),
                "Invalid preparation result");
        require(database.openConnections(plan.writer().toString()) == 0
                && database.openConnections(plan.reader().toString()) == 0, "Preparation connections remain");
        locked(c -> {
            if (initializationFinished(c, plan, outcome, digest))
                return null;
            String phase = requireInitialization(c, plan);
            try (var p = c.prepareStatement("SELECT COUNT(*) FROM information_schema.users WHERE user_name=?")) {
                p.setString(1, SchemaAccessScope.readerUser(plan.reader()));
                try (var r = p.executeQuery()) {
                    r.next();
                    require(outcome == Outcome.UNKNOWN || r.getLong(1) == 0, "Temporary preparation user remains");
                }
            }
            require(outcome != Outcome.SUCCEEDED
                    || phase.equals("VALIDATING") && digest != null && digest.matches("[0-9a-f]{64}"),
                    "Successful validation evidence is absent");
            long now = System.currentTimeMillis();
            try (var p = c.prepareStatement(
                    "UPDATE mod_schema SET state=?,validated_at=?,validation_digest=? WHERE generation=? AND state='PREPARING' AND NOT ever_used")) {
                p.setString(1, outcome == Outcome.SUCCEEDED ? "READY" : outcome.name());
                p.setObject(2, outcome == Outcome.SUCCEEDED ? now : null);
                p.setString(3, outcome == Outcome.SUCCEEDED ? digest : null);
                p.setObject(4, plan.target().generation());
                require(p.executeUpdate() == 1, "Original target cannot change state");
            }
            if (outcome == Outcome.UNKNOWN) {
                try (var p = c.prepareStatement(
                        "UPDATE mod_operation SET state='UNKNOWN',failure_code='MOD_SCHEMA_PREPARATION_UNKNOWN',updated_at=? WHERE operation_id IN (?,?)")) {
                    p.setLong(1, now);
                    p.setObject(2, plan.operation());
                    p.setObject(3, plan.intent());
                    require(p.executeUpdate() == 2, "Original preparation identities changed");
                }
            } else {
                var row = required(c, plan.intent());
                logs.store(c, archive, List.of(new LogRecordCodec.Record(plan.archive(), now, "app", "mod_operation",
                        outcome == Outcome.SUCCEEDED ? LogLevel.INFO : LogLevel.ERROR,
                        outcome == Outcome.SUCCEEDED ? "MOD_SCHEMA_INITIALIZED" : "MOD_SCHEMA_INITIALIZATION_FAILED",
                        outcome == Outcome.SUCCEEDED
                                ? "Initial data validation and cleanup succeeded"
                                : "Initial data preparation failed and resources are closed",
                        null, row.owner(), row.version(), null, plan.operation(), outcome, false)), 100);
                try (var p = c.prepareStatement("DELETE FROM mod_operation WHERE operation_id=?")) {
                    p.setObject(1, plan.operation());
                    require(p.executeUpdate() == 1, "Original preparation disappeared");
                }
            }
            return null;
        });
    }

    /** Recognizes an already committed original result without rebuilding a removed summary. / 识别已提交的原结果，不重建已移除摘要。
     * @param c serialized transaction / 串行事务
     * @param plan original in-memory attempt / 原内存尝试
     * @param outcome originally observed result / 原已观察结果
     * @param digest original validation receipt / 原验证凭据
     * @return whether the exact original terminal transaction is already visible / 是否已经可见精确原终态事务
     * @throws SQLException if retained evidence cannot be read / 无法读取保留证据时 */
    private boolean initializationFinished(Connection c, Initialization plan, Outcome outcome, String digest)
            throws SQLException {
        var row = required(c, plan.intent());
        require(plan.target().ownerId().equals(row.owner()) && plan.target().generation().equals(row.generation())
                && plan.target().schemaVersion().equals(row.targetSchema())
                && boot.equals(uuid(object(row.recovery()), "applicationBootId")),
                "Original terminal identity changed");
        registry(c, row);
        String state = outcome == Outcome.SUCCEEDED ? "READY" : outcome.name();
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
                return outcome == Outcome.UNKNOWN
                        ? exists && row.state().equals("UNKNOWN") && "UNKNOWN".equals(r.getString(1))
                                && initializationEvidence(plan).equals(r.getString(2))
                        : !exists && row.state().equals("RUNNING") && row.phase().equals("APPLYING");
            }
        }
    }

    /** Verifies both original operations and the untouched target generation. / 核验两个原操作及未交付的目标代次。
     * @param c platform transaction / 平台事务
     * @param plan original preparation / 原准备
     * @return current physical preparation phase / 当前物理准备阶段
     * @throws SQLException if ownership or registry evidence changed / 归属或登记证据变化时 */
    private String requireInitialization(Connection c, Initialization plan) throws SQLException {
        var row = required(c, plan.intent());
        require(row.owner().equals(plan.target().ownerId())
                && Objects.equals(row.targetSchema(), plan.target().schemaVersion())
                && plan.target().generation().equals(row.generation()) && row.state().equals("RUNNING")
                && row.phase().equals("APPLYING") && boot.equals(uuid(object(row.recovery()), "applicationBootId")),
                "Original preparation intent changed");
        requireClosed(registry(c, row));
        new SchemaCoordinates(plan.target()).requireRegistered(c, Set.of("PREPARING"));
        try (var p = c.prepareStatement(
                "SELECT phase FROM mod_operation WHERE operation_id=? AND mod_id=? AND operation_type='MIGRATE' AND target_generation=? AND state='RUNNING' AND outcome IS NULL AND recovery_json=? AND archive_log_id=?")) {
            p.setObject(1, plan.operation());
            p.setString(2, row.owner());
            p.setObject(3, plan.target().generation());
            p.setString(4, initializationEvidence(plan));
            p.setString(5, plan.archive().toString());
            try (var r = p.executeQuery()) {
                require(r.next(), "Original preparation ownership changed");
                return r.getString(1);
            }
        }
    }

    /** Encodes recoverable physical identities without passwords or author data. / 编码可恢复物理身份，不含口令或作者数据。
     * @param plan original preparation / 原准备
     * @return canonical recovery envelope / 规范恢复封套 */
    private String initializationEvidence(Initialization plan) {
        var coordinates = new SchemaCoordinates(plan.target());
        return JSON.createObjectNode().put("formatVersion", 1).put("bootId", boot.toString())
                .put("intent", plan.intent().toString()).put("writer", plan.writer().toString())
                .put("reader", plan.reader().toString()).put("physicalSchema", coordinates.physicalName())
                .put("databaseUser", coordinates.databaseUser()).toString();
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
            var row = read(c, id);
            if (row == null)
                return false;
            require(row.state().equals("RUNNING") && row.phase().equals("APPLYING") && row.outcome() == null,
                    "Original intent is not an active application");
            var recovery = object(row.recovery());
            require(fields(recovery).equals(Set.of("formatVersion", "applicationBootId"))
                    && recovery.path("formatVersion").isInt() && recovery.path("formatVersion").intValue() == 1
                    && boot.equals(uuid(recovery, "applicationBootId")), "Application belongs to another boot");
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
            var outcome = failed ? Outcome.FAILED : Outcome.SUCCEEDED;
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
            require(fields(data).equals(Set.of("mode")) && "NONE".equals(data.path("mode").textValue())
                    && row.generation() == null, "Stateless startup has invalid data evidence");
            return Optional.empty();
        }
        boolean initialized = fields(data).equals(Set.of("mode", "target"))
                && "INITIALIZE".equals(data.path("mode").textValue()) && row.state().equals("RUNNING")
                && row.phase().equals("APPLYING") && row.generation() != null;
        require((initialized || fields(data).equals(Set.of("mode", "target", "generation"))
                && "REUSE".equals(data.path("mode").textValue())) && schema.equals(data.path("target").textValue()),
                "Original data preparation must be completed before activation");
        var identity = new SchemaIdentity(row.owner(), schema,
                initialized ? row.generation() : uuid(data, "generation"));
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
                require(fields(request).equals(
                        Set.of("formatVersion", "requestId", "bootId", "ownerId", "base", "target", "data", "affected"))
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
        require(node.isObject() && fields(node).equals(Set.of("enabled", "version")) && node.path("enabled").isBoolean()
                && (node.path("version").isNull()
                        || node.path("version").isTextual() && !node.path("version").textValue().isBlank()
                                && node.path("version").textValue().length() <= 512)
                && (!node.path("enabled").booleanValue() || node.path("version").isTextual()), "Invalid Mod choice");
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
                RepositoryConnections.lockPlatformState(c);
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
            String state, String phase, String outcome, JsonNode data, String targetSchema, UUID generation,
            String recovery, UUID archive) {
    }
}
