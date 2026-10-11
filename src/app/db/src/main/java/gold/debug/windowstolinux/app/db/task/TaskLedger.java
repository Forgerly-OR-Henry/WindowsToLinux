package gold.debug.windowstolinux.app.db.task;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.sshrecover.BrowserRecoveryArchival;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Persists atomic admission and actual resource release independently of business SQL. / 独立于业务 SQL 持久化原子准入及实际资源释放。 */
public final class TaskLedger {
    /** Read-only task and occupancy facts on the same original transactions. / 同一原事务上的只读任务及占用依据。 */
    private final TaskLedgerQueries queries = new TaskLedgerQueries();

    /** Original conclusion, summary and durable archival operations. / 原结论、摘要及持久归档操作。 */
    private final TaskFinalization finalizationSupport = new TaskFinalization();

    /** Original FILE custody transactions. / 原 FILE 保管事务。 */
    private final TaskFileMaterial materialSupport = new TaskFileMaterial();

    /** Original synchronous provider admission and release transactions. / 原同步提供方准入及释放事务。 */
    private final TaskDependencies dependencySupport = new TaskDependencies();

    /** Original storage generation for finalization receipts. / 终态收尾凭据所属的原存储代次。 */
    private final PlatformDatabase database;

    /** Engine boot identity used to refuse execution of previous-process tasks. / 用于拒绝执行前一进程任务的引擎启动身份。 */
    private final UUID bootId;

    /** Scoped transactions sharing a single platform connection. / 共享单个平台连接的作用域事务。 */
    private final StoreConnections connections;

    /** Fixed internal JSON encoding, never arbitrary business serialization. / 固定内部 JSON 编码，不序列化任意业务对象。 */
    private final ObjectMapper json = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Binds the ledger to the exclusive engine owner. / 将账本绑定到引擎独占所有者。
     * @param database current platform engine / 当前平台引擎
     */
    public TaskLedger(PlatformDatabase database) {
        this.database = database;
        bootId = database.bootId();
        connections = new StoreConnections(database::openConnection);
    }

    /** Writes a queued task and its complete initial claims before author code runs. / 作者代码运行前写入排队任务及完整初始申请。
     * @param id host-generated identity retained even when commit acknowledgement fails / 提交确认失败时仍由宿主保留的预生成身份
     * @param ownerId caller Mod identity / 调用方 Mod 身份
     * @param ownerVersion bound caller release / 绑定的调用方发布版本
     * @param request immutable author request / 不可变作者请求
     * @param claims host-validated initial Mod, schema and server claims / 宿主已校验的初始 Mod、数据及服务器申请
     * @return durable task identity / 持久化任务身份
     * @throws SQLException if validation or persistence fails / 校验或持久化失败时
     */
    public UUID enqueue(UUID id, String ownerId, String ownerVersion, TaskRequest request, List<TaskResource> claims)
        throws SQLException {
        return enqueue(id, ownerId, ownerVersion, request, connection -> claims);
    }

    /** Validates live platform state and queues work within one serialized transaction. / 在同一串行事务内校验平台实时状态并将工作入队。
     * @param id host-generated durable identity / 宿主生成的持久身份
     * @param ownerId exact caller identity / 精确调用方身份
     * @param ownerVersion exact running release / 精确运行版本
     * @param request immutable author request / 不可变作者请求
     * @param resolver policy using the locked transaction connection / 使用锁定事务连接的策略
     * @return committed task identity / 已提交任务身份
     * @throws SQLException if validation or persistence fails / 校验或持久化失败时
     */
    public UUID enqueue(UUID id, String ownerId, String ownerVersion, TaskRequest request, InitialClaims resolver)
        throws SQLException {
        Objects.requireNonNull(id);
        if (ownerId.isBlank() || ownerId.length() > 512 || ownerVersion.isBlank() || ownerVersion.length() > 512
            || request.type().isBlank() || request.type().length() > 128 || request.title().isBlank()
            || request.title().length() > 4096)
            throw new SQLException("Invalid task request");
        return locked(() -> {
            try (var connection = connections.open()) {
                var ordered = orderedClaims(resolver.resolve(connection));
                var servers = ordered.stream().filter(r -> r.kind().equals("SERVER")).map(TaskResource::id)
                    .collect(java.util.stream.Collectors.toSet());
                if (!servers.equals(new HashSet<>(request.serverIds())) || ordered.stream().noneMatch(
                    r -> r.kind().equals("MOD") && r.id().equals(ownerId) && r.ownerId().equals(ownerId)))
                    throw new SQLException("Incomplete initial task resource set");
                for (var claim : ordered)
                    if (!Set.of("MOD", "SCHEMA", "SERVER").contains(claim.kind()))
                        throw new SQLException("Invalid initial resource kind");
                var graph = resolver.recoveryGraph(connection);
                if (graph.isPresent()) {
                    try {
                        graph.get().requireOwner(ownerId, ownerVersion, ordered);
                    } catch (IllegalArgumentException invalid) {
                        throw new SQLException("Invalid task recovery graph", invalid);
                    }
                }
                String recovery = encode(graph.isPresent()
                    ? Map.of("formatVersion", graph.get().legacy() ? 2 : 3, "initialClaims", ordered, "graph", graph.get().encode())
                    : Map.of("formatVersion", 1, "initialClaims", ordered));
                if (recovery.length() > 1024 * 1024)
                    throw new SQLException("Task recovery metadata is too large");
                long now = System.currentTimeMillis();
                try (var c = connections.open();
                     var p = c.prepareStatement(
                         "INSERT INTO platform_task(task_id,owner_id,owner_version,task_type,state,cancellable,boot_id,request_json,recovery_json,archive_log_id,progress_text,started_at) VALUES(?,?,?,?,'QUEUED',?,?,?,?,?,?,?)")) {
                    p.setObject(1, id);
                    p.setString(2, ownerId);
                    p.setString(3, ownerVersion);
                    p.setString(4, request.type());
                    p.setBoolean(5, request.cancellable());
                    p.setObject(6, bootId);
                    p.setString(7, encode(Map.of("type", request.type(), "title", request.title(), "serverIds",
                        request.serverIds(), "cancellable", request.cancellable())));
                    p.setString(8, recovery);
                    p.setString(9, UUID.randomUUID().toString());
                    p.setString(10, request.title());
                    p.setLong(11, now);
                    p.executeUpdate();
                }
                return id;
            }
        });
    }

    /** Resolves complete initial resources against the locked platform state. / 根据锁定的平台状态解析完整初始资源。 */
    @FunctionalInterface
    public interface InitialClaims {
        /** Validates state before any task row is written. / 在写入任何任务行之前校验状态。
         * @param connection current serialized platform transaction / 当前串行平台事务
         * @return complete initial claims / 完整初始申请
         * @throws SQLException if validation or queries fail / 校验或查询失败时
         */
        List<TaskResource> resolve(Connection connection) throws SQLException;

        /** Supplies original Mod identities in the same admission transaction; host protocols may omit them. / 在同一准入事务提供原 Mod 身份，宿主协议可省略。
         * @param connection original serialized transaction / 原串行事务
         * @return original graph when this policy admits Mod business / 此策略接纳 Mod 业务时的原图
         * @throws SQLException if original identities cannot be read / 无法读取原身份时 */
        default Optional<TaskRecoveryGraph> recoveryGraph(Connection connection) throws SQLException {
            return Optional.empty();
        }

        /** Supplies the host-validated original caller declaration kind. / 提供宿主已校验的原调用方声明类别。
         * @param connection serialized original platform connection / 串行原平台连接
         * @return verified original result / 已核验原结果
         * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
         */
        default Optional<TaskRecoveryGraph.EdgeKind> dependencyKind(Connection connection) throws SQLException {
            return Optional.empty();
        }

    }

    /** Sorts and rejects duplicate claims in the required global order. / 按要求的全局顺序排序并拒绝重复申请。
     * @param claims complete claim set / 完整申请集合
     * @return immutable ordered claims / 不可变有序申请
     * @throws SQLException if a resource appears twice / 同一资源重复出现时
     */
    private static List<TaskResource> orderedClaims(List<TaskResource> claims) throws SQLException {
        var keys = new HashSet<String>();
        for (var r : claims)
            if (!keys.add(r.kind() + "\0" + r.id()))
                throw new SQLException("Duplicate task resource");
        return claims.stream().sorted(Comparator.comparingInt((TaskResource r) -> switch (r.kind()) {
            case "MOD" -> 0;
            case "SCHEMA" -> 1;
            case "SERVER" -> 2;
            default -> 3;
        }).thenComparing(TaskResource::id)).toList();
    }

    /** Starts only when every persisted initial claim can be acquired together. / 仅在所有持久化初始申请可整体取得时启动。
     * @param taskId current-boot queued task / 当前启动会话的排队任务
     * @return true when all claims and RUNNING committed together / 全部申请与 RUNNING 一同提交时为真
     * @throws SQLException if the task or its recovery material is invalid / 任务或恢复材料无效时
     */
    public boolean tryStart(UUID taskId) throws SQLException {
        return locked(() -> {
            try (var c = connections.open();
                 var p = c.prepareStatement(
                     "SELECT state,boot_id,recovery_json,owner_id,owner_version FROM platform_task WHERE task_id=?")) {
                p.setObject(1, taskId);
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        throw new SQLException("Task does not exist");
                    if (!bootId.equals(r.getObject(2, UUID.class)))
                        throw new SQLException("Previous-boot work requires explicit reconciliation");
                    if (!r.getString(1).equals("QUEUED"))
                        return false;
                    List<TaskResource> claims;
                    try {
                        var node = queries.admissionDocument(r.getString(3), r.getString(4), r.getString(5));
                        claims = orderedClaims(
                            Arrays.asList(json.treeToValue(node.required("initialClaims"), TaskResource[].class)));
                    } catch (java.io.IOException | IllegalArgumentException failure) {
                        throw new SQLException("Invalid task recovery material", failure);
                    }
                    for (var claim : claims)
                        if (queries.conflicts(c, taskId, claim))
                            return false;
                    for (var claim : claims)
                        insertLease(c, taskId, claim, ResourceRecovery.TASK_END);
                }
                setState(c, taskId, "RUNNING");
                return true;
            }
        });
    }

    /** Appends an explicit resource whose actual close must be reported separately. / 追加必须单独报告实际关闭的显式资源。
     * @param taskId currently executing task / 正在执行的任务
     * @param resource host-bound resource identity / 宿主绑定的资源身份
     * @return retained lease identity / 保留的占用身份
     * @throws SQLException if the task no longer executes or a conflict exists / 任务不再执行或存在冲突时
     */
    public UUID retain(UUID taskId, TaskResource resource) throws SQLException {
        return retain(taskId, resource, null, false);
    }

    /** Retains a physical resource and its recovery facts in the same transaction. / 在同一事务中保留物理资源及其恢复事实。
     * @param taskId original executing task / 原执行任务
     * @param resource exact owned resource / 精确所属资源
     * @param recovery optional host recovery facts / 可选宿主恢复事实
     * @param firstCreation require this task/kind/identity to have no historical row / 要求本任务、类别及身份无历史行
     * @return durable lease identity / 持久占用身份
     * @throws SQLException if ownership, metadata or persistence fails / 归属、元数据或持久化失败时 */
    public UUID retain(UUID taskId, TaskResource resource, ResourceRecovery recovery, boolean firstCreation)
        throws SQLException {
        String metadata = recovery == null ? ResourceRecovery.EXPLICIT : recovery.encode();
        return locked(() -> {
            try (var c = connections.open()) {
                String state = queries.state(c, taskId);
                if (!Set.of("RUNNING", "CANCEL_REQUESTED", "UNKNOWN", "FINISHING").contains(state))
                    throw new SQLException("Task cannot acquire another owned resource");
                if (resource.kind().equals("SERVER"))
                    throw new SQLException("Servers must be declared in initial admission");
                if (queries.conflicts(c, taskId, resource))
                    throw new SQLException("Resource is busy");
                if (firstCreation)
                    try (var p = c.prepareStatement(
                        "SELECT COUNT(*) FROM platform_task_resource WHERE task_id=? AND resource_kind=? AND resource_id=?")) {
                        p.setObject(1, taskId);
                        p.setString(2, resource.kind());
                        p.setString(3, resource.id());
                        try (var r = p.executeQuery()) {
                            r.next();
                            if (r.getLong(1) != 0)
                                throw new SQLException("Owned resource identity was already used by this task");
                        }
                    }
                try (var p = c.prepareStatement(
                    "SELECT lease_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind=? AND resource_id=? AND released_at IS NULL")) {
                    p.setObject(1, taskId);
                    p.setString(2, resource.kind());
                    p.setString(3, resource.id());
                    try (var r = p.executeQuery()) {
                        if (r.next()) {
                            String prior = r.getString(4);
                            ResourceRecovery.requireExplicit(prior);
                            if (!resource.ownerId().equals(r.getString(2))
                                || !r.getString(3).equals(resource.exclusive() ? "EXCLUSIVE" : "SHARED")
                                || !(recovery == null
                                ? ResourceRecovery.EXPLICIT.equals(prior)
                                : recovery.equals(ResourceRecovery.decode(prior))))
                                throw new SQLException("Retained resource recovery facts cannot be replaced");
                            if (Set.of("UNKNOWN", "FINISHING").contains(state))
                                return r.getObject(1, UUID.class);
                            throw new SQLException("Resource identity is already retained by this task");
                        }
                    }
                }
                return insertLease(c, taskId, resource, metadata);
            }
        });
    }

    /** Inserts a claim within the already serialized transaction. / 在已串行化的事务内写入申请。
     * @param c scoped platform connection / 作用域平台连接
     * @param taskId owning task / 所属任务
     * @param resource exact resource claim / 精确资源申请
     * @param metadata validated release and recovery envelope / 已校验释放及恢复封套
     * @return unique lease identity / 唯一占用身份
     * @throws SQLException if persistence fails / 持久化失败时
     */
    private UUID insertLease(Connection c, UUID taskId, TaskResource resource, String metadata) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var p = c.prepareStatement(
            "INSERT INTO platform_task_resource(lease_id,task_id,resource_kind,resource_id,owner_id,access_mode,acquired_at,recovery_json) VALUES(?,?,?,?,?,?,?,?)")) {
            p.setObject(1, id);
            p.setObject(2, taskId);
            p.setString(3, resource.kind());
            p.setString(4, resource.id());
            p.setString(5, resource.ownerId());
            p.setString(6, resource.exclusive() ? "EXCLUSIVE" : "SHARED");
            p.setLong(7, System.currentTimeMillis());
            p.setString(8, metadata);
            p.executeUpdate();
        }
        return id;
    }

    /** Returns exact FILE custody operations sharing this ledger's serialized transaction. / 返回共用此账本串行事务的精确 FILE 保管操作。
     * @return original FILE custody transaction owner / 原 FILE 保管事务所有者 */
    public TaskFileMaterial materials() {
        return materialSupport;
    }

    /** Returns original synchronous call operations sharing this ledger's serialized transaction. / 返回共用此账本串行事务的原同步调用操作。
     * @return original provider admission transaction owner / 原提供方准入事务所有者 */
    public TaskDependencies dependencies() {
        return dependencySupport;
    }

    /** Committed exact service call proof and scoped provider leases. / 已提交的精确服务调用证明及作用域提供方占用。
     * @param recovery exact committed original call proof / 精确已提交原调用证明
     * @param leases complete scoped provider lease set / 完整作用域提供方占用集合
     */
    public record DependencyAdmission(DependencyRecovery recovery, List<UUID> leases) {
    }

    /** Exact original FILE custody operations under one platform lock. / 一个平台锁下的精确原 FILE 保管操作。 */
    public final class TaskFileMaterial {
        /** Only this ledger creates the original transaction owner. / 仅此账本创建原事务所有者。 */
        private TaskFileMaterial() {
        }

        /** Releases only abandoned original unpublished custody and its workspace atomically. / 仅原子释放已放弃的原未发布保管及其工作区。
         * @param taskId original accepted root task / 原已接纳根任务
         * @param workspaceLeaseId exact original workspace lease / 精确原工作区占用
         * @param workspace exact original source workspace / 精确原源码工作区
         * @param rootLeaseId original unpublished root lease / 原未发布根占用
         * @param root exact original material root / 精确原素材根
         * @param expectedRequested exact requested unpublished facts / 精确请求释放未发布依据
         * @param released confirmed original unpublished release / 已确认原未发布释放
         * @throws SQLException if exact custody or zero writer evidence is unavailable / 精确保管或零写入方依据不可用时 */
        public void releaseUnpublishedMaterial(UUID taskId, UUID workspaceLeaseId, TaskResource workspace,
                                               UUID rootLeaseId, TaskResource root, ResourceRecovery expectedRequested,
                                               ResourceRecovery released) throws SQLException {
            locked(() -> {
                try (var c = connections.open()) {
                    FileMaterialTransition.unpublished(c, taskId, workspaceLeaseId, workspace, rootLeaseId, root,
                        expectedRequested, released, queries.recoveryGraph(taskId)
                            .orElseThrow(() -> new SQLException("Original graph is absent")));
                }
                return null;
            });
        }

        /** Commits an exact material state CAS that closes hold acquisition. / 提交封闭持有获取的精确素材状态比较更新。
         * @param taskId original accepted task identity / 原已接纳任务身份
         * @param leaseId exact durable lease identity / 精确持久占用身份
         * @param resource exact original resource / 精确原资源
         * @param expected complete expected prior recovery facts / 完整预期先前恢复依据
         * @param next validated next recovery facts / 已校验下一恢复依据
         * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
         */
        public void checkpointFileMaterial(UUID taskId, UUID leaseId, TaskResource resource, ResourceRecovery expected, ResourceRecovery next) throws SQLException {
            locked(() -> {
                try (var c = connections.open()) {
                    FileMaterialTransition.checkpoint(c, taskId, leaseId, resource, expected, next, queries.recoveryGraph(taskId).orElseThrow(() -> new SQLException("Original graph is absent")));
                }
                return null;
            });
        }

        /** Commits custody publication and original workspace detach together. / 一同提交保管发布与原工作区脱离。
         * @param taskId original accepted task identity / 原已接纳任务身份
         * @param workspaceLeaseId original workspace lease / 原工作区占用
         * @param workspace original source workspace identity / 原源码工作区身份
         * @param materialLeaseId original material root lease / 原素材根占用
         * @param material original material root identity / 原素材根身份
         * @param expectedCapturing exact expected capturing header / 精确预期捕获头
         * @param published complete immutable published header / 完整不可变发布头
         * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
         */
        public void transferSourceWorkspace(UUID taskId, UUID workspaceLeaseId, TaskResource workspace, UUID materialLeaseId, TaskResource material, ResourceRecovery expectedCapturing, ResourceRecovery published) throws SQLException {
            locked(() -> {
                try (var c = connections.open()) {
                    FileMaterialTransition.transfer(c, taskId, workspaceLeaseId, workspace, materialLeaseId, material, expectedCapturing, published, queries.recoveryGraph(taskId).orElseThrow(() -> new SQLException("Original graph is absent")));
                }
                return null;
            });
        }

        /** Retains a read hold only while its exact original root is published. / 仅在精确原根已发布时保留读取持有。
         * @param taskId original accepted task identity / 原已接纳任务身份
         * @param rootLeaseId original root lease / 原根占用
         * @param root exact original material root / 精确原素材根
         * @param expectedPublished exact expected published header / 精确预期发布头
         * @param hold exact consumer read hold / 精确消费方读取持有
         * @param activeHeader exact new active hold header / 精确新活动持有头
         * @return verified original result / 已核验原结果
         * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
         */
        public UUID acquireMaterialHold(UUID taskId, UUID rootLeaseId, TaskResource root, ResourceRecovery expectedPublished, TaskResource hold, ResourceRecovery activeHeader) throws SQLException {
            return locked(() -> {
                try (var c = connections.open()) {
                    FileMaterialTransition.requirePublished(c, taskId, rootLeaseId, root, expectedPublished, queries.recoveryGraph(taskId).orElseThrow(() -> new SQLException("Original graph is absent")));
                    FileMaterialTransition.holdIdentity(taskId, hold, activeHeader, expectedPublished);
                    if (!activeHeader.fields().get("state").equals("ACTIVE") || !activeHeader.fields().get("bootId").equals(bootId.toString()))
                        throw new SQLException("Hold belongs to another boot or is already released");
                    try (var p = c.prepareStatement("SELECT lease_id FROM platform_task_resource WHERE task_id=? AND resource_kind='FILE' AND resource_id=?")) {
                        p.setObject(1, taskId);
                        p.setString(2, hold.id());
                        try (var r = p.executeQuery()) {
                            if (r.next()) throw new SQLException("Material hold identity was already used");
                        }
                    }
                    return insertLease(c, taskId, hold, activeHeader.encode());
                }
            });
        }

        /** Records actual closure without replacing the original hold identity. / 记录实际关闭且不替换原持有身份。
         * @param taskId original accepted task identity / 原已接纳任务身份
         * @param holdLeaseId original read hold lease / 原读取持有占用
         * @param hold exact consumer read hold / 精确消费方读取持有
         * @param expectedActive exact expected active hold header / 精确预期活动持有头
         * @param released confirmed original release facts / 已确认原释放依据
         * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
         */
        public void releaseMaterialHold(UUID taskId, UUID holdLeaseId, TaskResource hold, ResourceRecovery expectedActive, ResourceRecovery released) throws SQLException {
            locked(() -> {
                try (var c = connections.open()) {
                    FileMaterialTransition.releaseHold(c, taskId, holdLeaseId, hold, expectedActive, released);
                }
                return null;
            });
        }

        /** Records confirmed root deletion after every original hold is released. / 全部原持有释放后记录已确认根删除。
         * @param taskId original accepted task identity / 原已接纳任务身份
         * @param rootLeaseId original root lease / 原根占用
         * @param root exact original material root / 精确原素材根
         * @param expectedRequested exact expected release-requested root / 精确预期请求释放根
         * @param released confirmed original release facts / 已确认原释放依据
         * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
         */
        public void releaseMaterialRoot(UUID taskId, UUID rootLeaseId, TaskResource root, ResourceRecovery expectedRequested, ResourceRecovery released) throws SQLException {
            locked(() -> {
                try (var c = connections.open()) {
                    FileMaterialTransition.releaseRoot(c, taskId, rootLeaseId, root, expectedRequested, released, queries.recoveryGraph(taskId).orElseThrow(() -> new SQLException("Original graph is absent")));
                }
                return null;
            });
        }

    }

    /** Exact original provider call transactions and complete task pins. / 精确原提供方调用事务及完整任务占用。 */
    public final class TaskDependencies {
        /** Only this ledger creates the original transaction owner. / 仅此账本创建原事务所有者。 */
        private TaskDependencies() {
        }

        /** Acquires the whole provider resource set in one transaction before author code runs. / 作者代码运行前在同一事务取得完整提供方资源集合。
         * @param taskId original accepted task / 原已接纳任务
         * @param recovery exact provider and call identity / 精确提供方及调用身份
         * @param resolver actual provider admission under the platform lock / 平台锁下的实际提供方准入
         * @return independently owned lease IDs for this call / 此调用独立持有的占用 ID
         * @throws SQLException if validation, conflict or persistence prevents admission / 校验、冲突或持久化阻止准入时 */
        public List<UUID> retainDependency(UUID taskId, DependencyRecovery recovery, InitialClaims resolver)
            throws SQLException {
            if (!recovery.boot().equals(bootId))
                throw new SQLException("Dependency call belongs to another boot");
            return locked(() -> {
                try (var c = connections.open()) {
                    if (!Set.of("RUNNING", "CANCEL_REQUESTED", "UNKNOWN").contains(queries.state(c, taskId)))
                        throw new SQLException("Original task is not executing");
                    var claims = orderedClaims(resolver.resolve(c));
                    if (!claims.equals(orderedClaims(recovery.claims())))
                        throw new SQLException("Provider admission differs from its loaded generation");
                    for (var claim : claims)
                        if (queries.conflicts(c, taskId, claim))
                            throw new DependencyBusyException();
                    var ids = new ArrayList<UUID>();
                    for (var claim : claims)
                        ids.add(insertLease(c, taskId, claim, recovery.encode().encode()));
                    return List.copyOf(ids);
                }
            });
        }

        /** Commits actual optional use and task-wide original pins before provider effects. / 在提供方效果前提交实际可选使用及整任务原占用。
         * @param taskId original accepted task identity / 原已接纳任务身份
         * @param caller authenticated original caller / 已认证原调用方
         * @param callerVersion exact original caller release / 精确原调用方发布
         * @param provider exact original provider / 精确原提供方
         * @param version exact loaded release / 精确已加载发布
         * @param schema exact optional original data generation / 精确可选原数据代次
         * @param recoveryOnly whether this is original-task reconciliation / 是否属于原任务核对
         * @param resolver host admission policy under the same transaction / 同一事务内的宿主准入策略
         * @return verified original result / 已核验原结果
         * @throws SQLException if original identity or persistence cannot be verified / 无法核验原身份或持久化时
         */
        public DependencyAdmission admitDependency(UUID taskId, String caller, String callerVersion, String provider,
                                                   String version, Optional<gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity> schema,
                                                   boolean recoveryOnly, InitialClaims resolver) throws SQLException {
            return locked(() -> {
                try (var c = connections.open()) {
                    if (!Set.of("RUNNING", "CANCEL_REQUESTED", "UNKNOWN").contains(queries.state(c, taskId)))
                        throw new SQLException("Original task is not executing");
                    var original = queries.recoveryGraph(taskId)
                        .orElseThrow(() -> new SQLException("Original dependency graph is absent"));
                    var calling = original.release(caller);
                    if (!calling.version().equals(callerVersion))
                        throw new SQLException("Original caller release differs");
                    try (var p = c.prepareStatement("SELECT content_sha256 FROM mod_version WHERE mod_id=? AND version=? AND state='READY'")) {
                        p.setString(1, caller);
                        p.setString(2, callerVersion);
                        try (var r = p.executeQuery()) {
                            if (!r.next() || !calling.digest().equals(r.getString(1)))
                                throw new SQLException("Original caller code is absent");
                        }
                    }
                    var kind = resolver.dependencyKind(c)
                        .orElseThrow(() -> new SQLException("Original dependency declaration proof is absent"));
                    var proof = resolver.recoveryGraph(c)
                        .orElseThrow(() -> new SQLException("Actual provider identity proof is absent"));
                    var provided = proof.release(provider);
                    if (!proof.owner().equals(provider) || !provided.version().equals(version) || !provided.schema().equals(schema))
                        throw new SQLException("Actual provider identity differs");
                    TaskRecoveryGraph next = original;
                    if (original.legacy()) {
                        if (kind != TaskRecoveryGraph.EdgeKind.REQUIRED || !original.release(provider).equals(provided))
                            throw new SQLException("Legacy required dependency identity differs");
                    } else {
                        var edge = new TaskRecoveryGraph.Edge(caller, provider, kind);
                        if (recoveryOnly && !original.edges().contains(edge))
                            throw new SQLException("Recovery cannot introduce another optional provider");
                        next = original.extend(caller, proof, kind);
                        if (recoveryOnly && !next.equals(original))
                            throw new SQLException("Original recovery graph changed");
                    }
                    var facts = new DependencyRecovery(bootId, UUID.randomUUID(), provider, version, schema,
                        caller, callerVersion, provided.digest());
                    var claims = orderedClaims(resolver.resolve(c));
                    if (!claims.equals(orderedClaims(facts.claims()))) throw new SQLException("Provider claims differ");
                    var existingPins = new HashSet<>(original.claims());
                    for (var pin : next.claims())
                        if (!existingPins.contains(pin) && queries.conflicts(c, taskId, pin))
                            throw new DependencyBusyException();
                    for (var claim : claims)
                        if (queries.conflicts(c, taskId, claim)) throw new DependencyBusyException();
                    if (!next.equals(original)) {
                        extendOriginalDocument(c, taskId, next);
                        for (var pin : next.claims())
                            if (!existingPins.contains(pin)) insertLease(c, taskId, pin, ResourceRecovery.TASK_END);
                    }
                    var ids = new ArrayList<UUID>();
                    for (var claim : claims) ids.add(insertLease(c, taskId, claim, facts.encode().encode()));
                    return new DependencyAdmission(facts, List.copyOf(ids));
                } catch (IllegalArgumentException | NoSuchElementException invalid) {
                    throw new SQLException("Original dependency proof is invalid", invalid);
                }
            });
        }

        /** Replaces only the admitted graph under the original transaction, retaining server claims and cancellation. / 在原事务中仅替换已准入图，保留服务器占用与取消意图。
         * @param connection original locked transaction / 原锁定事务
         * @param taskId exact original task / 精确原任务
         * @param next already validated actual dependency graph / 已校验实际依赖图
         * @throws SQLException if original metadata cannot be retained / 原元数据无法保留时 */
        private void extendOriginalDocument(Connection connection, UUID taskId, TaskRecoveryGraph next) throws SQLException {
            List<TaskResource> priorClaims;
            com.fasterxml.jackson.databind.node.ObjectNode originalDocument;
            try (var query = connection.prepareStatement("SELECT recovery_json,owner_id,owner_version FROM platform_task WHERE task_id=?")) {
                query.setObject(1, taskId);
                try (var row = query.executeQuery()) {
                    if (!row.next()) throw new SQLException("Original task disappeared");
                    try {
                        originalDocument = (com.fasterxml.jackson.databind.node.ObjectNode) queries.admissionDocument(row.getString(1), row.getString(2), row.getString(3));
                        priorClaims = Arrays.asList(json.treeToValue(originalDocument.required("initialClaims"), TaskResource[].class));
                    } catch (java.io.IOException invalid) {
                        throw new SQLException("Original claims are invalid", invalid);
                    }
                }
            }
            var allClaims = new ArrayList<>(next.claims());
            priorClaims.stream().filter(claim -> claim.kind().equals("SERVER")).forEach(allClaims::add);
            originalDocument.put("formatVersion", 3);
            originalDocument.set("initialClaims", json.valueToTree(orderedClaims(allClaims)));
            originalDocument.set("graph", json.valueToTree(next.encode()));
            String document = gold.debug.windowstolinux.app.db.task.metadata.TaskCancellationDocument.encode(originalDocument);
            if (document.length() > 1024 * 1024) throw new SQLException("Original graph is too large");
            try (var update = connection.prepareStatement("UPDATE platform_task SET recovery_json=? WHERE task_id=?")) {
                update.setString(1, document);
                update.setObject(2, taskId);
                update.executeUpdate();
            }
        }

        /** Releases one original call's complete claims atomically, never another concurrent call. / 原子释放一次原调用的完整申请，不释放另一并发调用。
         * @param taskId owning task / 所属任务
         * @param recovery original call facts / 原调用事实
         * @param leases exact originally acquired lease IDs / 精确原取得占用 ID
         * @throws SQLException if identity or the complete commit cannot be confirmed / 身份或完整提交无法确认时 */
        public void releaseDependency(UUID taskId, DependencyRecovery recovery, List<UUID> leases) throws SQLException {
            if (leases.size() != recovery.claims().size() || Set.copyOf(leases).size() != leases.size())
                throw new SQLException("Incomplete dependency lease set");
            locked(() -> {
                try (var c = connections.open()) {
                    var observed = new ArrayList<TaskResource>();
                    for (var id : leases) {
                        try (var p = c.prepareStatement(
                            "SELECT resource_kind,resource_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE task_id=? AND lease_id=?")) {
                            p.setObject(1, taskId);
                            p.setObject(2, id);
                            try (var rows = p.executeQuery()) {
                                if (!rows.next() || !recovery.encode().equals(ResourceRecovery.decode(rows.getString(5))))
                                    throw new SQLException("Dependency release identity differs");
                                observed.add(new TaskResource(rows.getString(1), rows.getString(2), rows.getString(3),
                                    rows.getString(4).equals("EXCLUSIVE")));
                            }
                        }
                    }
                    if (!orderedClaims(observed).equals(orderedClaims(recovery.claims())))
                        throw new SQLException("Dependency release claims differ");
                    for (var id : leases)
                        try (var p = c.prepareStatement(
                            "UPDATE platform_task_resource SET released_at=COALESCE(released_at,?) WHERE task_id=? AND lease_id=?")) {
                            p.setLong(1, System.currentTimeMillis());
                            p.setObject(2, taskId);
                            p.setObject(3, id);
                            if (p.executeUpdate() != 1)
                                throw new SQLException("Dependency release was not confirmed");
                        }
                }
                return null;
            });
        }

    }

    /** Known rejection before any dependency claim is inserted. / 任一依赖申请插入前的已知拒绝。 */
    public static final class DependencyBusyException extends SQLException {
        /** Stable exception serialization identity. / 稳定异常序列化身份。 */
        private static final long serialVersionUID = 1L;

        /** Reports occupied provider resources without implying unknown side effects. / 报告提供方资源被占用，不暗示未知副作用。 */
        private DependencyBusyException() {
            super("Provider resource is busy");
        }
    }

    /** Records the first actual PID and start time before any process HELLO is sent. / 发送任何进程 HELLO 前记录首个实际 PID 及启动时间。
     * @param taskId original task / 原任务
     * @param resource exact retained process / 精确保留进程
     * @param before original pre-creation checkpoint / 原创建前检查点
     * @param after observed physical identity / 已观察物理身份
     * @throws SQLException if identity, phase or commit cannot be confirmed / 无法确认身份、阶段或提交时 */
    public void checkpointProcess(UUID taskId, TaskResource resource, ProcessRecovery before, ProcessRecovery after)
        throws SQLException {
        checkpointPhysicalProcess(taskId, resource, before, after, "process-v1:", "process.v1");
    }

    /** Records a browser's original PID before allowing terminal observation or input. / 允许终端观测或输入前记录浏览器原 PID。
     * @param taskId original task / 原任务
     * @param resource retained browser identity / 保留浏览器身份
     * @param before durable pre-launch facts / 持久启动前事实
     * @param after actual native identity / 实际原生身份
     * @throws SQLException if ownership, transition or commit is unconfirmed / 归属、转换或提交未确认时 */
    public void checkpointBrowser(UUID taskId, TaskResource resource, ProcessRecovery before, ProcessRecovery after)
        throws SQLException {
        checkpointPhysicalProcess(taskId, resource, before, after, "browser-v1:", "browser.v1");
    }

    /** Persists the original browser owner's confirmed cleanup before releasing its claim. / 释放申请前持久化原浏览器所有者已确认的清理。
     * @param taskId original task / 原任务
     * @param resource original browser claim / 原浏览器申请
     * @param before original pre-creation identity / 原创建前身份
     * @throws SQLException if ownership, physical boot or the durable checkpoint differs / 归属、物理启动代次或持久检查点不符时 */
    public void confirmBrowserClosed(UUID taskId, TaskResource resource, ProcessRecovery before) throws SQLException {
        if (!resource.kind().equals("PROCESS") || !resource.id().equals("browser-v1:" + before.process())
            || resource.exclusive() || !bootId.equals(before.boot()) || before.pid().isPresent())
            throw new SQLException("Invalid browser closure identity");
        locked(() -> {
            try (var c = connections.open();
                 var p = c.prepareStatement(
                     "SELECT lease_id,recovery_json,released_at FROM platform_task_resource WHERE task_id=? AND resource_kind='PROCESS' AND resource_id=? AND owner_id=? AND access_mode='SHARED'")) {
                p.setObject(1, taskId);
                p.setString(2, resource.id());
                p.setString(3, resource.ownerId());
                UUID lease;
                ResourceRecovery recorded;
                boolean released;
                try (var rows = p.executeQuery()) {
                    if (!rows.next())
                        throw new SQLException("Original browser lease is unavailable");
                    lease = rows.getObject(1, UUID.class);
                    recorded = ResourceRecovery.decode(rows.getString(2));
                    released = rows.getObject(3) != null;
                    if (rows.next())
                        throw new SQLException("Duplicate browser lease identity");
                }
                boolean closed = recorded.protocol().equals("browser.closed.v1");
                ProcessRecovery actual;
                try {
                    actual = closed
                        ? ProcessRecovery.decodeBrowserClosed(recorded)
                        : ProcessRecovery.decode(recorded, "browser.v1");
                } catch (IllegalArgumentException invalid) {
                    throw new SQLException("Browser creation facts are invalid", invalid);
                }
                if (!before.boot().equals(actual.boot()) || !before.process().equals(actual.process()))
                    throw new SQLException("Browser closure differs from original creation");
                if (closed)
                    return null;
                if (released)
                    throw new SQLException("Browser lease was released without confirmed cleanup");
                try (var update = c.prepareStatement(
                    "UPDATE platform_task_resource SET recovery_json=? WHERE task_id=? AND lease_id=? AND released_at IS NULL")) {
                    update.setString(1, actual.encodeBrowserClosed().encode());
                    update.setObject(2, taskId);
                    update.setObject(3, lease);
                    if (update.executeUpdate() != 1)
                        throw new SQLException("Browser closure is no longer owned");
                }
            }
            return null;
        });
    }

    /** Atomically advances one exact physical-process protocol once. / 原子推进一个精确物理进程协议一次。
     * @param taskId original task / 原任务
     * @param resource retained physical owner / 保留物理所有者
     * @param before original creation facts / 原创建事实
     * @param after observed native identity / 已观察原生身份
     * @param prefix exact resource namespace / 精确资源命名空间
     * @param protocol exact recovery protocol / 精确恢复协议
     * @throws SQLException if identity, phase or persistence differs / 身份、阶段或持久化不符时 */
    private void checkpointPhysicalProcess(UUID taskId, TaskResource resource, ProcessRecovery before,
                                           ProcessRecovery after, String prefix, String protocol) throws SQLException {
        if (!resource.kind().equals("PROCESS") || !resource.id().equals(prefix + before.process())
            || resource.exclusive() || !bootId.equals(before.boot()) || before.pid().isPresent()
            || after.pid().isEmpty() || !before.boot().equals(after.boot())
            || !before.process().equals(after.process()))
            throw new SQLException("Invalid process checkpoint transition");
        locked(() -> {
            try (var c = connections.open();
                 var p = c.prepareStatement(
                     "SELECT lease_id,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='PROCESS' AND resource_id=? AND owner_id=? AND access_mode='SHARED' AND released_at IS NULL")) {
                p.setObject(1, taskId);
                p.setString(2, resource.id());
                p.setString(3, resource.ownerId());
                UUID lease;
                ResourceRecovery original;
                try (var rows = p.executeQuery()) {
                    if (!rows.next())
                        throw new SQLException("Original process lease is unavailable");
                    lease = rows.getObject(1, UUID.class);
                    original = ResourceRecovery.decode(rows.getString(2));
                    if (rows.next())
                        throw new SQLException("Duplicate process lease identity");
                }
                if (original.equals(after.encode(protocol)))
                    return null;
                if (!original.equals(before.encode(protocol)))
                    throw new SQLException("Process checkpoint differs from original creation");
                try (var update = c.prepareStatement(
                    "UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=? AND task_id=? AND released_at IS NULL")) {
                    update.setString(1, after.encode(protocol).encode());
                    update.setObject(2, lease);
                    update.setObject(3, taskId);
                    if (update.executeUpdate() != 1)
                        throw new SQLException("Process checkpoint no longer owned");
                }
            }
            return null;
        });
    }

    /** Records a verified physical release without releasing another task's resources. / 记录已核实的物理释放，不释放其他任务资源。
     * @param taskId owning task / 所属任务
     * @param leaseId exact owned lease / 精确的所属占用
     * @throws SQLException if the lease is foreign, automatic or cannot be persisted / 占用属于他方、自动管理或无法持久化时
     */
    public void release(UUID taskId, UUID leaseId) throws SQLException {
        locked(() -> {
            try (var c = connections.open()) {
                try (var p = c.prepareStatement(
                    "SELECT recovery_json FROM platform_task_resource WHERE lease_id=? AND task_id=?")) {
                    p.setObject(1, leaseId);
                    p.setObject(2, taskId);
                    try (var row = p.executeQuery()) {
                        if (!row.next())
                            throw new SQLException("Resource lease is not owned by this task");
                        ResourceRecovery.requireExplicit(row.getString(1));
                    }
                }
                try (var p = c.prepareStatement(
                    "UPDATE platform_task_resource SET released_at=COALESCE(released_at,?) WHERE lease_id=? AND task_id=?")) {
                    p.setLong(1, System.currentTimeMillis());
                    p.setObject(2, leaseId);
                    p.setObject(3, taskId);
                    if (p.executeUpdate() != 1)
                        throw new SQLException("Resource lease is not owned by this task");
                }
            }
            return null;
        });
    }

    /** Releases vanished SSH clients from an earlier exclusive database boot, preserving file claims. / 释放更早独占数据库启动中已消失的 SSH 客户端，保留文件申请。
     * @param taskId original recovery task / 原恢复任务
     * @param owner original Mod identity / 原 Mod 身份
     * @param version original release / 原发布版本
     * @throws SQLException if task or connection identity cannot be proved / 无法证明任务或连接身份时 */
    public void releasePreviousSshConnections(UUID taskId, String owner, String version) throws SQLException {
        locked(() -> {
            try (var c = connections.open()) {
                try (var p = c.prepareStatement(
                    "SELECT boot_id,owner_id,owner_version,state FROM platform_task WHERE task_id=?")) {
                    p.setObject(1, taskId);
                    try (var row = p.executeQuery()) {
                        if (!row.next())
                            throw new SQLException("Original SSH recovery owner differs");
                        if ((!owner.equals(row.getString(2)) || !version.equals(row.getString(3)))
                            && TaskLedgerQueries.readDependencies(c, taskId).keySet().stream().noneMatch(
                            call -> call.provider().equals(owner) && call.version().equals(version)))
                            throw new SQLException("Original SSH recovery owner differs");
                        if (bootId.equals(row.getObject(1, UUID.class)))
                            return null;
                        if (!Set.of("UNKNOWN", "FINISHING").contains(row.getString(4)))
                            throw new SQLException("Previous SSH task is not recoverable");
                    }
                }
                var leases = new ArrayList<UUID>();
                try (var p = c.prepareStatement(
                    "SELECT lease_id,resource_id,owner_id,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='CONNECTION' AND released_at IS NULL")) {
                    p.setObject(1, taskId);
                    try (var rows = p.executeQuery()) {
                        while (rows.next()) {
                            String id = rows.getString(2);
                            if (!id.startsWith("ssh-") || !owner.equals(rows.getString(3)))
                                continue;
                            var facts = ResourceRecovery.decode(rows.getString(4));
                            if (!facts.protocol().equals("ssh.v1")
                                || !facts.fields().keySet().equals(Set.of("bootId", "serverId"))
                                || facts.fields().get("serverId").isBlank())
                                throw new SQLException("Unknown SSH connection ownership");
                            try {
                                if (!id.equals("ssh-" + UUID.fromString(id.substring(4))))
                                    throw new IllegalArgumentException();
                                if (bootId.equals(UUID.fromString(facts.fields().get("bootId"))))
                                    continue;
                            } catch (IllegalArgumentException invalid) {
                                throw new SQLException("Invalid SSH connection identity", invalid);
                            }
                            leases.add(rows.getObject(1, UUID.class));
                        }
                    }
                }
                try (var p = c.prepareStatement(
                    "UPDATE platform_task_resource SET released_at=? WHERE lease_id=? AND task_id=? AND released_at IS NULL")) {
                    for (var lease : leases) {
                        p.setLong(1, System.currentTimeMillis());
                        p.setObject(2, lease);
                        p.setObject(3, taskId);
                        if (p.executeUpdate() != 1)
                            throw new SQLException("SSH connection lease changed");
                    }
                }
            }
            return null;
        });
    }

    /** Persists cooperative cancellation without inventing termination. / 持久化协作取消，不虚构任务终止。
     * @param taskId selected task / 选中的任务
     * @return true when queued work was cancelled before it could run / 排队工作在执行前被取消时为真
     * @throws SQLException if cancellation is unsupported or persistence fails / 不支持取消或持久化失败时
     */
    public boolean cancel(UUID taskId) throws SQLException {
        return cancel(taskId, () -> {
        });
    }

    /** Changes only a live callback's user-wait state and bounded progress. / 仅改变存活回调的用户等待状态和有界进度。
     * @param taskId original accepted task / 原已接纳任务
     * @param waiting whether to enter waiting or resume execution / 进入等待或恢复执行
     * @param reason bounded non-secret reason / 有界非秘密原因
     * @throws SQLException if state or persistence changed / 状态或持久化改变时
     */
    public void waiting(UUID taskId, boolean waiting, String reason) throws SQLException {
        if (reason == null || reason.length() > 4096)
            throw new SQLException("Invalid waiting reason");
        locked(() -> {
            try (var c = connections.open(); var p = c.prepareStatement(
                "UPDATE platform_task SET state=?,progress_text=? WHERE task_id=? AND state=? AND ended_at IS NULL")) {
                p.setString(1, waiting ? "WAITING" : "RUNNING");
                p.setString(2, reason);
                p.setObject(3, taskId);
                p.setString(4, waiting ? "RUNNING" : "WAITING");
                if (p.executeUpdate() != 1)
                    throw new SQLException("Original waiting task changed state");
            }
            return null;
        });
    }

    /** Releases host authorization before queued cancellation becomes terminal. / 排队取消成为终态前释放宿主授权。
     * @param taskId accepted task identity / 已接纳任务身份
     * @param beforeEnd nonblocking host cleanup after resource checks, without database reentry / 资源检查后的非阻塞宿主清理，不重入数据库
     * @return whether queued cancellation was durably completed / 排队取消是否已持久化完成
     * @throws SQLException if cancellation or persistence fails / 取消或持久化失败时
     */
    public boolean cancel(UUID taskId, Runnable beforeEnd) throws SQLException {
        Objects.requireNonNull(beforeEnd);
        return locked(() -> {
            try (var c = connections.open();
                 var p = c.prepareStatement("SELECT state,cancellable,recovery_json,owner_id,owner_version FROM platform_task WHERE task_id=?")) {
                p.setObject(1, taskId);
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        throw new SQLException("Task does not exist");
                    String state = r.getString(1);
                    if (state.equals("ENDED"))
                        return false;
                    if (!r.getBoolean(2))
                        throw new SQLException("Task does not support cancellation");
                    queries.admissionDocument(r.getString(3), r.getString(4), r.getString(5));
                    try (var update = c.prepareStatement("UPDATE platform_task SET recovery_json=? WHERE task_id=?")) {
                        update.setString(1, gold.debug.windowstolinux.app.db.task.metadata.TaskCancellationDocument.request(r.getString(3)));
                        update.setObject(2, taskId);
                        if (update.executeUpdate() != 1)
                            throw new SQLException("Original cancellation task disappeared");
                    }
                    if (state.equals("QUEUED")) {
                        beforeEnd.run();
                        finalizationSupport.finish(c, taskId, TaskResult.CANCELLED);
                        return true;
                    }
                    if (state.equals("RUNNING") || state.equals("WAITING"))
                        setState(c, taskId, "CANCEL_REQUESTED");
                    return false;
                }
            }
        });
    }

    /** Records an observed result, ending only after all explicit resources were released. / 记录观察结果，仅在全部显式资源释放后结束。
     * @param taskId existing accepted task / 已接纳任务
     * @param outcome observed business conclusion / 已观察的业务结论
     * @param resultJson bounded non-secret result evidence / 有界且不含秘密的结果证据
     * @param reconciliation whether explicit recovery is allowed to resolve UNKNOWN / 显式恢复是否允许核实 UNKNOWN
     * @return true only after durable terminal completion / 仅在持久化终态完成后为真
     * @throws SQLException if transition or persistence fails / 状态转换或持久化失败时
     */
    public boolean complete(UUID taskId, TaskResult outcome, String resultJson, boolean reconciliation)
        throws SQLException {
        return complete(taskId, outcome, resultJson, reconciliation, () -> {
        });
    }

    /** Finalizes host memory resources after explicit cleanup and before durable task end. / 显式清理后、持久化任务结束前收尾宿主内存资源。
     * @param taskId accepted original identity / 已接纳原身份
     * @param outcome observed business result / 已观察业务结果
     * @param resultJson bounded diagnostic evidence / 有界诊断证据
     * @param reconciliation whether original uncertainty is explicitly resolved / 是否显式核实原不确定性
     * @param beforeEnd nonblocking host cleanup without database reentry / 不重入数据库的非阻塞宿主清理
     * @return whether all resources and the known outcome reached terminal state / 全部资源及已知结果是否到达终态
     * @throws SQLException if state or persistence fails / 状态或持久化失败时
     */
    public boolean complete(UUID taskId, TaskResult outcome, String resultJson, boolean reconciliation, Runnable beforeEnd)
        throws SQLException {
        Objects.requireNonNull(beforeEnd);
        if (resultJson.length() > 65536)
            throw new SQLException("Task result evidence is too large");
        return locked(() -> {
            try (var c = connections.open()) {
                String state = queries.state(c, taskId);
                if (state.equals("ENDED")) {
                    try (var p = c.prepareStatement("SELECT outcome FROM platform_task WHERE task_id=?")) {
                        p.setObject(1, taskId);
                        try (var r = p.executeQuery()) {
                            r.next();
                            if (!r.getString(1).equals(outcome.name()))
                                throw new SQLException("A persisted terminal outcome cannot be replaced");
                        }
                    }
                    beforeEnd.run();
                    return true;
                }
                if (state.equals("QUEUED") || state.equals("UNKNOWN") && outcome != TaskResult.UNKNOWN && !reconciliation)
                    throw new SQLException("Task cannot complete without explicit reconciliation");
                try (var p = c.prepareStatement("UPDATE platform_task SET result_json=? WHERE task_id=?")) {
                    p.setString(1, resultJson);
                    p.setObject(2, taskId);
                    p.executeUpdate();
                }
                if (outcome == TaskResult.UNKNOWN) {
                    setState(c, taskId, "UNKNOWN");
                    return false;
                }
                try (var p = c.prepareStatement(
                    "SELECT COUNT(*) FROM platform_task_resource WHERE task_id=? AND released_at IS NULL AND (recovery_json IS NULL OR recovery_json<>?)")) {
                    p.setObject(1, taskId);
                    p.setString(2, ResourceRecovery.TASK_END);
                    try (var r = p.executeQuery()) {
                        r.next();
                        if (r.getLong(1) != 0) {
                            setState(c, taskId, "FINISHING");
                            return false;
                        }
                    }
                }
                beforeEnd.run();
                finalizationSupport.finish(c, taskId, outcome);
                return true;
            }
        });
    }

    /** Original fully drained result retained when its archive transaction cannot be confirmed. / 归档事务无法确认时保留的原已排空结果。 */
    public static final class Finalization {
        /** Original database generation. / 原数据库代次。 */
        private final UUID generation;

        /** Original fixed summary identity. / 原固定摘要身份。 */
        private final UUID archive;

        /** Known result and original finalization time; not itself a commit receipt. / 已知结果及原收尾时间，本身不是提交凭据。 */
        private final TaskSnapshot snapshot;

        /** Original bounded public diagnostic evidence. / 原有界公开诊断依据。 */
        private final String evidence;

        /** Captures only facts obtained inside the original task transaction. / 仅捕获原任务事务取得的事实。
         * @param generation original storage generation / 原存储代次
         * @param archive fixed summary identity / 固定摘要身份
         * @param snapshot original drained task / 原已排空任务
         * @param evidence original diagnostic / 原诊断 */
        private Finalization(UUID generation, UUID archive, TaskSnapshot snapshot, String evidence) {
            this.generation = generation;
            this.archive = archive;
            this.snapshot = snapshot;
            this.evidence = evidence;
        }

        /** Returns original observed facts without claiming durable archive completion. / 返回原观察事实，不声称持久归档完成。
         * @return original drained snapshot / 原已排空快照 */
        public TaskSnapshot snapshot() {
            return snapshot;
        }
    }

    /** Separates known business completion from unconfirmed platform archival. / 区分已知业务结束与未确认的平台归档。 */
    public static final class FinalizationException extends SQLException {
        /** Original host-only retry receipt. / 原仅宿主可用重试凭据。 */
        private final Finalization finalization;

        /** Retains the original facts after failed archive or commit acknowledgement. / 归档或提交确认失败后保留原事实。
         * @param finalization original receipt / 原凭据
         * @param cause original storage failure / 原存储失败 */
        private FinalizationException(Finalization finalization, SQLException cause) {
            super("Original task archive requires reconciliation", cause);
            this.finalization = finalization;
        }

        /** Returns the original receipt for metadata-only retry. / 返回供仅元数据重试的原凭据。
         * @return original finalization identity / 原收尾身份 */
        public Finalization finalization() {
            return finalization;
        }
    }

    /** Commits actual completion, fixed summary and execution cleanup in one transaction. / 在一个事务内提交实际结束、固定摘要及执行清理。
     * @param taskId accepted original task / 已接纳原任务
     * @param outcome observed business result / 已观察业务结果
     * @param evidence original public diagnostic / 原公开诊断
     * @param reconciliation whether uncertainty was explicitly resolved / 是否显式核实不确定性
     * @param beforeEnd nonblocking host cleanup / 非阻塞宿主清理
     * @return committed current observation, including unfinished resources / 已提交当前观察，含未收尾资源
     * @throws SQLException if the observation or archival cannot be confirmed / 观察或归档无法确认时 */
    public TaskSnapshot completeAndArchive(UUID taskId, TaskResult outcome, String evidence, boolean reconciliation,
                                           Runnable beforeEnd) throws SQLException {
        return finalizationSupport.finalizeTransaction(taskId, () -> complete(taskId, outcome, evidence, reconciliation, beforeEnd));
    }

    /** Archives queued cancellation without executing its business callback. / 归档排队取消，不执行其业务回调。
     * @param taskId original accepted task / 原已接纳任务
     * @param beforeEnd nonblocking host cleanup / 非阻塞宿主清理
     * @return committed cancellation request or ended snapshot / 已提交取消请求或结束快照
     * @throws SQLException if cancellation or archive is unconfirmed / 取消或归档未确认时 */
    public TaskSnapshot cancelAndArchive(UUID taskId, Runnable beforeEnd) throws SQLException {
        return finalizationSupport.finalizeTransaction(taskId, () -> cancel(taskId, beforeEnd));
    }

    /** Retries only an original proven finalization, never a business callback or missing history. / 仅重试原已证明收尾，不调用业务回调或重建缺失历史。
     * @param original original host receipt / 原宿主凭据
     * @param beforeEnd idempotent nonblocking host cleanup / 幂等非阻塞宿主清理
     * @return original committed terminal observation / 原已提交终态观察
     * @throws SQLException if identity, resources or persistence is unconfirmed / 身份、资源或持久化未确认时 */
    public TaskSnapshot retryFinalization(Finalization original, Runnable beforeEnd) throws SQLException {
        if (!database.generation().equals(original.generation))
            throw new SQLException("Finalization belongs to another database generation");
        var target = finalizationSupport.summaryTarget();
        var recoveryTarget = finalizationSupport.recoverySummaryTarget(original.snapshot);
        try {
            return locked(() -> {
                try (var c = connections.open()) {
                    if (!finalizationSupport.matches(c, original))
                        return original.snapshot;
                    complete(original.snapshot.id(), original.snapshot.outcome().orElseThrow(), original.evidence, true,
                        beforeEnd);
                    try (var p = c.prepareStatement(
                        "UPDATE platform_task SET ended_at=? WHERE task_id=? AND state='ENDED'")) {
                        p.setLong(1, original.snapshot.endedAt().orElseThrow().toEpochMilli());
                        p.setObject(2, original.snapshot.id());
                        if (p.executeUpdate() != 1)
                            throw new SQLException("Original task is no longer drained");
                    }
                    finalizationSupport.archive(c, target, recoveryTarget, original);
                    return original.snapshot;
                }
            });
        } catch (SQLException failure) {
            throw new FinalizationException(original, failure);
        }
    }

    /** Preserves known physical completion as FINISHING after an unconfirmed archive. / 归档未确认后以 FINISHING 保留已知物理收尾。
     * @param original original host receipt / 原宿主凭据
     * @return true if retained, false if original execution was already removed / 已保留返回真，原执行已移除返回假
     * @throws SQLException if original identity or persistence cannot be proven / 原身份或持久化无法证明时 */
    public boolean retainFinalization(Finalization original) throws SQLException {
        if (!database.generation().equals(original.generation))
            throw new SQLException("Finalization belongs to another database generation");
        return locked(() -> {
            try (var c = connections.open()) {
                if (!finalizationSupport.matches(c, original))
                    return false;
                try (var p = c.prepareStatement(
                    "UPDATE platform_task SET state='FINISHING',outcome=?,ended_at=?,result_json=? WHERE task_id=?")) {
                    p.setString(1, original.snapshot.outcome().orElseThrow().name());
                    p.setLong(2, original.snapshot.endedAt().orElseThrow().toEpochMilli());
                    p.setString(3, original.evidence);
                    p.setObject(4, original.snapshot.id());
                    if (p.executeUpdate() != 1)
                        throw new SQLException("Original task disappeared");
                }
                return true;
            }
        });
    }

    /** Updates bounded progress for a task that has not ended. / 为尚未结束的任务更新有界进度。
     * @param taskId task identity / 任务身份
     * @param text non-secret progress text / 不含秘密的进度文字
     * @throws SQLException if text or task state is invalid / 文字或任务状态无效时
     */
    public void progress(UUID taskId, String text) throws SQLException {
        if (text.length() > 4096)
            throw new SQLException("Task progress is too large");
        try (var c = connections.open();
             var p = c.prepareStatement(
                 "UPDATE platform_task SET progress_text=? WHERE task_id=? AND state<>'ENDED'")) {
            p.setString(1, text);
            p.setObject(2, taskId);
            if (p.executeUpdate() != 1)
                throw new SQLException("Task is absent or ended");
        }
    }

    /** Retains an uncertain transition without releasing any resource or replaying work. / 保留不确定的状态转换，不释放资源或重放工作。
     * @param taskId possibly accepted task / 可能已经接纳的任务
     * @throws SQLException if uncertainty itself cannot be persisted / 不确定状态本身无法持久化时
     */
    public void uncertain(UUID taskId) throws SQLException {
        locked(() -> {
            try (var c = connections.open()) {
                if (!queries.state(c, taskId).equals("ENDED"))
                    setState(c, taskId, "UNKNOWN");
            }
            return null;
        });
    }

    /** Writes a nonterminal state without assigning an outcome. / 写入非终态状态，不分配终态结果。
     * @param c scoped platform connection / 作用域平台连接
     * @param taskId task identity / 任务身份
     * @param state validated internal successor / 已校验的内部后继状态
     * @throws SQLException if persistence fails / 持久化失败时
     */
    private void setState(Connection c, UUID taskId, String state) throws SQLException {
        try (var p = c
            .prepareStatement("UPDATE platform_task SET state=?,outcome=NULL,ended_at=NULL WHERE task_id=?")) {
            p.setString(1, state);
            p.setObject(2, taskId);
            p.executeUpdate();
        }
    }

    /** Serializes admission and release across all ledger instances using the platform row. / 利用平台行在全部账本实例间串行化准入与释放。
     * @param <T> operation result type / 操作结果类型
     * @param work operation sharing the locked transaction / 共享锁定事务的操作
     * @return committed operation result / 已提交操作结果
     * @throws SQLException if locking, work or commit fails / 加锁、操作或提交失败时
     */
    private <T> T locked(Query<T> work) throws SQLException {
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                StoreConnections.lockPlatformState(c);
            }
            result.set(work.run());
        });
        return result.get();
    }

    /** Internal transaction work returning a result only after commit. / 仅在提交后返回结果的内部事务工作。
     * @param <T> committed result type / 已提交结果类型
     */
    @FunctionalInterface
    private interface Query<T> {
        /** Executes within the already locked transaction. / 在已锁定事务内执行。
         * @return pending transaction result / 待提交事务结果
         * @throws SQLException if persistence fails / 持久化失败时
         */
        T run() throws SQLException;
    }

    /** Encodes only host-defined task metadata. / 仅编码宿主定义的任务元数据。
     * @param value fixed internal metadata / 固定内部元数据
     * @return JSON recovery or request document / JSON 恢复或请求文档
     * @throws SQLException if encoding fails / 编码失败时
     */
    private String encode(Object value) throws SQLException {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new SQLException("Task metadata encoding failed", failure);
        }
    }

    /** Returns existing read-only facts while reusing the ledger transaction and lock. / 复用账本事务及锁，返回既有只读依据。
     * @return original read-only query capability / 原只读查询能力 */
    public TaskLedgerQueries queries() {
        return queries;
    }

    /** Reads original task, resource and dependency facts using the same ledger transaction. / 使用同一账本事务读取原任务、资源及依赖依据。 */
    public final class TaskLedgerQueries {
        /** Creates this ledger/runtime collaborator. / 创建此账本或运行时职责协作者。 */
        private TaskLedgerQueries() {
        }

        /** Prevents endpoint changes for accepted work, including queued and uncertain tasks. / 防止修改已接纳工作使用的端点，包括排队及未知任务。
         * @param connection platform transaction already holding the state lock / 已持有状态锁的平台事务
         * @param serverId exact saved server identity / 精确已保存服务器身份
         * @throws SQLException if any accepted task requires the server or metadata cannot be verified / 已接纳任务需要服务器或无法核实元数据时
         */
        public static void requireServerIdle(Connection connection, String serverId) throws SQLException {
            var mapper = new ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            try (var statement = connection.prepareStatement("SELECT request_json FROM platform_task WHERE state<>'ENDED'");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        var ids = mapper.readTree(rows.getString(1)).required("serverIds");
                        if (!ids.isArray())
                            throw new SQLException("Accepted task server metadata is invalid");
                        for (var id : ids) {
                            if (!id.isTextual() || id.asText().isBlank())
                                throw new SQLException("Accepted task server metadata is invalid");
                            if (id.asText().equals(serverId))
                                throw new SQLException("Accepted tasks still require this server configuration");
                        }
                    } catch (java.io.IOException | IllegalArgumentException failure) {
                        throw new SQLException("Accepted task server metadata cannot be verified", failure);
                    }
                }
            }
        }

        /** Reads retained original code and data without using current Mod selection. / 不使用当前 Mod 选择，读取保留原代码及数据。
         * @param taskId original task / 原任务
         * @return captured graph, absent for host or pre-capture records / 捕获图，宿主或捕获能力加入前的记录不存在
         * @throws SQLException if the record is missing or malformed / 记录缺失或损坏时 */
        public Optional<TaskRecoveryGraph> recoveryGraph(UUID taskId) throws SQLException {
            try (var c = connections.open();
                 var p = c.prepareStatement(
                     "SELECT owner_id,owner_version,recovery_json FROM platform_task WHERE task_id=?")) {
                p.setObject(1, taskId);
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        throw new SQLException("Original task does not exist");
                    var node = queries.admissionDocument(r.getString(3), r.getString(1), r.getString(2));
                    return node.path("formatVersion").intValue() == 1
                        ? Optional.empty()
                        : Optional.of(TaskRecoveryGraph.decode(node.path("graph")));
                }
            }
        }

        /** Loads retained dependency groups without interpreting other resource protocols. / 加载保留依赖组，不解释其他资源协议。
         * @param taskId original task / 原任务
         * @return original call identities and complete lease sets / 原调用身份及完整占用集合
         * @throws SQLException if any recognized call is inconsistent / 任一识别的调用不一致时 */
        public Map<DependencyRecovery, List<UUID>> dependencies(UUID taskId) throws SQLException {
            return locked(() -> {
                try (var c = connections.open()) {
                    return TaskLedgerQueries.readDependencies(c, taskId);
                }
            });
        }

        /** Reads complete dependency groups using the caller's existing transaction. / 使用调用方已有事务读取完整依赖组。
         * @param connection existing platform transaction / 已有平台事务
         * @param taskId original task / 原任务
         * @return validated original groups / 已校验原依赖组
         * @throws SQLException if persisted claims or identities differ / 持久申请或身份不符时 */
        public static Map<DependencyRecovery, List<UUID>> readDependencies(Connection connection, UUID taskId)
            throws SQLException {
            var groups = new LinkedHashMap<DependencyRecovery, List<UUID>>();
            var resources = new HashMap<DependencyRecovery, List<TaskResource>>();
            try (var p = connection.prepareStatement(
                "SELECT lease_id,recovery_json,resource_kind,resource_id,owner_id,access_mode FROM platform_task_resource WHERE task_id=? AND resource_kind IN ('MOD','SCHEMA') AND released_at IS NULL")) {
                p.setObject(1, taskId);
                try (var rows = p.executeQuery()) {
                    while (rows.next()) {
                        String value = rows.getString(2);
                        if (ResourceRecovery.TASK_END.equals(value) || ResourceRecovery.EXPLICIT.equals(value))
                            continue;
                        var decoded = ResourceRecovery.decode(value);
                        if (!Set.of("service.v1", "service.v2").contains(decoded.protocol()))
                            continue;
                        DependencyRecovery identity;
                        try {
                            identity = DependencyRecovery.decode(decoded);
                        } catch (RuntimeException invalid) {
                            throw new SQLException("Invalid dependency recovery identity");
                        }
                        groups.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(rows.getObject(1, UUID.class));
                        resources.computeIfAbsent(identity, ignored -> new ArrayList<>())
                            .add(new TaskResource(rows.getString(3), rows.getString(4), rows.getString(5),
                                rows.getString(6).equals("EXCLUSIVE")));
                    }
                }
            }
            var result = new LinkedHashMap<DependencyRecovery, List<UUID>>();
            for (var entry : groups.entrySet()) {
                if (entry.getValue().size() != entry.getKey().claims().size())
                    throw new SQLException("Incomplete dependency claim group");
                if (!orderedClaims(resources.get(entry.getKey())).equals(orderedClaims(entry.getKey().claims())))
                    throw new SQLException("Dependency claim identity differs");
                result.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(result);
        }

        /** Checks physical claims of retained service providers before releasing their code/data ownership. / 释放代码及数据归属前检查保留服务提供方的物理申请。
         * @param taskId original task / 原任务
         * @param providers original provider identities / 原提供方身份
         * @return whether any provider resource remains active / 是否仍有提供方资源活动
         * @throws SQLException if the ledger cannot be read / 无法读取账本时 */
        public boolean hasDependencyResources(UUID taskId, Set<String> providers) throws SQLException {
            try (var c = connections.open();
                 var p = c.prepareStatement(
                     "SELECT owner_id FROM platform_task_resource WHERE task_id=? AND released_at IS NULL AND resource_kind NOT IN ('MOD','SCHEMA')")) {
                p.setObject(1, taskId);
                try (var rows = p.executeQuery()) {
                    while (rows.next())
                        if (providers.contains(rows.getString(1)))
                            return true;
                }
                return false;
            }
        }

        /** Reads outstanding recovery facts from an exact host-owned resource namespace. / 从精确宿主资源命名空间读取未完成恢复事实。
         * @param taskId original task / 原任务
         * @param kind exact resource kind / 精确资源种类
         * @param prefix host-owned resource prefix / 宿主所属资源前缀
         * @return immutable resource facts / 不可变资源事实
         * @throws SQLException if any matching record is malformed / 任一匹配记录损坏时 */
        public Map<TaskResource, ResourceRecovery> recoveries(UUID taskId, String kind, String prefix) throws SQLException {
            return locked(() -> {
                var found = new LinkedHashMap<TaskResource, ResourceRecovery>();
                try (var c = connections.open();
                     var p = c.prepareStatement(
                         "SELECT resource_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind=? AND released_at IS NULL ORDER BY resource_id")) {
                    p.setObject(1, taskId);
                    p.setString(2, kind);
                    try (var rows = p.executeQuery()) {
                        while (rows.next()) {
                            if (!rows.getString(1).startsWith(prefix))
                                continue;
                            var resource = new TaskResource(kind, rows.getString(1), rows.getString(2),
                                rows.getString(3).equals("EXCLUSIVE"));
                            if (found.put(resource, ResourceRecovery.decode(rows.getString(4))) != null)
                                throw new SQLException("Duplicate original recovery resource");
                        }
                    }
                }
                return Map.copyOf(found);
            });
        }

        /** Checks all capability instances for another outstanding SSH client before file recovery. / 文件恢复前跨能力实例检查其他未释放 SSH 客户端。
         * @param taskId original task / 原任务
         * @param currentLease this authenticated connection lease / 此已认证连接占用
         * @return whether only this SSH client remains / 是否仅余此 SSH 客户端
         * @throws SQLException if the ledger cannot be read / 无法读取账本时 */
        public boolean soleSshConnection(UUID taskId, UUID currentLease) throws SQLException {
            return locked(() -> {
                boolean currentFound = false;
                try (var c = connections.open();
                     var p = c.prepareStatement(
                         "SELECT lease_id,resource_id FROM platform_task_resource WHERE task_id=? AND resource_kind='CONNECTION' AND released_at IS NULL")) {
                    p.setObject(1, taskId);
                    try (var rows = p.executeQuery()) {
                        while (rows.next()) {
                            if (!rows.getString(2).startsWith("ssh-"))
                                continue;
                            if (!currentLease.equals(rows.getObject(1, UUID.class)))
                                return false;
                            currentFound = true;
                        }
                    }
                }
                return currentFound;
            });
        }

        /** Reads only a retained terminal row or explicitly persisted finalization marker. / 仅读取保留终态行或明确持久化的收尾标记。
         * @param taskId original retained task / 原保留任务
         * @return exact original finalization if known / 已知时返回精确原收尾
         * @throws SQLException if marker or ownership is inconsistent / 标记或归属不一致时 */
        public Optional<Finalization> pendingFinalization(UUID taskId) throws SQLException {
            return locked(() -> {
                try (var c = connections.open();
                     var p = c.prepareStatement("SELECT state,outcome,ended_at FROM platform_task WHERE task_id=?")) {
                    p.setObject(1, taskId);
                    try (var r = p.executeQuery()) {
                        if (!r.next())
                            throw new SQLException("Original task is absent");
                        if (!Set.of("FINISHING", "ENDED").contains(r.getString(1)) || r.getString(2) == null
                            || r.getObject(3) == null)
                            return Optional.empty();
                        var value = queries.snapshot(taskId);
                        if (!value.blockers().isEmpty())
                            throw new SQLException("Finalization still has explicit resources");
                        var terminal = new TaskSnapshot(value.id(), value.ownerId(), value.ownerVersion(), value.type(),
                            TaskState.ENDED, Optional.of(TaskResult.valueOf(r.getString(2))), value.progressText(),
                            value.startedAt(), Optional.of(Instant.ofEpochMilli(r.getLong(3))), List.of());
                        return Optional.of(finalizationSupport.finalization(c, terminal));
                    }
                }
            });
        }

        /** Reads the actual persisted state and outstanding explicit resource descriptions. / 读取实际持久化状态及尚未释放的显式资源描述。
         * @param taskId task identity / 任务身份
         * @return immutable public snapshot / 不可变公开快照
         * @throws SQLException if the task does not exist or cannot be read / 任务不存在或无法读取时
         */
        public TaskSnapshot snapshot(UUID taskId) throws SQLException {
            return locked(() -> {
                try (var c = connections.open();
                     var p = c.prepareStatement(
                         "SELECT owner_id,owner_version,task_type,state,outcome,progress_text,started_at,ended_at FROM platform_task WHERE task_id=?")) {
                    p.setObject(1, taskId);
                    try (var r = p.executeQuery()) {
                        if (!r.next())
                            throw new SQLException("Task does not exist");
                        var blockers = new ArrayList<String>();
                        try (var leases = c.prepareStatement(
                            "SELECT resource_kind,resource_id FROM platform_task_resource WHERE task_id=? AND released_at IS NULL AND (recovery_json IS NULL OR recovery_json<>?) ORDER BY resource_kind,resource_id")) {
                            leases.setObject(1, taskId);
                            leases.setString(2, ResourceRecovery.TASK_END);
                            try (var rows = leases.executeQuery()) {
                                while (rows.next())
                                    blockers.add(rows.getString(1) + ": " + rows.getString(2));
                            }
                        }
                        if (r.getString(4).equals("UNKNOWN"))
                            blockers.add("Business result requires explicit reconciliation");
                        Long ended = r.getString(4).equals("ENDED") ? r.getObject(8, Long.class) : null;
                        String result = r.getString(4).equals("ENDED") ? r.getString(5) : null;
                        return new TaskSnapshot(taskId, r.getString(1), r.getString(2), r.getString(3),
                            TaskState.valueOf(r.getString(4)),
                            result == null ? Optional.empty() : Optional.of(TaskResult.valueOf(result)), r.getString(6),
                            Instant.ofEpochMilli(r.getLong(7)),
                            ended == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(ended)), blockers);
                    }
                }
            });
        }

        /** Lists execution rows requiring startup recovery or completion of retained archival. / 列出需要启动恢复或完成保留归档的执行行。
         * @return original retained identities / 原保留身份
         * @throws SQLException if storage cannot be read / 存储无法读取时 */
        public List<UUID> retained() throws SQLException {
            try (var c = connections.open();
                 var s = c.createStatement();
                 var r = s.executeQuery("SELECT task_id FROM platform_task ORDER BY started_at,task_id")) {
                var ids = new ArrayList<UUID>();
                while (r.next())
                    ids.add(r.getObject(1, UUID.class));
                return List.copyOf(ids);
            }
        }

        /** Lists retained unfinished identities for host recovery and shutdown checks. / 列出保留的未结束身份，供宿主恢复及关闭检查。
         * @return immutable identities across boot sessions / 跨启动会话的不可变身份列表
         * @throws SQLException if the ledger cannot be read / 无法读取账本时
         */
        public List<UUID> unfinished() throws SQLException {
            try (var c = connections.open();
                 var p = c.prepareStatement(
                     "SELECT task_id FROM platform_task WHERE state<>'ENDED' ORDER BY started_at,task_id");
                 var r = p.executeQuery()) {
                var ids = new ArrayList<UUID>();
                while (r.next())
                    ids.add(r.getObject(1, UUID.class));
                return List.copyOf(ids);
            }
        }

        /** Finds unfinished work owned by or currently borrowing the given Mod. / 查找所属或当前借用指定 Mod 的未结束工作。
         * @param ownerId exact Mod identity across its versions / 跨版本的精确 Mod 身份
         * @return retained task identities including dependency callers / 包含依赖调用方的保留任务身份
         * @throws SQLException if occupancy cannot be read / 无法读取占用时
         */
        public List<UUID> ownedTasks(String ownerId) throws SQLException {
            try (var c = connections.open();
                 var p = c.prepareStatement(
                     "SELECT t.task_id FROM platform_task t WHERE t.state<>'ENDED' AND (t.owner_id=? OR EXISTS(SELECT 1 FROM platform_task_resource r WHERE r.task_id=t.task_id AND r.owner_id=? AND r.released_at IS NULL)) ORDER BY t.started_at,t.task_id")) {
                p.setString(1, ownerId);
                p.setString(2, ownerId);
                try (var r = p.executeQuery()) {
                    var ids = new ArrayList<UUID>();
                    while (r.next())
                        ids.add(r.getObject(1, UUID.class));
                    return List.copyOf(ids);
                }
            }
        }

        /** Checks an exact already-held host resource without acquiring another lock. / 检查精确的已有宿主资源占用，不取得额外资源锁。
         * @param taskId accepted task identity / 已接纳任务身份
         * @param resource required kind, identity and owner / 所需类型、身份及所有者
         * @return whether an unreleased matching claim exists / 是否存在未释放的匹配占用
         * @throws SQLException if the ledger cannot be read / 无法读取账本时
         */
        public boolean holds(UUID taskId, TaskResource resource) throws SQLException {
            try (var c = connections.open();
                 var p = c.prepareStatement(
                     "SELECT COUNT(*) FROM platform_task_resource WHERE task_id=? AND resource_kind=? AND resource_id=? AND owner_id=? AND released_at IS NULL")) {
                p.setObject(1, taskId);
                p.setString(2, resource.kind());
                p.setString(3, resource.id());
                p.setString(4, resource.ownerId());
                try (var r = p.executeQuery()) {
                    r.next();
                    return r.getLong(1) > 0;
                }
            }
        }

        /** Validates admission metadata before execution or recovery. / 执行或恢复前校验准入元数据。
         * @param value original stored document / 原存储文档
         * @param owner original task owner / 原任务所有者
         * @param version original task version / 原任务版本
         * @return validated original document / 已校验原文档
         * @throws SQLException if the shape or identity is inconsistent / 形状或身份不一致时 */
        private com.fasterxml.jackson.databind.JsonNode admissionDocument(String value, String owner, String version)
            throws SQLException {
            try {
                if (value == null || value.length() > 1024 * 1024)
                    throw new IllegalArgumentException("Invalid task metadata size");
                var node = gold.debug.windowstolinux.app.db.task.metadata.TaskCancellationDocument.parse(value);
                if (node.path("formatVersion").intValue() != 1) {
                    var graph = TaskRecoveryGraph.decode(node.path("graph"));
                    if (graph.legacy() != (node.path("formatVersion").intValue() == 2))
                        throw new IllegalArgumentException("Original graph format cannot be downgraded");
                    graph.requireOwner(owner, version,
                        Arrays.asList(json.treeToValue(node.path("initialClaims"), TaskResource[].class)));
                }
                return node;
            } catch (java.io.IOException | IllegalArgumentException invalid) {
                throw new SQLException("Invalid original task admission metadata", invalid);
            }
        }

        /** Reads persisted original cancellation independently of UNKNOWN or nonterminal state. / 独立于UNKNOWN或非终态读取持久原取消。
         * @param taskId exact original task / 精确原任务
         * @return known original cancellation intent / 已知原取消意图
         * @throws SQLException if original metadata is missing or invalid / 原元数据缺失或无效时 */
        public boolean cancellationRequested(UUID taskId) throws SQLException {
            try (var c = connections.open();
                 var p = c.prepareStatement("SELECT state,recovery_json,owner_id,owner_version FROM platform_task WHERE task_id=?")) {
                p.setObject(1, taskId);
                try (var rows = p.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Original task does not exist");
                    var document = admissionDocument(rows.getString(2), rows.getString(3), rows.getString(4));
                    return rows.getString(1).equals("CANCEL_REQUESTED") || document.path("cancelRequested").asBoolean(false);
                }
            }
        }

        /** Tests conflicts without taking any partial resource set. / 检查冲突而不取得部分资源集合。
         * @param c scoped platform connection / 作用域平台连接
         * @param taskId existing task identity / 已有任务身份
         * @param resource desired resource / 期望资源
         * @return whether another active holder conflicts / 是否与其他活动持有者冲突
         * @throws SQLException if the ledger cannot be read / 无法读取账本时
         */
        private boolean conflicts(Connection c, UUID taskId, TaskResource resource) throws SQLException {
            try (var p = c.prepareStatement(
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind=? AND resource_id=? AND released_at IS NULL AND task_id<>? AND (? OR access_mode='EXCLUSIVE')")) {
                p.setString(1, resource.kind());
                p.setString(2, resource.id());
                p.setObject(3, taskId);
                p.setBoolean(4, resource.exclusive());
                try (var r = p.executeQuery()) {
                    r.next();
                    return r.getLong(1) != 0;
                }
            }
        }

        /** Reads one state within an admitted transaction. / 在已准入事务内读取一个状态。
         * @param c scoped platform connection / 作用域平台连接
         * @param taskId task identity / 任务身份
         * @return persisted state / 持久化状态
         * @throws SQLException if the task is absent / 任务不存在时
         */
        private String state(Connection c, UUID taskId) throws SQLException {
            try (var p = c.prepareStatement("SELECT state FROM platform_task WHERE task_id=?")) {
                p.setObject(1, taskId);
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        throw new SQLException("Task does not exist");
                    return r.getString(1);
                }
            }
        }

    }

    /** Owns exact original summary receipts and atomic archival on the same ledger transaction. / 在同一账本事务持有精确原摘要凭据及原子归档。 */
    private final class TaskFinalization {
        /** Creates this ledger/runtime collaborator. / 创建此账本或运行时职责协作者。 */
        private TaskFinalization() {
        }

        /** Stable original platform summary category. / 稳定原平台摘要类别。 */
        private LogStore.Target summaryTarget;

        /** Original host recovery summary category. / 原宿主恢复摘要类别。 */
        private LogStore.Target recoverySummaryTarget;

        /** Shares the original outer commit across conclusion and archive. / 结论及归档共用原最外层提交。
         * @param taskId original identity / 原身份
         * @param conclusion original state transition / 原状态转换
         * @return committed observation / 已提交观察
         * @throws SQLException if original conclusion or archive is unconfirmed / 原结论或归档未确认时 */
        private TaskSnapshot finalizeTransaction(UUID taskId, Query<Boolean> conclusion) throws SQLException {
            var target = finalizationSupport.summaryTarget();
            var recoveryTarget = finalizationSupport.recoverySummaryTarget(queries.snapshot(taskId));
            Finalization[] original = {null};
            try {
                return locked(() -> {
                    boolean ended = conclusion.run();
                    var current = queries.snapshot(taskId);
                    if (ended) {
                        try (var c = connections.open()) {
                            original[0] = finalizationSupport.finalization(c, current);
                            finalizationSupport.archive(c, target, recoveryTarget, original[0]);
                        }
                    }
                    return current;
                });
            } catch (SQLException failure) {
                if (original[0] != null)
                    throw new FinalizationException(original[0], failure);
                throw failure;
            }
        }

        /** Obtains the stable summary category outside the outer task transaction. / 在最外层任务事务之外取得稳定摘要类别。
         * @return verified platform category / 已验证平台类别
         * @throws SQLException if registration cannot be confirmed / 登记无法确认时 */
        private LogStore.Target summaryTarget() throws SQLException {
            synchronized (TaskLedger.this) {
                if (summaryTarget == null)
                    summaryTarget = new LogStore(database).register("app", Optional.empty(),
                        new LogCategory("platform_task", "platform_task", Optional.empty()));
                return summaryTarget;
            }
        }

        /** Registers rescue summaries only for the original host protocol before its task lock. / 仅为原宿主协议在任务锁之前登记救援摘要。
         * @param task original task observation / 原任务观察
         * @return original rescue target, or null for other tasks / 原救援目标，其他任务为空
         * @throws SQLException if original registration cannot be confirmed / 无法确认原登记时 */
        private LogStore.Target recoverySummaryTarget(TaskSnapshot task) throws SQLException {
            synchronized (TaskLedger.this) {
                if (!BrowserRecoveryArchival.owns(task))
                    return null;
                if (recoverySummaryTarget == null)
                    recoverySummaryTarget = new LogStore(database).register("app", Optional.empty(),
                        new LogCategory("browser_recovery", "browser_recovery", Optional.empty()));
                return recoverySummaryTarget;
            }
        }

        /** Captures fixed identity and original diagnostics for an actually ended row. / 为实际结束行捕获固定身份及原诊断。
         * @param c original transaction / 原事务
         * @param snapshot original terminal observation / 原终态观察
         * @return opaque original receipt / 不透明原凭据
         * @throws SQLException if original metadata is inconsistent / 原元数据不一致时 */
        private Finalization finalization(Connection c, TaskSnapshot snapshot) throws SQLException {
            if (snapshot.state() != TaskState.ENDED || snapshot.outcome().isEmpty() || snapshot.endedAt().isEmpty()
                || !snapshot.blockers().isEmpty())
                throw new SQLException("Task is not a fully drained known result");
            try (var p = c.prepareStatement("SELECT archive_log_id,result_json FROM platform_task WHERE task_id=?")) {
                p.setObject(1, snapshot.id());
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        throw new SQLException("Original task disappeared before archival");
                    try {
                        return new Finalization(database.generation(), UUID.fromString(r.getString(1)), snapshot,
                            Objects.requireNonNullElse(r.getString(2), "{}"));
                    } catch (IllegalArgumentException invalid) {
                        throw new SQLException("Invalid original task summary identity", invalid);
                    }
                }
            }
        }

        /** Stores a bounded fixed summary and removes only the fully released original execution. / 保存有界固定摘要，并仅移除完全释放的原执行。
         * @param c original serialized transaction / 原串行事务
         * @param target original platform category / 原平台类别
         * @param recoveryTarget rescue category if applicable / 适用时的救援类别
         * @param original original concluded execution / 原已得出结论执行
         * @throws SQLException if summary, retained ownership or cleanup fails / 摘要、保留归属或清理失败时 */
        private void archive(Connection c, LogStore.Target target, LogStore.Target recoveryTarget,
                             Finalization original) throws SQLException {
            var value = original.snapshot;
            try (var p = c.prepareStatement(
                "SELECT COUNT(*) FROM platform_task_resource WHERE task_id=? AND released_at IS NULL")) {
                p.setObject(1, value.id());
                try (var r = p.executeQuery()) {
                    r.next();
                    if (r.getLong(1) != 0)
                        throw new SQLException("Original task resources are still held");
                }
            }
            var outcome = value.outcome().orElseThrow();
            String details = encode(Map.of("taskType", LogRecordCodec.redact(value.type()), "startedAt",
                value.startedAt().toEpochMilli(), "endedAt", value.endedAt().orElseThrow().toEpochMilli(),
                "progressText", LogRecordCodec.redact(value.progressText()), "result",
                LogRecordCodec.redact(original.evidence)));
            LogRecordCodec.Record record;
            try {
                record = LogRecordCodec.createSummary(target, value.ownerVersion(),
                    new LogEvent(outcome == TaskResult.FAILED ? LogLevel.ERROR : LogLevel.INFO, "PLATFORM_TASK_" + outcome,
                        "Platform task finished: " + outcome, Optional.of(details), Optional.of(value.id()),
                        Optional.empty(), Optional.of(outcome)),
                    original.archive, value.endedAt().orElseThrow().toEpochMilli(),
                    value.ownerId().equals("@platform") ? null : value.ownerId());
            } catch (IllegalArgumentException invalid) {
                throw new SQLException("Original task summary cannot be encoded", invalid);
            }
            new LogStore(database).store(c, target, List.of(record), 100);
            BrowserRecoveryArchival.archive(database, c, recoveryTarget, original.archive, value);
            try (var p = c
                .prepareStatement("DELETE FROM platform_task_resource WHERE task_id=? AND released_at IS NOT NULL")) {
                p.setObject(1, value.id());
                p.executeUpdate();
            }
            try (var p = c
                .prepareStatement("DELETE FROM platform_task WHERE task_id=? AND state='ENDED' AND archive_log_id=?")) {
                p.setObject(1, value.id());
                p.setString(2, original.archive.toString());
                if (p.executeUpdate() != 1)
                    throw new SQLException("Original task cleanup could not be confirmed");
            }
        }

        /** Compares immutable original execution identity without consulting retained log history. / 比较不可变原执行身份，不查询保留日志历史。
         * @param c original transaction / 原事务
         * @param original original receipt / 原凭据
         * @return whether the original execution still exists / 原执行是否仍存在
         * @throws SQLException if an existing identity differs / 已有身份不同时 */
        private boolean matches(Connection c, Finalization original) throws SQLException {
            try (var p = c.prepareStatement(
                "SELECT owner_id,owner_version,task_type,started_at,archive_log_id,outcome,ended_at,result_json FROM platform_task WHERE task_id=?")) {
                p.setObject(1, original.snapshot.id());
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        return false;
                    var value = original.snapshot;
                    if (!value.ownerId().equals(r.getString(1)) || !value.ownerVersion().equals(r.getString(2))
                        || !value.type().equals(r.getString(3)) || value.startedAt().toEpochMilli() != r.getLong(4)
                        || !original.archive.toString().equals(r.getString(5)))
                        throw new SQLException("Original task identity changed");
                    if (r.getObject(7) != null && (!value.outcome().orElseThrow().name().equals(r.getString(6))
                        || value.endedAt().orElseThrow().toEpochMilli() != r.getLong(7)
                        || !original.evidence.equals(Objects.requireNonNullElse(r.getString(8), "{}"))))
                        throw new SQLException("Original task conclusion changed");
                    return true;
                }
            }
        }

        /** Completes logical claims and a known result in the same transaction. / 在同一事务中结束逻辑申请及已知结果。
         * @param c scoped platform connection / 作用域平台连接
         * @param taskId task with no explicit resources remaining / 已无显式资源残留的任务
         * @param outcome known terminal outcome / 已知终态结果
         * @throws SQLException if persistence fails / 持久化失败时
         */
        private void finish(Connection c, UUID taskId, TaskResult outcome) throws SQLException {
            long now = System.currentTimeMillis();
            try (var p = c.prepareStatement(
                "UPDATE platform_task_resource SET released_at=? WHERE task_id=? AND released_at IS NULL AND recovery_json=?")) {
                p.setLong(1, now);
                p.setObject(2, taskId);
                p.setString(3, "{\"release\":\"TASK_END\"}");
                p.executeUpdate();
            }
            try (var p = c.prepareStatement(
                "UPDATE platform_task SET state='ENDED',outcome=?,ended_at=CASE WHEN started_at>? THEN started_at ELSE ? END WHERE task_id=?")) {
                p.setString(1, outcome.name());
                p.setLong(2, now);
                p.setLong(3, now);
                p.setObject(4, taskId);
                p.executeUpdate();
            }
        }

    }
}
