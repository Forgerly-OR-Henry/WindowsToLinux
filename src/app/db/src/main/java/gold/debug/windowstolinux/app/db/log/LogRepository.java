package gold.debug.windowstolinux.app.db.log;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Owns registered standard log tables and atomic insertion/retention. / 持有登记标准日志表及原子写入和保留。 */
public final class LogRepository {
    /** Protected platform database. / 受保护平台数据库。 */
    private final PlatformDatabase database;

    /** Exact SQL record column order. / 精确 SQL 记录列顺序。 */
    private static final String COLUMNS = "log_id,recorded_at,owner_id,category,level,event_code,message,details,subject_mod_id,mod_version,task_id,operation_id,outcome,truncated";

    /** Required version-one SQL types, bounded identity widths and nullability. / 第一版必需 SQL 类型、有界身份宽度及可空性。 */
    private static final Map<String, String> STANDARD_COLUMNS = Map.ofEntries(Map.entry("log_id", "CHARACTER:36:NO"),
            Map.entry("recorded_at", "BIGINT:null:NO"), Map.entry("owner_id", "CHARACTER VARYING:512:NO"),
            Map.entry("category", "CHARACTER VARYING:64:NO"), Map.entry("level", "CHARACTER VARYING:8:NO"),
            Map.entry("event_code", "CHARACTER VARYING:128:NO"), Map.entry("message", "CHARACTER VARYING:*:NO"),
            Map.entry("details", "CHARACTER LARGE OBJECT:*:YES"),
            Map.entry("subject_mod_id", "CHARACTER VARYING:512:YES"),
            Map.entry("mod_version", "CHARACTER VARYING:512:YES"), Map.entry("task_id", "UUID:null:YES"),
            Map.entry("operation_id", "UUID:null:YES"), Map.entry("outcome", "CHARACTER VARYING:16:YES"),
            Map.entry("truncated", "BOOLEAN:null:NO"));

    /** Platform categories with deterministic early-startup identity. / 具有确定性早期启动身份的平台类别。 */
    public static final Set<String> PLATFORM_CATEGORIES = Set.of("runtime", "mod_operation", "platform_task",
            "browser_recovery");

    /** Binds the actual database without creating business tables. / 绑定实际数据库，不创建业务表。
     * @param database owned engine / 所属引擎 */
    public LogRepository(PlatformDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Registers or verifies a fixed caller-owned category and its standard table. / 登记或核对固定调用方类别及标准表。
     * @param owner trusted producer identity / 可信生产方身份
     * @param schema optional selected business generation / 可选选定业务代次
     * @param category immutable registration / 不可变登记
     * @return authoritative persisted target / 权威持久目标
     * @throws SQLException if attribution, table shape or registration conflicts / 归属、表形状或登记冲突时 */
    public Target register(String owner, Optional<SchemaIdentity> schema, LogCategory category) throws SQLException {
        if (owner == null || owner.isBlank() || owner.length() > 512 || category.displayName().isBlank()
                || category.displayName().length() > 256)
            throw new SQLException("Invalid diagnostic registration identity");
        boolean business = category.businessTable().isPresent();
        if (business && (schema.isEmpty() || !owner.equals(schema.get().ownerId()) || owner.equals("app")))
            throw new SQLException("Business log target requires its own generation");
        if (!business && !owner.equals("app") && !category.category().equals("runtime"))
            throw new SQLException("Ordinary Mod diagnostics use the runtime category");
        if (owner.equals("app") && !PLATFORM_CATEGORIES.contains(category.category()))
            throw new SQLException("Unrecognized platform category");
        String logical = business ? "v" + schema.orElseThrow().schemaVersion() : "app";
        UUID generation = business ? schema.orElseThrow().generation() : database.generation();
        String physical = business ? new SchemaCoordinates(schema.orElseThrow()).physicalName() : "app";
        UUID key = owner.equals("app") ? platformKey(database.generation(), category.category()) : UUID.randomUUID();
        var requested = new Target(key, owner, logical, generation, physical, category.category(),
                category.businessTable().orElse("runtime_log"), category.displayName());
        try (var c = database.openConnection()) {
            c.setAutoCommit(false);
            try {
                RepositoryConnections.lockPlatformState(c);
                requireSchema(c, requested);
                validateTable(c, requested);
                try (var p = c.prepareStatement(
                        "SELECT * FROM log_category WHERE owner_id=? AND logical_schema=? AND category=?")) {
                    p.setString(1, owner);
                    p.setString(2, logical);
                    p.setString(3, category.category());
                    try (var rows = p.executeQuery()) {
                        if (rows.next()) {
                            var existing = target(rows);
                            if (!existing.equals(new Target(existing.key(), owner, logical, generation, physical,
                                    category.category(), requested.table(), requested.displayName()))
                                    || owner.equals("app") && !existing.key().equals(key))
                                throw new SQLException("Log category registration differs from existing ownership");
                            c.commit();
                            return existing;
                        }
                    }
                }
                try (var p = c.prepareStatement(
                        "INSERT INTO log_category(target_key,owner_id,logical_schema,schema_generation,physical_schema,category,target_table,display_name,format_version) VALUES(?,?,?,?,?,?,?,?,1)")) {
                    p.setObject(1, requested.key());
                    p.setString(2, owner);
                    p.setString(3, logical);
                    p.setObject(4, generation);
                    p.setString(5, physical);
                    p.setString(6, requested.category());
                    p.setString(7, requested.table());
                    p.setString(8, requested.displayName());
                    p.executeUpdate();
                }
                c.commit();
                return requested;
            } catch (SQLException | RuntimeException failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    /** Derives the documented stable platform category key. / 派生文档规定的稳定平台类别键。
     * @param generation protected database generation / 受保护数据库代次
     * @param category fixed platform category / 固定平台类别
     * @return original deterministic registration identity / 原确定性登记身份 */
    public static UUID platformKey(UUID generation, String category) {
        if (!PLATFORM_CATEGORIES.contains(category))
            throw new IllegalArgumentException("Unknown platform category");
        return UUID.nameUUIDFromBytes(("WTL-LOG-1\0" + generation + "\0" + category).getBytes(StandardCharsets.UTF_8));
    }

    /** Loads persisted registrations without starting any Mod. / 不启动任何 Mod 地加载持久登记。
     * @return all retained category targets / 全部保留类别目标
     * @throws SQLException if registry data is invalid / 登记数据无效时 */
    public List<Target> targets() throws SQLException {
        try (var c = database.openConnection();
                var p = c.prepareStatement("SELECT * FROM log_category ORDER BY owner_id,logical_schema,category");
                var rows = p.executeQuery()) {
            var targets = new ArrayList<Target>();
            while (rows.next())
                targets.add(target(rows));
            return List.copyOf(targets);
        }
    }

    /** Reads owners whose retained installed deletion must prevent diagnostic replay after restart. / 读取其保留安装删除必须阻止重启后诊断回填的所有者。
     * @return exact retained owner identities / 精确保留所有者身份
     * @throws SQLException if deletion evidence cannot be read / 无法读取删除证据时 */
    public Set<String> deletingOwners() throws SQLException {
        try (var c = database.openConnection();
                var p = c.prepareStatement(
                        "SELECT DISTINCT mod_id FROM mod_operation WHERE operation_type IN ('DELETE_MOD','DELETE_VERSION')");
                var rows = p.executeQuery()) {
            var owners = new HashSet<String>();
            while (rows.next())
                owners.add(rows.getString(1));
            return Set.copyOf(owners);
        }
    }

    /** Commits exact deduplication and retention as one category transaction. / 以同一类别事务提交精确去重及保留。
     * @param target original registration / 原登记
     * @param records original records including emergency replays / 含应急回填的原记录
     * @param keep explicit retention limit / 明确保留上限
     * @return confirmed removed and remaining record counts / 确认删除及保留记录数
     * @throws SQLException if attribution, content, commit or cleanup is unconfirmed / 归属、内容、提交或清理未确认时 */
    public Retention store(Target target, List<LogRecordCodec.Record> records, int keep) throws SQLException {
        try (var c = database.openConnection()) {
            c.setAutoCommit(false);
            try {
                var result = store(c, target, records, keep);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    /** Stores and retains records inside an existing platform state transaction without committing it. / 在既有平台状态事务内写入并保留记录，不提交该事务。
     * @param c original owning platform transaction / 原所属平台事务
     * @param target verified category / 已核验类别
     * @param records exact original archive records / 精确原归档记录
     * @param keep documented retention limit / 文档规定的保留上限
     * @return counts pending the caller's transaction commit / 等待调用方事务提交的计数
     * @throws SQLException if ownership, evidence or writes cannot be verified / 无法核验归属、证据或写入时 */
    public Retention store(Connection c, Target target, List<LogRecordCodec.Record> records, int keep)
            throws SQLException {
        if (c.getAutoCommit())
            throw new SQLException("Log archive requires an owning platform transaction");
        if (keep != 10 && keep != 100 || records.size() > 100)
            throw new IllegalArgumentException("Invalid log retention batch");
        requireTarget(c, target);
        validateTable(c, target);
        for (var record : records) {
            try {
                LogRecordCodec.decode(target, LogRecordCodec.encode(target.key(), record));
            } catch (java.io.IOException | RuntimeException invalid) {
                throw new SQLException("Invalid bounded log record");
            }
            try (var p = c.prepareStatement("SELECT " + COLUMNS + " FROM " + qualified(target) + " WHERE log_id=?")) {
                p.setString(1, record.id().toString());
                try (var rows = p.executeQuery()) {
                    if (rows.next()) {
                        if (!record.equals(record(rows)))
                            throw new SQLException("Existing log identity has different content");
                        continue;
                    }
                }
            }
            insert(c, target, record);
        }
        long removed;
        try (var p = c.prepareStatement("DELETE FROM " + qualified(target)
                + " WHERE owner_id=? AND category=? AND log_id NOT IN (SELECT log_id FROM " + qualified(target)
                + " WHERE owner_id=? AND category=? ORDER BY recorded_at DESC,log_id DESC FETCH FIRST " + keep
                + " ROWS ONLY)")) {
            p.setString(1, target.owner());
            p.setString(2, target.category());
            p.setString(3, target.owner());
            p.setString(4, target.category());
            removed = p.executeUpdate();
        }
        long retained;
        try (var p = c
                .prepareStatement("SELECT COUNT(*) FROM " + qualified(target) + " WHERE owner_id=? AND category=?")) {
            p.setString(1, target.owner());
            p.setString(2, target.category());
            try (var rows = p.executeQuery()) {
                rows.next();
                retained = rows.getLong(1);
            }
        }
        return new Retention(removed, retained);
    }

    /** Reads only one verified category in its canonical newest-first ordering. / 仅按规范新旧顺序读取一个已核验类别。
     * @param target exact registered target / 精确登记目标
     * @return bounded immutable category records / 有界不可变类别记录
     * @throws SQLException if registry or table is unavailable / 登记或表不可用时 */
    public List<LogRecordCodec.Record> read(Target target) throws SQLException {
        try (var c = database.openConnection()) {
            c.setAutoCommit(false);
            try {
                requireTarget(c, target);
                validateTable(c, target);
                try (var p = c.prepareStatement("SELECT " + COLUMNS + " FROM " + qualified(target)
                        + " WHERE owner_id=? AND category=? ORDER BY recorded_at DESC,log_id DESC FETCH FIRST 101 ROWS ONLY")) {
                    p.setString(1, target.owner());
                    p.setString(2, target.category());
                    try (var rows = p.executeQuery()) {
                        var records = new ArrayList<LogRecordCodec.Record>();
                        while (rows.next()) {
                            var record = record(rows);
                            try {
                                LogRecordCodec.decode(target, LogRecordCodec.encode(target.key(), record));
                            } catch (java.io.IOException | RuntimeException invalid) {
                                throw new SQLException(
                                        "Stored diagnostic does not match its bounded attribution contract");
                            }
                            records.add(record);
                        }
                        if (records.size() > 100)
                            throw new SQLException("Log category exceeds its retention contract");
                        c.commit();
                        return List.copyOf(records);
                    }
                }
            } catch (SQLException | RuntimeException failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    /** Inserts the validated standard shape using only bound record values. / 仅使用绑定记录值写入已校验标准形状。
     * @param c category transaction / 类别事务
     * @param target exact registered target / 精确登记目标
     * @param r original immutable record / 原不可变记录
     * @throws SQLException if insertion fails / 写入失败时 */
    private static void insert(Connection c, Target target, LogRecordCodec.Record r) throws SQLException {
        try (var p = c.prepareStatement(
                "INSERT INTO " + qualified(target) + "(" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, r.id().toString());
            p.setLong(2, r.recordedAt());
            p.setString(3, r.owner());
            p.setString(4, r.category());
            p.setString(5, r.level().name());
            p.setString(6, r.eventCode());
            p.setString(7, r.message());
            p.setString(8, r.details());
            p.setString(9, r.subjectMod());
            p.setString(10, r.version());
            p.setObject(11, r.taskId());
            p.setObject(12, r.operationId());
            p.setString(13, r.outcome() == null ? null : r.outcome().name());
            p.setBoolean(14, r.truncated());
            p.executeUpdate();
        }
    }

    /** Verifies the same immutable registry row while holding its category lock. / 持有类别锁期间核对同一不可变登记行。
     * @param c active transaction / 活动事务
     * @param expected original target / 原目标
     * @throws SQLException if ownership changed or vanished / 归属变化或消失时 */
    private void requireTarget(Connection c, Target expected) throws SQLException {
        try (var p = c.prepareStatement("SELECT * FROM log_category WHERE target_key=? FOR UPDATE")) {
            p.setObject(1, expected.key());
            try (var rows = p.executeQuery()) {
                if (!rows.next() || !expected.equals(target(rows)))
                    throw new SQLException("Log registration is stale");
            }
        }
        requireSchema(c, expected);
    }

    /** Rejects old business generations and unrelated physical schemas. / 拒绝旧业务代次及无关物理数据结构。
     * @param c platform connection / 平台连接
     * @param target exact ownership / 精确归属
     * @throws SQLException if current schema identity does not match / 当前数据结构身份不符时 */
    private void requireSchema(Connection c, Target target) throws SQLException {
        if (target.logical().equals("app")) {
            if (!target.physical().equals("app") || !target.table().equals("runtime_log")
                    || !target.generation().equals(database.generation()))
                throw new SQLException("Platform diagnostic generation differs");
        } else {
            var identity = new SchemaIdentity(target.owner(), target.logical().substring(1), target.generation());
            var coordinates = new SchemaCoordinates(identity);
            if (!coordinates.physicalName().equals(target.physical()))
                throw new SQLException("Business log physical schema differs");
            coordinates.requireRegistered(c, Set.of("PREPARING", "READY"));
        }
    }

    /** Compares exact column types/nullability and primary key with the fixed version-one contract. / 与固定第一版契约比较精确列类型、可空性及主键。
     * @param c platform connection / 平台连接
     * @param target registered destination / 登记目的地
     * @throws SQLException if the table is not the standard diagnostic shape / 表不符合标准诊断形状时 */
    private static void validateTable(Connection c, Target target) throws SQLException {
        if (!STANDARD_COLUMNS.equals(columns(c, target.physical(), target.table())))
            throw new SQLException("Diagnostic table does not match the standard record shape");
        var keys = new ArrayList<String>();
        try (var rows = c.getMetaData().getPrimaryKeys(c.getCatalog(), target.physical(), target.table())) {
            while (rows.next())
                keys.add(rows.getString("COLUMN_NAME"));
        }
        if (!keys.equals(List.of("log_id")))
            throw new SQLException("Diagnostic table requires the standard log key");
    }

    /** Reads exact standard metadata, including all extra or missing columns. / 读取精确标准元数据，包含全部额外或缺失列。
     * @param c internal connection / 内部连接
     * @param schema schema identifier / 数据结构标识
     * @param table table identifier / 表标识
     * @return exact metadata map / 精确元数据映射
     * @throws SQLException if metadata is unavailable / 元数据不可用时 */
    private static Map<String, String> columns(Connection c, String schema, String table) throws SQLException {
        var found = new TreeMap<String, String>();
        try (var p = c.prepareStatement(
                "SELECT column_name,data_type,character_maximum_length,is_nullable FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position")) {
            p.setString(1, schema);
            p.setString(2, table);
            try (var rows = p.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1), length = rows.getString(3);
                    if (name.equals("message") || name.equals("details")) {
                        if (length == null || rows.getLong(3) < LogRecordCodec.MAX_BYTES)
                            throw new SQLException("Diagnostic body column is too small");
                        length = "*";
                    }
                    found.put(name,
                            rows.getString(2).toUpperCase(Locale.ROOT) + ":" + length + ":" + rows.getString(4));
                }
            }
        }
        if (found.size() != 14)
            throw new SQLException("Diagnostic table is absent or has different columns");
        return found;
    }

    /** Decodes one registry row with strict identifier validation. / 严格校验标识地解码一条登记行。
     * @param rows positioned result / 已定位结果
     * @return immutable target / 不可变目标
     * @throws SQLException if stored metadata is invalid / 已存元数据无效时 */
    private static Target target(ResultSet rows) throws SQLException {
        if (rows.getInt("format_version") != 1)
            throw new SQLException("Unknown log record format");
        try {
            return new Target(rows.getObject("target_key", UUID.class), rows.getString("owner_id"),
                    rows.getString("logical_schema"), rows.getObject("schema_generation", UUID.class),
                    rows.getString("physical_schema"), rows.getString("category"), rows.getString("target_table"),
                    rows.getString("display_name"));
        } catch (RuntimeException invalid) {
            throw new SQLException("Invalid log registry identifiers");
        }
    }

    /** Reads one original SQL record without generating replacement fields. / 不生成替换字段地读取一条原 SQL 记录。
     * @param rows positioned result / 已定位结果
     * @return original record / 原记录
     * @throws SQLException if stored fields are invalid / 已存字段无效时 */
    private static LogRecordCodec.Record record(ResultSet rows) throws SQLException {
        try {
            return new LogRecordCodec.Record(UUID.fromString(rows.getString("log_id")), rows.getLong("recorded_at"),
                    rows.getString("owner_id"), rows.getString("category"), LogLevel.valueOf(rows.getString("level")),
                    rows.getString("event_code"), rows.getString("message"), rows.getString("details"),
                    rows.getString("subject_mod_id"), rows.getString("mod_version"),
                    rows.getObject("task_id", UUID.class), rows.getObject("operation_id", UUID.class),
                    rows.getString("outcome") == null ? null : Outcome.valueOf(rows.getString("outcome")),
                    rows.getBoolean("truncated"));
        } catch (RuntimeException invalid) {
            throw new SQLException("Invalid stored log record");
        }
    }

    /** Quotes only identifiers already validated by the target constructor. / 仅引用目标构造器已校验的标识。
     * @param target registered table / 登记表
     * @return qualified SQL identifier / 限定 SQL 标识 */
    private static String qualified(Target target) {
        return "\"" + target.physical() + "\".\"" + target.table() + "\"";
    }

    /** Preserves rollback failures alongside the original storage uncertainty. / 随原存储不确定性保留回滚失败。
     * @param c owning transaction / 所属事务
     * @param failure original failure / 原失败 */
    private static void rollback(Connection c, Exception failure) {
        try {
            c.rollback();
        } catch (SQLException rollback) {
            failure.addSuppressed(rollback);
        }
    }

    /** Confirmed committed retention effects, never inferred from a before/after difference. / 已确认提交的保留效果，不由前后差值推测。
     * @param removed rows actually deleted in this committed transaction / 此已提交事务实际删除行数
     * @param retained observed rows remaining at commit / 提交时已观察剩余行数 */
    public record Retention(long removed, long retained) {
    }

    /** Validated category identity including the original generation. / 包含原代次的已校验类别身份。
     * @param key unique registration / 唯一登记
     * @param owner producer owner / 生产方所有者
     * @param logical logical schema / 逻辑数据结构
     * @param generation original schema generation / 原数据结构代次
     * @param physical actual schema / 实际数据结构
     * @param category fixed category / 固定类别
     * @param table registered standard table / 登记标准表
     * @param displayName category label / 类别标签 */
    public record Target(UUID key, String owner, String logical, UUID generation, String physical, String category,
            String table, String displayName) {
        /** Checks identifiers before any dynamic SQL or path construction. / 任一动态 SQL 或路径构造前校验标识。
         * @param key unique registration / 唯一登记
         * @param owner producer owner / 生产方所有者
         * @param logical logical schema / 逻辑数据结构
         * @param generation original generation / 原代次
         * @param physical actual schema / 实际数据结构
         * @param category fixed category / 固定类别
         * @param table standard table / 标准表
         * @param displayName category label / 类别标签 */
        public Target {
            Objects.requireNonNull(key);
            Objects.requireNonNull(generation);
            Objects.requireNonNull(displayName);
            if (owner == null || owner.isBlank() || owner.length() > 512
                    || !logical.matches("app|v(?:0|[1-9][0-9]{0,63})")
                    || !physical.matches("app|m_[0-9a-f]{64}_v(?:0|[1-9][0-9]{0,63})")
                    || !category.matches("[a-z0-9_]{1,64}") || table.length() > 128
                    || !table.matches("[a-z][a-z0-9_]*_log") || displayName.isBlank() || displayName.length() > 256)
                throw new IllegalArgumentException("Invalid diagnostic target");
        }
    }
}
