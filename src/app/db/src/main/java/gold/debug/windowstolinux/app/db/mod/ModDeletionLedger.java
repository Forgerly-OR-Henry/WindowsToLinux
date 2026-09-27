package gold.debug.windowstolinux.app.db.mod;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.persistence.repository.CredentialReferenceRepository;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Persists exact installed-release or whole-Mod deletion before unregistering verified cleanup. / 持久化精确安装版本或整 Mod 删除，核验清理后才注销。
 * File removal belongs to the installer; this ledger independently proves the registered directory is absent. / 文件由安装服务移除；本账本独立证明登记目录已不存在。 */
public final class ModDeletionLedger {
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
    public ModDeletionLedger(PlatformDatabase database, Path home) throws SQLException {
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
        private final ModDeletionLedger issuer = ModDeletionLedger.this;

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

        /** Returns the exact version directory or whole-owner installed root. / 返回精确版本目录或整所有者安装根。
         * @return reviewed program path / 已审阅程序路径 */
        public Path program() {
            return request.program();
        }

        /** Returns the last-reference data generation to remove, if prepared. / 返回需移除的最后引用数据代次，如已准备。
         * @return exact removable generation / 精确可移除代次 */
        public Optional<SchemaIdentity> removedSchema() {
            return request.version() == null ? Optional.empty() : removedSchemas().stream().findFirst();
        }

        /** Lists all exact generations covered by this review. / 列出此审阅覆盖的全部精确代次。
         * @return immutable removal identities / 不可变移除身份 */
        public List<SchemaIdentity> removedSchemas() {
            return request.removals().stream().map(v -> identity(request, v)).toList();
        }

        /** Lists exact category identities whose emergency files must be removed by the host. / 列出其应急文件须由宿主移除的精确类别身份。
         * @return original owned categories; empty for a version-only request / 原自有类别，单版本请求为空 */
        public List<LogRepository.Target> logTargets() {
            return request.targets();
        }

        /** Lists exact owner-local pinned pages removed with the whole owner. / 列出随整所有者移除的精确所有者内部固定页面。
         * @return reviewed page identities, empty for a version-only request / 已审阅页面身份，单版本请求为空 */
        public List<String> removedNavigationPages() {
            return request.pins().stream().map(ModNavigationPreferences.PagePin::pageId).toList();
        }

        /** Lists owned credentials that have no outside consumers. / 列出没有外部使用方的自有凭据。
         * @return identities only, never secret values / 仅身份，从不包含秘密值 */
        public List<String> removedSecrets() {
            return request.secrets().stream().filter(v -> !v.shared()).map(Secret::id).toList();
        }

        /** Lists owned credentials protected by shared platform or other-Mod references. / 列出受平台或其他 Mod 共享引用保护的自有凭据。
         * @return retained identities only / 仅保留身份 */
        public List<String> retainedSecrets() {
            return request.secrets().stream().filter(Secret::shared).map(Secret::id).toList();
        }

        /** Lists exact installed releases in the requested scope. / 列出请求范围中的精确安装版本。
         * @return immutable version identities / 不可变版本身份 */
        public List<String> versions() {
            return request.selected().stream().map(Release::version).toList();
        }

        /** Lists remaining versions that protect the same data. / 列出保护同一数据的保留版本。
         * @return immutable referring versions / 不可变引用版本 */
        public List<String> retainedSchemaVersions() {
            if (request.version() == null)
                return List.of();
            return request
                    .versions().stream().filter(v -> !v.version().equals(request.version())
                            && request.release().schema() != null && request.release().schema().equals(v.schema()))
                    .map(Release::version).toList();
        }
    }

    /** Reviews every installed release and owned generation of a stopped Mod, including orphan data. / 审阅已停用 Mod 的全部安装版本及自有代次，包含孤立数据。
     * @param owner exact Mod ID / 精确 Mod ID
     * @return one-use whole-owner review / 一次性整所有者审阅
     * @throws SQLException if admission or ownership cannot be verified / 无法核验准入或归属时 */
    public Review review(String owner) throws SQLException {
        return review(owner, null);
    }

    /** Reviews a disabled, restart-acknowledged release without changing its files or data. / 审阅已禁用且重启确认的版本，不变更文件及数据。
     * @param owner exact Mod ID / 精确 Mod ID
     * @param version exact release; null selects the whole owner / 精确发布版本，空值选择整所有者
     * @return one-use review / 一次性审阅
     * @throws SQLException if activity, maintenance, source mode or ownership prevents deletion / 活动、维护、源码模式或归属阻止删除时 */
    public Review review(String owner, String version) throws SQLException {
        return locked(c -> {
            var request = snapshot(c, owner, version);
            validate(request);
            drained(c, owner, null);
            dependents(c, owner);
            for (var data : request.removals())
                physicalSafe(c, new SchemaCoordinates(identity(request, data)));
            encode(request);
            return new Review(request);
        });
    }

    /** Atomically reserves the exact reviewed releases, data and owner cleanup scope. / 原子保留精确已审阅版本、数据及所有者清理范围。
     * @param review original review from this service / 此服务的原审阅
     * @param files bounded host-reviewed physical scope, preserved verbatim / 有界宿主审阅物理范围，原样保留
     * @return durable operation identity / 持久操作身份
     * @throws SQLException if review became stale or admission is unconfirmed / 审阅过期或准入未确认时 */
    public synchronized UUID begin(Review review, String files) throws SQLException {
        require(review != null && review.issuer == this && !review.attempted, "Deletion review is stale or foreign");
        review.attempted = true;
        return locked(c -> {
            var before = review.request;
            require(before.equals(snapshot(c, before.owner(), before.version())), "Reviewed release or data changed");
            require(files != null && files.length() <= 4 * 1024 * 1024, "Physical deletion scope exceeds bounds");
            var request = new Request(before.format(), before.home(), before.owner(), before.version(),
                    before.ownerState(), before.versions(), before.schemas(), before.targets(), before.pins(),
                    before.secrets(), files);
            drained(c, request.owner(), null);
            dependents(c, request.owner());
            for (var data : request.removals())
                physicalSafe(c, new SchemaCoordinates(identity(request, data)));
            long now = System.currentTimeMillis();
            try (var p = c.prepareStatement(
                    "INSERT INTO mod_operation(operation_id,mod_id,operation_type,source_version,source_schema_version,target_generation,accepted_revision,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'RUNNING','QUIESCENT',?,?,?,?,?)")) {
                p.setObject(1, review.operation);
                p.setString(2, request.owner());
                p.setString(3, request.type());
                p.setString(4, request.version());
                p.setString(5, request.version() == null ? null : request.release().schema());
                p.setObject(6, request.sourceGeneration());
                p.setLong(7, request.ownerState().revision());
                p.setString(8, encode(request));
                p.setString(9, recovery());
                p.setString(10, review.archiveId.toString());
                p.setLong(11, now);
                p.setLong(12, now);
                p.executeUpdate();
            }
            for (var release : request.selected())
                update(c, "UPDATE mod_version SET state='DELETE_PENDING' WHERE mod_id=? AND version=?", request.owner(),
                        release.version());
            for (var data : request.removals())
                update(c, "UPDATE mod_schema SET state='DELETE_PENDING' WHERE mod_id=? AND schema_version=?",
                        request.owner(), data.version());
            return review.operation;
        });
    }

    /** Retained physical cleanup scope and exact original checkpoint. / 保留物理清理范围及精确原检查点。
     * @param operation original operation / 原操作
     * @param owner original Mod / 原 Mod
     * @param version original release; null for whole-owner deletion / 原发布，整所有者删除为空
     * @param program original installed version or whole-owner root / 原安装版本或整所有者根
     * @param phase last confirmed checkpoint / 最后确认检查点
     * @param state retained execution state / 保留执行状态
     * @param files original host-reviewed physical scope / 原宿主审阅物理范围
     * @param versions exact requested installed releases / 精确请求安装版本
     * @param targets original owned diagnostic categories / 原自有诊断类别 */
    public record Execution(UUID operation, String owner, String version, Path program, String phase, String state,
            String files, List<String> versions, List<LogRepository.Target> targets) {
    }

    /** Reads original evidence without granting permission to replay completed work. / 读取原证据，不授予重放已完成工作的权限。
     * @param operation original identity / 原身份
     * @return retained immutable evidence / 保留不可变证据
     * @throws SQLException if the original range is absent or changed / 原范围缺失或变化时 */
    public Execution execution(UUID operation) throws SQLException {
        return locked(c -> {
            var row = load(c, operation);
            unchanged(c, row);
            var request = row.request();
            return new Execution(operation, request.owner(), request.version(), request.program(), row.phase(),
                    row.state(), request.files(), request.selected().stream().map(Release::version).toList(),
                    request.targets());
        });
    }

    /** Marks interrupted host file work unknown without changing its original phase or scope. / 将中断宿主文件工作标未知，不改变原阶段或范围。
     * @param operation original identity / 原身份
     * @throws SQLException if the uncertainty cannot be retained / 无法保留不确定性时 */
    public void unconfirmed(UUID operation) throws SQLException {
        locked(c -> {
            update(c,
                    "UPDATE mod_operation SET state='UNKNOWN',failure_code='MOD_DELETE_UNCONFIRMED',failure_detail=NULL,updated_at=? WHERE operation_id=? AND operation_type IN ('DELETE_VERSION','DELETE_MOD') AND state='RUNNING' AND outcome IS NULL",
                    System.currentTimeMillis(), operation);
            return null;
        });
    }

    /** Confirms actual program absence; it never deletes source or accepts a boolean cleanup claim. / 确认程序实际不存在；不删除源码，不接收布尔清理声明。
     * @param operation original accepted operation / 原接纳操作
     * @throws SQLException if the original program still exists or ownership changed / 原程序仍存在或归属变化时 */
    public void programRemoved(UUID operation) throws SQLException {
        transition(operation, "QUIESCENT", "PROGRAM_REMOVED", (c, row) -> absentProgram(row.request()));
    }

    /** Removes the reviewed unreferenced or whole-owner data using independent DDL connections. / 用独立 DDL 连接移除已审阅无引用或整所有者数据。
     * @param operation original accepted operation / 原接纳操作
     * @throws SQLException if physical ownership, activity or the checkpoint is unconfirmed / 物理归属、活动或检查点未确认时 */
    public void removeData(UUID operation) throws SQLException {
        transition(operation, "PROGRAM_REMOVED", "DATA_REMOVED", (c, row) -> {
            absentProgram(row.request());
            for (var data : row.request().removals()) {
                var coordinates = new SchemaCoordinates(identity(row.request(), data));
                coordinates.requireRegistered(c, Set.of("DELETE_PENDING"));
                try (var ddl = database.openConnection()) {
                    physicalSafe(ddl, coordinates);
                    removeTables(ddl, coordinates);
                    try (var statement = ddl.createStatement()) {
                        statement.execute("DROP SCHEMA IF EXISTS " + quote(coordinates.physicalName()) + " CASCADE");
                        statement.execute("DROP USER IF EXISTS " + quote(coordinates.databaseUser()));
                    }
                    physicalAbsent(ddl, coordinates);
                }
            }
        });
    }

    /** Releases exact data references and, for whole-owner deletion, owned diagnostics, pins and unshared secrets. / 释放精确数据引用；整所有者删除还清理自有诊断、固定导航及非共享秘密。
     * @param operation original operation / 原操作
     * @throws SQLException if physical data remains or reference cleanup fails / 物理数据残留或引用清理失败时 */
    public void removeReferences(UUID operation) throws SQLException {
        transition(operation, "DATA_REMOVED", "REFERENCES_REMOVED", (c, row) -> {
            absentProgram(row.request());
            var request = row.request();
            absentOwnedFiles(request);
            for (var data : request.removals()) {
                var schema = identity(request, data);
                physicalAbsent(c, new SchemaCoordinates(schema));
                for (var table : List.of("mod_server_reference", "mod_credential_reference"))
                    update(c, "DELETE FROM " + table + " WHERE mod_id=? AND schema_generation=?", schema.ownerId(),
                            schema.generation());
                update(c, "DELETE FROM log_category WHERE owner_id=? AND schema_generation=?", schema.ownerId(),
                        schema.generation());
            }
            if (request.version() == null) {
                update(c, "DELETE FROM runtime_log WHERE owner_id=?", request.owner());
                update(c, "DELETE FROM log_category WHERE owner_id=?", request.owner());
                ModNavigationPreferences.removeOwner(c, request.owner());
                for (var secret : request.secrets())
                    if (!secret.shared()) {
                        require(!sharedSecret(c, request.owner(), secret.id()), "Credential gained a shared reference");
                        update(c, "DELETE FROM credential WHERE credential_id=?", secret.id());
                    }
            }
        });
    }

    /** Unregisters verified cleanup; version-only deletion preserves the owner and first-install history. / 注销已核验清理；仅删版本时保留所有者及首次安装历史。
     * @param operation original operation / 原操作
     * @throws SQLException if data, references, program or registry cleanup is unconfirmed / 数据、引用、程序或登记清理未确认时 */
    public void removeRegistry(UUID operation) throws SQLException {
        transition(operation, "REFERENCES_REMOVED", "REGISTRY_REMOVED", (c, row) -> {
            var request = row.request();
            absentProgram(request);
            absentOwnedFiles(request);
            for (var data : request.removals()) {
                var schema = identity(request, data);
                physicalAbsent(c, new SchemaCoordinates(schema));
                require(noReferences(c, schema), "Removed data still has registered references");
                update(c, "DELETE FROM mod_schema WHERE mod_id=? AND generation=?", request.owner(),
                        schema.generation());
            }
            if (request.version() == null) {
                require(noOwnerReferences(c, request.owner()), "Owned references remain");
                update(c, "UPDATE mod_registry SET selected_version=NULL WHERE mod_id=?", request.owner());
                update(c, "DELETE FROM mod_version WHERE mod_id=?", request.owner());
                update(c, "DELETE FROM mod_registry WHERE mod_id=?", request.owner());
            } else {
                update(c,
                        "UPDATE mod_registry SET selected_version=NULL,updated_at=? WHERE mod_id=? AND selected_version=?",
                        System.currentTimeMillis(), request.owner(), request.version());
                update(c, "DELETE FROM mod_version WHERE mod_id=? AND version=?", request.owner(), request.version());
            }
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
                                "mod_operation", LogLevel.INFO,
                                row.request().version() == null ? "MOD_DELETED" : "MOD_VERSION_DELETED",
                                "Installed Mod deletion was verified", null, row.request().owner(),
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
                        "UPDATE mod_operation SET state='UNKNOWN',failure_code='MOD_DELETE_UNCONFIRMED',failure_detail=NULL,updated_at=? WHERE operation_id=? AND operation_type IN ('DELETE_VERSION','DELETE_MOD') AND state='RUNNING' AND outcome IS NULL",
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
     * @param version requested release; null for the complete owner / 请求版本，空值表示完整所有者
     * @return immutable bounded request / 不可变有界请求
     * @throws SQLException if release or restart acknowledgement is unavailable / 版本或重启确认不可用时 */
    private Request snapshot(Connection c, String owner, String version) throws SQLException {
        var state = owner(c, owner);
        var versions = releases(c, owner);
        require(versions.stream().allMatch(v -> "READY".equals(v.state())), "Installed owner has pending maintenance");
        var data = new ArrayList<Schema>();
        if (version == null) {
            data.addAll(schemas(c, owner));
        } else {
            var release = versions.stream().filter(v -> v.version().equals(version)).findFirst()
                    .orElseThrow(() -> new SQLException("Installed release is absent"));
            var target = schema(c, owner, release.schema());
            if (target != null)
                data.add(target);
        }
        return new Request(2, home.toString(), owner, version, state, versions, List.copyOf(data),
                version == null ? targets(c, owner) : List.of(), version == null ? ownerPins(c, owner) : List.of(),
                version == null ? secrets(c, owner) : List.of(), "{}");
    }

    /** Rechecks the reviewed catalog with only this operation's confirmed changes allowed. / 复核已审阅目录，仅允许此操作已确认变更。
     * @param c serialized transaction / 串行事务
     * @param row original operation / 原操作
     * @throws SQLException if catalog, generation or intent changed / 目录、代次或意图变化时 */
    private void unchanged(Connection c, Row row) throws SQLException {
        var request = row.request();
        boolean removed = PHASES.indexOf(row.phase()) >= PHASES.indexOf("REGISTRY_REMOVED");
        var before = request.ownerState();
        boolean whole = request.version() == null;
        if (whole && removed) {
            require(count(c, "SELECT COUNT(*) FROM mod_registry WHERE mod_id=?", request.owner()) == 0,
                    "Removed owner reappeared");
        } else {
            var expectedOwner = removed && Objects.equals(request.version(), before.selected())
                    ? new Owner(before.revision(), null, before.initialized(), before.ackBoot())
                    : before;
            require(expectedOwner.equals(owner(c, request.owner())), "Accepted disabled intent changed");
        }
        var expected = request.versions().stream().filter(v -> !removed || !request.selects(v))
                .map(v -> !removed && request.selects(v) ? v.pending() : v).toList();
        require(expected.equals(releases(c, request.owner())),
                "Installed versions or retained Schema references changed");
        var expectedData = request.schemas().stream().filter(v -> !removed || !request.removals().contains(v))
                .map(v -> !removed && request.removals().contains(v) ? v.pending() : v).toList();
        if (whole) {
            require(expectedData.equals(schemas(c, request.owner())), "Owned Schema catalog changed");
            boolean referencesGone = PHASES.indexOf(row.phase()) >= PHASES.indexOf("REFERENCES_REMOVED");
            require((referencesGone ? List.of() : request.targets()).equals(targets(c, request.owner())),
                    "Owned log targets changed");
            require((referencesGone ? List.of() : request.pins()).equals(ownerPins(c, request.owner())),
                    "Owned navigation preferences changed");
            if (referencesGone) {
                require(noOwnerReferences(c, request.owner()), "Owned references reappeared");
                require(request.secrets().stream().filter(Secret::shared).map(Secret::id).toList()
                        .equals(CredentialReferenceRepository.ownedIdentities(c, request.owner())),
                        "Owned credential cleanup changed");
            } else
                require(request.secrets().equals(secrets(c, request.owner())),
                        "Owned credential identities or shared references changed");
        } else {
            var actual = schema(c, request.owner(), request.release().schema());
            require(expectedData.equals(actual == null ? List.of() : List.of(actual)),
                    "Original Schema registration changed");
        }
    }

    /** Reads only a fully stopped and restart-acknowledged owner. / 仅读取完全停止且重启确认的所有者。
     * @param c serialized connection / 串行连接
     * @param owner exact identity / 精确身份
     * @return original disabled choice / 原禁用选择
     * @throws SQLException if shutdown has not been acknowledged / 尚未确认停用时 */
    private static Owner owner(Connection c, String owner) throws SQLException {
        var value = ModProgramMaintenance.stopped(c, owner);
        return new Owner(value.revision(), value.selected(), value.initialized(), value.ackBoot());
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
                        : new Schema(version, r.getObject(1, UUID.class).toString(), r.getString(2), r.getString(3),
                                r.getString(4), r.getBoolean(5), (Long) r.getObject(6), r.getString(7));
            }
        }
    }

    /** Reads every registered generation, including those no release references. / 读取全部登记代次，包含无版本引用者。
     * @param c original transaction / 原事务
     * @param owner exact owner / 精确所有者
     * @return ordered complete data catalog / 有序完整数据目录
     * @throws SQLException if catalog reading fails or exceeds bounds / 目录读取失败或超限时 */
    private static List<Schema> schemas(Connection c, String owner) throws SQLException {
        var values = new ArrayList<Schema>();
        try (var p = c
                .prepareStatement("SELECT schema_version FROM mod_schema WHERE mod_id=? ORDER BY schema_version")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    require(values.size() < 1024, "Owned schema catalog exceeds bounds");
                    values.add(schema(c, owner, r.getString(1)));
                }
            }
        }
        return List.copyOf(values);
    }

    /** Captures immutable owned category rows without requiring their tables to remain usable. / 捕获不可变自有类别行，不要求其表仍可用。
     * @param c original transaction / 原事务
     * @param owner exact owner / 精确所有者
     * @return ordered registered targets / 有序登记目标
     * @throws SQLException if identities are corrupt or exceed bounds / 身份损坏或超限时 */
    private static List<LogRepository.Target> targets(Connection c, String owner) throws SQLException {
        var values = new ArrayList<LogRepository.Target>();
        try (var p = c
                .prepareStatement("SELECT * FROM log_category WHERE owner_id=? ORDER BY logical_schema,category")) {
            p.setString(1, owner);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    require(values.size() < 4096 && r.getInt("format_version") == 1, "Owned log catalog is invalid");
                    values.add(new LogRepository.Target(r.getObject("target_key", UUID.class), owner,
                            r.getString("logical_schema"), r.getObject("schema_generation", UUID.class),
                            r.getString("physical_schema"), r.getString("category"), r.getString("target_table"),
                            r.getString("display_name")));
                }
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid log catalog", invalid);
        }
        return List.copyOf(values);
    }

    /** Reads only this owner's navigation identities while validating the shared preference. / 校验共用偏好，仅返回此所有者导航身份。
     * @param c original transaction / 原事务
     * @param owner exact owner / 精确所有者
     * @return original owned navigation order / 原自有导航顺序
     * @throws SQLException if stored preferences cannot be verified / 无法核验已存偏好时 */
    private static List<ModNavigationPreferences.PagePin> ownerPins(Connection c, String owner) throws SQLException {
        return ModNavigationPreferences.read(c).stream().filter(pin -> pin.ownerId().equals(owner)).toList();
    }

    /** Captures owned protected values as identities only, preserving pre-existing shared references. / 仅以身份捕获自有受保护值，保留既有共享引用。
     * @param c serialized connection / 串行连接
     * @param owner exact owner / 精确所有者
     * @return original credential cleanup scope / 原凭据清理范围
     * @throws SQLException if ownership cannot be verified / 无法核验归属时 */
    private static List<Secret> secrets(Connection c, String owner) throws SQLException {
        var result = new ArrayList<Secret>();
        for (String id : CredentialReferenceRepository.ownedIdentities(c, owner))
            result.add(new Secret(id, sharedSecret(c, owner, id)));
        return List.copyOf(result);
    }

    /** Checks all platform and other-Mod consumers before removing a protected value. / 移除受保护值前检查全部平台及其他 Mod 使用方。
     * @param c serialized connection / 串行连接
     * @param owner deleted owner / 被删所有者
     * @param id exact credential identity / 精确凭据身份
     * @return whether a shared consumer protects the value / 是否存在保护该值的共享使用方
     * @throws SQLException if reference reading fails / 引用读取失败时 */
    private static boolean sharedSecret(Connection c, String owner, String id) throws SQLException {
        return count(c,
                "SELECT (SELECT COUNT(*) FROM server_profile WHERE credential_id=?)+(SELECT COUNT(*) FROM ai_model WHERE credential_id=?)+(SELECT COUNT(*) FROM mod_credential_reference WHERE credential_id=? AND mod_id<>?)",
                id, id, id, owner) != 0;
    }

    /** Owned credential identity without any protected payload. / 不含受保护载荷的自有凭据身份。
     * @param id exact platform-issued identity / 精确平台签发身份
     * @param shared whether an existing outside consumer requires preservation / 既有外部使用方是否要求保留 */
    private record Secret(String id, boolean shared) {
    }

    /** Rejects active own or cross-Mod work and all other retained maintenance. / 拒绝活动自有或跨 Mod 工作及其他全部保留维护。
     * @param c serialized transaction / 串行事务
     * @param owner exact identity / 精确身份
     * @param operation current deletion, null before admission / 当前删除，准入前为空
     * @throws SQLException if unresolved occupancy exists / 存在未解决占用时 */
    private static void drained(Connection c, String owner, UUID operation) throws SQLException {
        ModProgramMaintenance.drained(c, owner, operation);
    }

    /** Requires the existing explicit cascade-disable flow to have handled enabled consumers. / 要求既有明确连带禁用流程已处理开启的使用方。
     * @param c serialized connection / 串行连接
     * @param owner removed provider / 被删除提供方
     * @throws SQLException if enabled declared consumers remain / 仍有开启的声明使用方时 */
    private static void dependents(Connection c, String owner) throws SQLException {
        ModProgramMaintenance.dependents(c, owner);
    }

    /** Validates persisted request shape and trusted program/data coordinates. / 校验持久请求形状及可信程序与数据坐标。
     * @param request retained request / 保留请求
     * @throws SQLException if any identity or derived range is invalid / 任一身份或派生范围无效时 */
    private void validate(Request request) throws SQLException {
        try {
            boolean whole = request.version() == null;
            require(request.format() == 2 && home.toString().equals(request.home()) && request.ownerState() != null
                    && request.ownerState().revision() >= 0 && request.versions() != null
                    && (whole || !request.versions().isEmpty()) && request.versions().size() <= 1024
                    && request.files() != null && request.files().length() <= 4 * 1024 * 1024
                    && request.schemas() != null && request.schemas().size() <= 1024 && request.targets() != null
                    && request.targets().size() <= 4096 && request.pins() != null && request.pins().size() <= 1024
                    && request.secrets() != null && request.secrets().size() <= 10000, "Invalid deletion request");
            UUID.fromString(request.ownerState().ackBoot());
            require(segment(request.owner()) && !Set.of("app", "@platform").contains(request.owner())
                    && (whole || segment(request.version())), "Invalid installed path identity");
            require(request.versions().stream().map(Release::version).distinct().count() == request.versions().size()
                    && request.versions().stream().allMatch(v -> "READY".equals(v.state()))
                    && (whole || request.release() != null), "Invalid original releases");
            for (var release : request.selected())
                require(segment(release.version()) && Set.of("LOCAL", "BUNDLED", "STORE").contains(release.origin())
                        && home.resolve("mod").resolve(request.owner()).resolve(release.version()).toString()
                                .equals(release.path()),
                        "Only registered ordinary installed programs can be removed; CLASS sources are not install directories");
            require(request.schemas().stream().map(Schema::version).distinct().count() == request.schemas().size(),
                    "Duplicate owned data");
            for (var data : request.schemas()) {
                var coordinates = new SchemaCoordinates(identity(request, data));
                require(coordinates.physicalName().equals(data.physical())
                        && coordinates.databaseUser().equals(data.user())
                        && Set.of("PREPARING", "READY", "FAILED", "UNKNOWN").contains(data.state()),
                        "Invalid original Schema ownership");
                require(whole || data.version().equals(request.release().schema()), "Unrelated single-version data");
            }
            require(whole || request.schemas().size() <= 1 && request.targets().isEmpty() && request.pins().isEmpty()
                    && request.secrets().isEmpty(), "Invalid version-only range");
            require(request.targets().stream().map(LogRepository.Target::key).distinct().count() == request.targets()
                    .size() && new HashSet<>(request.pins()).size() == request.pins().size(),
                    "Duplicate owned references");
            for (var target : request.targets()) {
                require(target.owner().equals(request.owner()), "Foreign log ownership");
                if (target.logical().equals("app"))
                    require(target.physical().equals("app") && target.table().equals("runtime_log")
                            && target.category().equals("runtime") && target.generation().equals(database.generation()),
                            "Invalid runtime log generation");
                else
                    require(request.schemas().stream()
                            .anyMatch(v -> target.logical().equals("v" + v.version())
                                    && target.generation().toString().equals(v.generation())
                                    && target.physical().equals(v.physical())),
                            "Unowned business log data");
            }
            require(request.pins().stream().allMatch(pin -> pin.ownerId().equals(request.owner())),
                    "Foreign navigation preference");
            require(request.secrets().stream().map(Secret::id).distinct().count() == request.secrets().size()
                    && request.secrets().stream()
                            .allMatch(v -> CredentialReferenceRepository.ownedIdentity(request.owner(), v.id())),
                    "Invalid owned credential identities");
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
        Path path = request.program();
        absent(path);
    }

    /** Proves a bounded original path is absent. / 证明有界原路径不存在。
     * @param path trusted exact path / 可信精确路径
     * @throws SQLException if present or inaccessible / 存在或不可访问时 */
    private static void absent(Path path) throws SQLException {
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
        absentOwnedFiles(request);
        for (var release : request.selected())
            require(count(c, "SELECT COUNT(*) FROM mod_version WHERE mod_id=? AND version=?", request.owner(),
                    release.version()) == 0, "Original release remains registered");
        for (var data : request.removals()) {
            var schema = identity(request, data);
            physicalAbsent(c, new SchemaCoordinates(schema));
            require(noReferences(c, schema)
                    && count(c, "SELECT COUNT(*) FROM mod_schema WHERE generation=?", schema.generation()) == 0,
                    "Original data registration remains");
        }
        if (request.version() == null)
            require(count(c, "SELECT COUNT(*) FROM mod_registry WHERE mod_id=?", request.owner()) == 0
                    && noOwnerReferences(c, request.owner()), "Removed Mod still has registrations");
    }

    /** Proves registered owner files were actually removed before losing their attribution. / 在失去归属登记前证明自有登记文件已实际移除。
     * @param request exact original request / 精确原请求
     * @throws SQLException if an owned auxiliary or emergency file remains / 自有辅助或应急文件残留时 */
    private void absentOwnedFiles(Request request) throws SQLException {
        if (request.version() != null)
            return;
        absent(database.root().resolve("mod").resolve(request.owner()));
        for (var target : request.targets())
            for (String extension : List.of(".log", ".pending"))
                absent(database.root().resolve("log").resolve("emergency").resolve(target.key() + extension));
    }

    /** Proves exact owner references are gone while preserving shared resources and platform history. / 证明精确所有者引用已清除，保留共享资源及平台历史。
     * @param c serialized connection / 串行连接
     * @param owner exact removed owner / 精确被删所有者
     * @return whether owned reference cleanup is complete / 自有引用清理是否完成
     * @throws SQLException if reading cannot be verified / 无法核验读取时 */
    private static boolean noOwnerReferences(Connection c, String owner) throws SQLException {
        return count(c,
                "SELECT (SELECT COUNT(*) FROM mod_server_reference WHERE mod_id=?)+(SELECT COUNT(*) FROM mod_credential_reference WHERE mod_id=?)+(SELECT COUNT(*) FROM log_category WHERE owner_id=?)+(SELECT COUNT(*) FROM runtime_log WHERE owner_id=?)",
                owner, owner, owner, owner) == 0 && ownerPins(c, owner).isEmpty();
    }

    /** Loads bounded retained evidence and cross-checks its relational operation identity. / 加载有界保留证据并交叉检查关系操作身份。
     * @param c serialized connection / 串行连接
     * @param operation exact operation / 精确操作
     * @return validated row / 已校验行
     * @throws SQLException if absent or invalid / 缺失或无效时 */
    private Row load(Connection c, UUID operation) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT * FROM mod_operation WHERE operation_id=? AND operation_type IN ('DELETE_VERSION','DELETE_MOD') AND outcome IS NULL")) {
            p.setObject(1, operation);
            try (var r = p.executeQuery()) {
                require(r.next(), "Original deletion evidence is absent");
                try {
                    String value = r.getString("request_json"), recovery = r.getString("recovery_json");
                    require(value.length() <= 8 * 1024 * 1024 && recovery.length() <= 256,
                            "Deletion evidence exceeds bounds");
                    var request = decode(value);
                    validate(request);
                    var resume = JSON.readTree(recovery);
                    require(resume != null && resume.isObject() && resume.size() == 1 && resume.path("boot").isTextual()
                            && UUID.fromString(resume.path("boot").textValue()).toString()
                                    .equals(resume.path("boot").textValue()),
                            "Invalid deletion recovery identity");
                    require(request.owner().equals(r.getString("mod_id"))
                            && Objects.equals(request.version(), r.getString("source_version"))
                            && request.type().equals(r.getString("operation_type"))
                            && r.getString("target_version") == null
                            && Objects.equals(request.version() == null ? null : request.release().schema(),
                                    r.getString("source_schema_version"))
                            && r.getString("target_schema_version") == null
                            && Objects.equals(request.sourceGeneration(), r.getObject("target_generation", UUID.class))
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

    /** Reads the current scope or translates the already persisted single-release format without rewriting it. / 读取当前范围，或转换既有持久单版本格式，不重写原记录。
     * @param value bounded original JSON / 有界原 JSON
     * @return equivalent exact request / 等价精确请求
     * @throws IOException if strict decoding fails / 严格解码失败时
     * @throws SQLException if older evidence cannot prove its original range / 旧证据无法证明原范围时 */
    private static Request decode(String value) throws IOException, SQLException {
        var raw = JSON.readTree(value);
        if (raw != null && raw.isObject() && raw.path("format").isInt() && raw.path("format").intValue() == 1) {
            var original = JSON.treeToValue(raw, OriginalRequest.class);
            require(original.version() != null && original.versions() != null, "Invalid original version deletion");
            var release = original.versions().stream().filter(v -> original.version().equals(v.version())).findFirst()
                    .orElseThrow(() -> new SQLException("Original release evidence is absent"));
            var data = original.schema();
            require(data != null && (data.isNull() || data.isObject() && data.size() == 7),
                    "Invalid original data evidence");
            var schemas = new ArrayList<Schema>();
            if (!data.isNull()) {
                var translated = ((com.fasterxml.jackson.databind.node.ObjectNode) data).deepCopy();
                translated.put("version", release.schema());
                schemas.add(JSON.treeToValue(translated, Schema.class));
            }
            var request = new Request(2, original.home(), original.owner(), original.version(), original.ownerState(),
                    original.versions(), List.copyOf(schemas), List.of(), List.of(), List.of(), original.files());
            require(original.removeSchema() == !request.removals().isEmpty(),
                    "Original last-reference evidence differs");
            return request;
        }
        return JSON.treeToValue(raw, Request.class);
    }

    /** Original persisted version-only request, retained solely to finish already accepted work. / 原持久单版本请求，仅用于完成已接纳工作。
     * @param format original format / 原格式
     * @param home original program home / 原程序根
     * @param owner original owner / 原所有者
     * @param version original release / 原版本
     * @param ownerState original disabled intention / 原禁用意图
     * @param versions original retained catalog / 原保留目录
     * @param schema original optional data evidence / 原可选数据证据
     * @param removeSchema original last-reference decision / 原最后引用判定
     * @param files original host physical scope / 原宿主物理范围 */
    private record OriginalRequest(int format, String home, String owner, String version, Owner ownerState,
            List<Release> versions, JsonNode schema, boolean removeSchema, String files) {
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
     * @param data original registered data / 原登记数据
     * @return original identity / 原身份 */
    private static SchemaIdentity identity(Request request, Schema data) {
        return new SchemaIdentity(request.owner(), data.version(), UUID.fromString(data.generation()));
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
     * @param version original logical data version / 原逻辑数据版本
     * @param generation original generation / 原代次
     * @param physical original Schema / 原 Schema
     * @param user original owner user / 原所有者用户
     * @param state original state / 原状态
     * @param used actual business-use evidence / 实际业务使用证据
     * @param validated original validation time / 原验证时间
     * @param digest original validation digest / 原验证摘要 */
    private record Schema(String version, String generation, String physical, String user, String state, boolean used,
            Long validated, String digest) {
        /** Applies this operation's sole data-state change. / 应用此操作唯一数据状态变更。
         * @return expected pending data / 预期待清理数据 */
        Schema pending() {
            return new Schema(version, generation, physical, user, "DELETE_PENDING", used, validated, digest);
        }
    }

    /** Durable reviewed deletion range. / 持久已审阅删除范围。
     * @param format request format / 请求格式
     * @param home original application home / 原程序根
     * @param owner exact owner / 精确所有者
     * @param version deleted version; null for a whole-owner request / 被删版本，整所有者请求为空
     * @param ownerState original disabled intent / 原禁用意图
     * @param versions complete retained release evidence / 完整保留版本证据
     * @param schemas reviewed prepared data / 已审阅已准备数据
     * @param targets whole-owner log registrations / 整所有者日志登记
     * @param pins whole-owner navigation identities / 整所有者导航身份
     * @param secrets original owned credentials and shared-resource protection / 原自有凭据及共享资源保护
     * @param files original host physical scope / 原宿主物理范围 */
    private record Request(int format, String home, String owner, String version, Owner ownerState,
            List<Release> versions, List<Schema> schemas, List<LogRepository.Target> targets,
            List<ModNavigationPreferences.PagePin> pins, List<Secret> secrets, String files) {
        /** Determines membership without allowing partial whole-owner cleanup. / 判断成员归属，不允许部分整所有者清理。
         * @param release original retained release / 原保留版本
         * @return whether selected for removal / 是否选择移除 */
        boolean selects(Release release) {
            return version == null || release.version().equals(version);
        }

        /** Lists all selected original releases. / 列出全部选定原版本。
         * @return original selected releases / 原选定版本 */
        List<Release> selected() {
            return versions.stream().filter(this::selects).toList();
        }

        /** Lists data with no remaining release reference, or all data for a whole-owner request. / 列出无剩余版本引用的数据，整所有者请求则包含全部数据。
         * @return exact removable data / 精确可移除数据 */
        List<Schema> removals() {
            return schemas.stream()
                    .filter(data -> version == null || versions.stream()
                            .noneMatch(release -> !selects(release) && data.version().equals(release.schema())))
                    .toList();
        }

        /** Derives the sole relational generation for a single-release request. / 派生单版本请求唯一关系代次。
         * @return generation, absent for whole-owner or retained shared data / 代次，整所有者或保留共享数据为空 */
        UUID sourceGeneration() {
            return version != null && !removals().isEmpty()
                    ? UUID.fromString(removals().getFirst().generation())
                    : null;
        }

        /** Returns the explicit relational operation kind. / 返回明确关系操作种类。
         * @return fixed operation type / 固定操作类型 */
        String type() {
            return version == null ? "DELETE_MOD" : "DELETE_VERSION";
        }

        /** Derives the trusted original program root for this exact scope. / 派生此精确范围的可信原程序根。
         * @return absolute normalized installed scope / 绝对规范安装范围 */
        Path program() {
            return version == null ? Path.of(home).resolve("mod").resolve(owner) : Path.of(release().path());
        }

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
