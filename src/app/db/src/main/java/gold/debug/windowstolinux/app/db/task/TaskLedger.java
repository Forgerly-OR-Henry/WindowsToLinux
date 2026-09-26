package gold.debug.windowstolinux.app.db.task;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.appapi.task.*;

/** Persists atomic admission and actual resource release independently of business SQL. / 独立于业务 SQL 持久化原子准入及实际资源释放。 */
public final class TaskLedger {
    /** Engine boot identity used to refuse execution of previous-process tasks. / 用于拒绝执行前一进程任务的引擎启动身份。 */
    private final UUID bootId;

    /** Scoped transactions sharing a single platform connection. / 共享单个平台连接的作用域事务。 */
    private final RepositoryConnections connections;

    /** Fixed internal JSON encoding, never arbitrary business serialization. / 固定内部 JSON 编码，不序列化任意业务对象。 */
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Binds the ledger to the exclusive engine owner. / 将账本绑定到引擎独占所有者。
     * @param database current platform engine / 当前平台引擎
     */
    public TaskLedger(PlatformDatabase database) {
        bootId = database.bootId();
        connections = new RepositoryConnections(database::openConnection);
    }

    /**
     * Writes a queued task and its complete initial claims before author code runs. / 作者代码运行前写入排队任务及完整初始申请。
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

    /**
     * Validates live platform state and queues work within one serialized transaction. / 在同一串行事务内校验平台实时状态并将工作入队。
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
                    p.setString(8, encode(Map.of("formatVersion", 1, "initialClaims", ordered)));
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
        /**
         * Validates state before any task row is written. / 在写入任何任务行之前校验状态。
         * @param connection current serialized platform transaction / 当前串行平台事务
         * @return complete initial claims / 完整初始申请
         * @throws SQLException if validation or queries fail / 校验或查询失败时
         */
        List<TaskResource> resolve(Connection connection) throws SQLException;
    }

    /**
     * Prevents endpoint changes for accepted work, including queued and uncertain tasks. / 防止修改已接纳工作使用的端点，包括排队及未知任务。
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

    /**
     * Sorts and rejects duplicate claims in the required global order. / 按要求的全局顺序排序并拒绝重复申请。
     * @param claims complete claim set / 完整申请集合
     * @return immutable ordered claims / 不可变有序申请
     * @throws SQLException if a resource appears twice / 同一资源重复出现时
     */
    private List<TaskResource> orderedClaims(List<TaskResource> claims) throws SQLException {
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

    /**
     * Starts only when every persisted initial claim can be acquired together. / 仅在所有持久化初始申请可整体取得时启动。
     * @param taskId current-boot queued task / 当前启动会话的排队任务
     * @return true when all claims and RUNNING committed together / 全部申请与 RUNNING 一同提交时为真
     * @throws SQLException if the task or its recovery material is invalid / 任务或恢复材料无效时
     */
    public boolean tryStart(UUID taskId) throws SQLException {
        return locked(() -> {
            try (var c = connections.open();
                    var p = c.prepareStatement(
                            "SELECT state,boot_id,recovery_json FROM platform_task WHERE task_id=?")) {
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
                        var node = json.readTree(r.getString(3));
                        if (node.path("formatVersion").asInt() != 1)
                            throw new SQLException("Unsupported task recovery format");
                        claims = orderedClaims(
                                Arrays.asList(json.treeToValue(node.required("initialClaims"), TaskResource[].class)));
                    } catch (java.io.IOException | IllegalArgumentException failure) {
                        throw new SQLException("Invalid task recovery material", failure);
                    }
                    for (var claim : claims)
                        if (conflicts(c, taskId, claim))
                            return false;
                    for (var claim : claims)
                        insertLease(c, taskId, claim, ResourceRecovery.TASK_END);
                }
                setState(c, taskId, "RUNNING");
                return true;
            }
        });
    }

    /**
     * Tests conflicts without taking any partial resource set. / 检查冲突而不取得部分资源集合。
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

    /**
     * Appends an explicit resource whose actual close must be reported separately. / 追加必须单独报告实际关闭的显式资源。
     * @param taskId currently executing task / 正在执行的任务
     * @param resource host-bound resource identity / 宿主绑定的资源身份
     * @return retained lease identity / 保留的占用身份
     * @throws SQLException if the task no longer executes or a conflict exists / 任务不再执行或存在冲突时
     */
    public UUID retain(UUID taskId, TaskResource resource) throws SQLException {
        return retain(taskId, resource, null);
    }

    /** Retains a physical resource and its recovery facts in the same transaction. / 在同一事务中保留物理资源及其恢复事实。
     * @param taskId original executing task / 原执行任务
     * @param resource exact owned resource / 精确所属资源
     * @param recovery optional host recovery facts / 可选宿主恢复事实
     * @return durable lease identity / 持久占用身份
     * @throws SQLException if ownership, metadata or persistence fails / 归属、元数据或持久化失败时 */
    public UUID retain(UUID taskId, TaskResource resource, ResourceRecovery recovery) throws SQLException {
        String metadata = recovery == null ? ResourceRecovery.EXPLICIT : recovery.encode();
        return locked(() -> {
            try (var c = connections.open()) {
                String state = state(c, taskId);
                if (!Set.of("RUNNING", "CANCEL_REQUESTED", "UNKNOWN", "FINISHING").contains(state))
                    throw new SQLException("Task cannot acquire another owned resource");
                if (resource.kind().equals("SERVER"))
                    throw new SQLException("Servers must be declared in initial admission");
                if (conflicts(c, taskId, resource))
                    throw new SQLException("Resource is busy");
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

    /**
     * Inserts a claim within the already serialized transaction. / 在已串行化的事务内写入申请。
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

    /** Records the first actual PID and start time before any process HELLO is sent. / 发送任何进程 HELLO 前记录首个实际 PID 及启动时间。
     * @param taskId original task / 原任务
     * @param resource exact retained process / 精确保留进程
     * @param before original pre-creation checkpoint / 原创建前检查点
     * @param after observed physical identity / 已观察物理身份
     * @throws SQLException if identity, phase or commit cannot be confirmed / 无法确认身份、阶段或提交时 */
    public void checkpointProcess(UUID taskId, TaskResource resource, ProcessRecovery before, ProcessRecovery after)
            throws SQLException {
        if (!resource.kind().equals("PROCESS") || !resource.id().equals("process-v1:" + before.process())
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
                if (original.equals(after.encode()))
                    return null;
                if (!original.equals(before.encode()))
                    throw new SQLException("Process checkpoint differs from original creation");
                try (var update = c.prepareStatement(
                        "UPDATE platform_task_resource SET recovery_json=? WHERE lease_id=? AND task_id=? AND released_at IS NULL")) {
                    update.setString(1, after.encode().encode());
                    update.setObject(2, lease);
                    update.setObject(3, taskId);
                    if (update.executeUpdate() != 1)
                        throw new SQLException("Process checkpoint no longer owned");
                }
            }
            return null;
        });
    }

    /**
     * Records a verified physical release without releasing another task's resources. / 记录已核实的物理释放，不释放其他任务资源。
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
                        if (!row.next() || !owner.equals(row.getString(2)) || !version.equals(row.getString(3)))
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
                            if (!id.startsWith("ssh-"))
                                continue;
                            var facts = ResourceRecovery.decode(rows.getString(4));
                            if (!owner.equals(rows.getString(3)) || !facts.protocol().equals("ssh.v1")
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

    /**
     * Persists cooperative cancellation without inventing termination. / 持久化协作取消，不虚构任务终止。
     * @param taskId selected task / 选中的任务
     * @return true when queued work was cancelled before it could run / 排队工作在执行前被取消时为真
     * @throws SQLException if cancellation is unsupported or persistence fails / 不支持取消或持久化失败时
     */
    public boolean cancel(UUID taskId) throws SQLException {
        return cancel(taskId, () -> {
        });
    }

    /**
     * Releases host authorization before queued cancellation becomes terminal. / 排队取消成为终态前释放宿主授权。
     * @param taskId accepted task identity / 已接纳任务身份
     * @param beforeEnd nonblocking host cleanup after resource checks, without database reentry / 资源检查后的非阻塞宿主清理，不重入数据库
     * @return whether queued cancellation was durably completed / 排队取消是否已持久化完成
     * @throws SQLException if cancellation or persistence fails / 取消或持久化失败时
     */
    public boolean cancel(UUID taskId, Runnable beforeEnd) throws SQLException {
        Objects.requireNonNull(beforeEnd);
        return locked(() -> {
            try (var c = connections.open();
                    var p = c.prepareStatement("SELECT state,cancellable FROM platform_task WHERE task_id=?")) {
                p.setObject(1, taskId);
                try (var r = p.executeQuery()) {
                    if (!r.next())
                        throw new SQLException("Task does not exist");
                    String state = r.getString(1);
                    if (state.equals("ENDED"))
                        return false;
                    if (!r.getBoolean(2))
                        throw new SQLException("Task does not support cancellation");
                    if (state.equals("QUEUED")) {
                        beforeEnd.run();
                        finish(c, taskId, Outcome.CANCELLED);
                        return true;
                    }
                    if (state.equals("RUNNING"))
                        setState(c, taskId, "CANCEL_REQUESTED");
                    return false;
                }
            }
        });
    }

    /**
     * Records an observed result, ending only after all explicit resources were released. / 记录观察结果，仅在全部显式资源释放后结束。
     * @param taskId existing accepted task / 已接纳任务
     * @param outcome observed business conclusion / 已观察的业务结论
     * @param resultJson bounded non-secret result evidence / 有界且不含秘密的结果证据
     * @param reconciliation whether explicit recovery is allowed to resolve UNKNOWN / 显式恢复是否允许核实 UNKNOWN
     * @return true only after durable terminal completion / 仅在持久化终态完成后为真
     * @throws SQLException if transition or persistence fails / 状态转换或持久化失败时
     */
    public boolean complete(UUID taskId, Outcome outcome, String resultJson, boolean reconciliation)
            throws SQLException {
        return complete(taskId, outcome, resultJson, reconciliation, () -> {
        });
    }

    /**
     * Finalizes host memory resources after explicit cleanup and before durable task end. / 显式清理后、持久化任务结束前收尾宿主内存资源。
     * @param taskId accepted original identity / 已接纳原身份
     * @param outcome observed business result / 已观察业务结果
     * @param resultJson bounded diagnostic evidence / 有界诊断证据
     * @param reconciliation whether original uncertainty is explicitly resolved / 是否显式核实原不确定性
     * @param beforeEnd nonblocking host cleanup without database reentry / 不重入数据库的非阻塞宿主清理
     * @return whether all resources and the known outcome reached terminal state / 全部资源及已知结果是否到达终态
     * @throws SQLException if state or persistence fails / 状态或持久化失败时
     */
    public boolean complete(UUID taskId, Outcome outcome, String resultJson, boolean reconciliation, Runnable beforeEnd)
            throws SQLException {
        Objects.requireNonNull(beforeEnd);
        if (resultJson.length() > 65536)
            throw new SQLException("Task result evidence is too large");
        return locked(() -> {
            try (var c = connections.open()) {
                String state = state(c, taskId);
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
                if (state.equals("QUEUED") || state.equals("UNKNOWN") && !reconciliation)
                    throw new SQLException("Task cannot complete without explicit reconciliation");
                try (var p = c.prepareStatement("UPDATE platform_task SET result_json=? WHERE task_id=?")) {
                    p.setString(1, resultJson);
                    p.setObject(2, taskId);
                    p.executeUpdate();
                }
                if (outcome == Outcome.UNKNOWN) {
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
                finish(c, taskId, outcome);
                return true;
            }
        });
    }

    /**
     * Completes logical claims and a known result in the same transaction. / 在同一事务中结束逻辑申请及已知结果。
     * @param c scoped platform connection / 作用域平台连接
     * @param taskId task with no explicit resources remaining / 已无显式资源残留的任务
     * @param outcome known terminal outcome / 已知终态结果
     * @throws SQLException if persistence fails / 持久化失败时
     */
    private void finish(Connection c, UUID taskId, Outcome outcome) throws SQLException {
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

    /**
     * Updates bounded progress for a task that has not ended. / 为尚未结束的任务更新有界进度。
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

    /**
     * Reads the actual persisted state and outstanding explicit resource descriptions. / 读取实际持久化状态及尚未释放的显式资源描述。
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
                    Long ended = r.getObject(8, Long.class);
                    String result = r.getString(5);
                    return new TaskSnapshot(taskId, r.getString(1), r.getString(2), r.getString(3),
                            TaskState.valueOf(r.getString(4)),
                            result == null ? Optional.empty() : Optional.of(Outcome.valueOf(result)), r.getString(6),
                            Instant.ofEpochMilli(r.getLong(7)),
                            ended == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(ended)), blockers);
                }
            }
        });
    }

    /**
     * Lists retained unfinished identities for host recovery and shutdown checks. / 列出保留的未结束身份，供宿主恢复及关闭检查。
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

    /**
     * Finds unfinished work owned by or currently borrowing the given Mod. / 查找所属或当前借用指定 Mod 的未结束工作。
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

    /**
     * Checks an exact already-held host resource without acquiring another lock. / 检查精确的已有宿主资源占用，不取得额外资源锁。
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

    /**
     * Retains an uncertain transition without releasing any resource or replaying work. / 保留不确定的状态转换，不释放资源或重放工作。
     * @param taskId possibly accepted task / 可能已经接纳的任务
     * @throws SQLException if uncertainty itself cannot be persisted / 不确定状态本身无法持久化时
     */
    public void uncertain(UUID taskId) throws SQLException {
        locked(() -> {
            try (var c = connections.open()) {
                if (!state(c, taskId).equals("ENDED"))
                    setState(c, taskId, "UNKNOWN");
            }
            return null;
        });
    }

    /**
     * Reads one state within an admitted transaction. / 在已准入事务内读取一个状态。
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

    /**
     * Writes a nonterminal state without assigning an outcome. / 写入非终态状态，不分配终态结果。
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

    /**
     * Serializes admission and release across all ledger instances using the platform row. / 利用平台行在全部账本实例间串行化准入与释放。
     * @param <T> operation result type / 操作结果类型
     * @param work operation sharing the locked transaction / 共享锁定事务的操作
     * @return committed operation result / 已提交操作结果
     * @throws SQLException if locking, work or commit fails / 加锁、操作或提交失败时
     */
    private <T> T locked(Query<T> work) throws SQLException {
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        connections.transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
            }
            result.set(work.run());
        });
        return result.get();
    }

    /**
     * Internal transaction work returning a result only after commit. / 仅在提交后返回结果的内部事务工作。
     * @param <T> committed result type / 已提交结果类型
     */
    @FunctionalInterface
    private interface Query<T> {
        /**
         * Executes within the already locked transaction. / 在已锁定事务内执行。
         * @return pending transaction result / 待提交事务结果
         * @throws SQLException if persistence fails / 持久化失败时
         */
        T run() throws SQLException;
    }

    /**
     * Encodes only host-defined task metadata. / 仅编码宿主定义的任务元数据。
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
}
