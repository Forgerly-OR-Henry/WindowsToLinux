package gold.debug.windowstolinux.app.db.mod;

import static gold.debug.windowstolinux.app.db.mod.ModProgramMaintenance.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.appapi.task.*;

/** Owns same-release replacement checkpoints without modifying Mod data or user choices. / 持有同版本替换检查点，不修改 Mod 数据或用户选择。 */
public final class ModReinstallationLedger {
    /** Original database and boot authority. / 原数据库及启动权限。 */
    private final PlatformDatabase database;

    /** Original installed application root. / 原安装应用根。 */
    private final Path home;

    /** Serializes short platform transactions. / 串行化短平台事务。 */
    private final RepositoryConnections connections;

    /** Retains fixed terminal receipts in the platform log. / 在平台日志保留固定终态凭据。 */
    private final LogRepository logs;

    /** Original archive category. / 原归档类别。 */
    private final LogRepository.Target archive;

    /** Strict original request and recovery decoder. / 严格原请求及恢复解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Valid durable phases, including explicitly selected restoration. / 有效持久阶段，包含明确选择的恢复原程序。 */
    private static final Set<String> PHASES = Set.of("VALIDATED", "STAGED", "OLD_SAVED", "FILES_PUBLISHED",
            "REGISTERED", "CLEANUP", "RESTORING", "RESTORED");

    /** Binds the original engine and installed home; CLASS output is never a repair target. / 绑定原引擎及安装程序根，CLASS 输出绝非修复目标。
     * @param database original engine / 原引擎
     * @param home trusted installed home / 可信安装根
     * @throws SQLException if the root or archive registration is unavailable / 根或归档登记不可用时 */
    public ModReinstallationLedger(PlatformDatabase database, Path home) throws SQLException {
        this.database = Objects.requireNonNull(database);
        this.home = Objects.requireNonNull(home);
        require(home.isAbsolute() && home.normalize().equals(home), "Explicit installed home required");
        ordinary(home);
        connections = new RepositoryConnections(database::openConnection);
        logs = new LogRepository(database);
        archive = logs.register("app", Optional.empty(),
                new LogCategory("mod_operation", "mod_operation", Optional.empty()));
    }

    /** Exact immutable release metadata preserved throughout replacement. / 替换全程保留的精确不可变发布元数据。
     * @param version exact release / 精确发布
     * @param path registered installed directory / 登记安装目录
     * @param content original immutable content digest / 原不可变内容摘要
     * @param archive original package byte digest, possibly absent / 原包字节摘要，可缺省
     * @param schema original data version, possibly absent / 原数据版本，可缺省
     * @param manifest original manifest JSON / 原清单 JSON
     * @param origin original acquisition kind / 原获取种类
     * @param installed original installation time / 原安装时间 */
    private record Release(String version, String path, String content, String archive, String schema, String manifest,
            String origin, long installed) {
    }

    /** Single-use review of stopped intention and the immutable selected release. / 已停止意图及不可变所选发布的一次性审阅。 */
    public final class Review {
        /** Issuing ledger instance. / 签发账本实例。 */
        private final ModReinstallationLedger issuer = ModReinstallationLedger.this;

        /** Original catalog evidence. / 原目录依据。 */
        private final Request request;

        /** Stable original operation identity. / 稳定原操作身份。 */
        private final UUID id = UUID.randomUUID();

        /** Prevents replay after uncertain admission. / 防止未知准入后的重放。 */
        private boolean used;
        /** Captures the inspected catalog. / 捕获已检查目录。
         * @param request original evidence / 原依据 */
        private Review(Request request) {
            this.request = request;
        }

        /** Returns the original operation identity. / 返回原操作身份。
         * @return stable operation / 稳定操作 */
        public UUID id() {
            return id;
        }

        /** Returns the registered program directory. / 返回登记程序目录。
         * @return exact installed path / 精确安装路径 */
        public Path program() {
            return Path.of(request.release().path());
        }

        /** Returns the original registered manifest for host compatibility checks. / 返回原登记清单供宿主兼容检查。
         * @return unchanged manifest JSON / 未变清单 JSON */
        public String manifest() {
            return request.release().manifest();
        }
    }

    /** Reviews the same immutable content after actual whole-owner shutdown. / 整所有者实际停用后审阅相同不可变内容。
     * The host separately verifies package bytes, compatibility and its physical replacement scope. / 宿主另行验证包字节、兼容性及其物理替换范围。
     * @param owner exact installed Mod / 精确安装 Mod
     * @param version exact installed release / 精确安装发布
     * @param contentHash content digest proven by the package reader / 包读取器证明的内容摘要
     * @param archiveHash exact selected package byte digest / 精确选定包字节摘要
     * @return one-use original review / 一次性原审阅
     * @throws SQLException if stopped state, immutable identity or occupancy is unconfirmed / 停止状态、不可变身份或占用未确认时 */
    public Review review(String owner, String version, String contentHash, String archiveHash) throws SQLException {
        return locked(c -> {
            var request = new Request(1, home.toString(), owner, stopped(c, owner), release(c, owner, version, "READY"),
                    archiveHash, null);
            validate(request);
            require(request.release().content().equals(contentHash), "Same release has different content");
            admissible(c, owner, null);
            require(count(c, "SELECT COUNT(*) FROM mod_version WHERE mod_id=? AND state<>'READY'", owner) == 0,
                    "Installed owner has retained program maintenance");
            return new Review(request);
        });
    }

    /** Saves original physical scope before reserving the version for replacement. / 保留版本供替换前保存原物理范围。
     * @param review original one-use review / 原一次性审阅
     * @param files host-validated immutable replacement evidence / 宿主已验证不可变替换依据
     * @param checkpoint initial host physical checkpoint / 初始宿主物理检查点
     * @return accepted original identity / 接纳原身份
     * @throws SQLException if evidence, choice, release or admission changed / 依据、选择、发布或准入变化时 */
    public synchronized UUID begin(Review review, String files, String checkpoint) throws SQLException {
        require(review != null && review.issuer == this && !review.used, "Original unused replacement review required");
        review.used = true;
        var before = review.request;
        var request = new Request(1, home.toString(), before.owner(), before.stopped(), before.release(),
                before.archiveHash(), files);
        validate(request);
        object(files, 4 * 1024 * 1024);
        String encoded = encode(request), recovery = recovery(checkpoint);
        try {
            return locked(c -> {
                require(before.stopped().equals(stopped(c, before.owner()))
                        && before.release().equals(release(c, before.owner(), before.release().version(), "READY")),
                        "Reviewed replacement catalog changed");
                admissible(c, before.owner(), null);
                require(count(c, "SELECT COUNT(*) FROM mod_version WHERE mod_id=? AND state<>'READY'",
                        before.owner()) == 0, "Installed owner has retained maintenance");
                long now = System.currentTimeMillis();
                update(c,
                        "INSERT INTO mod_operation(operation_id,mod_id,operation_type,source_version,target_version,accepted_revision,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'REINSTALL',?,?,?,'RUNNING','VALIDATED',?,?,?,?,?)",
                        review.id(), before.owner(), before.release().version(), before.release().version(),
                        before.stopped().revision(), encoded, recovery, UUID.randomUUID().toString(), now, now);
                update(c, "UPDATE mod_version SET state='REINSTALLING' WHERE mod_id=? AND version=?", before.owner(),
                        before.release().version());
                return review.id();
            });
        } catch (SQLException failure) {
            retain(review.id(), failure);
            throw failure;
        }
    }

    /** Original execution coordinates and physical evidence; no new scope may be inferred from current files. / 原执行坐标及物理依据，不得从当前文件推导新范围。
     * @param id original operation / 原操作
     * @param owner original Mod / 原 Mod
     * @param version original release / 原发布
     * @param program original program directory / 原程序目录
     * @param phase confirmed original stage / 已确认原阶段
     * @param state current execution state / 当前执行状态
     * @param files immutable host scope / 不可变宿主范围
     * @param checkpoint last host physical observations / 最后宿主物理观察 */
    public record Execution(UUID id, String owner, String version, Path program, String phase, String state,
            String files, String checkpoint) {
    }

    /** Reads only original validated evidence without claiming execution ownership. / 仅读取原已验证依据，不取得执行归属。
     * @param operation original identity / 原身份
     * @return authoritative original scope / 权威原范围
     * @throws SQLException if the original catalog or request differs / 原目录或请求不同时 */
    public Execution execution(UUID operation) throws SQLException {
        return locked(c -> {
            var row = load(c, operation);
            unchanged(c, row);
            return new Execution(operation, row.request().owner(), row.request().release().version(),
                    Path.of(row.request().release().path()), row.phase(), row.state(), row.request().files(),
                    row.recovery().files());
        });
    }

    /** Persists a physical observation before another side effect, without advancing the phase. / 在另一副作用前持久物理观察，不推进阶段。
     * @param operation original operation / 原操作
     * @param phase expected original phase / 期望原阶段
     * @param files host-verified physical checkpoint / 宿主已验证物理检查点
     * @throws SQLException if the claim or commit is unconfirmed / 取得记录或提交未确认时 */
    public void checkpoint(UUID operation, String phase, String files) throws SQLException {
        transition(operation, phase, phase, files, false);
    }

    /** Advances only ordered physical stages after host verification of the original file identities. / 宿主验证原文件身份后仅推进有序物理阶段。
     * @param operation original identity / 原身份
     * @param from required prior phase / 所需先前阶段
     * @param to next physically verified phase / 下一物理已验证阶段
     * @param files original host checkpoint / 原宿主检查点
     * @throws SQLException if the transition is out of order or unconfirmed / 转换乱序或未确认时 */
    public void advance(UUID operation, String from, String to, String files) throws SQLException {
        require(Map.of("VALIDATED", "STAGED", "STAGED", "OLD_SAVED", "OLD_SAVED", "FILES_PUBLISHED", "REGISTERED",
                "CLEANUP").getOrDefault(from, "").equals(to), "Replacement physical stages must remain ordered");
        transition(operation, from, to, files, false);
    }

    /** Confirms the verified new program and its exact archive digest without changing content or data. / 确认已验证新程序及其精确原包摘要，不改变内容或数据。
     * @param operation original operation / 原操作
     * @param files host evidence after complete new-directory verification / 新目录完整验证后的宿主依据
     * @throws SQLException if original registration or phase is unconfirmed / 原登记或阶段未确认时 */
    public void registered(UUID operation, String files) throws SQLException {
        transition(operation, "FILES_PUBLISHED", "REGISTERED", files, true);
    }

    /** Commits explicit restoration intent before moving a saved old program back. / 移回已保留原程序前提交明确恢复意图。
     * @param operation original operation / 原操作
     * @param from last original pre-registration phase / 最后原登记前阶段
     * @param files host proof identifying complete saved old files and any new files / 宿主证明，标识完整保留旧文件及所有新文件
     * @throws SQLException if new registration already committed or scope is unavailable / 新登记已提交或范围不可用时 */
    public void beginRestore(UUID operation, String from, String files) throws SQLException {
        require(Set.of("STAGED", "OLD_SAVED", "FILES_PUBLISHED").contains(from),
                "Registered replacement cannot restore old files");
        transition(operation, from, "RESTORING", files, false);
    }

    /** Restores only original catalog readiness after the host proves old files were restored. / 宿主证明旧文件已恢复后仅恢复原目录就绪状态。
     * @param operation original operation / 原操作
     * @param files original host restoration proof / 原宿主恢复证明
     * @throws SQLException if original restoration is unconfirmed / 原恢复未确认时 */
    public void restored(UUID operation, String files) throws SQLException {
        transition(operation, "RESTORING", "RESTORED", files, true);
    }

    /** Claims an original unknown operation for explicit continuation in this boot. / 在本次启动取得原未知操作供明确继续。
     * @param operation original identity / 原身份
     * @return unchanged last confirmed phase / 未变的最后确认阶段
     * @throws SQLException if another invocation owns the work or catalog has drifted / 另一调用持有工作或目录已漂移时 */
    public String resume(UUID operation) throws SQLException {
        return locked(c -> {
            var row = load(c, operation);
            require(row.state().equals("UNKNOWN"), "Replacement is not awaiting explicit reconciliation");
            unchanged(c, row);
            admissible(c, row.request().owner(), operation);
            update(c,
                    "UPDATE mod_operation SET state='RUNNING',recovery_json=?,failure_code=NULL,failure_detail=NULL,updated_at=? WHERE operation_id=?",
                    recovery(row.recovery().files()), System.currentTimeMillis(), operation);
            return row.phase();
        });
    }

    /** Retains uncertainty without selecting rollback or replacing the original request. / 保留不确定性，不选择回退或替换原请求。
     * @param operation original identity / 原身份
     * @throws SQLException if the uncertainty itself cannot be retained / 无法保留不确定性本身时 */
    public void unconfirmed(UUID operation) throws SQLException {
        locked(c -> {
            update(c,
                    "UPDATE mod_operation SET state='UNKNOWN',failure_code='MOD_REINSTALL_UNCONFIRMED',failure_detail=NULL,updated_at=? WHERE operation_id=? AND operation_type='REINSTALL' AND state='RUNNING' AND outcome IS NULL",
                    System.currentTimeMillis(), operation);
            return null;
        });
    }

    /** Archives verified replacement or failed repair with restored old files after physical staging cleanup. / 物理暂存清理后归档已验证替换或已恢复旧文件的修复失败。
     * The host must verify the entire final program and remove only its original temporary scope before this call. / 宿主须在调用前验证完整最终程序，且仅移除其原临时范围。
     * @param operation original operation / 原操作
     * @return true when this call archived it, false when no working row remains / 本次归档为真，无工作行残留为假
     * @throws SQLException if physical presence, final catalog or archive is unconfirmed / 物理存在、最终目录或归档未确认时 */
    public boolean finish(UUID operation) throws SQLException {
        try {
            return locked(c -> {
                if (count(c, "SELECT COUNT(*) FROM mod_operation WHERE operation_id=?", operation) == 0)
                    return false;
                var row = active(c, operation, null);
                require(Set.of("CLEANUP", "RESTORED").contains(row.phase()), "Replacement has not completed cleanup");
                ordinary(Path.of(row.request().release().path()));
                absent(home.resolve(".mod-staging").resolve(operation.toString()));
                boolean restored = row.phase().equals("RESTORED");
                var outcome = restored ? Outcome.FAILED : Outcome.SUCCEEDED;
                logs.store(c, archive, List.of(new LogRecordCodec.Record(row.archive(), row.created(), "app",
                        "mod_operation", restored ? LogLevel.WARN : LogLevel.INFO,
                        restored ? "MOD_REINSTALL_RESTORED" : "MOD_REINSTALLED",
                        restored
                                ? "Repair failed; original program restored and data retained"
                                : "Original release repaired; data and choices retained",
                        null, row.request().owner(), row.request().release().version(), null, operation, outcome,
                        false)), 100);
                update(c, "DELETE FROM mod_operation WHERE operation_id=?", operation);
                return true;
            });
        } catch (SQLException failure) {
            retain(operation, failure);
            throw failure;
        }
    }

    /** Serializes one phase and any matching release-state update in the same transaction. / 在同一事务串行保存一个阶段及相应发布状态更新。
     * @param operation original operation / 原操作
     * @param from original required phase / 原所需阶段
     * @param to confirmed next phase / 已确认下一阶段
     * @param files host physical observations / 宿主物理观察
     * @param ready whether final program identity has been verified / 是否已验证最终程序身份
     * @throws SQLException if admission or commit fails / 准入或提交失败时 */
    private void transition(UUID operation, String from, String to, String files, boolean ready) throws SQLException {
        String recovery = recovery(files);
        require(PHASES.contains(from) && PHASES.contains(to), "Unknown replacement phase");
        try {
            locked(c -> {
                var row = active(c, operation, from);
                if (ready)
                    update(c, "UPDATE mod_version SET state='READY',archive_sha256=? WHERE mod_id=? AND version=?",
                            to.equals("RESTORED") ? row.request().release().archive() : row.request().archiveHash(),
                            row.request().owner(), row.request().release().version());
                update(c, "UPDATE mod_operation SET phase=?,recovery_json=?,updated_at=? WHERE operation_id=?", to,
                        recovery, System.currentTimeMillis(), operation);
                return null;
            });
        } catch (SQLException failure) {
            retain(operation, failure);
            throw failure;
        }
    }

    /** Rechecks the exact original boot claim and continuing admission. / 复核精确原启动取得记录及持续准入。
     * @param c original transaction / 原事务
     * @param operation original operation / 原操作
     * @param phase required phase, null for final selection / 所需阶段，最终选择为空
     * @return validated original row / 已验证原行
     * @throws SQLException if another call owns the phase or catalog changed / 另一调用持有阶段或目录变化时 */
    private Row active(Connection c, UUID operation, String phase) throws SQLException {
        var row = load(c, operation);
        require(row.state().equals("RUNNING") && row.recovery().boot().equals(database.bootId().toString())
                && (phase == null || phase.equals(row.phase())), "Replacement does not own the original phase");
        unchanged(c, row);
        admissible(c, row.request().owner(), operation);
        return row;
    }

    /** Preserves original intentions and metadata except the two explicitly committed repair fields. / 保留原意图及元数据，仅允许两个明确提交的修复字段变化。
     * @param c original transaction / 原事务
     * @param row original row / 原行
     * @throws SQLException if anything outside the repair commitment changed / 修复承诺外任意内容变化时 */
    private static void unchanged(Connection c, Row row) throws SQLException {
        var request = row.request();
        var original = request.release();
        require(request.stopped().equals(stopped(c, request.owner())), "Original stopped intention changed");
        boolean registered = Set.of("REGISTERED", "CLEANUP").contains(row.phase());
        boolean ready = registered || row.phase().equals("RESTORED");
        var expected = new Release(original.version(), original.path(), original.content(),
                registered ? request.archiveHash() : original.archive(), original.schema(), original.manifest(),
                original.origin(), original.installed());
        require(expected.equals(release(c, request.owner(), original.version(), ready ? "READY" : "REINSTALLING")),
                "Original replacement registration changed");
    }

    /** Reuses owner/task admission and checks actual H2 owner or reader sessions. / 复用所有者及任务准入，并检查实际 H2 所有者或读取者会话。
     * @param c original transaction / 原事务
     * @param owner exact owner / 精确所有者
     * @param operation current repair or null / 当前修复或空
     * @throws SQLException if a retained task, consumer, maintenance or actual data session remains / 保留任务、使用方、维护或实际数据会话仍存在时 */
    private static void admissible(Connection c, String owner, UUID operation) throws SQLException {
        drained(c, owner, operation);
        dependents(c, owner);
        require(count(c,
                "SELECT COUNT(*) FROM information_schema.sessions WHERE LOWER(user_name) IN (SELECT database_user FROM mod_schema WHERE mod_id=? UNION SELECT LOWER(grantee) FROM information_schema.rights WHERE table_schema IN (SELECT physical_name FROM mod_schema WHERE mod_id=?) AND LOWER(grantee)<>'app_internal')",
                owner, owner) == 0, "Actual Mod data sessions still require release");
    }

    /** Reads the exact existing release in its expected maintenance state. / 读取处于期望维护状态的精确现有发布。
     * @param c original transaction / 原事务
     * @param owner exact owner / 精确所有者
     * @param version original release / 原发布
     * @param state expected program state / 期望程序状态
     * @return immutable catalog evidence / 不可变目录依据
     * @throws SQLException if absent or mismatched / 缺失或不匹配时 */
    private static Release release(Connection c, String owner, String version, String state) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT install_path,content_sha256,archive_sha256,schema_version,manifest_json,origin_kind,installed_at,state FROM mod_version WHERE mod_id=? AND version=?")) {
            p.setString(1, owner);
            p.setString(2, version);
            try (var r = p.executeQuery()) {
                require(r.next() && state.equals(r.getString(8)), "Installed replacement release is unavailable");
                return new Release(version, r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getLong(7));
            }
        }
    }

    /** Validates retained immutable evidence against trusted program coordinates. / 按可信程序坐标校验保留不可变依据。
     * @param request original request / 原请求
     * @throws SQLException if malformed or outside ordinary installed sources / 畸形或超出普通安装来源时 */
    private void validate(Request request) throws SQLException {
        try {
            require(request.format() == 1 && home.toString().equals(request.home())
                    && request.stopped().revision() >= 0, "Invalid replacement request");
            UUID.fromString(request.stopped().ackBoot());
            var release = request.release();
            require(segment(request.owner()) && !Set.of("app", "@platform").contains(request.owner())
                    && segment(release.version())
                    && home.resolve("mod").resolve(request.owner()).resolve(release.version()).toString()
                            .equals(release.path())
                    && Set.of("LOCAL", "BUNDLED", "STORE").contains(release.origin()),
                    "Only original installed programs can be repaired");
            require(release.content().matches("[0-9a-f]{64}") && request.archiveHash().matches("[0-9a-f]{64}")
                    && (release.archive() == null || release.archive().matches("[0-9a-f]{64}"))
                    && release.installed() >= 0, "Invalid original release digests");
            object(release.manifest(), 1024 * 1024);
            var manifest = JSON.readTree(release.manifest());
            require(request.owner().equals(manifest.path("id").textValue())
                    && release.version().equals(manifest.path("version").textValue()),
                    "Original manifest identity differs");
            var schema = manifest.get("schemaVersion");
            require((schema == null || schema.isNull() || schema.isTextual())
                    && Objects.equals(release.schema(), schema == null ? null : schema.textValue()),
                    "Original manifest data version differs");
        } catch (IOException | RuntimeException invalid) {
            throw new SQLException("Invalid replacement evidence", invalid);
        }
    }

    /** Reads only the original bounded request and exact fixed receipt identity. / 仅读取原有界请求及精确固定凭据身份。
     * @param c original transaction / 原事务
     * @param operation original identity / 原身份
     * @return validated retained row / 已验证保留行
     * @throws SQLException if absent, malformed or internally inconsistent / 缺失、畸形或内部不一致时 */
    private Row load(Connection c, UUID operation) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT * FROM mod_operation WHERE operation_id=? AND operation_type='REINSTALL' AND outcome IS NULL")) {
            p.setObject(1, operation);
            try (var r = p.executeQuery()) {
                require(r.next(), "Original replacement operation is absent");
                try {
                    String json = r.getString("request_json"), raw = r.getString("recovery_json");
                    require(json.length() <= 8 * 1024 * 1024 && raw.length() <= 2 * 1024 * 1024,
                            "Replacement evidence exceeds bounds");
                    var request = JSON.readValue(json, Request.class);
                    validate(request);
                    object(request.files(), 4 * 1024 * 1024);
                    var recovery = JSON.readValue(raw, Recovery.class);
                    require(recovery.format() == 1
                            && UUID.fromString(recovery.boot()).toString().equals(recovery.boot()),
                            "Invalid recovery boot");
                    object(recovery.files(), 1024 * 1024);
                    require(request.owner().equals(r.getString("mod_id"))
                            && request.release().version().equals(r.getString("source_version"))
                            && request.release().version().equals(r.getString("target_version"))
                            && r.getObject("accepted_revision") != null
                            && request.stopped().revision() == r.getLong("accepted_revision")
                            && PHASES.contains(r.getString("phase"))
                            && Set.of("RUNNING", "UNKNOWN").contains(r.getString("state")),
                            "Replacement row identity differs");
                    return new Row(request, r.getString("phase"), r.getString("state"), recovery,
                            UUID.fromString(r.getString("archive_log_id")), r.getLong("created_at"));
                } catch (IOException | RuntimeException invalid) {
                    throw new SQLException("Invalid retained replacement", invalid);
                }
            }
        }
    }

    /** Saves uncertainty without hiding the original exception. / 保存不确定性，不隐藏原异常。
     * @param operation original identity / 原身份
     * @param failure original failure / 原失败 */
    private void retain(UUID operation, SQLException failure) {
        try {
            unconfirmed(operation);
        } catch (SQLException unavailable) {
            failure.addSuppressed(unavailable);
        }
    }

    /** Encodes a bounded current-boot physical checkpoint. / 编码有界本次启动物理检查点。
     * @param files original host proof / 原宿主证明
     * @return strict recovery JSON / 严格恢复 JSON
     * @throws SQLException if malformed or too large / 畸形或过大时 */
    private String recovery(String files) throws SQLException {
        object(files, 1024 * 1024);
        return encode(new Recovery(1, database.bootId().toString(), files));
    }

    /** Requires nonempty bounded host evidence without interpreting its private fields. / 要求非空有界宿主依据，不解释其私有字段。
     * @param value original JSON / 原 JSON
     * @param limit character bound / 字符界限
     * @throws SQLException if not a bounded nonempty object / 不是有界非空对象时 */
    private static void object(String value, int limit) throws SQLException {
        try {
            require(value != null && value.length() <= limit, "Host replacement evidence exceeds bounds");
            var node = JSON.readTree(value);
            require(node != null && node.isObject() && !node.isEmpty(), "Host replacement evidence is missing");
        } catch (IOException invalid) {
            throw new SQLException("Host replacement evidence is malformed", invalid);
        }
    }

    /** Encodes original immutable records without uncontrolled payload size. / 编码原不可变记录，限制载荷大小。
     * @param value original record / 原记录
     * @return exact retained JSON / 精确保留 JSON
     * @throws SQLException if unencodable or oversized / 无法编码或超限时 */
    private static String encode(Object value) throws SQLException {
        try {
            String json = JSON.writeValueAsString(value);
            require(json.length() <= 8 * 1024 * 1024, "Encoded replacement evidence exceeds bounds");
            return json;
        } catch (IOException invalid) {
            throw new SQLException("Cannot encode replacement", invalid);
        }
    }

    /** Checks exact safe program path components. / 检查精确安全程序路径分量。
     * @param value original spelling / 原拼写
     * @return whether a bounded ordinary component / 是否有界普通分量 */
    private static boolean segment(String value) {
        return value != null && value.matches("[A-Za-z0-9_][A-Za-z0-9_.+\\-]{0,511}") && !value.endsWith(".");
    }

    /** Requires ordinary directories and their exact canonical ancestor spelling. / 要求普通目录及其精确规范祖先拼写。
     * @param path original directory / 原目录
     * @throws SQLException if absent, redirected or irregular / 缺失、重定向或非普通目录时 */
    private static void ordinary(Path path) throws SQLException {
        try {
            for (Path part = path; part != null; part = part.getParent()) {
                var attrs = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(attrs.isDirectory() && !attrs.isOther() && !attrs.isSymbolicLink()
                        && part.toRealPath().toString().equals(part.toString()),
                        "Replacement path is redirected or irregular");
            }
        } catch (IOException failure) {
            throw new SQLException("Replacement directory is unconfirmed", failure);
        }
    }

    /** Proves staging absence without accepting unreadable or redirected ancestors. / 证明暂存不存在，不接受不可读取或重定向祖先。
     * @param path exact original staging directory / 精确原暂存目录
     * @throws SQLException if staging remains or its absence cannot be proved / 暂存仍在或无法证明不存在时 */
    private static void absent(Path path) throws SQLException {
        try {
            Path parent = path.getParent();
            while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS))
                parent = parent.getParent();
            require(parent != null, "Replacement parent is unavailable");
            ordinary(parent);
            try {
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                throw new SQLException("Replacement staging still requires cleanup");
            } catch (NoSuchFileException missing) {
                /* Exact original scope is gone. / 精确原范围已不存在。 */ }
        } catch (IOException failure) {
            throw new SQLException("Replacement staging absence is unconfirmed", failure);
        }
    }

    /** Executes bound catalog mutations. / 执行绑定目录变更。
     * @param c original transaction / 原事务
     * @param sql fixed platform statement / 固定平台语句
     * @param values bound values / 绑定值
     * @throws SQLException if execution fails / 执行失败时 */
    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (var p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++)
                p.setObject(i + 1, values[i]);
            p.executeUpdate();
        }
    }

    /** Holds the platform decision lock only during bounded SQL work. / 仅在有界 SQL 工作期间持有平台决策锁。
     * @param <T> result / 结果
     * @param action host-owned transaction / 宿主所属事务
     * @return committed result / 已提交结果
     * @throws SQLException if boot, action or commit is unavailable / 启动、动作或提交不可用时 */
    private <T> T locked(Action<T> action) throws SQLException {
        var result = new AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
                require(count(c,
                        "SELECT COUNT(*) FROM app_state WHERE singleton_id=1 AND boot_id=? AND shutdown_state IN ('STARTING','RUNNING')",
                        database.bootId()) == 1, "Original application boot is unavailable");
                result.set(action.run(c));
            }
        });
        return result.get();
    }

    /** Original immutable replacement identity and host scope. / 原不可变替换身份及宿主范围。
     * @param format request format / 请求格式
     * @param home installed home / 安装根
     * @param owner exact Mod / 精确 Mod
     * @param stopped acknowledged choice / 已确认选择
     * @param release immutable original release / 不可变原发布
     * @param archiveHash replacement archive bytes / 替换原包字节摘要
     * @param files original host scope / 原宿主范围 */
    private record Request(int format, String home, String owner, ModProgramMaintenance.Stopped stopped,
            Release release, String archiveHash, String files) {
    }

    /** Original checkpoint and current exclusive boot. / 原检查点及当前独占启动。
     * @param format recovery format / 恢复格式
     * @param boot current claiming boot / 当前取得启动
     * @param files host physical checkpoint / 宿主物理检查点 */
    private record Recovery(int format, String boot, String files) {
    }

    /** Retained authoritative operation row. / 保留权威操作行。
     * @param request original request / 原请求
     * @param phase original phase / 原阶段
     * @param state current claim state / 当前取得状态
     * @param recovery original observations / 原观察
     * @param archive fixed receipt ID / 固定凭据 ID
     * @param created original acceptance time / 原接纳时间 */
    private record Row(Request request, String phase, String state, Recovery recovery, UUID archive, long created) {
    }

    /** Host-only bounded platform transaction. / 仅宿主有界平台事务。
     * @param <T> result type / 结果类型 */
    @FunctionalInterface
    private interface Action<T> {
        /** Runs original SQL work. / 运行原 SQL 工作。
         * @param c original transaction / 原事务
         * @return original result / 原结果
         * @throws SQLException if work fails / 工作失败时 */
        T run(Connection c) throws SQLException;
    }
}
