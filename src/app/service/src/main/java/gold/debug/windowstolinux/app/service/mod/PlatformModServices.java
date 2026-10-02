package gold.debug.windowstolinux.app.service.mod;

import gold.debug.windowstolinux.app.db.task.DependencyRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskRequest;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;

import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.mod.service.ServiceSnapshot;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.mod.*;


/** Shared actual service registry, with activation publication and per-call task ownership. / 具有启用发布及逐调用任务归属的共用实际服务登记器。 */
public final class PlatformModServices implements AutoCloseable {
    /** Current authoritative database. / 当前权威数据库。 */
    private final PlatformDatabase database;

    /** Central admission and physical ownership. / 中央准入及物理归属。 */
    private final PlatformTaskRuntime tasks;

    /** Separate normal and recovery graphs. / 分离的普通及恢复图。 */
    private final Map<Key, Binding> bindings = new HashMap<>();

    /** Whether new graphs are sealed. / 是否封闭新图。 */
    private boolean closed;

    /** Binds the actual platform owners without discovering or starting author code. / 绑定实际平台所有者，不发现或启动作者代码。
     * @param database actual engine / 实际引擎
     * @param tasks central runtime / 中央运行时 */
    public PlatformModServices(PlatformDatabase database, PlatformTaskRuntime tasks) {
        this.database = Objects.requireNonNull(database);
        this.tasks = Objects.requireNonNull(tasks);
    }

    /** Prepares one exact loaded activation's staged service contribution. / 准备一个精确加载启用的暂存服务扩展。
     * @param loader actual owning loader / 实际所属加载器
     * @param schema selected data generation / 选定数据代次
     * @param mode actual launch mode / 实际启动模式
     * @return original staged contribution / 原暂存扩展 */
    public synchronized Binding bind(ModClassLoader loader, Optional<SchemaIdentity> schema, ModLaunchMode mode) {
        if (closed)
            throw new IllegalStateException("Service registry is closing");
        var binding = new Binding(loader, schema, mode);
        var key = new Key(binding.owner(), mode);
        if (bindings.putIfAbsent(key, binding) != null)
            throw new IllegalStateException("This service activation already exists");
        return binding;
    }

    /** Closes actual contributions without discarding active calls. / 关闭实际扩展，不丢弃活动调用。
     * @throws ApiException if a call remains active / 调用仍活动时 */
    @Override
    public void close() throws ApiException {
        List<Binding> originals;
        synchronized (this) {
            closed = true;
            originals = List.copyOf(bindings.values());
        }
        for (var binding : originals)
            binding.close();
    }

    /** Host-owned cleanup within an authenticated provider recovery scope. / 在已认证提供方恢复作用域内执行的宿主收尾。 */
    @FunctionalInterface
    public interface RecoveryCleanup {
        /** Closes only this provider's actual resources for the original task. / 仅关闭此提供方对原任务持有的实际资源。
         * @param task exact original task context / 精确原任务上下文
         * @throws ApiException if actual release is not confirmed / 实际释放未确认时 */
        void close(TaskContext task) throws ApiException;
    }

    /** One loaded graph node providing the public author service interface. / 提供公开作者服务接口的单个已加载图节点。 */
    public final class Binding implements ModServices, ModHostLease.Contribution {
        /** Actual loader whose shared Class identities are authoritative. / 共享 Class 身份具有权威性的实际加载器。 */
        private final ModClassLoader loader;

        /** Exact selected business generation. / 精确选定业务代次。 */
        private final Optional<SchemaIdentity> schema;

        /** Actual graph mode. / 实际图模式。 */
        private final ModLaunchMode mode;

        /** Original registered implementations never directly exposed to callers. / 从不直接暴露给调用方的原登记实现。 */
        private final Map<Class<?>, Registration<?>> registrations = new HashMap<>();

        /** Original discovery handles owned by this activation. / 此启用持有的原发现句柄。 */
        private final Set<Handle<?>> handles = Collections.newSetFromMap(new IdentityHashMap<>());

        /** Cleanup captured from this activation's actual host context before publication. / 发布前从此启用实际宿主上下文捕获的收尾。 */
        private RecoveryCleanup recoveryCleanup;

        /** Publication and permanent admission state. / 发布及永久准入状态。 */
        private boolean published, stopping, detached;

        /** Captures exact metadata and selected generation. / 捕获精确元数据及选定代次。
         * @param loader actual loader / 实际加载器
         * @param schema selected generation / 选定代次
         * @param mode graph mode / 图模式 */
        private Binding(ModClassLoader loader, Optional<SchemaIdentity> schema, ModLaunchMode mode) {
            this.loader = Objects.requireNonNull(loader);
            this.schema = Objects.requireNonNull(schema);
            this.mode = Objects.requireNonNull(mode);
            if (!loader.manifest().schemaVersion().equals(schema.map(SchemaIdentity::schemaVersion))
                || schema.isPresent() && !schema.get().ownerId().equals(owner()))
                throw new IllegalArgumentException("Service generation differs from its loaded manifest");
        }

        /** Binds the real host cleanup once while this contribution remains unpublished. / 此扩展仍未发布时一次绑定真实宿主收尾。
         * @param cleanup same activation's physical-resource cleanup / 同一启用的物理资源收尾
         * @throws IllegalStateException if cleanup was bound or publication already occurred / 已绑定收尾或已发布时 */
        public void bindRecoveryCleanup(RecoveryCleanup cleanup) {
            synchronized (PlatformModServices.this) {
                if (published || stopping || detached || recoveryCleanup != null)
                    throw new IllegalStateException("Provider cleanup must bind once before publication");
                recoveryCleanup = Objects.requireNonNull(cleanup);
            }
        }

        /** Returns actual owning Mod identity. / 返回实际所属 Mod 身份。
         * @return exact owner / 精确所有者 */
        private String owner() {
            return loader.manifest().id();
        }

        /** Returns actual loaded release. / 返回实际加载发布。
         * @return exact release / 精确发布 */
        private String version() {
            return loader.manifest().version().toString();
        }

        /** Registers only this loader's public exported interfaces and actual implementations. / 仅登记此加载器的公开导出接口及实际实现。
         * @param <T> shared public contract / 共享公开契约
         * @param contract exact exported interface Class / 精确导出接口 Class
         * @param implementation owning implementation / 所属实现
         * @return original registration / 原登记
         * @throws ApiException if ownership, shape or lifecycle is invalid / 归属、形状或生命周期无效时 */
        @Override
        public <T> ServiceRegistration register(Class<T> contract, T implementation) throws ApiException {
            synchronized (PlatformModServices.this) {
                open();
                if (contract == null || !contract.isInterface() || !Modifier.isPublic(contract.getModifiers())
                    || !exported(contract) || implementation == null || !contract.isInstance(implementation)
                    || implementation.getClass().getClassLoader() != loader)
                    throw error(ProblemCode.CONTRACT_VIOLATION, "SERVICE_CONTRACT",
                        "Service registration requires this Mod's exported public interface and implementation");
                if (registrations.containsKey(contract))
                    throw error(ProblemCode.BUSY, "SERVICE_DUPLICATE", "This interface is already registered");
                var value = new Registration<>(this, contract, implementation);
                registrations.put(contract, value);
                return value;
            }
        }

        /** Finds a declared provider without acquiring any business claims. / 查找已声明提供方，不取得任何业务申请。
         * @param <T> exact shared contract / 精确共享契约
         * @param providerModId declared provider / 已声明提供方
         * @param contract exact shared Class / 精确共享 Class
         * @return original discovery handle / 原发现句柄
         * @throws ApiException if dependencies, exports or actual startup differ / 依赖、导出或实际启动不符时 */
        @Override
        public <T> ServiceHandle<T> require(String providerModId, Class<T> contract) throws ApiException {
            synchronized (PlatformModServices.this) {
                open();
                var provider = bindings.get(new Key(providerModId, mode));
                if (provider == null || !provider.published || provider.stopping || provider.detached
                    || provider.loader.isClosed())
                    throw error(ProblemCode.UNAVAILABLE, "SERVICE_PROVIDER",
                        "The declared provider has not published its services");
                boolean declared = loader.manifest().dependencies().stream()
                    .anyMatch(dependency -> dependency.id().equals(providerModId)
                        && dependency.versionRange().contains(provider.loader.manifest().version()));
                try {
                    if (!declared || contract == null || loader.loadClass(contract.getName()) != contract
                        || !provider.exported(contract))
                        throw error(ProblemCode.INCOMPATIBLE, "SERVICE_IDENTITY",
                            "Dependency version, export or shared Class identity differs");
                } catch (ClassNotFoundException invalid) {
                    throw error(ProblemCode.INCOMPATIBLE, "SERVICE_CLASS",
                        "The actual dependency Class is unavailable");
                }
                @SuppressWarnings("unchecked")
                var registered = (Registration<T>) provider.registrations.get(contract);
                if (registered == null || registered.closing)
                    throw error(ProblemCode.UNAVAILABLE, "SERVICE_MISSING",
                        "The provider has no active registration for this interface");
                var handle = new Handle<>(this, registered);
                handles.add(handle);
                return handle;
            }
        }

        /** Publishes registrations only after successful author startup. / 作者启动成功后才发布登记。
         * @throws ApiException if this activation was sealed / 此启用已封闭时 */
        @Override
        public void publish() throws ApiException {
            synchronized (PlatformModServices.this) {
                open();
                published = true;
            }
        }

        /** Seals discovery and new calls without interrupting accepted synchronous work. / 封闭发现及新调用，不中断已接纳同步工作。 */
        public void seal() {
            synchronized (PlatformModServices.this) {
                stopping = true;
            }
        }

        /** Closes owned handles and registrations only after their calls actually return. / 所属调用实际返回后才关闭句柄及登记。
         * @throws ApiException if synchronous work remains / 同步工作仍存在时 */
        @Override
        public void close() throws ApiException {
            synchronized (PlatformModServices.this) {
                if (detached)
                    return;
                stopping = true;
                for (var handle : List.copyOf(handles))
                    handle.close();
                for (var registration : List.copyOf(registrations.values()))
                    registration.close();
                bindings.remove(new Key(owner(), mode), this);
                detached = true;
            }
        }

        /** Checks one public type's exact defining loader and declared package. / 检查公开类型的精确定义加载器及声明包。
         * @param type actual Class / 实际 Class
         * @return whether this activation exports it / 此启用是否导出该类型 */
        private boolean exported(Class<?> type) {
            return type.getClassLoader() == loader && Modifier.isPublic(type.getModifiers())
                && loader.manifest().exports().stream().anyMatch(
                pkg -> type.getPackageName().equals(pkg) || type.getPackageName().startsWith(pkg + "."));
        }

        /** Rejects new use after sealing or actual loader closure. / 封闭或加载器实际关闭后拒绝新使用。
         * @throws ApiException if the activation is unavailable / 启用不可用时 */
        private void open() throws ApiException {
            if (closed || stopping || detached || loader.isClosed())
                throw error(ProblemCode.UNAVAILABLE, "SERVICES_CLOSED", "Service activation is unavailable");
        }
    }

    /** One exact registered provider implementation. / 一个精确登记的提供方实现。
     * @param <T> public interface / 公开接口 */
    private final class Registration<T> implements ServiceRegistration {
        /** Owning activation. / 所属启用。 */
        private final Binding provider;

        /** Shared contract identity. / 共享契约身份。 */
        private final Class<T> contract;

        /** Actual private implementation. / 实际私有实现。 */
        private final T implementation;

        /** Actual in-flight calls and sealed state. / 实际在途调用及封闭状态。 */
        private int active;

        /** Whether registration close has been requested. / 是否请求关闭登记。 */
        private boolean closing;

        /** Captures exact provider objects. / 捕获精确提供方对象。
         * @param provider owning activation / 所属启用
         * @param contract shared interface / 共享接口
         * @param implementation actual implementation / 实际实现 */
        private Registration(Binding provider, Class<T> contract, T implementation) {
            this.provider = provider;
            this.contract = contract;
            this.implementation = implementation;
        }

        /** Seals new calls, retaining registration while actual work remains. / 封闭新调用，实际工作仍存在时保留登记。
         * @throws ApiException if actual calls remain / 实际调用仍存在时 */
        @Override
        public void close() throws ApiException {
            synchronized (PlatformModServices.this) {
                closing = true;
                if (active != 0)
                    throw error(ProblemCode.BUSY, "SERVICE_CALL_ACTIVE", "Registered provider calls are still active");
                provider.registrations.remove(contract, this);
            }
        }
    }

    /** Discovery handle that never exposes an unguarded provider object. / 从不暴露无作用域保护的提供方对象的发现句柄。
     * @param <T> shared provider interface / 共享提供方接口 */
    private final class Handle<T> implements ServiceHandle<T> {
        /** Exact caller activation. / 精确调用方启用。 */
        private final Binding caller;

        /** Exact original provider registration. / 精确原提供方登记。 */
        private final Registration<T> registration;

        /** Actual call count and closed admission. / 实际调用计数及关闭准入。 */
        private int active;

        /** Whether the handle was closed. / 句柄是否关闭。 */
        private boolean stopped;

        /** Binds original caller and provider objects. / 绑定原调用方及提供方对象。
         * @param caller actual caller / 实际调用方
         * @param registration actual registration / 实际登记 */
        private Handle(Binding caller, Registration<T> registration) {
            this.caller = caller;
            this.registration = registration;
        }

        /** Executes synchronously with immutable values and per-call provider leases. / 使用不可变值及逐调用提供方占用同步执行。
         * @param <R> detached result / 分离结果
         * @param task original task / 原任务
         * @param call synchronous client callback / 同步客户端回调
         * @return independent immutable result / 独立不可变结果
         * @throws ApiException if authority, work, data or cleanup is invalid / 授权、工作、数据或清理无效时 */
        @Override
        public <R> R call(TaskContext task, ServiceCall<T, R> call) throws ApiException {
            Objects.requireNonNull(call);
            var provider = registration.provider;
            synchronized (PlatformModServices.this) {
                caller.open();
                provider.open();
                if (stopped || registration.closing || !provider.published || !caller.published)
                    throw error(ProblemCode.UNAVAILABLE, "SERVICE_CALL_CLOSED",
                        "Service call admission is unavailable");
                active++;
                registration.active++;
            }
            try {
                return tasks.dependencyCall(task, caller.owner(), caller.version(), provider.owner(),
                    provider.version(), provider.schema, caller.mode == ModLaunchMode.RECOVERY,
                    connection -> claims(connection, provider, task), () -> {
                        if (tasks.reconciling(task)) {
                            if (provider.recoveryCleanup == null)
                                throw error(ProblemCode.UNAVAILABLE, "SERVICE_RECOVERY_CLEANUP_MISSING",
                                    "The original provider's host cleanup is not bound");
                            provider.recoveryCleanup.close(task);
                        }
                        var valid = new java.util.concurrent.atomic.AtomicBoolean(true);
                        Thread thread = Thread.currentThread();
                        var publicType = (java.util.function.Predicate<Class<?>>) type -> provider.exported(type)
                            || caller.exported(type)
                            || type.getName().startsWith("gold.debug.windowstolinux.appapi.")
                            && type.getClassLoader() == ModServices.class.getClassLoader();
                        @SuppressWarnings("unchecked")
                        T guarded = (T) Proxy.newProxyInstance(registration.contract.getClassLoader(),
                            new Class<?>[]{registration.contract}, (proxy, method, args) -> {
                                if (!valid.get() || thread != Thread.currentThread())
                                    throw error(ProblemCode.ACCESS_DENIED, "SERVICE_SCOPE_ENDED",
                                        "Provider proxy is outside its original synchronous call");
                                tasks.requireExecuting(task, provider.owner(), provider.version());
                                if (method.getDeclaringClass() == Object.class)
                                    return switch (method.getName()) {
                                        case "toString" -> "Service(" + provider.owner() + ")";
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" -> proxy == args[0];
                                        default -> throw new IllegalArgumentException("Unsupported Object method");
                                    };
                                var values = args == null ? new Object[0] : args.clone();
                                for (int i = 0; i < values.length; i++)
                                    if (values[i] != task)
                                        values[i] = ServiceSnapshot.copy(values[i], publicType);
                                Object result;
                                try {
                                    result = method.invoke(registration.implementation, values);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                                return ServiceSnapshot.copy(result, publicType);
                            });
                        try {
                            @SuppressWarnings("unchecked")
                            R copied = (R) ServiceSnapshot.copy(call.invoke(guarded), publicType);
                            return copied;
                        } finally {
                            valid.set(false);
                        }
                    });
            } catch (ApiException failure) {
                throw failure;
            } catch (Exception | LinkageError | AssertionError failure) {
                tasks.reportUncertainEffect(task);
                throw error(ProblemCode.CONTRACT_VIOLATION, "SERVICE_CALL_FAILED",
                    "Synchronous service call or value contract failed");
            } finally {
                synchronized (PlatformModServices.this) {
                    active--;
                    registration.active--;
                }
            }
        }

        /** Closes future discovery use while retaining actual in-flight ownership. / 关闭后续发现使用并保留实际在途归属。
         * @throws ApiException if accepted calls remain / 已接纳调用仍存在时 */
        @Override
        public void close() throws ApiException {
            synchronized (PlatformModServices.this) {
                stopped = true;
                if (active != 0)
                    throw error(ProblemCode.BUSY, "SERVICE_HANDLE_ACTIVE",
                        "Accepted service calls must return before closing");
                caller.handles.remove(this);
            }
        }
    }

    /** Validates normal or original recovery provider state under the same acquisition transaction. / 在同一取得事务中校验普通或原恢复提供方状态。
     * @param connection locked platform transaction / 锁定的平台事务
     * @param provider exact loaded provider / 精确加载提供方
     * @param task original task / 原任务
     * @return exact required Mod and Schema claims / 精确所需 Mod 及 Schema 申请
     * @throws SQLException if persistence cannot be observed / 无法观察持久化时
     * @throws ApiException if actual provider state is incompatible / 实际提供方状态不兼容时 */
    private List<TaskResource> claims(Connection connection, Binding provider, TaskContext task)
        throws SQLException, ApiException {
        if (provider.mode == ModLaunchMode.NORMAL) {
            var actual = new PlatformTaskAdmission(database).claims(connection, provider.owner(), provider.version(),
                new TaskRequest("service.call", "Synchronous service call", List.of(), false));
            var expected = new DependencyRecovery(database.bootId(), UUID.randomUUID(), provider.owner(),
                provider.version(), provider.schema).claims();
            if (!new HashSet<>(actual).equals(new HashSet<>(expected)))
                throw error(ProblemCode.UNAVAILABLE, "SERVICE_SCHEMA_CHANGED",
                    "Provider data generation differs from its activation");
            return actual;
        }
        if (!tasks.reconciling(task))
            throw error(ProblemCode.ACCESS_DENIED, "SERVICE_RECOVERY_ONLY",
                "Recovery graph requires explicit reconciliation");
        boolean original = TaskLedger.TaskLedgerQueries.readDependencies(connection, task.id()).keySet().stream()
            .anyMatch(call -> call.provider().equals(provider.owner()) && call.version().equals(provider.version())
                && call.schema().equals(provider.schema));
        if (!original)
            throw error(ProblemCode.ACCESS_DENIED, "SERVICE_RECOVERY_VERSION",
                "The original task does not retain this provider release and generation");
        try (var p = connection
            .prepareStatement("SELECT state,schema_version FROM mod_version WHERE mod_id=? AND version=?")) {
            p.setString(1, provider.owner());
            p.setString(2, provider.version());
            try (var row = p.executeQuery()) {
                if (!row.next() || !row.getString(1).equals("READY")
                    || !Optional.ofNullable(row.getString(2)).equals(provider.loader.manifest().schemaVersion()))
                    throw error(ProblemCode.UNAVAILABLE, "SERVICE_RECOVERY_INSTALL",
                        "Original provider release is unavailable");
            }
        }
        if (provider.schema.isPresent())
            new SchemaCoordinates(provider.schema.get()).requireRegistered(connection, Set.of("READY"));
        return new DependencyRecovery(database.bootId(), UUID.randomUUID(), provider.owner(), provider.version(),
            provider.schema).claims();
    }

    /** Distinguishes normal publication from an isolated recovery graph. / 区分普通发布及隔离恢复图。
     * @param owner exact Mod / 精确 Mod
     * @param mode actual launch graph / 实际启动图 */
    private record Key(String owner, ModLaunchMode mode) {
    }

    /** Creates a bounded public diagnostic without author exception content. / 创建有界公开诊断，不含作者异常内容。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @param message safe text / 安全文字
     * @return public error / 公开错误 */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }
}
