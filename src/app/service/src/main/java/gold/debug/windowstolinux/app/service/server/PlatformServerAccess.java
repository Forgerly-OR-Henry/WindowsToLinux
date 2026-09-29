package gold.debug.windowstolinux.app.service.server;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.persistence.repository.ServerReferenceRepository;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.app.service.task.TaskCapabilityScope;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Exposes task-admitted server observations and exact-generation persistent references. / 提供任务已准入服务器观测及精确代次持久引用。 */
public final class PlatformServerAccess implements ServerService, AutoCloseable {
    /** Current task and callback identity authority. / 当前任务及回调身份授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Authoritative server reference transactions. / 权威服务器引用事务。 */
    private final ServerReferenceRepository repository;

    /** Durable initial resource ownership. / 持久初始资源归属。 */
    private final TaskLedger ledger;

    /** Exact author identity bound by the host. / 宿主绑定的精确作者身份。 */
    private final String owner;

    /** Bound business generation, absent for stateless Mods. / 绑定业务代次，无状态 Mod 不存在。 */
    private final Optional<SchemaIdentity> schema;

    /** Shared task ownership and actual release tracking. / 共享任务归属和实际释放跟踪。 */
    private final TaskCapabilityScope scope;

    /**
     * Binds one activation's server capability without exposing platform repositories. / 绑定一次启用的服务器能力，不暴露平台仓库。
     * @param database current platform engine / 当前平台引擎
     * @param tasks current central task authority / 当前中央任务授权方
     * @param repository shared reference transactions / 共享引用事务
     * @param owner exact activating Mod / 精确正在启用的 Mod
     * @param version actual loaded release / 实际已加载版本
     * @param schema exact selected data generation, if any / 精确选定数据代次，可不存在
     */
    public PlatformServerAccess(PlatformDatabase database, PlatformTaskRuntime tasks,
            ServerReferenceRepository repository, String owner, String version, Optional<SchemaIdentity> schema) {
        this.tasks = Objects.requireNonNull(tasks);
        this.repository = Objects.requireNonNull(repository);
        this.ledger = new TaskLedger(database);
        this.owner = Objects.requireNonNull(owner);
        this.schema = Objects.requireNonNull(schema);
        scope = new TaskCapabilityScope(database, tasks, owner, version, schema, "server-access-");
    }

    /**
     * Lists only the original task's complete admitted server set. / 仅列出原任务完整已准入服务器集合。
     * @param cursor optional task-bound cursor / 可选任务绑定游标
     * @param limit page size from one to two hundred / 一到二百之间的页大小
     * @param task authentic executing task / 真实执行任务
     * @return bounded non-secret observations / 有界非秘密观测
     * @throws ApiException if identity, pagination or storage is unavailable / 身份、分页或存储不可用时
     */
    @Override
    public PageSlice<ServerInfo> list(Optional<String> cursor, int limit, TaskContext task) throws ApiException {
        if (cursor == null || limit < 1 || limit > 200)
            throw error(ProblemCode.INVALID_INPUT, "SERVER_PAGE", "Invalid server page request");
        return access(task, false, false, () -> {
            String after = cursor.isEmpty() ? null : decodeCursor(cursor.orElseThrow(), task.id());
            var values = repository.list(task.id(), tasks.requireContext(task).ownerId(), after, limit + 1);
            boolean more = values.size() > limit;
            var page = more ? values.subList(0, limit) : values;
            return new PageSlice<>(page,
                    more ? Optional.of(encodeCursor(task.id(), page.getLast().ref().id())) : Optional.empty());
        });
    }

    /** Discovers saved server summaries for selection without granting server use. / 发现可供选择的已保存服务器摘要，不授予服务器使用权。
     * @param cursor original discovery-only cursor / 原发现专用游标
     * @param limit bounded page size / 有界页容量
     * @param task authentic current owner task / 真实当前所属任务
     * @return non-secret summaries / 非秘密摘要
     * @throws ApiException if current access, cursor or storage fails / 当前访问、游标或存储失败时 */
    @Override
    public PageSlice<ServerInfo> listConfigured(Optional<String> cursor, int limit, TaskContext task)
            throws ApiException {
        if (cursor == null || limit < 1 || limit > 200)
            throw error(ProblemCode.INVALID_INPUT, "SERVER_PAGE", "Invalid server page request");
        return access(task, false, false, () -> {
            if (tasks.reconciling(task))
                throw error(ProblemCode.CONTRACT_VIOLATION, "SERVER_DISCOVERY_RECOVERY",
                        "Recovery cannot discover new servers");
            String after = cursor.isEmpty() ? null : decodeConfiguredCursor(cursor.orElseThrow(), task.id());
            var values = repository.listConfigured(after, limit + 1);
            boolean more = values.size() > limit;
            var page = more ? values.subList(0, limit) : values;
            return new PageSlice<>(page,
                    more
                            ? Optional.of(encodeConfiguredCursor(task.id(), page.getLast().ref().id()))
                            : Optional.empty());
        });
    }

    /**
     * Reads saved metadata only for an admitted server. / 仅为已准入服务器读取保存元数据。
     * @param server exact selected server / 精确所选服务器
     * @param task authentic executing task / 真实执行任务
     * @return saved observation, not a live probe / 保存观测，不是实时探测
     * @throws ApiException if access or storage is unavailable / 访问或存储不可用时
     */
    @Override
    public ServerInfo get(ServerRef server, TaskContext task) throws ApiException {
        String id = serverId(server);
        return access(task, false, false, () -> {
            requireServer(task, id);
            return repository.get(id);
        });
    }

    /**
     * Reserves a generation-owned association before author SQL commits. / 在作者 SQL 提交前预留代次所属关联。
     * @param server original admitted server / 原始已准入服务器
     * @param businessKey stable author association key / 稳定作者关联键
     * @param task authentic executing task / 真实执行任务
     * @return original idempotent reservation / 原始幂等预留
     * @throws ApiException if input, data generation or persistence is unavailable / 输入、数据代次或持久化不可用时
     */
    @Override
    public ServerReference reserve(ServerRef server, String businessKey, TaskContext task) throws ApiException {
        String id = serverId(server);
        if (businessKey == null || businessKey.isBlank() || businessKey.length() > 512)
            throw error(ProblemCode.INVALID_INPUT, "SERVER_REFERENCE_KEY", "Invalid server business key");
        var identity = requireSchema();
        return access(task, false, true, () -> {
            requireServer(task, id);
            return repository.reserve(identity, id, businessKey);
        });
    }

    /**
     * Reads an exact owned association during execution or original-task recovery without replaying reserve. / 执行或原任务恢复时读取精确所属关联，不重放预留。
     * @param server original resource identity / 原资源身份
     * @param businessKey exact original business key / 精确原业务键
     * @param task authentic executing or reconciling task / 真实执行或核对任务
     * @return current reference or absence, without proving a business outcome / 当前引用或空值，不证明业务结果
     * @throws ApiException if input, authorization or reading fails / 输入、授权或读取失败时
     */
    @Override
    public Optional<ServerReference> findReference(ServerRef server, String businessKey, TaskContext task)
            throws ApiException {
        String id = serverId(server);
        if (businessKey == null || businessKey.isBlank() || businessKey.length() > 512)
            throw error(ProblemCode.INVALID_INPUT, "SERVER_REFERENCE_KEY", "Invalid server business key");
        var identity = requireSchema();
        return access(task, true, false, () -> {
            requireServer(task, id);
            return repository.find(identity, id, businessKey);
        });
    }

    /**
     * Resolves only an existing original-generation association, including cancellation cleanup. / 仅核对已有原代次关联，包括取消收尾。
     * @param referenceId original reservation identity / 原始预留身份
     * @param result author's confirmed association result / 作者确认的关联结果
     * @param task authentic executing or reconciling task / 真实执行或核对任务
     * @throws ApiException if authorization, identity or persistence fails / 授权、身份或持久化失败时
     */
    @Override
    public void resolve(UUID referenceId, ReferenceResolution result, TaskContext task) throws ApiException {
        if (referenceId == null || result == null)
            throw error(ProblemCode.INVALID_INPUT, "SERVER_REFERENCE_RESULT",
                    "A reference and resolution are required");
        var identity = requireSchema();
        access(task, true, true, () -> {
            var reference = repository.find(identity, referenceId);
            if (reference.isPresent())
                requireServer(task, reference.orElseThrow().server().id());
            repository.resolve(identity, referenceId, result);
            return null;
        });
    }

    /**
     * Holds an explicit resource until all scoped platform connections return. / 保持显式资源直至所有限定平台连接返回。
     * @param task authentic original task / 真实原任务
     * @param cleanup whether only an existing association is being reconciled / 是否仅核对已有关联
     * @param writes whether failed persistence can leave a reference result unknown / 持久化失败是否可能留下未知引用结果
     * @param work scoped platform access / 限定平台访问
     * @param <T> public result type / 公开结果类型
     * @return copied platform result / 复制的平台结果
     * @throws ApiException if admission, access or durable cleanup fails / 准入、访问或持久清理失败时
     */
    private <T> T access(TaskContext task, boolean cleanup, boolean writes, TaskCapabilityScope.Work<T> work)
            throws ApiException {
        try {
            return scope.call(task, cleanup, () -> {
                if (writes && !cleanup && tasks.reconciling(task))
                    throw error(ProblemCode.CONTRACT_VIOLATION, "SERVER_REFERENCE_RECOVERY_WRITE",
                            "Recovery cannot create new reference reservations");
                try {
                    return work.run();
                } catch (SQLException failure) {
                    String state = failure.getSQLState();
                    if ("42501".equals(state))
                        throw error(ProblemCode.ACCESS_DENIED, "SERVER_REFERENCE_OWNER",
                                "The reference belongs to another generation");
                    if ("55000".equals(state))
                        throw error(ProblemCode.UNAVAILABLE, "SERVER_REFERENCE_SCHEMA",
                                "The data generation is unavailable");
                    if ("02000".equals(state))
                        throw error(ProblemCode.NOT_FOUND, "SERVER_REFERENCE_MISSING",
                                "The requested reference or server is unavailable");
                    if (writes)
                        tasks.reportUncertainEffect(task);
                    throw error(writes ? ProblemCode.UNKNOWN : ProblemCode.IO_FAILURE, "SERVER_REFERENCE_STORAGE",
                            writes
                                    ? "Server reference persistence requires reconciliation"
                                    : "Server observations could not be read");
                }
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "SERVER_ACCESS_CLAIM", "Server capability admission could not be read");
        }
    }

    /**
     * Retries only physically returned operations during explicit original-task recovery. / 在显式原任务恢复期间仅重试物理已返回的操作。
     * @param task original retained task / 原始保留任务
     * @throws ApiException if a release still cannot persist / 仍无法持久化释放时
     */
    public void retryFinishedReleases(TaskContext task) throws ApiException {
        scope.retryFinishedReleases(task);
    }

    /**
     * Seals new access and releases only completed operations. / 封闭新访问并仅释放已完成操作。
     * @throws ApiException if actual operations or unrecorded releases remain / 仍有实际操作或未记录释放时
     */
    @Override
    public void close() throws ApiException {
        scope.close();
    }

    /** Returns the selected data identity or rejects persistent references for stateless Mods. / 返回选定数据身份，或拒绝无状态 Mod 的持久引用。
     * @return exact selected identity / 精确选定身份
     * @throws ApiException if the Mod has no business schema / Mod 没有业务数据结构时 */
    private SchemaIdentity requireSchema() throws ApiException {
        if (schema.isEmpty())
            throw error(ProblemCode.UNAVAILABLE, "SERVER_REFERENCE_NO_SCHEMA",
                    "Persistent references require a business schema");
        return schema.orElseThrow();
    }

    /**
     * Requires an already held server even while cleaning up after cancellation. / 即使取消后收尾也要求服务器已经持有。
     * @param task original task / 原任务
     * @param id exact server / 精确服务器
     * @throws SQLException if durable admission cannot be read / 无法读取持久准入时
     * @throws ApiException if this server was not initially admitted / 此服务器未初始准入时
     */
    private void requireServer(TaskContext task, String id) throws SQLException, ApiException {
        if (!ledger.queries().holds(task.id(), new TaskResource("SERVER", id, tasks.requireContext(task).ownerId(), true)))
            throw error(ProblemCode.ACCESS_DENIED, "SERVER_NOT_ADMITTED", "The server was not admitted with this task");
    }

    /**
     * Validates an exact server identity without changing it. / 校验精确服务器身份，不改变其值。
     * @param server requested server / 请求服务器
     * @return exact identity / 精确身份
     * @throws ApiException if input is invalid / 输入无效时
     */
    private static String serverId(ServerRef server) throws ApiException {
        if (server == null || server.id().isBlank() || server.id().length() > 512)
            throw error(ProblemCode.INVALID_INPUT, "SERVER_ID", "Invalid server identity");
        return server.id();
    }

    /**
     * Encodes one opaque task-bound continuation. / 编码一个不透明任务绑定续页。
     * @param task original task / 原始任务
     * @param id last returned server / 最后返回的服务器
     * @return opaque continuation / 不透明续页
     */
    private static String encodeCursor(UUID task, String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((task + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Rejects malformed or other-task cursors before reading configuration. / 读取配置前拒绝畸形或其他任务游标。
     * @param encoded opaque continuation / 不透明续页
     * @param task original task / 原始任务
     * @return exclusive server boundary / 不包含的服务器边界
     * @throws ApiException if the cursor is invalid / 游标无效时
     */
    private static String decodeCursor(String encoded, UUID task) throws ApiException {
        try {
            if (encoded.isEmpty() || encoded.length() > 4096)
                throw new IllegalArgumentException();
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))).toString();
            String prefix = task + ":";
            if (!decoded.startsWith(prefix) || decoded.length() <= prefix.length())
                throw new IllegalArgumentException();
            String id = decoded.substring(prefix.length());
            if (id.isBlank() || id.length() > 512)
                throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw error(ProblemCode.INVALID_INPUT, "SERVER_CURSOR", "Invalid server continuation cursor");
        }
    }

    /** Encodes a discovery cursor distinct from admitted-server cursors. / 编码区别于已准入服务器游标的发现游标。
     * @param task original lookup task / 原查询任务
     * @param id last returned server / 最后一项服务器
     * @return discovery-only cursor / 发现专用游标 */
    private static String encodeConfiguredCursor(UUID task, String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("configured:" + task + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes only the original task's discovery continuation. / 只解析原任务的发现续页。
     * @param encoded opaque cursor / 不透明游标
     * @param task original lookup task / 原查询任务
     * @return exclusive server boundary / 不含的服务器边界
     * @throws ApiException if task or cursor kind differs / 任务或游标类别不符时 */
    private static String decodeConfiguredCursor(String encoded, UUID task) throws ApiException {
        try {
            if (encoded.isEmpty() || encoded.length() > 4096)
                throw new IllegalArgumentException();
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))).toString();
            String prefix = "configured:" + task + ":";
            if (!decoded.startsWith(prefix) || decoded.length() <= prefix.length())
                throw new IllegalArgumentException();
            String id = decoded.substring(prefix.length());
            if (id.isBlank() || id.length() > 512)
                throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw error(ProblemCode.INVALID_INPUT, "SERVER_CURSOR", "Invalid server continuation cursor");
        }
    }

    /**
     * Builds a safe public failure without database exception text. / 构造不含数据库异常文本的安全公开失败。
     * @param code public category / 公开类别
     * @param event stable diagnostic identity / 稳定诊断身份
     * @param message non-secret explanation / 非秘密说明
     * @return public failure / 公开失败
     */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

}
