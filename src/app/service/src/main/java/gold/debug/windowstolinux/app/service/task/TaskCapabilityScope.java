package gold.debug.windowstolinux.app.service.task;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.ResourceLease;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import gold.debug.windowstolinux.appapi.db.SchemaIdentity;


/** Tracks synchronous platform capability work until its physical and durable release. / 跟踪同步平台能力操作，直至物理和持久释放完成。 */
public final class TaskCapabilityScope implements AutoCloseable {
    /** Current task authority. / 当前任务授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Initial resource claims. / 初始资源占用。 */
    private final TaskLedger ledger;

    /** Exact capability owner. / 精确能力所有者。 */
    private final String owner;

    /** Loaded release bound by the host. / 宿主绑定的已加载版本。 */
    private final String version;

    /** Selected business generation, when present. / 可选的业务数据代次。 */
    private final Optional<SchemaIdentity> schema;

    /** Host-chosen operation resource prefix. / 宿主选择的操作资源前缀。 */
    private final String resourcePrefix;

    /** Physically completed operations awaiting durable release. / 等待持久释放的物理已完成操作。 */
    private final Map<UUID, PendingRelease> pending = new HashMap<>();

    /** Number of physically active calls. / 物理活动调用数。 */
    private int active;

    /** Whether owner teardown has sealed new calls. / 所有者拆除是否封闭新调用。 */
    private boolean closed;

    /**
     * Binds one activation without opening connections. / 绑定一次启用，不打开连接。
     * @param database current engine / 当前引擎
     * @param tasks current task authority / 当前任务授权方
     * @param owner exact Mod owner / 精确 Mod 所有者
     * @param version loaded release / 已加载版本
     * @param schema selected optional generation / 选定的可选代次
     * @param resourcePrefix host-defined resource prefix / 宿主定义的资源前缀
     */
    public TaskCapabilityScope(PlatformDatabase database, PlatformTaskRuntime tasks, String owner, String version,
                               Optional<SchemaIdentity> schema, String resourcePrefix) {
        this.tasks = Objects.requireNonNull(tasks);
        ledger = new TaskLedger(database);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        this.schema = Objects.requireNonNull(schema);
        this.resourcePrefix = Objects.requireNonNull(resourcePrefix);
        if (schema.isPresent() && !owner.equals(schema.orElseThrow().ownerId()))
            throw new IllegalArgumentException("Capability schema belongs to another Mod");
    }

    /**
     * Holds a resource through synchronous work and retains failed durable cleanup. / 在同步操作期间持有资源，并保留失败的持久清理。
     * @param task authentic executing task / 真实执行任务
     * @param cleanup whether the call only reconciles existing ownership / 是否仅核对已有归属
     * @param work synchronous platform work / 同步平台操作
     * @param <T> copied result type / 复制的结果类型
     * @return platform result / 平台结果
     * @throws ApiException if authorization or release fails / 授权或释放失败时
     * @throws SQLException if scoped storage fails / 限定存储失败时
     */
    public <T> T call(TaskContext task, boolean cleanup, Work<T> work) throws ApiException, SQLException {
        worker();
        if (cleanup)
            tasks.requireCleanup(task, owner, version);
        else
            tasks.requireExecuting(task, owner, version);
        synchronized (this) {
            if (closed)
                throw error(ProblemCode.UNAVAILABLE, "CAPABILITY_CLOSED", "The capability is closed");
            active++;
        }
        ResourceLease lease;
        try {
            lease = tasks.retain(task, new TaskResource("CUSTOM", resourcePrefix + UUID.randomUUID(), owner, false));
        } catch (ApiException | RuntimeException failure) {
            synchronized (this) {
                active--;
            }
            throw failure;
        }
        try {
            if (schema.isPresent() && !ledger.queries().holds(task.id(),
                new TaskResource("SCHEMA", schema.orElseThrow().generation().toString(), owner, false)))
                throw error(ProblemCode.ACCESS_DENIED, "CAPABILITY_SCHEMA",
                    "The task does not hold this schema generation");
            return work.run();
        } finally {
            try {
                lease.close();
            } catch (ApiException failure) {
                synchronized (this) {
                    pending.put(lease.id(), new PendingRelease(task, lease));
                }
                throw failure;
            } finally {
                synchronized (this) {
                    active--;
                }
            }
        }
    }

    /**
     * Retries only releases whose physical work has already ended. / 仅重试物理操作已经结束的释放。
     * @param task authentic original task / 真实原任务
     * @throws ApiException if authentication or persistence fails / 认证或持久化失败时
     */
    public void retryFinishedReleases(TaskContext task) throws ApiException {
        worker();
        tasks.requireCleanup(task, owner, version);
        List<PendingRelease> releases;
        synchronized (this) {
            releases = List.copyOf(pending.values());
        }
        for (var entry : releases) {
            if (entry.task() != task)
                continue;
            entry.lease().close();
            synchronized (this) {
                pending.remove(entry.lease().id());
            }
        }
    }

    /**
     * Seals admission and releases only completed operations. / 封闭准入并仅释放已完成操作。
     * @throws ApiException if active work or failed releases remain / 仍有活动操作或失败释放时
     */
    @Override
    public void close() throws ApiException {
        worker();
        List<PendingRelease> releases;
        synchronized (this) {
            closed = true;
            if (active != 0)
                throw error(ProblemCode.BUSY, "CAPABILITY_BUSY", "Capability operations are still running");
            releases = List.copyOf(pending.values());
        }
        for (var entry : releases) {
            entry.lease().close();
            synchronized (this) {
                pending.remove(entry.lease().id());
            }
        }
    }

    /** Rejects event-thread storage access. / 拒绝事件线程存储访问。
     * @throws ApiException if called on the event thread / 从事件线程调用时 */
    private static void worker() throws ApiException {
        if (javax.swing.SwingUtilities.isEventDispatchThread())
            throw error(ProblemCode.CONTRACT_VIOLATION, "CAPABILITY_BACKGROUND",
                "Capability access requires a background thread");
    }

    /** Builds a non-secret public failure. / 构造非秘密公开失败。
     * @param code public category / 公开类别
     * @param event stable diagnostic ID / 稳定诊断标识
     * @param message safe explanation / 安全说明
     * @return public failure / 公开失败 */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Completed work awaiting durable release. / 等待持久释放的已完成操作。
     * @param task original task object / 原任务对象
     * @param lease original resource lease / 原资源占用 */
    private record PendingRelease(TaskContext task, ResourceLease lease) {
    }

    /** Synchronous work that returns after physical access ends. / 物理访问结束后才返回的同步操作。
     * @param <T> copied result type / 复制的结果类型 */
    @FunctionalInterface
    public interface Work<T> {
        /** Runs the platform operation. / 运行平台操作。
         * @return copied result / 复制的结果
         * @throws ApiException if the contract fails / 契约失败时
         * @throws SQLException if storage fails / 存储失败时 */
        T run() throws ApiException, SQLException;
    }
}
