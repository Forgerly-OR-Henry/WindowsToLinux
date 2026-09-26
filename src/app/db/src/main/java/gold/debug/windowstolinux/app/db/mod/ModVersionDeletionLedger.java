package gold.debug.windowstolinux.app.db.mod;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Persists single installed-release deletion and verifies data cleanup before unregistering it. / 持久化单个安装版本删除，核验数据清理后才注销。
 * File removal belongs to the installer; this ledger independently proves the registered directory is absent. / 文件由安装服务移除；本账本独立证明登记目录已不存在。 */
public final class ModVersionDeletionLedger {
    /** Exclusive physical engine. / 独占物理引擎。 */
    private final PlatformDatabase database;

    /** Trusted application home, never a caller-supplied deletion target. / 可信程序根，不接收调用方任意删除目标。 */
    private final Path home;

    /** Shared admission transactions. / 共用准入事务。 */
    private final RepositoryConnections connections;

    /** Standard atomic archive writer. / 标准原子归档写入器。 */
    private final LogRepository logs;

    /** Fixed platform operation category. / 固定平台操作类别。 */
    private final LogRepository.Target archive;

    /** Strict bounded recovery decoder. / 严格有界恢复解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    /** Durable ordered completion checkpoints. / 持久有序完成检查点。 */
    private static final List<String> PHASES = List.of("QUIESCENT", "PROGRAM_REMOVED", "DATA_REMOVED",
            "REFERENCES_REMOVED", "REGISTRY_REMOVED", "CLEANUP");

    /** Binds ordinary installed packages to their original application home. / 将普通安装包绑定到原程序根。
     * @param database owned engine / 所属引擎
     * @param home trusted JAR or APP home / 可信 JAR 或 APP 程序根
     * @throws SQLException if home or archive registration cannot be verified / 无法核验程序根或归档登记时 */
    public ModVersionDeletionLedger(PlatformDatabase database, Path home) throws SQLException {
        this.database = Objects.requireNonNull(database);
        require(home.isAbsolute() && home.normalize().equals(home), "Application home must be absolute and normalized");
        this.home = home;
        ordinaryAncestors(home);
        connections = new RepositoryConnections(database::openConnection);
        logs = new LogRepository(database);
        archive = logs.register("app", Optional.empty(),
                new LogCategory("mod_operation", "mod_operation", Optional.empty()));
    }

    /** Immutable opaque review of one exact registry snapshot. / 一个精确登记快照的不可变不透明审阅。 */
    public final class Review {
        /** Original service identity. / 原服务身份。 */
        private final ModVersionDeletionLedger issuer = ModVersionDeletionLedger.this;

        /** Original database evidence. / 原数据库证据。 */
        private final Request request;

        /** Stable request and archive identities. / 稳定请求及归档身份。 */
        private final UUID operation = UUID.randomUUID(), archiveId = UUID.randomUUID();

        /** Prevents automatic replay after an uncertain admission commit. / 防止准入提交未知后自动重放。 */
        private boolean attempted;

        /** Captures verified evidence without mutation. / 捕获已核验证据，不作变更。
         * @param request immutable evidence / 不可变证据 */
        private Review(Request request) {
            this.request = request;
        }

        /** Returns the original operation identity. / 返回原操作身份。
         * @return original identity / 原身份 */
        public UUID operation() {
            return operation;
        }

        /** Returns the exact registered installed directory. / 返回精确登记安装目录。
         * @return reviewed program path / 已审阅程序路径 */
        public Path program() {
            return Path.of(request.release().path());
        }

        /** Returns the last-reference data generation to remove, if prepared. / 返回需移除的最后引用数据代次，如已准备。
         * @return exact removable generation / 精确可移除代次 */
        public Optional<SchemaIdentity> removedSchema() {
            return request.removeSchema() ? Optional.of(identity(request)) : Optional.empty();
        }

        /** Lists remaining versions that protect the same data. / 列出保护同一数据的保留版本。
         * @return immutable referring versions / 不可变引用版本 */
        public List<String> retainedSchemaVersions() {
            return request
                    .versions().stream().filter(v -> !v.version().equals(request.version())
                            && request.release().schema() != null && request.release().schema().equals(v.schema()))
                    .map(Release::version).toList();
        }
    }

    /** Reviews a disabled, restart-acknowledged release without changing its files or data. / 审阅已禁用且重启确认的版本，不变更文件及数据。
     * @param owner exact Mod ID / 精确 Mod ID
     * @param version exact release / 精确发布版本
     * @return one-use review / 一次性审阅
     * @throws SQLException if activity, maintenance, source mode or ownership prevents deletion / 活动、维护、源码模式或归属阻止删除时 */
    public Review review(String owner, String version) throws SQLException {
        return locked(c -> {
            var request = snapshot(c, owner, version);
            validate(request);
            drained(c, owner, null);
            dependents(c, owner);
            if (request.removeSchema())
                physicalSafe(c, new SchemaCoordinates(identity(request)));
            encode(request);
            return new Review(request);
        });
    }

    /** Atomically reserves exactly the reviewed release and last-reference generation. / 原子保留精确已审阅版本及最后引用代次。
     * @param review original review from this service / 此服务的原审阅
     * @return durable operation identity / 持久操作身份
     * @throws SQLException if review became stale or admission is unconfirmed / 审阅过期或准入未确认时 */
    public synchronized UUID begin(Review review) throws SQLException {
        require(review != null && review.issuer == this && !review.attempted, "Deletion review is stale or foreign");
        review.attempted = true;
        return locked(c -> {
            var request = review.request;
            require(request.equals(snapshot(c, request.owner(), request.version())),
                    "Reviewed release or data changed");
            drained(c, request.owner(), null);
            dependents(c, request.owner());
            if (request.removeSchema())
                physicalSafe(c, new SchemaCoordinates(identity(request)));
            long now = System.currentTimeMillis();
            try (var p = c.prepareStatement(
                    "INSERT INTO mod_operation(operation_id,mod_id,operation_type,source_version,source_schema_version,target_generation,accepted_revision,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'DELETE_VERSION',?,?,?,?,'RUNNING','QUIESCENT',?,?,?, ?,?)")) {
                p.setObject(1, review.operation);
                p.setString(2, request.owner());
                p.setString(3, request.version());
                p.setString(4, request.release().schema());
                p.setObject(5, request.removeSchema() ? identity(request).generation() : null);
                p.setLong(6, request.ownerState().revision());
                p.setString(7, encode(request));
                p.setString(8, recovery());
                p.setString(9, review.archiveId.toString());
                p.setLong(10, now);
                p.setLong(11, now);
                p.executeUpdate();
            }
            update(c, "UPDATE mod_version SET state='DELETE_PENDING' WHERE mod_id=? AND version=?", request.owner(),
                    request.version());
            if (request.removeSchema())
                update(c, "UPDATE mod_schema SET state='DELETE_PENDING' WHERE mod_id=? AND schema_version=?",
                        request.owner(), request.release().schema());
            return review.operation;
        });
    }

    /** Confirms actual program absence; it never deletes source or accepts a boolean cleanup claim. / 确认程序实际不存在；不删除源码，不接收布尔清理声明。
     * @param operation original accepted operation / 原接纳操作
     * @throws SQLException if the original program still exists or ownership changed / 原程序仍存在或归属变化时 */
    public void programRemoved(UUID operation) throws SQLException {
        transition(operation, "QUIESCENT", "PROGRAM_REMOVED", (c, row) -> absentProgram(row.request()));
    }

    /** Removes only a registered last-reference Schema on an independent DDL connection. / 仅用独立 DDL 连接移除登记的最后引用 Schema。
     * @param operation original accepted operation / 原接纳操作
     * @throws SQLException if physical ownership, activity or the checkpoint is unconfirmed / 物理归属、活动或检查点未确认时 */
    public void removeData(UUID operation) throws SQLException {
        transition(operation, "PROGRAM_REMOVED", "DATA_REMOVED", (c, row) -> {
            absentProgram(row.request());
            if (!row.request().removeSchema())
                return;
            var coordinates = new SchemaCoordinates(identity(row.request()));
            coordinates.requireRegistered(c, Set.of("DELETE_PENDING"));
            try (var ddl = database.openConnection()) {
                physicalSafe(ddl, coordinates);
                removeTables(ddl, coordinates);
                try (var s = ddl.createStatement()) {
                    s.execute("DROP SCHEMA IF EXISTS " + quote(coordinates.physicalName()) + " CASCADE");
                    s.execute("DROP USER IF EXISTS " + quote(coordinates.databaseUser()));
                }
                physicalAbsent(ddl, coordinates);
            }
        });
    }

    /** Releases only references and business log registrations belonging to removed data. / 仅释放已移除数据所属引用及业务日志登记。
     * @param operation original operation / 原操作
     * @throws SQLException if physical data remains or reference cleanup fails / 物理数据残留或引用清理失败时 */
    public void removeReferences(UUID operation) throws SQLException {
        transition(operation, "DATA_REMOVED", "REFERENCES_REMOVED", (c, row) -> {
            absentProgram(row.request());
            if (!row.request().removeSchema())
                return;
            var schema = identity(row.request());
            physicalAbsent(c, new SchemaCoordinates(schema));
            for (var table : List.of("mod_server_reference", "mod_credential_reference"))
                update(c, "DELETE FROM " + table + " WHERE mod_id=? AND schema_generation=?", schema.ownerId(),
                        schema.generation());
            update(c, "DELETE FROM log_category WHERE owner_id=? AND schema_generation=?", schema.ownerId(),
                    schema.generation());
        });
    }

    /** Unregisters the removed release and clears its selection without resetting first-install history. / 注销已删除版本并清空其单选，不重置首次安装历史。
     * @param operation original operation / 原操作
     * @throws SQLException if data, references, program or registry cleanup is unconfirmed / 数据、引用、程序或登记清理未确认时 */
    public void removeRegistry(UUID operation) throws SQLException {
        transition(operation, "REFERENCES_REMOVED", "REGISTRY_REMOVED", (c, row) -> {
            var request = row.request();
            absentProgram(request);
            if (request.removeSchema()) {
                var schema = identity(request);
                physicalAbsent(c, new SchemaCoordinates(schema));
                require(noReferences(c, schema), "Removed data still has registered references");
                update(c, "DELETE FROM mod_schema WHERE mod_id=? AND generation=?", request.owner(),
                        schema.generation());
            }
            update(c,
                    "UPDATE mod_registry SET selected_version=NULL,updated_at=? WHERE mod_id=? AND selected_version=?",
                    System.currentTimeMillis(), request.owner(), request.version());
            update(c, "DELETE FROM mod_version WHERE mod_id=? AND version=?", request.owner(), request.version());
        });
    }

    /** Verifies all registered removals before the final archive transaction. / 最终归档事务前核验全部登记清理。
     * @param operation original operation / 原操作
     * @throws SQLException if cleanup remains unconfirmed / 清理仍未确认时 */
    public void cleanup(UUID operation) throws SQLException {
        transition(operation, "REGISTRY_REMOVED", "CLEANUP", (c, row) -> verifyRemoved(c, row.request()));
    }

    /** Atomically archives the fixed result and removes the working row; absent work is never replayed. / 原子归档固定结果并移除工作行；不存在的工作从不重放。
     * @param operation original operation / 原操作
     * @return whether an original working row was archived / 是否归档了原工作行
     * @throws SQLException if final verification or archival fails / 最终核验或归档失败时 */
    public boolean finish(UUID operation) throws SQLException {
        try {
            return locked(c -> {
                if (count(c, "SELECT COUNT(*) FROM mod_operation WHERE operation_id=?", operation) == 0)
                    return false;
                var row = active(c, operation, "CLEANUP");
                verifyRemoved(c, row.request());
                logs.store(c, archive,
                        List.of(new LogRecordCodec.Record(row.archive(), System.currentTimeMillis(), "app",
                                "mod_operation", LogLevel.INFO, "MOD_VERSION_DELETED",
                                "Installed Mod release deletion was verified", null, row.request().owner(),
                                row.request().version(), null, operation, Outcome.SUCCEEDED, false)),
                        100);
                update(c, "DELETE FROM mod_operation WHERE operation_id=?", operation);
                return true;
            });
        } catch (SQLException failure) {
            retain(operation, failure);
            throw failure;
        }
    }

    /** Explicitly claims only the retained unknown deletion in the current boot. / 仅在当前启动明确取得保留的未知删除。
     * @param operation original identity / 原身份
     * @return last confirmed checkpoint; callers continue from it / 最后确认检查点，调用方据此继续
     * @throws SQLException if evidence, occupancy or state changed / 证据、占用或状态变化时 */
    public String resume(UUID operation) throws SQLException {
        return locked(c -> {
            var row = load(c, operation);
            require("UNKNOWN".equals(row.state()), "Deletion is not waiting for explicit reconciliation");
            unchanged(c, row);
            drained(c, row.request().owner(), operation);
            dependents(c, row.request().owner());
            update(c,
                    "UPDATE mod_operation SET state='RUNNING',recovery_json=?,failure_code=NULL,failure_detail=NULL,updated_at=? WHERE operation_id=?",
                    recovery(), System.currentTimeMillis(), operation);
            return row.phase();
        });
    }

    /** Executes one ordered checkpoint while preserving any failed operation as unknown. / 执行一个有序检查点，将失败操作保留为未知。
     * @param operation original operation / 原操作
     * @param from required phase / 所需阶段
     * @param to next confirmed phase / 下一确认阶段
     * @param action bounded platform cleanup / 有界平台清理
     * @throws SQLException if cleanup or commit fails / 清理或提交失败时 */
    private void transition(UUID operation, String from, String to, Action action) throws SQLException {
        try {
            locked(c -> {
                var row = active(c, operation, from);
                action.run(c, row);
                update(c, "UPDATE mod_operation SET phase=?,updated_at=? WHERE operation_id=?", to,
                        System.currentTimeMillis(), operation);
                return null;
            });
        } catch (SQLException failure) {
            retain(operation, failure);
            throw failure;
        }
    }

    /** Records safe failure without changing the original range or confirmed phase. / 记录安全失败，不改变原范围及已确认阶段。
     * @param operation original identity / 原身份
     * @param failure original diagnostic / 原诊断 */
    private void retain(UUID operation, SQLException failure) {
        try {
            locked(c -> {
                update(c,
                        "UPDATE mod_operation SET state='UNKNOWN',failure_code='MOD_DELETE_UNCONFIRMED',failure_detail=NULL,updated_at=? WHERE operation_id=? AND operation_type='DELETE_VERSION' AND state='RUNNING' AND outcome IS NULL",
                        System.currentTimeMillis(), operation);
                return null;
            });
        } catch (SQLException retaining) {
            failure.addSuppressed(retaining);
        }
    }

    /** Checks the exact current claim and all continuing admission conditions. / 检查精确当前取得记录及全部持续准入条件。
     * @param c serialized transaction / 串行事务
     * @param operation original identity / 原身份
     * @param phase required checkpoint / 所需检查点
     * @return verified original row / 已核验原行
     * @throws SQLException if claim or evidence differs / 取得记录或证据不同时 */
    private Row active(Connection c, UUID operation, String phase) throws SQLException {
        var row = load(c, operation);
        require("RUNNING".equals(row.state()) && phase.equals(row.phase()) && recovery().equals(row.recovery()),
                "Deletion does not own this boot and phase");
        unchanged(c, row);
        drained(c, row.request().owner(), operation);
        dependents(c, row.request().owner());
        return row;
    }

    /** Reads exact restart and version evidence without inferring reference counts from selection. / 读取精确重启及版本证据，不从单选推断引用数。
     * @param c serialized transaction / 串行事务
     * @param owner exact Mod / 精确 Mod
     * @param version requested release / 请求版本
     * @return immutable bounded request / 不可变有界请求
     * @throws SQLException if release or restart acknowledgement is unavailable / 版本或重启确认不可用时 */
    private Request snapshot(Connection c, String owner, String version) throws SQLException {
        var state = owner(c, owner);
        var versions = releases(c, owner);
        var release = versions.stream().filter(v -> v.version().equals(version)).findFirst()
                .orElseThrow(() -> new SQLException("Installed release is absent"));
        require(versions.stream().allMatch(v -> "READY".equals(v.state())), "Installed owner has pending maintenance");
        Schema schema = schema(c, owner, release.schema());
        boolean remove = schema != null && versions.stream()
                .noneMatch(v -> !v.version().equals(version) && Objects.equals(v.schema(), release.schema()));
        return new Request(1, home.toString(), owner, version, state, versions, schema, remove);
    }

    /** Rechecks the reviewed catalog with only this operation's confirmed changes allowed. / 复核已审阅目录，仅允许此操作已确认变更。
     * @param c serialized transaction / 串行事务
     * @param row original operation / 原操作
     * @throws SQLException if catalog, generation or intent changed / 目录、代次或意图变化时 */
    private void unchanged(Connection c, Row row) throws SQLException {
        var request = row.request();
        boolean removed = PHASES.indexOf(row.phase()) >= PHASES.indexOf("REGISTRY_REMOVED");
        var before = request.ownerState();
        var expectedOwner = removed && request.version().equals(before.selected())
                ? new Owner(before.revision(), null, before.initialized(), before.ackBoot())
                : before;
        require(expectedOwner.equals(owner(c, request.owner())), "Accepted disabled intent changed");
        var expected = request.versions().stream().filter(v -> !removed || !v.version().equals(request.version()))
                .map(v -> !removed && v.version().equals(request.version()) ? v.pending() : v).toList();
        require(expected.equals(releases(c, request.owner())),
                "Installed versions or retained Schema references changed");
        var expectedSchema = request.schema();
        if (request.removeSchema())
            expectedSchema = removed ? null : expectedSchema.pending();
        require(Objects.equals(expectedSchema, schema(c, request.owner(), request.release().schema())),
                "Original Schema registration changed");
    }

    /** Reads only a fully stopped and restart-acknowledged owner. / 仅读取完全停止且重启确认的所有者。
     * @param c serialized connection / 串行连接
     * @param owner exact identity / 精确身份
     * @return original disabled choice / 原禁用选择
     * @throws SQLException if shutdown has not been acknowledged / 尚未确认停用时 */
    private static Owner owner(Connection c, String owner) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT enabled,selected_version,selection_initialized,revision,disable_ack_revision,last_boot_id,last_runtime_state,actual_version FROM mod_registry WHERE mod_id=?")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                require(r.next() && !r.getBoolean(1) && r.getObject(5) != null && r.getLong(4) == r.getLong(5)
                        && r.getObject(6) != null && "STOPPED".equals(r.getString(7)) && r.getString(8) == null,
                        "Whole Mod must be disabled and restart-stop acknowledged before deleting any release");
                return new Owner(r.getLong(4), r.getString(2), r.getBoolean(3), r.getObject(6, UUID.class).toString());
            }
        }
    }

    /** Reads all retained versions, including unselected and previously failed activations. / 读取全部保留版本，包括未选中及过去启用失败版本。
     * @param c serialized connection / 串行连接
     * @param owner exact identity / 精确身份
     * @return immutable releases / 不可变版本
     * @throws SQLException if catalog exceeds the review bound / 目录超出审阅边界时 */
    private static List<Release> releases(Connection c, String owner) throws SQLException {
        var values = new ArrayList<Release>();
        try (var p = c.prepareStatement(
                "SELECT version,install_path,content_sha256,archive_sha256,schema_version,manifest_json,origin_kind,state FROM mod_version WHERE mod_id=? ORDER BY version")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    require(values.size() < 1024 && r.getString(6).length() <= 1024 * 1024,
                            "Installed catalog exceeds deletion review bounds");
                    values.add(new Release(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getString(5), r.getString(6), r.getString(7), r.getString(8)));
                }
            }
        }
        return List.copyOf(values);
    }

    /** Reads a prepared generation independently of release selection. / 独立于版本单选读取已准备代次。
     * @param c serialized connection / 串行连接
     * @param owner exact identity / 精确身份
     * @param version data version, or null for stateless release / 数据版本，无状态版本为空
     * @return registration, possibly absent / 登记，可不存在
     * @throws SQLException if reading fails / 读取失败时 */
    private static Schema schema(Connection c, String owner, String version) throws SQLException {
        if (version == null)
            return null;
        try (var p = c.prepareStatement(
                "SELECT generation,physical_name,database_user,state,ever_used,validated_at,validation_digest FROM mod_schema WHERE mod_id=? AND schema_version=?")) {
            p.setString(1, owner);
            p.setString(2, version);
            try (var r = p.executeQuery()) {
                return !r.next()
                        ? null
                        : new Schema(r.getObject(1, UUID.class).toString(), r.getString(2), r.getString(3),
                                r.getString(4), r.getBoolean(5), (Long) r.getObject(6), r.getString(7));
            }
        }
    }

    /** Rejects active own or cross-Mod work and all other retained maintenance. / 拒绝活动自有或跨 Mod 工作及其他全部保留维护。
     * @param c serialized transaction / 串行事务
     * @param owner exact identity / 精确身份
     * @param operation current deletion, null before admission / 当前删除，准入前为空
     * @throws SQLException if unresolved occupancy exists / 存在未解决占用时 */
    private static void drained(Connection c, String owner, UUID operation) throws SQLException {
        require(count(c, "SELECT COUNT(*) FROM platform_task WHERE owner_id=?", owner) == 0,
                "Owned task records still require final cleanup");
        require(count(c,
                "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL AND (owner_id=? OR resource_kind='MOD' AND resource_id=? OR resource_kind='SCHEMA' AND resource_id IN (SELECT CAST(generation AS VARCHAR) FROM mod_schema WHERE mod_id=?))",
                owner, owner, owner) == 0, "Owned or cross-Mod resources still require release");
        require(count(c, "SELECT COUNT(*) FROM mod_operation WHERE mod_id=? AND (? IS NULL OR operation_id<>?)", owner,
                operation, operation) == 0, "Other retained maintenance still requires cleanup");
    }

    /** Requires the existing explicit cascade-disable flow to have handled enabled consumers. / 要求既有明确连带禁用流程已处理开启的使用方。
     * @param c serialized connection / 串行连接
     * @param owner removed provider / 被删除提供方
     * @throws SQLException if enabled declared consumers remain / 仍有开启的声明使用方时 */
    private static void dependents(Connection c, String owner) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT v.manifest_json FROM mod_registry r JOIN mod_version v ON r.mod_id=v.mod_id AND r.selected_version=v.version WHERE r.enabled AND r.mod_id<>?")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    try {
                        String value = r.getString(1);
                        require(value.length() <= 1024 * 1024, "Enabled dependency evidence exceeds bounds");
                        var manifest = JSON.readTree(value);
                        require(manifest != null && manifest.isObject()
                                && (!manifest.has("dependencies") || manifest.path("dependencies").isArray()
                                        && manifest.path("dependencies").size() <= 128),
                                "Enabled dependency evidence is invalid");
                        for (var dependency : manifest.path("dependencies")) {
                            require(dependency.path("id").isTextual(), "Enabled dependency identity is invalid");
                            require(!owner.equals(dependency.path("id").textValue()),
                                    "Enabled dependents must first be explicitly disabled and restarted");
                        }
                    } catch (IOException invalid) {
                        throw new SQLException("Invalid enabled dependency evidence", invalid);
                    }
                }
            }
        }
    }

    /** Validates persisted request shape and trusted program/data coordinates. / 校验持久请求形状及可信程序与数据坐标。
     * @param request retained request / 保留请求
     * @throws SQLException if any identity or derived range is invalid / 任一身份或派生范围无效时 */
    private void validate(Request request) throws SQLException {
        try {
            require(request.format() == 1 && home.toString().equals(request.home()) && request.ownerState() != null
                    && request.ownerState().revision() >= 0 && request.versions() != null
                    && !request.versions().isEmpty() && request.versions().size() <= 1024, "Invalid deletion request");
            UUID.fromString(request.ownerState().ackBoot());
            require(segment(request.owner()) && segment(request.version()), "Invalid installed path identity");
            var release = request.release();
            require(release != null
                    && request.versions().stream().map(Release::version).distinct().count() == request.versions().size()
                    && request.versions().stream().allMatch(v -> "READY".equals(v.state()))
                    && Set.of("LOCAL", "BUNDLED", "STORE").contains(release.origin())
                    && home.resolve("mod").resolve(request.owner()).resolve(request.version()).toString()
                            .equals(release.path()),
                    "Only the registered ordinary installed program can be removed; CLASS sources are not install directories");
            if (request.schema() != null) {
                var coordinates = new SchemaCoordinates(identity(request));
                require(coordinates.physicalName().equals(request.schema().physical())
                        && coordinates.databaseUser().equals(request.schema().user())
                        && Set.of("PREPARING", "READY", "FAILED", "UNKNOWN").contains(request.schema().state()),
                        "Invalid original Schema ownership");
            }
            boolean remove = request.schema() != null && request.versions().stream().noneMatch(
                    v -> !v.version().equals(request.version()) && Objects.equals(v.schema(), release.schema()));
            require(request.removeSchema() == remove, "Last Schema reference evidence changed");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid original deletion evidence", invalid);
        }
    }

    /** Rejects path separators, traversal and ambiguous Windows terminal dots. / 拒绝路径分隔符、越界及 Windows 末尾点歧义。
     * @param value exact path component / 精确路径分量
     * @return whether it is a safe component / 是否为安全分量 */
    private static boolean segment(String value) {
        return value != null && value.matches("[A-Za-z0-9_][A-Za-z0-9_.+\\-]{0,511}") && !value.endsWith(".");
    }

    /** Proves absence without following links or treating access errors as missing paths. / 不跟随链接、不将访问错误当成路径缺失地证明不存在。
     * @param request exact original request / 精确原请求
     * @throws SQLException if the program exists or any ancestor is unsafe / 程序存在或任一祖先不安全时 */
    private void absentProgram(Request request) throws SQLException {
        Path path = Path.of(request.release().path());
        ordinaryAncestors(path.getParent());
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            throw new SQLException("Original installed program still exists");
        } catch (NoSuchFileException absent) {
            // A missing program is evidence for this phase only. / 程序缺失仅是本阶段的证据。
        } catch (IOException failure) {
            throw new SQLException("Program absence cannot be verified", failure);
        }
    }

    /** Checks every existing absolute ancestor without resolving links into another tree. / 检查每个已存在绝对祖先，不将链接解析到其他目录树。
     * @param path bounded absolute path / 有界绝对路径
     * @throws SQLException if attributes are unsafe or unavailable / 属性不安全或不可用时 */
    private static void ordinaryAncestors(Path path) throws SQLException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            try {
                var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(attrs.isDirectory() && !attrs.isSymbolicLink() && !attrs.isOther(),
                        "Installed ancestor is not an ordinary directory");
            } catch (NoSuchFileException absent) {
                return;
            } catch (IOException failure) {
                throw new SQLException("Installed ancestor cannot be verified", failure);
            }
        }
    }

    /** Proves exact physical ownership and absence of owner or reader sessions/privileges. / 证明精确物理归属及无所有者或读取会话与额外权限。
     * @param c privileged connection / 特权连接
     * @param coordinates registered data target / 登记数据目标
     * @throws SQLException if unrelated rights, objects or active sessions exist / 存在无关权限、对象或活动会话时 */
    private static void physicalSafe(Connection c, SchemaCoordinates coordinates) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                coordinates.physicalName()) != 0)
            coordinates.requirePhysicalOwnership(c);
        require(count(c,
                "SELECT (SELECT COUNT(*) FROM information_schema.users WHERE user_name=? AND is_admin)+(SELECT COUNT(*) FROM information_schema.schemata WHERE schema_owner=? AND schema_name<>?)+(SELECT COUNT(*) FROM information_schema.sessions WHERE LOWER(user_name)=?)",
                coordinates.databaseUser(), coordinates.databaseUser(), coordinates.physicalName(),
                coordinates.databaseUser()) == 0, "Physical owner gained privileges, other objects or active sessions");
        require(count(c,
                "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE unique_constraint_schema=? AND constraint_schema<>?",
                coordinates.physicalName(), coordinates.physicalName()) == 0,
                "Another Schema retains a foreign key into this data");
        require(count(c,
                "SELECT (SELECT COUNT(*) FROM information_schema.synonyms WHERE synonym_for_schema=? AND synonym_schema<>?)+(SELECT COUNT(*) FROM information_schema.columns WHERE domain_schema=? AND table_schema<>?)+(SELECT COUNT(*) FROM information_schema.domains WHERE parent_domain_schema=? AND domain_schema<>?)",
                coordinates.physicalName(), coordinates.physicalName(), coordinates.physicalName(),
                coordinates.physicalName(), coordinates.physicalName(), coordinates.physicalName()) == 0,
                "Another Schema retains a synonym or domain reference into this data");
        try (var p = c.prepareStatement(
                "SELECT grantee,grantedrole,table_schema FROM information_schema.rights WHERE grantee=? OR table_schema=?")) {
            p.setString(1, coordinates.databaseUser());
            p.setString(2, coordinates.physicalName());
            try (var r = p.executeQuery()) {
                while (r.next())
                    require(coordinates.databaseUser().equals(r.getString(1)) && r.getString(2) == null
                            && coordinates.physicalName().equals(r.getString(3)),
                            "Schema has external grants or an unreleased reader identity");
            }
        }
    }

    /** Drops the complete owned table/view set with RESTRICT to protect external dependent views. / 用 RESTRICT 移除全部自有表及视图，保护外部依赖视图。
     * @param c independent DDL connection / 独立 DDL 连接
     * @param coordinates original registered target / 原登记目标
     * @throws SQLException if outside dependencies exist or bounded cleanup fails / 存在外部依赖或有界清理失败时 */
    private static void removeTables(Connection c, SchemaCoordinates coordinates) throws SQLException {
        var tables = new ArrayList<String>();
        try (var p = c.prepareStatement(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=? ORDER BY table_name")) {
            p.setString(1, coordinates.physicalName());
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    require(tables.size() < 10000, "Schema table count exceeds bounded deletion");
                    tables.add(quote(coordinates.physicalName()) + "." + quote(r.getString(1)));
                }
            }
        }
        if (!tables.isEmpty()) {
            String command = "DROP TABLE " + String.join(",", tables) + " RESTRICT";
            require(command.length() <= 8 * 1024 * 1024, "Schema deletion statement exceeds bounds");
            try (var s = c.createStatement()) {
                s.execute(command);
            }
        }
    }

    /** Proves both the original Schema and its private user are gone. / 证明原 Schema 及其私有用户均已移除。
     * @param c privileged connection / 特权连接
     * @param coordinates original coordinates / 原坐标
     * @throws SQLException if either physical object remains / 任一物理对象残留时 */
    private static void physicalAbsent(Connection c, SchemaCoordinates coordinates) throws SQLException {
        require(count(c,
                "SELECT (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?)+(SELECT COUNT(*) FROM information_schema.users WHERE user_name=?)",
                coordinates.physicalName(), coordinates.databaseUser()) == 0,
                "Original physical Schema or owner still remains");
    }

    /** Tests retained reference and business-log registrations for one exact generation. / 检查一个精确代次的保留引用及业务日志登记。
     * @param c serialized connection / 串行连接
     * @param schema original generation / 原代次
     * @return whether no registered references remain / 是否无登记引用残留
     * @throws SQLException if reading fails / 读取失败时 */
    private static boolean noReferences(Connection c, SchemaIdentity schema) throws SQLException {
        return count(c,
                "SELECT (SELECT COUNT(*) FROM mod_server_reference WHERE schema_generation=?)+(SELECT COUNT(*) FROM mod_credential_reference WHERE schema_generation=?)+(SELECT COUNT(*) FROM log_category WHERE schema_generation=?)",
                schema.generation(), schema.generation(), schema.generation()) == 0;
    }

    /** Rechecks physical absence and exact registration cleanup before success. / 成功前再次检查物理不存在及精确登记清理。
     * @param c serialized connection / 串行连接
     * @param request original request / 原请求
     * @throws SQLException if any required cleanup is unconfirmed / 任一必要清理未确认时 */
    private void verifyRemoved(Connection c, Request request) throws SQLException {
        absentProgram(request);
        require(count(c, "SELECT COUNT(*) FROM mod_version WHERE mod_id=? AND version=?", request.owner(),
                request.version()) == 0, "Original release remains registered");
        if (request.removeSchema()) {
            var schema = identity(request);
            physicalAbsent(c, new SchemaCoordinates(schema));
            require(noReferences(c, schema)
                    && count(c, "SELECT COUNT(*) FROM mod_schema WHERE generation=?", schema.generation()) == 0,
                    "Original data registration remains");
        }
    }

    /** Loads bounded retained evidence and cross-checks its relational operation identity. / 加载有界保留证据并交叉检查关系操作身份。
     * @param c serialized connection / 串行连接
     * @param operation exact operation / 精确操作
     * @return validated row / 已校验行
     * @throws SQLException if absent or invalid / 缺失或无效时 */
    private Row load(Connection c, UUID operation) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT * FROM mod_operation WHERE operation_id=? AND operation_type='DELETE_VERSION' AND outcome IS NULL")) {
            p.setObject(1, operation);
            try (var r = p.executeQuery()) {
                require(r.next(), "Original deletion evidence is absent");
                try {
                    String value = r.getString("request_json"), recovery = r.getString("recovery_json");
                    require(value.length() <= 8 * 1024 * 1024 && recovery.length() <= 256,
                            "Deletion evidence exceeds bounds");
                    var request = JSON.readValue(value, Request.class);
                    validate(request);
                    var resume = JSON.readTree(recovery);
                    require(resume != null && resume.isObject() && resume.size() == 1 && resume.path("boot").isTextual()
                            && UUID.fromString(resume.path("boot").textValue()).toString()
                                    .equals(resume.path("boot").textValue()),
                            "Invalid deletion recovery identity");
                    require(request.owner().equals(r.getString("mod_id"))
                            && request.version().equals(r.getString("source_version"))
                            && r.getString("target_version") == null
                            && Objects.equals(request.release().schema(), r.getString("source_schema_version"))
                            && r.getString("target_schema_version") == null
                            && Objects.equals(request.removeSchema() ? identity(request).generation() : null,
                                    r.getObject("target_generation", UUID.class))
                            && r.getObject("accepted_revision") != null
                            && request.ownerState().revision() == r.getLong("accepted_revision")
                            && PHASES.contains(r.getString("phase"))
                            && Set.of("RUNNING", "UNKNOWN").contains(r.getString("state")),
                            "Original deletion identity changed");
                    return new Row(request, r.getString("phase"), r.getString("state"), recovery,
                            UUID.fromString(r.getString("archive_log_id")));
                } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
                    throw new SQLException("Invalid retained deletion evidence", invalid);
                }
            }
        }
    }

    /** Serializes a bounded immutable request. / 序列化有界不可变请求。
     * @param request original request / 原请求
     * @return strict JSON / 严格 JSON
     * @throws SQLException if serialization exceeds limits or fails / 序列化超限或失败时 */
    private static String encode(Request request) throws SQLException {
        try {
            String value = JSON.writeValueAsString(request);
            require(value.length() <= 8 * 1024 * 1024, "Deletion evidence exceeds bounds");
            return value;
        } catch (IOException invalid) {
            throw new SQLException("Deletion evidence cannot be encoded", invalid);
        }
    }

    /** Returns the exact original generation. / 返回精确原代次。
     * @param request original request / 原请求
     * @return original identity / 原身份 */
    private static SchemaIdentity identity(Request request) {
        return new SchemaIdentity(request.owner(), request.release().schema(),
                UUID.fromString(request.schema().generation()));
    }

    /** Binds the active claim to the current physical boot. / 将活动取得记录绑定到当前物理启动。
     * @return canonical recovery JSON / 规范恢复 JSON */
    private String recovery() {
        return "{\"boot\":\"" + database.bootId() + "\"}";
    }

    /** Quotes only registered internal identifiers. / 仅引用登记的内部标识。
     * @param value verified internal identifier / 已验证内部标识
     * @return SQL quoted identifier / SQL 引用标识 */
    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** Executes a parameterized registry mutation. / 执行参数化登记变更。
     * @param c serialized connection / 串行连接
     * @param sql fixed platform SQL / 固定平台 SQL
     * @param values bound values / 绑定值
     * @throws SQLException if execution fails / 执行失败时 */
    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++)
                p.setObject(i + 1, values[i]);
            p.executeUpdate();
        }
    }

    /** Reads a parameterized count without exposing arbitrary SQL publicly. / 读取参数化计数，不公开任意 SQL。
     * @param c original connection / 原连接
     * @param sql fixed platform SQL / 固定平台 SQL
     * @param values bound values / 绑定值
     * @return exact count / 精确计数
     * @throws SQLException if reading fails / 读取失败时 */
    private static long count(Connection c, String sql, Object... values) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++)
                p.setObject(i + 1, values[i]);
            try (var r = p.executeQuery()) {
                require(r.next(), "Missing platform count");
                return r.getLong(1);
            }
        }
    }

    /** Rejects unproven cleanup before advancing a durable checkpoint. / 推进持久检查点前拒绝未证明清理。
     * @param condition required proof / 所需证明
     * @param message safe fixed reason / 安全固定原因
     * @throws SQLException if unproven / 未证明时 */
    private static void require(boolean condition, String message) throws SQLException {
        if (!condition)
            throw new SQLException(message);
    }

    /** Serializes admission and cleanup against the current running boot. / 按当前运行启动串行准入及清理。
     * @param <T> result type / 结果类型
     * @param work bounded transaction / 有界事务
     * @return committed result / 已提交结果
     * @throws SQLException if transaction or boot gate fails / 事务或启动门失败时 */
    private <T> T locked(Work<T> work) throws SQLException {
        var result = new AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
                require(count(c,
                        "SELECT COUNT(*) FROM app_state WHERE singleton_id=1 AND boot_id=? AND shutdown_state='RUNNING'",
                        database.bootId()) == 1, "Deletion requires the original running platform boot");
                result.set(work.run(c));
            }
        });
        return result.get();
    }

    /** Internal serialized work. / 内部串行工作。
     * @param <T> result type / 结果类型 */
    @FunctionalInterface
    private interface Work<T> {
        /** Runs without author callbacks. / 不运行作者回调地执行。
         * @param c transaction / 事务
         * @return result / 结果
         * @throws SQLException if work fails / 工作失败时 */
        T run(Connection c) throws SQLException;
    }

    /** Internal ordered cleanup action. / 内部有序清理动作。 */
    @FunctionalInterface
    private interface Action {
        /** Performs the next bounded cleanup. / 执行下一有界清理。
         * @param c transaction / 事务
         * @param row original row / 原行
         * @throws SQLException if cleanup fails / 清理失败时 */
        void run(Connection c, Row row) throws SQLException;
    }

    /** Disabled restart intent evidence. / 禁用重启意图证据。
     * @param revision accepted revision / 接纳修订
     * @param selected original selection / 原单选
     * @param initialized persistent first-selection history / 持久首次选择历史
     * @param ackBoot acknowledged boot / 确认启动 */
    private record Owner(long revision, String selected, boolean initialized, String ackBoot) {
    }

    /** Original installed version evidence. / 原安装版本证据。
     * @param version release / 版本
     * @param path program directory / 程序目录
     * @param content content digest / 内容摘要
     * @param original original-package digest / 原包摘要
     * @param schema optional data version / 可选数据版本
     * @param manifest original metadata / 原元数据
     * @param origin source kind / 来源类型
     * @param state original install state / 原安装状态 */
    private record Release(String version, String path, String content, String original, String schema, String manifest,
            String origin, String state) {
        /** Applies this operation's sole version-state change. / 应用此操作唯一版本状态变更。
         * @return expected pending version / 预期待清理版本 */
        Release pending() {
            return new Release(version, path, content, original, schema, manifest, origin, "DELETE_PENDING");
        }
    }

    /** Exact original prepared data evidence. / 精确原已准备数据证据。
     * @param generation original generation / 原代次
     * @param physical original Schema / 原 Schema
     * @param user original owner user / 原所有者用户
     * @param state original state / 原状态
     * @param used actual business-use evidence / 实际业务使用证据
     * @param validated original validation time / 原验证时间
     * @param digest original validation digest / 原验证摘要 */
    private record Schema(String generation, String physical, String user, String state, boolean used, Long validated,
            String digest) {
        /** Applies this operation's sole data-state change. / 应用此操作唯一数据状态变更。
         * @return expected pending data / 预期待清理数据 */
        Schema pending() {
            return new Schema(generation, physical, user, "DELETE_PENDING", used, validated, digest);
        }
    }

    /** Durable reviewed deletion range. / 持久已审阅删除范围。
     * @param format request format / 请求格式
     * @param home original application home / 原程序根
     * @param owner exact owner / 精确所有者
     * @param version deleted version / 被删版本
     * @param ownerState original disabled intent / 原禁用意图
     * @param versions complete retained release evidence / 完整保留版本证据
     * @param schema optional original prepared data / 可选原已准备数据
     * @param removeSchema whether this deletes the final reference / 是否删除最后引用 */
    private record Request(int format, String home, String owner, String version, Owner ownerState,
            List<Release> versions, Schema schema, boolean removeSchema) {
        /** Finds the unique original release. / 查找唯一原版本。
         * @return original release, or null for invalid evidence / 原版本，无效证据则为空 */
        Release release() {
            return versions.stream().filter(v -> v.version().equals(version)).findFirst().orElse(null);
        }
    }

    /** Original execution checkpoint. / 原执行检查点。
     * @param request reviewed range / 已审阅范围
     * @param phase last confirmed phase / 最后确认阶段
     * @param state current execution state / 当前执行状态
     * @param recovery claimed boot / 已取得启动
     * @param archive fixed archive identity / 固定归档身份 */
    private record Row(Request request, String phase, String state, String recovery, UUID archive) {
    }
}
