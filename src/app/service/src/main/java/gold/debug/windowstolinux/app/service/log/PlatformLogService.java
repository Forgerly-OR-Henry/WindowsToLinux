package gold.debug.windowstolinux.app.service.log;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;

/** Coordinates real SQL and emergency diagnostics independently of business outcomes. / 独立于业务结果协调真实 SQL 及应急诊断。 */
public final class PlatformLogService implements AutoCloseable {
    /** Validated category persistence. / 已校验类别持久化。 */
    private final LogRepository repository;

    /** Fixed-root emergency files. / 固定根应急文件。 */
    private final EmergencyLogs emergency;

    /** Current-user boundary for host management controls. / 宿主管理控件的当前用户边界。 */
    private final DesktopSecurityService security;

    /** Latest visible diagnostic issue, retained if the window is not yet mounted. / 最近可见诊断问题，窗口尚未挂载时仍保留。 */
    private volatile Optional<Problem> issue = Optional.empty();

    /** Non-blocking host feedback listeners, never used as another log sink. / 非阻塞宿主反馈监听器，不作为另一日志写入目标。 */
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<Problem>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Per-category serialization shared by every binding and maintenance call. / 所有绑定及维护调用共用的逐类别串行化。 */
    private final Map<UUID, ReentrantLock> categoryLocks = new ConcurrentHashMap<>();

    /** Coordinates complete owner deletion with every category's actual I/O. / 协调完整所有者删除与每个类别的实际 I/O。 */
    private final Map<String, ReentrantReadWriteLock> ownerLocks = new ConcurrentHashMap<>();

    /** Owners blocked by accepted or currently reviewed deletion, guarded by this service monitor. / 被已接纳或当前审阅删除封闭的所有者，由本服务监视器保护。 */
    private final Set<String> blockedOwners = new HashSet<>();

    /** Exact owners whose deletion scope is currently held by a worker. / 当前由工作线程持有删除作用域的精确所有者。 */
    private final Set<String> deleting = new HashSet<>();

    /** Known original category keys retained until deletion absence is confirmed. / 保留到确认删除不存在的已知原类别键。 */
    private final Map<String, Set<UUID>> deletionKeys = new HashMap<>();

    /** Removed keys cannot be revived by a previously captured maintenance snapshot. / 已移除键不能被之前捕获的维护快照复活。 */
    private final Set<UUID> retiredKeys = new HashSet<>();

    /** Actual caller bindings owned by this boot. / 此启动持有的实际调用方绑定。 */
    private final Set<Binding> bindings = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Transient debug preference, reset at every boot. / 每次启动重置的临时调试偏好。 */
    private volatile boolean debug;

    /** Actual shutdown and in-flight storage counts. / 实际关闭状态及在途存储计数。 */
    private boolean closed;

    /** Active calls whose actual storage has not returned. / 实际存储尚未返回的活动调用。 */
    private int active;

    /** Prevents simultaneous whole-registry maintenance operations. / 防止同时执行整体登记维护。 */
    private final java.util.concurrent.atomic.AtomicBoolean maintaining = new java.util.concurrent.atomic.AtomicBoolean();

    /** Latest startup or explicit emergency reconciliation report. / 最近启动或明确应急核对报告。 */
    private volatile List<Maintenance> recovery = List.of();

    /** Initializes fixed platform categories against the actual database. / 针对实际数据库初始化固定平台类别。
     * @param database confirmed engine and data root / 已确认引擎及数据根
     * @param security current-user management boundary / 当前用户管理边界
     * @throws SQLException if category registration is inconsistent / 类别登记不一致时 */
    public PlatformLogService(PlatformDatabase database, DesktopSecurityService security) throws SQLException {
        this.repository = new LogRepository(database);
        this.emergency = new EmergencyLogs(database.root());
        this.security = Objects.requireNonNull(security);
        for (String category : new TreeSet<>(LogRepository.PLATFORM_CATEGORIES))
            repository.register("app", Optional.empty(), new LogCategory(category, category, Optional.empty()));
        blockedOwners.addAll(repository.deletingOwners());
    }

    /** Creates an activation-owned public logger without exposing table or file access. / 创建启用所属公开日志器，不暴露表或文件访问。
     * @param owner actual Mod identity or reserved platform owner / 实际 Mod 身份或保留平台所有者
     * @param version actual loaded release / 实际加载发布版本
     * @param schema optional selected business generation / 可选选定业务代次
     * @param tasks actual task attribution authority / 实际任务归属授权方
     * @return revocable caller logger / 可撤销调用方日志器 */
    public synchronized Binding bind(String owner, String version, Optional<SchemaIdentity> schema,
            PlatformTaskRuntime tasks) {
        if (closed || blockedOwners.contains(owner))
            throw new IllegalStateException("Platform logs are closed");
        var binding = new Binding(owner, version, schema, tasks);
        bindings.add(binding);
        return binding;
    }

    /** Enables DEBUG only for this application boot. / 仅对此应用启动启用 DEBUG。
     * @param enabled temporary diagnostic preference / 临时诊断偏好 */
    public void debug(boolean enabled) {
        try (var operation = security.enterOperation()) {
            debug = enabled;
        }
    }

    /** Reads the transient boot-only DEBUG selection. / 读取仅本次启动的临时 DEBUG 选择。
     * @return current debug setting / 当前调试设置 */
    public boolean debug() {
        return debug;
    }

    /** Returns the latest storage warning without recursive logging. / 返回最近存储警告，不递归记日志。
     * @return latest visible issue / 最近可见问题 */
    public Optional<Problem> issue() {
        return issue;
    }

    /** Attaches a host-owned non-blocking feedback callback. / 附加宿主所属非阻塞反馈回调。
     * @param listener actual host feedback / 实际宿主反馈
     * @return exact listener removal / 精确监听移除 */
    public AutoCloseable onIssue(java.util.function.Consumer<Problem> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    /** Reports a persistence issue while preserving the actual write result. / 报告持久化问题并保留实际写入结果。
     * @param result observed storage result / 已观察存储结果
     * @return the unchanged result / 未改变的结果 */
    private LogWriteResult reported(LogWriteResult result) {
        result.problem().ifPresent(problem -> {
            issue = Optional.of(problem);
            for (var listener : listeners)
                try {
                    listener.accept(problem);
                } catch (RuntimeException feedbackFailure) {
                    System.err.println("LOG_FEEDBACK_FAILED: diagnostic feedback could not be displayed");
                }
        });
        return result;
    }

    /** Returns actual last reconciliation results without retrying any operation. / 返回实际最近核对结果，不重试任何操作。
     * @return last immutable report / 最近不可变报告 */
    public List<Maintenance> recovery() {
        return recovery;
    }

    /** Replays registered emergency records without changing their identities or timestamps. / 回填已登记应急记录，不改变其身份及时间。
     * @return actual per-category results / 实际逐类别结果
     * @throws Exception if registry enumeration or lifecycle admission fails / 枚举登记或生命周期准入失败时 */
    public List<Maintenance> replayAll() throws Exception {
        replayStartup(false);
        return maintain(false);
    }

    /** Imports original host startup records only after confirmed database storage. / 仅在数据库存储确认后导入原宿主启动记录。
     * @param clean explicit ten-record cleanup / 明确保留十条的清理
     * @throws Exception if the service lifecycle cannot admit maintenance / 服务生命周期不能接纳维护时 */
    private void replayStartup(boolean clean) throws Exception {
        begin();
        CategoryAccess access = null;
        try {
            var source = LogRepository.startupTarget();
            synchronized (LogRepository.class) {
                if (emergency.read(source).isEmpty())
                    return;
            }
            var target = repository.targets().stream()
                    .filter(value -> value.owner().equals("app") && value.category().equals("runtime")).findFirst()
                    .orElseThrow();
            access = lock(target);
            synchronized (LogRepository.class) {
                var pending = emergency.read(source);
                if (pending.isEmpty())
                    return;
                try {
                    repository.store(target, pending, clean ? 10 : 100);
                } catch (SQLException unavailable) {
                    if (clean)
                        emergency.replace(source, EmergencyLogs.merge(pending, 10));
                    throw unavailable;
                }
                emergency.replace(source, List.of());
            }
        } catch (Exception failure) {
            reported(new LogWriteResult(UUID.randomUUID(), LogStorage.NOT_SAVED, false,
                    Optional.of(problem(ProblemCode.IO_FAILURE, "LOG_STARTUP_REPLAY_PENDING",
                            "Original startup diagnostics remain pending"))));
        } finally {
            if (access != null)
                access.close();
            end();
        }
    }

    /** Writes one already bounded host failure without changing its original identity. / 写入一条已有界宿主失败，不改变原身份。
     * @param record original startup or uncaught failure / 原启动或未捕获失败
     * @return actual confirmed storage result / 实际确认的存储结果 */
    public LogWriteResult writeStartup(LogRecordCodec.Record record) {
        Objects.requireNonNull(record);
        if (!record.owner().equals("app") || !record.category().equals("runtime"))
            throw new IllegalArgumentException("Startup diagnostics must belong to the host runtime");
        try {
            begin();
            try {
                var target = repository.targets().stream()
                        .filter(value -> value.owner().equals("app") && value.category().equals("runtime")).findFirst()
                        .orElseThrow();
                return reported(write(target, record));
            } finally {
                end();
            }
        } catch (Exception failure) {
            return reported(new LogWriteResult(record.id(), LogStorage.NOT_SAVED, record.truncated(),
                    Optional.of(problem(ProblemCode.IO_FAILURE, "LOG_STARTUP_WRITE_FAILED",
                            "Host diagnostic storage could not be confirmed"))));
        }
    }

    /** Keeps ten records per registered category, including inactive business generations. / 每个登记类别保留十条记录，包含未启用业务代次。
     * @return actual per-storage counts and failures / 实际逐存储计数及失败
     * @throws Exception if registry enumeration or lifecycle admission fails / 枚举登记或生命周期准入失败时 */
    public List<Maintenance> cleanHistory() throws Exception {
        try (var operation = security.enterOperation()) {
            replayStartup(true);
            return maintain(true);
        }
    }

    /** Returns host-visible maintenance and unregistered-file results under current-user authority. / 在当前用户授权下返回宿主可见维护及未登记文件结果。
     * @param clean explicit history cleanup request / 明确历史清理请求
     * @return actual report including files that must remain untouched / 包含须保留文件的实际报告
     * @throws Exception if storage or authority is unavailable / 存储或授权不可用时 */
    public Inventory inventory(boolean clean) throws Exception {
        try (var operation = security.enterOperation()) {
            begin();
            try {
                var results = clean ? cleanHistory() : replayAll();
                var keys = repository.targets().stream().map(LogRepository.Target::key)
                        .collect(java.util.stream.Collectors.toSet());
                keys.add(LogRepository.startupTarget().key());
                return new Inventory(results, emergency.unregistered(keys));
            } finally {
                end();
            }
        }
    }

    /** Reads already redacted records from a selected registered category for the host UI. / 为宿主界面读取选定登记类别的已脱敏记录。
     * @param key exact current registration key / 精确当前登记键
     * @return bounded merged records and any unavailable location / 有界合并记录及任一不可用位置
     * @throws Exception if category selection or user authority is invalid / 类别选择或用户授权无效时 */
    public View inspect(UUID key) throws Exception {
        try (var operation = security.enterOperation()) {
            begin();
            CategoryAccess lock = null;
            try {
                var target = repository.targets().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
                lock = lock(target);
                var records = new ArrayList<LogRecordCodec.Record>();
                Problem problem = null;
                try {
                    records.addAll(repository.read(target));
                } catch (SQLException unavailable) {
                    problem = problem(ProblemCode.IO_FAILURE, "LOG_VIEW_DATABASE",
                            "Database diagnostics could not be read");
                }
                try {
                    var merged = new ArrayList<>(records);
                    merged.addAll(emergency.read(target));
                    if (target.owner().equals("app") && target.category().equals("runtime"))
                        synchronized (LogRepository.class) {
                            merged.addAll(emergency.read(LogRepository.startupTarget()));
                        }
                    records = new ArrayList<>(EmergencyLogs.merge(merged, 100));
                } catch (Exception unavailable) {
                    problem = problem(ProblemCode.IO_FAILURE, "LOG_VIEW_EMERGENCY",
                            "Emergency diagnostics could not be verified");
                }
                return new View(target, List.copyOf(records), Optional.ofNullable(problem));
            } finally {
                if (lock != null)
                    lock.close();
                end();
            }
        }
    }

    /** Coordinates each snapshot independently so one failure does not hide other results. / 独立协调每个快照，避免一个失败遮蔽其他结果。
     * @param clean whether ten-record cleanup was explicitly requested / 是否明确请求保留十条清理
     * @return immutable actual report / 不可变实际报告
     * @throws Exception if the overall registry cannot be read / 无法读取整体登记时 */
    private List<Maintenance> maintain(boolean clean) throws Exception {
        begin();
        if (!maintaining.compareAndSet(false, true)) {
            end();
            throw new IllegalStateException("Log maintenance is already running");
        }
        try {
            var results = new ArrayList<Maintenance>();
            for (var target : repository.targets()) {
                Long dbBefore = null, dbAfter = null, dbRemoved = null, fileBefore = null, fileAfter = null,
                        fileRemoved = null;
                long confirmedRemoved = 0;
                CategoryAccess lock = null;
                Problem problem = null;
                try {
                    lock = lock(target);
                    var pending = emergency.read(target);
                    fileBefore = (long) pending.size();
                    fileAfter = fileBefore;
                    fileRemoved = 0L;
                    try {
                        dbBefore = (long) repository.read(target).size();
                    } catch (SQLException unavailable) {
                        if (clean && !pending.isEmpty()) {
                            var kept = EmergencyLogs.merge(pending, 10);
                            fileAfter = null;
                            fileRemoved = null;
                            emergency.replace(target, kept);
                            fileAfter = (long) kept.size();
                            fileRemoved = fileBefore - fileAfter;
                        }
                        throw unavailable;
                    }
                    if (!pending.isEmpty()) {
                        var replayed = repository.store(target, pending, 100);
                        confirmedRemoved += replayed.removed();
                        dbAfter = replayed.retained();
                        dbRemoved = confirmedRemoved;
                        fileAfter = null;
                        fileRemoved = null;
                        emergency.replace(target, List.of());
                        fileAfter = 0L;
                        fileRemoved = fileBefore;
                    }
                    if (clean) {
                        dbAfter = null;
                        dbRemoved = null;
                        var cleaned = repository.store(target, List.of(), 10);
                        confirmedRemoved += cleaned.removed();
                        dbAfter = cleaned.retained();
                    } else
                        dbAfter = (long) repository.read(target).size();
                    dbRemoved = confirmedRemoved;
                } catch (Exception failure) {
                    problem = failure instanceof ApiException api
                            ? api.problem()
                            : problem(ProblemCode.IO_FAILURE, "LOG_MAINTENANCE",
                                    "Diagnostic category remains partly unconfirmed");
                } finally {
                    if (lock != null)
                        lock.close();
                }
                results.add(new Maintenance(target, Optional.ofNullable(dbBefore), Optional.ofNullable(dbAfter),
                        Optional.ofNullable(dbRemoved), Optional.ofNullable(fileBefore), Optional.ofNullable(fileAfter),
                        Optional.ofNullable(fileRemoved), Optional.ofNullable(problem)));
            }
            recovery = List.copyOf(results);
            return recovery;
        } finally {
            maintaining.set(false);
            end();
        }
    }

    /** Writes a new event even if an older emergency replay remains pending. / 即使较旧应急回填仍待处理，也写入新事件。
     * @param target exact original category / 精确原类别
     * @param record already bounded redacted record / 已有界脱敏记录
     * @return actual persistence destination and optional replay problem / 实际持久位置及可选回填问题 */
    private LogWriteResult write(LogRepository.Target target, LogRecordCodec.Record record) {
        CategoryAccess lock = null;
        try {
            lock = lock(target);
            Problem pendingProblem = null;
            try {
                var pending = emergency.read(target);
                if (!pending.isEmpty()) {
                    repository.store(target, pending, 100);
                    emergency.replace(target, List.of());
                }
            } catch (Exception pending) {
                pendingProblem = problem(ProblemCode.IO_FAILURE, "LOG_REPLAY_PENDING",
                        "Older emergency diagnostics remain pending");
            }
            try {
                repository.store(target, List.of(record), 100);
                return new LogWriteResult(record.id(), LogStorage.DATABASE, record.truncated(),
                        Optional.ofNullable(pendingProblem));
            } catch (SQLException databaseFailure) {
                emergency.append(target, record);
                return new LogWriteResult(record.id(), LogStorage.EMERGENCY_FILE, record.truncated(),
                        Optional.of(problem(ProblemCode.IO_FAILURE, "LOG_DATABASE_UNCONFIRMED",
                                "Diagnostic saved only to its emergency file")));
            }
        } catch (Exception failure) {
            return new LogWriteResult(record.id(), LogStorage.NOT_SAVED, record.truncated(),
                    Optional.of(failure instanceof ApiException api
                            ? api.problem()
                            : problem(ProblemCode.IO_FAILURE, "LOG_NOT_SAVED",
                                    "Diagnostic persistence could not be confirmed")));
        } finally {
            if (lock != null)
                lock.close();
        }
    }

    /** Acquires owner admission before the category lock, holding both through all physical I/O. / 先取得所有者准入再取得类别锁，在全部物理 I/O 期间持有两者。
     * @param target actual category / 实际类别
     * @return original access scope / 原访问作用域
     * @throws Exception if deletion or existing storage prevents timely access / 删除或既有存储阻止及时访问时 */
    private CategoryAccess lock(LogRepository.Target target) throws Exception {
        var owner = ownerLocks.computeIfAbsent(target.owner(), ignored -> new ReentrantReadWriteLock(true));
        boolean acquired = false;
        try {
            if (!owner.readLock().tryLock(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Diagnostic owner is busy");
            acquired = true;
            synchronized (this) {
                if (blockedOwners.contains(target.owner()) || retiredKeys.contains(target.key()))
                    throw new ApiException(problem(ProblemCode.BUSY, "LOG_OWNER_DELETING",
                            "Diagnostic owner or original category is retained for deletion"));
            }
            var category = categoryLocks.computeIfAbsent(target.key(), ignored -> new ReentrantLock());
            if (!category.tryLock(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Diagnostic category is busy");
            return new CategoryAccess(owner, category);
        } catch (Exception failure) {
            if (acquired)
                owner.readLock().unlock();
            if (failure instanceof InterruptedException)
                Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /** Retains the exact owner and category locks in their acquisition order. / 按取得顺序保留精确所有者及类别锁。
     * @param owner original owner lock / 原所有者锁
     * @param category original category lock / 原类别锁 */
    private record CategoryAccess(ReentrantReadWriteLock owner, ReentrantLock category) implements AutoCloseable {
        /** Releases only completed I/O, in reverse acquisition order. / 仅释放已完成 I/O，按取得相反顺序。 */
        @Override
        public void close() {
            category.unlock();
            owner.readLock().unlock();
        }
    }

    /** Seals one stopped owner before deletion review, admission or recovery; other owners remain available. / 在删除审阅、准入或恢复前封闭一个已停止所有者，其他所有者保持可用。
     * @param owner exact installed Mod identity / 精确安装 Mod 身份
     * @return worker-owned deletion scope, which must enclose all deletion I/O / 须覆盖全部删除 I/O 的工作线程所属删除作用域
     * @throws SQLException if bindings, storage or lifecycle admission remain active / 绑定、存储或生命周期准入仍活动时 */
    public DeletionScope sealDeletion(String owner) throws SQLException {
        if (owner == null || owner.isBlank() || owner.equals("app") || owner.equals("@platform"))
            throw new IllegalArgumentException("Only a Mod diagnostic owner can be deleted");
        begin();
        var gate = ownerLocks.computeIfAbsent(owner, ignored -> new ReentrantReadWriteLock(true));
        boolean acquired = false, sealed = false, previous = false;
        try {
            if (!gate.writeLock().tryLock(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Diagnostic owner remains busy");
            acquired = true;
            synchronized (this) {
                if (deleting.contains(owner)
                        || bindings.stream().anyMatch(b -> b.logOwner.equals(owner) && (!b.closed || b.active != 0)))
                    throw new IllegalStateException("Original diagnostic bindings must finish closing first");
                previous = blockedOwners.contains(owner);
                blockedOwners.add(owner);
                deleting.add(owner);
                sealed = true;
            }
            var keys = repository.targets().stream().filter(t -> t.owner().equals(owner)).map(LogRepository.Target::key)
                    .toList();
            synchronized (this) {
                deletionKeys.computeIfAbsent(owner, ignored -> new HashSet<>()).addAll(keys);
            }
            return new DeletionScope(owner, gate);
        } catch (Exception failure) {
            synchronized (this) {
                if (sealed) {
                    deleting.remove(owner);
                    if (!previous)
                        blockedOwners.remove(owner);
                }
            }
            if (acquired)
                gate.writeLock().unlock();
            end();
            if (failure instanceof InterruptedException)
                Thread.currentThread().interrupt();
            throw new SQLException("Owned diagnostics could not be sealed for deletion", failure);
        }
    }

    /** Holds physical owner exclusion while retaining durable pending-deletion and retired-category protection. / 持有物理所有者排他，同时保留持久待删除及已注销类别保护。 */
    public final class DeletionScope implements AutoCloseable {
        /** Exact sealed owner. / 精确封闭所有者。 */
        private final String owner;

        /** Original thread-bound lock. / 原线程绑定锁。 */
        private final ReentrantReadWriteLock gate;

        /** Prevents duplicate release. / 防止重复释放。 */
        private boolean released;

        /** Captures already acquired owner exclusion. / 捕获已取得的所有者排他。
         * @param owner sealed identity / 封闭身份
         * @param gate acquired exclusion / 已取得排他 */
        private DeletionScope(String owner, ReentrantReadWriteLock gate) {
            this.owner = owner;
            this.gate = gate;
        }

        /** Releases actual I/O exclusion; unresolved durable deletion still blocks future replay and writes. / 释放实际 I/O 排他；未解决持久删除仍阻止后续回填及写入。
         * @throws SQLException if absence of retained deletion cannot be confirmed / 无法确认保留删除不存在时 */
        @Override
        public synchronized void close() throws SQLException {
            if (released)
                return;
            if (!gate.isWriteLockedByCurrentThread())
                throw new IllegalStateException("Deletion scope belongs to its worker");
            try {
                if (!repository.deletingOwners().contains(owner)) {
                    var remaining = repository.targets().stream().map(LogRepository.Target::key)
                            .collect(java.util.stream.Collectors.toSet());
                    synchronized (PlatformLogService.this) {
                        var known = deletionKeys.remove(owner);
                        if (known != null)
                            for (UUID key : known)
                                if (!remaining.contains(key))
                                    retiredKeys.add(key);
                        blockedOwners.remove(owner);
                    }
                }
            } finally {
                released = true;
                synchronized (PlatformLogService.this) {
                    deleting.remove(owner);
                }
                gate.writeLock().unlock();
                end();
            }
        }
    }

    /** Retains storage lifetime before any blocking I/O. / 任一阻塞 I/O 前保留存储寿命。 */
    private synchronized void begin() {
        if (SwingUtilities.isEventDispatchThread() || closed)
            throw new IllegalStateException("Diagnostic storage requires an active worker");
        active++;
    }

    /** Releases only calls that have actually returned. / 仅释放实际返回的调用。 */
    private synchronized void end() {
        active--;
    }

    /** Closes log admission only after all actual writes and maintenance return. / 所有实际写入和维护返回后才关闭日志准入。
     * @throws ApiException if physical storage is still in flight / 实际存储仍在进行时 */
    @Override
    public synchronized void close() throws ApiException {
        if (active != 0)
            throw new ApiException(problem(ProblemCode.BUSY, "LOG_STORAGE_BUSY", "Diagnostic storage is still active"));
        for (var binding : bindings)
            binding.closed = true;
        bindings.clear();
        listeners.clear();
        closed = true;
    }

    /** One original owner capability with object-identity registered targets. / 具有原对象身份登记目标的单个原所有者能力。 */
    public final class Binding implements LogService, AutoCloseable {
        /** Actual task owner and diagnostic owner. / 实际任务所有者及诊断所有者。 */
        private final String owner, logOwner, version;

        /** Exact optional business generation. / 精确可选业务代次。 */
        private final Optional<SchemaIdentity> schema;

        /** Actual task attribution authority for this activation. / 此启用的实际任务归属授权方。 */
        private final PlatformTaskRuntime tasks;

        /** Original target objects are never reconstructed from copied UUIDs. / 原目标对象不从复制 UUID 重建。 */
        private final Map<LogTarget, LogRepository.Target> targets = new IdentityHashMap<>();

        /** Actual storage calls belonging to this binding. / 属于此绑定的实际存储调用。 */
        private int active;

        /** Revokes this activation's future diagnostics. / 撤销此启用的后续诊断。 */
        private volatile boolean closed;

        /** Captures exact host attribution without new business authority. / 捕获精确宿主归属，不增加业务授权。
         * @param owner actual capability owner / 实际能力所有者
         * @param version loaded release / 加载发布版本
         * @param schema selected business generation / 选定业务代次
         * @param tasks actual task attribution / 实际任务归属 */
        private Binding(String owner, String version, Optional<SchemaIdentity> schema, PlatformTaskRuntime tasks) {
            this.owner = Objects.requireNonNull(owner);
            this.version = Objects.requireNonNull(version);
            this.schema = Objects.requireNonNull(schema);
            this.tasks = Objects.requireNonNull(tasks);
            logOwner = owner.equals("@platform") ? "app" : owner;
            if (owner.isBlank() || owner.equals("app") || schema.isPresent() && !schema.get().ownerId().equals(owner))
                throw new IllegalArgumentException("Invalid logger owner");
        }

        /** Registers one consistent caller category on a worker. / 在工作线程登记一个一致的调用方类别。
         * @param category fixed owner category / 固定所有者类别
         * @return original opaque target / 原不透明目标
         * @throws ApiException if registration or lifetime fails / 登记或寿命失败时 */
        @Override
        public LogTarget register(LogCategory category) throws ApiException {
            enter();
            try {
                var target = repository.register(logOwner, schema, category);
                synchronized (targets) {
                    for (var entry : targets.entrySet())
                        if (entry.getValue().equals(target))
                            return entry.getKey();
                    var handle = new LogTarget(target.key());
                    targets.put(handle, target);
                    return handle;
                }
            } catch (Exception invalid) {
                throw new ApiException(problem(ProblemCode.CONTRACT_VIOLATION, "LOG_CATEGORY",
                        "Diagnostic category could not be registered"));
            } finally {
                leave();
            }
        }

        /** Writes only an original target owned by this activation. / 仅写入此启用持有的原目标。
         * @param handle original target / 原目标
         * @param event bounded diagnostic intent / 有界诊断意图
         * @return actual storage result without altering the business task / 不改变业务任务的实际存储结果 */
        @Override
        public LogWriteResult write(LogTarget handle, LogEvent event) {
            return writeExcerpt(handle, event, false);
        }

        /** Preserves upstream truncation when a host stream has already discarded excess bytes. / 宿主流已丢弃超量字节时保留上游截断标记。
         * @param handle original target / 原目标
         * @param event bounded diagnostic intent / 有界诊断意图
         * @param truncated whether the host already truncated the source / 宿主是否已截断来源
         * @return actual persistence observation / 实际持久观察 */
        public LogWriteResult writeExcerpt(LogTarget handle, LogEvent event, boolean truncated) {
            UUID rejectedId = UUID.randomUUID();
            try {
                enter();
            } catch (ApiException unavailable) {
                return reported(new LogWriteResult(rejectedId, LogStorage.NOT_SAVED, false,
                        Optional.of(unavailable.problem())));
            }
            try {
                LogRepository.Target target;
                synchronized (targets) {
                    target = targets.get(handle);
                }
                if (target == null)
                    throw new IllegalArgumentException("Foreign diagnostic target");
                if (event.taskId().isPresent() && !tasks.diagnosticOwner(event.taskId().get(), owner, version))
                    throw new IllegalArgumentException("Foreign task attribution");
                var record = LogRecordCodec.create(target, version, event);
                if (truncated)
                    record = record.withDetails(record.details(), true);
                if (event.level() == LogLevel.DEBUG && !debug)
                    return new LogWriteResult(record.id(), LogStorage.NOT_SAVED, record.truncated(), Optional.empty());
                return reported(PlatformLogService.this.write(target, record));
            } catch (Exception invalid) {
                return reported(new LogWriteResult(rejectedId, LogStorage.NOT_SAVED, false,
                        Optional.of(problem(ProblemCode.CONTRACT_VIOLATION, "LOG_RECORD",
                                "Diagnostic record or original target is invalid"))));
            } finally {
                leave();
            }
        }

        /** Retains binding and platform lifetimes atomically. / 原子保留绑定及平台寿命。
         * @throws ApiException if detached or called from the EDT / 已分离或从 EDT 调用时 */
        private void enter() throws ApiException {
            synchronized (PlatformLogService.this) {
                if (closed || blockedOwners.contains(logOwner))
                    throw new ApiException(problem(ProblemCode.UNAVAILABLE, "LOGGER_CLOSED", "Logger is detached"));
                try {
                    begin();
                    active++;
                } catch (RuntimeException invalid) {
                    throw new ApiException(problem(ProblemCode.CONTRACT_VIOLATION, "LOG_WORKER",
                            "Diagnostic storage requires an active worker"));
                }
            }
        }

        /** Releases only this returned storage operation. / 仅释放此已返回存储操作。 */
        private void leave() {
            synchronized (PlatformLogService.this) {
                active--;
                end();
            }
        }

        /** Revokes new writes while retaining any actual in-flight operation. / 撤销新写入并保留任一实际在途操作。
         * @throws ApiException if writes have not actually returned / 写入尚未实际返回时 */
        @Override
        public void close() throws ApiException {
            synchronized (PlatformLogService.this) {
                closed = true;
                if (active != 0)
                    throw new ApiException(problem(ProblemCode.BUSY, "LOGGER_BUSY", "Owned diagnostics remain active"));
                targets.clear();
                bindings.remove(this);
            }
        }
    }

    /** Actual category counts; empty counts mean unverified, never zero. / 实际类别计数；空计数表示未核验，不是零。
     * @param target original category / 原类别
     * @param databaseBefore observed original SQL count / 已观察原 SQL 计数
     * @param databaseAfter confirmed remaining SQL count / 已确认剩余 SQL 计数
     * @param databaseRemoved confirmed deleted SQL rows / 已确认删除 SQL 行数
     * @param fileBefore observed emergency count / 已观察应急计数
     * @param fileAfter confirmed remaining emergency count / 已确认剩余应急计数
     * @param fileRemoved confirmed removed emergency records / 已确认移除应急记录数
     * @param problem any unconfirmed part / 任一未确认部分 */
    public record Maintenance(LogRepository.Target target, Optional<Long> databaseBefore, Optional<Long> databaseAfter,
            Optional<Long> databaseRemoved, Optional<Long> fileBefore, Optional<Long> fileAfter,
            Optional<Long> fileRemoved, Optional<Problem> problem) {
    }

    /** Complete host inventory retaining unknown files as explicit unresolved evidence. / 完整宿主清单，将未知文件保留为明确未解决证据。
     * @param categories actual per-category results / 实际逐类别结果
     * @param unregistered untouched files without a current registration / 没有当前登记且未触碰的文件 */
    public record Inventory(List<Maintenance> categories, List<String> unregistered) {
    }

    /** Host-only record view; ordinary Mod capabilities cannot enumerate other owners. / 仅宿主的记录视图；普通 Mod 能力不能枚举其他所有者。
     * @param target selected target / 选定目标
     * @param records validated redacted records / 已校验脱敏记录
     * @param problem any location not successfully observed / 任一未成功观察的位置 */
    public record View(LogRepository.Target target, List<LogRecordCodec.Record> records, Optional<Problem> problem) {
    }

    /** Builds bounded non-recursive diagnostics without source exception text. / 不包含源异常文字地构造有界非递归诊断。
     * @param code public category / 公开类别
     * @param event stable event code / 稳定事件码
     * @param message safe description / 安全说明
     * @return structured problem / 结构化问题 */
    private static Problem problem(ProblemCode code, String event, String message) {
        return new Problem(code, event, message, Map.of(), Optional.empty());
    }
}
