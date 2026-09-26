package gold.debug.windowstolinux.app.service.task;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.task.*;

/** Runs caller-bound author work after durable admission and preserves unfinished ownership. / 在持久化准入后运行绑定调用方的作者工作，并保留未完成归属。 */
public final class PlatformTaskRuntime implements AutoCloseable {
    /** Authoritative persistent resource and result ledger. / 权威的持久化资源及结果账本。 */
    private final TaskLedger ledger;

    /** Host policy that validates current Mod, schema, server and security admission. / 校验当前 Mod、数据、服务器及安全准入的宿主策略。 */
    private final Admission admission;

    /** Current-user credential authorization retained for accepted tasks. / 为已接纳任务保留的当前用户凭据授权。 */
    private final DesktopSecurityService security;

    /** Nonblocking count used by security settings while no current key scope exists. / 当前密钥作用域缺失时供安全设置使用的非阻塞计数。 */
    private volatile int uncredentialedTasks;

    /** Security-setting blocker owned until this runtime drains. / 执行器排空前持有的安全设置阻断登记。 */
    private final AutoCloseable securityRegistration;

    /** Encodes public diagnostic fields without serializing business payload objects. / 编码公开诊断字段，不序列化业务载荷对象。 */
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Cooperative background execution without forced task interruption. / 不强制中断任务的协作后台执行器。 */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /** Current-session handles, including recovered identities but no old Java future. / 当前会话句柄，包含恢复身份但不恢复旧 Java Future。 */
    private final Map<UUID, Execution<?>> executions = new LinkedHashMap<>();

    /** Exact owner/version/type recovery registrations. / 精确所有者、版本及类型的恢复登记。 */
    private final Map<RecoveryKey, TaskRecovery> recoveries = new HashMap<>();

    /** Bound author services revoked when their owning activation closes. / 所属启用关闭时撤销的绑定作者服务。 */
    private final Set<BoundTasks> bindings = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Owner gates shared by normal submissions and initial dependency claims. / 普通提交及初始依赖申请共用的所有者准入门。 */
    private final Map<String, String> blockedOwners = new HashMap<>();

    /** Whether the host has sealed new business admission. / 宿主是否已封闭新业务准入。 */
    private boolean sealed;

    /** Whether orderly executor shutdown has started. / 是否已开始有序关闭执行器。 */
    private boolean closing;

    /**
     * Loads retained task identities without replaying their business callbacks. / 加载保留任务身份，不重放其业务回调。
     * @param database exclusively owned platform engine / 独占的平台引擎
     * @param admission host validation policy / 宿主校验策略
     * @param security current-user session owner / 当前用户会话所有者
     * @throws SQLException if retained identities cannot be read / 无法读取保留身份时
     */
    public PlatformTaskRuntime(PlatformDatabase database, Admission admission, DesktopSecurityService security)
            throws SQLException {
        ledger = new TaskLedger(database);
        this.admission = Objects.requireNonNull(admission);
        this.security = Objects.requireNonNull(security);
        for (var id : ledger.unfinished()) {
            ledger.uncertain(id);
            var snapshot = ledger.snapshot(id);
            var execution = new Execution<Void>(snapshot, null, true);
            execution.returned = true;
            executions.put(id, execution);
        }
        refreshAuthorizationBlockers();
        securityRegistration = security.trackRetainedTasks(() -> uncredentialedTasks);
    }

    /**
     * Returns a service whose owner and startup mode cannot be supplied by author calls. / 返回作者调用不能自行指定所有者及启动模式的服务。
     * @param ownerId exact bound Mod identity / 精确绑定的 Mod 身份
     * @param version exact running release / 精确运行发布版本
     * @param recoveryOnly whether normal submissions are forbidden / 是否禁止普通提交
     * @return caller-bound public service / 绑定调用方的公开服务
     */
    public synchronized TaskService bind(String ownerId, String version, boolean recoveryOnly) {
        if (closing)
            throw new IllegalStateException("Task runtime is closing");
        var binding = new BoundTasks(ownerId, version, recoveryOnly);
        bindings.add(binding);
        return binding;
    }

    /**
     * Seals or reopens new work while accepted tasks retain their original resources. / 封闭或重开新工作，已接纳任务保留原有资源。
     * @param sealed whether to reject new submissions / 是否拒绝新提交
     * @return previous admission gate, for restoring a rejected shutdown attempt / 原准入门状态，用于恢复被拒绝的关闭尝试
     */
    public synchronized boolean seal(boolean sealed) {
        if (closing && !sealed)
            throw new IllegalStateException("Task runtime is closing");
        boolean previous = this.sealed;
        this.sealed = sealed;
        return previous;
    }

    /**
     * Seals one Mod before lifecycle occupancy checks. / 生命周期占用检查前封闭一个 Mod。
     * @param ownerId exact stable Mod identity / 精确稳定的 Mod 身份
     * @param reason non-secret host blocking reason / 不含秘密的宿主阻断原因
     */
    public synchronized void blockOwner(String ownerId, String reason) {
        blockedOwners.put(Objects.requireNonNull(ownerId), Objects.requireNonNull(reason));
    }

    /**
     * Reopens an owner after successful host publication. / 宿主成功发布后重新开放所有者。
     * @param ownerId exact stable Mod identity / 精确稳定的 Mod 身份
     */
    public synchronized void allowOwner(String ownerId) {
        if (closing)
            throw new IllegalStateException("Task runtime is closing");
        blockedOwners.remove(ownerId);
    }

    /**
     * Reads all unfinished work that prevents the owner's shutdown. / 读取阻止所有者关闭的全部未结束工作。
     * @param ownerId exact Mod identity across versions / 跨版本的精确 Mod 身份
     * @return immutable task blocking descriptions / 不可变任务阻断描述
     * @throws SQLException if occupancy cannot be verified / 无法核实占用时
     */
    public synchronized List<String> ownerBlockers(String ownerId) throws SQLException {
        return ledger.ownedTasks(ownerId).stream().map(id -> "Task " + id + " still owns Mod resources").toList();
    }

    /**
     * Revokes task bindings and original-version recovery callbacks after the owner drains. / 所有者排空后撤销任务绑定及原版本恢复回调。
     * @param ownerId exact owning Mod identity / 精确所属 Mod 身份
     * @param version exact original release / 精确原始发布版本
     * @throws ApiException if work remains or occupancy cannot be verified / 仍有工作或无法核实占用时
     */
    public synchronized void revokeOwnerServices(String ownerId, String version) throws ApiException {
        try {
            if (!ledger.ownedTasks(ownerId).isEmpty())
                throw error(ProblemCode.BUSY, "MOD_TASKS_RETAINED", "Mod tasks still require recovery or cleanup");
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "MOD_OCCUPANCY_UNKNOWN", "Mod occupancy could not be verified");
        }
        recoveries.keySet().removeIf(key -> key.owner().equals(ownerId) && key.version().equals(version));
        for (var binding : List.copyOf(bindings))
            if (binding.owner.equals(ownerId) && binding.version.equals(version)) {
                binding.revoked = true;
                bindings.remove(binding);
            }
    }

    /**
     * Verifies the actual host-created context rather than trusting a copied UUID. / 验证实际宿主上下文，而不信任复制的 UUID。
     * @param context context received by a platform adapter / 平台适配器收到的上下文
     * @return current owned task snapshot / 当前所属任务快照
     * @throws ApiException if the context is forged or no longer active / 上下文伪造或不再活动时
     */
    public synchronized TaskSnapshot requireContext(TaskContext context) throws ApiException {
        if (context == null)
            throw error(ProblemCode.ACCESS_DENIED, "TASK_CONTEXT", "Task context is required");
        var task = executions.get(context.id());
        if (task == null || task.context != context || task.snapshot.state() == TaskState.ENDED)
            throw error(ProblemCode.ACCESS_DENIED, "TASK_CONTEXT", "Task context is foreign or ended");
        return task.snapshot;
    }

    /**
     * Verifies a capability's bound caller while its original callback or explicit recovery executes. / 在原回调或显式恢复执行期间核实能力绑定的调用方。
     * @param context authentic original task / 真实原始任务
     * @param ownerId caller bound by the host / 宿主绑定的调用方
     * @param version exact loaded caller release / 精确已加载调用方版本
     * @return current task identity and state / 当前任务身份及状态
     * @throws ApiException if the caller is foreign, cancelled or no longer executing / 调用方不符、取消或不再执行时
     */
    public synchronized TaskSnapshot requireExecuting(TaskContext context, String ownerId, String version)
            throws ApiException {
        var snapshot = requireCleanup(context, ownerId, version);
        var execution = executions.get(snapshot.id());
        if (execution.uncertainEffect)
            throw error(ProblemCode.UNKNOWN, "TASK_EFFECT_UNCERTAIN",
                    "An external effect requires explicit reconciliation");
        if (!execution.reconciling && context.cancellation().requested())
            throw error(ProblemCode.CANCELLED, "TASK_CANCELLED", "The task requested cooperative cancellation");
        return snapshot;
    }

    /**
     * Authenticates cleanup of already owned resources even after cancellation or uncertainty. / 即使已取消或出现不确定性，仍认证已持有资源的清理。
     * @param context original currently executing or reconciling context / 原始且正在执行或核对的上下文
     * @param ownerId capability owner / 能力所有者
     * @param version exact bound release / 精确绑定版本
     * @return authenticated task snapshot / 已认证任务快照
     * @throws ApiException if the context is foreign or no callback is executing / 上下文属于他方或没有正在执行的回调时
     */
    public synchronized TaskSnapshot requireCleanup(TaskContext context, String ownerId, String version)
            throws ApiException {
        var snapshot = requireContext(context);
        var execution = executions.get(snapshot.id());
        if (!snapshot.ownerId().equals(ownerId) || !snapshot.ownerVersion().equals(version))
            throw error(ProblemCode.ACCESS_DENIED, "TASK_CALLER", "The capability belongs to a different Mod release");
        if (execution.returned && !execution.reconciling || snapshot.state() == TaskState.QUEUED)
            throw error(ProblemCode.UNAVAILABLE, "TASK_NOT_EXECUTING", "The original task callback is not executing");
        return snapshot;
    }

    /**
     * Requires a server from the task's complete initial resource set. / 要求服务器属于任务的完整初始资源集合。
     * @param context authentic original task / 真实原始任务
     * @param ownerId bound Mod identity / 绑定的 Mod 身份
     * @param version bound loaded release / 绑定的已加载版本
     * @param serverId exact saved server identity / 精确已保存服务器身份
     * @throws ApiException if ownership or admission cannot be verified / 无法核实归属或准入时
     */
    public synchronized void requireServer(TaskContext context, String ownerId, String version, String serverId)
            throws ApiException {
        requireExecuting(context, ownerId, version);
        if (serverId == null || serverId.isBlank() || serverId.length() > 512)
            throw error(ProblemCode.INVALID_INPUT, "TASK_SERVER_ID", "Invalid saved server identity");
        try {
            if (!ledger.holds(context.id(), new TaskResource("SERVER", serverId, ownerId, true)))
                throw error(ProblemCode.ACCESS_DENIED, "TASK_SERVER_NOT_ADMITTED",
                        "The server was not admitted with this task");
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "TASK_SERVER_CLAIM", "Server admission could not be verified");
        }
    }

    /**
     * Runs a retained adapter operation using the original task's credential scope. / 使用原任务凭据作用域运行已保留的适配操作。
     * @param <T> internal operation result / 内部操作结果
     * @param context authentic active task / 真实活动任务
     * @param ownerId bound Mod identity / 绑定的 Mod 身份
     * @param version bound release / 绑定版本
     * @param work adapter work whose resource lease remains held until return / 资源占用持续到返回的适配工作
     * @return operation result / 操作结果
     * @throws Exception if authorization or work fails / 授权或工作失败时
     */
    public <T> T authorized(TaskContext context, String ownerId, String version, Callable<T> work) throws Exception {
        DesktopSecurityService.Task authorization;
        synchronized (this) {
            requireExecuting(context, ownerId, version);
            authorization = executions.get(context.id()).authorization;
        }
        return authorization.execute(work);
    }

    /**
     * Returns cancellation for current work; explicit recovery does not replay an older cancellation. / 返回当前工作取消状态；显式恢复不重放旧取消。
     * @param context authentic original task / 真实原始任务
     * @return cancellation checked dynamically during adapter work / 适配工作期间动态检查的取消状态
     */
    public synchronized CancellationToken operationCancellation(TaskContext context) {
        var execution = executions.get(context.id());
        if (execution == null || execution.context != context)
            return () -> true;
        return () -> execution.snapshot.state() == TaskState.ENDED || !execution.reconciling && execution.cancelled;
    }

    /**
     * Retains uncertainty even if author code subsequently returns a known result. / 即使作者代码随后返回已知结果，仍保留已观察到的不确定性。
     * @param context original task whose external effect is uncertain / 外部副作用不确定的原始任务
     * @throws ApiException if the task identity is invalid / 任务身份无效时
     */
    public synchronized void reportUncertainEffect(TaskContext context) throws ApiException {
        requireContext(context);
        var execution = executions.get(context.id());
        execution.uncertainEffect = true;
        uncertain(execution, "A platform operation could not establish its external outcome");
    }

    /**
     * Registers actual host resources; adapters call close only after physical release. / 登记实际宿主资源；适配器仅在物理释放后调用关闭。
     * @param context original accepted context / 原始已接纳上下文
     * @param resource host-validated resource claim / 宿主已校验的资源申请
     * @return explicit release marker / 显式释放标记
     * @throws ApiException if ownership or persistence fails / 归属或持久化失败时
     */
    public synchronized ResourceLease retain(TaskContext context, TaskResource resource) throws ApiException {
        return retain(context, resource, null);
    }

    /** Persists host recovery facts before allowing a physical resource to be created. / 允许创建物理资源前持久化宿主恢复事实。
     * @param context original accepted context / 原已接纳上下文
     * @param resource exact host-owned resource / 精确宿主所属资源
     * @param recovery optional non-secret recovery facts / 可选且不含秘密的恢复事实
     * @return explicit release marker / 显式释放标记
     * @throws ApiException if ownership or durable registration cannot be confirmed / 无法确认归属或持久登记时 */
    public synchronized ResourceLease retain(TaskContext context, TaskResource resource, ResourceRecovery recovery)
            throws ApiException {
        requireContext(context);
        var execution = executions.get(context.id());
        if (execution.returned && !execution.reconciling)
            throw error(ProblemCode.UNAVAILABLE, "TASK_CALLBACK_RETURNED",
                    "The callback has returned and cannot retain new resources");
        try {
            if (resource.kind().equals("MOD") && blockedOwners.containsKey(resource.id())
                    && !ledger.holds(context.id(), resource))
                throw error(ProblemCode.BUSY, "MOD_DEPENDENCY_CLOSED", blockedOwners.get(resource.id()));
            return new OwnedLease(execution, ledger.retain(context.id(), resource, recovery));
        } catch (SQLException failure) {
            if (recovery != null) {
                execution.uncertainEffect = true;
                uncertain(execution, "Recovery resource registration could not be confirmed");
            }
            throw error(ProblemCode.IO_FAILURE, "TASK_RESOURCE", "Resource registration failed");
        }
    }

    /**
     * Retains a JDBC connection only inside the task's already admitted Mod and schema generation. / 仅在任务已接纳的 Mod 与数据代次内保留 JDBC 连接。
     * @param context authentic original task / 真实原始任务
     * @param identity bound database generation / 绑定的数据代次
     * @return connection occupancy marker / 连接占用标记
     * @throws ApiException if the task lacks either required claim / 任务缺少任一必需占用时
     */
    public synchronized ResourceLease retainSchemaConnection(TaskContext context,
            gold.debug.windowstolinux.appapi.db.SchemaIdentity identity) throws ApiException {
        requireContext(context);
        try {
            if (!ledger.holds(context.id(), new TaskResource("MOD", identity.ownerId(), identity.ownerId(), false))
                    || !ledger.holds(context.id(),
                            new TaskResource("SCHEMA", identity.generation().toString(), identity.ownerId(), false)))
                throw error(ProblemCode.ACCESS_DENIED, "TASK_SCHEMA_NOT_ADMITTED",
                        "The task does not own this Mod schema generation");
            return retain(context,
                    new TaskResource("CONNECTION", UUID.randomUUID().toString(), identity.ownerId(), false));
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "TASK_SCHEMA_CLAIM", "Schema admission could not be verified");
        }
    }

    /**
     * Returns all current-session snapshots for the host task page. / 返回所有当前会话快照，供宿主任务页面使用。
     * @return immutable task snapshots / 不可变任务快照
     */
    public synchronized List<TaskSnapshot> snapshots() {
        return executions.values().stream().map(t -> t.snapshot).toList();
    }

    /**
     * Reconciles a retained task only after an explicit host action. / 仅在显式宿主操作后核对保留任务。
     * @param taskId original task identity / 原始任务身份
     * @return completion of this reconciliation attempt, not a replayed business operation / 本次核对尝试的完成状态，不是重放业务操作
     * @throws ApiException if recovery is unavailable, busy or inappropriate / 恢复不可用、忙碌或不适用时
     */
    public CompletionStage<TaskSnapshot> reconcile(UUID taskId) throws ApiException {
        var authorization = authorize();
        boolean retained = false;
        try {
            synchronized (this) {
                var task = executions.get(taskId);
                if (task == null)
                    throw error(ProblemCode.NOT_FOUND, "TASK_MISSING", "Task does not exist");
                if (closing || task.reconciling || !task.returned
                        || !Set.of(TaskState.UNKNOWN, TaskState.FINISHING).contains(task.snapshot.state()))
                    throw error(ProblemCode.BUSY, "TASK_RECOVERY_BUSY", "Task is not ready for reconciliation");
                var recovery = recoveries.get(
                        new RecoveryKey(task.snapshot.ownerId(), task.snapshot.ownerVersion(), task.snapshot.type()));
                if (recovery == null)
                    throw error(ProblemCode.UNAVAILABLE, "TASK_RECOVERY_MISSING",
                            "The original task recovery implementation is unavailable");
                if (task.authorization == null) {
                    task.authorization = authorization;
                    retained = true;
                    refreshAuthorizationBlockers();
                }
                task.reconciling = true;
                task.uncertainEffect = false;
                var completion = new CompletableFuture<TaskSnapshot>();
                workers.execute(() -> {
                    OperationResult<Void> result;
                    try {
                        result = task.authorization
                                .execute(() -> Objects.requireNonNull(recovery.reconcile(task.snapshot, task.context)));
                    } catch (Exception | LinkageError | AssertionError failure) {
                        result = unknown("TASK_RECOVERY_FAILED", "Recovery did not establish a known result");
                    }
                    boolean ended;
                    synchronized (this) {
                        task.reconciling = false;
                        task.observed = new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
                        ended = record(task, true);
                        dispatch();
                    }
                    if (ended)
                        task.completeObserved();
                    completion.complete(task.snapshot);
                });
                return completion.minimalCompletionStage();
            }
        } finally {
            if (!retained)
                authorization.close();
        }
    }

    /**
     * Attempts queued work without taking partial resources or retrying unknown business effects. / 尝试排队工作，不取得部分资源或重试未知业务副作用。
     */
    private void dispatch() {
        for (var task : executions.values())
            if (task.snapshot.state() == TaskState.QUEUED) {
                try {
                    if (ledger.tryStart(task.snapshot.id())) {
                        task.snapshot = ledger.snapshot(task.snapshot.id());
                        workers.execute(() -> run(task));
                    }
                } catch (SQLException | RejectedExecutionException failure) {
                    task.returned = true;
                    uncertain(task, "Task admission could not be confirmed");
                }
            }
    }

    /**
     * Invokes an admitted callback once on a background thread. / 在后台线程中仅调用一次已接纳回调。
     * @param <T> public result payload type / 公开结果载荷类型
     * @param task admitted execution / 已准入执行
     */
    private <T> void run(Execution<T> task) {
        OperationResult<T> result;
        try {
            result = task.authorization.execute(() -> Objects.requireNonNull(task.work.run(task.context)));
        } catch (Exception | LinkageError | AssertionError failure) {
            result = unknown("TASK_CALLBACK_UNCERTAIN", "The callback did not establish a business outcome");
        }
        boolean ended;
        synchronized (this) {
            task.returned = true;
            task.observed = result;
            ended = record(task, false);
            dispatch();
        }
        if (ended)
            task.completeObserved();
    }

    /**
     * Commits the observed outcome or retains uncertainty after any persistence failure. / 提交已观察结果，持久化失败则保留不确定状态。
     * @param task execution with an observed result / 具有已观察结果的执行
     * @param reconciliation whether this is explicit recovery / 是否为显式恢复
     * @return whether the task reached durable terminal state / 任务是否达到持久化终态
     */
    private boolean record(Execution<?> task, boolean reconciliation) {
        try {
            if (task.uncertainEffect)
                task.observed = unknown("TASK_EFFECT_UNCERTAIN",
                        "A platform operation requires explicit reconciliation");
            boolean ended = ledger.complete(task.snapshot.id(), task.observed.outcome(), evidence(task.observed),
                    reconciliation, task::releaseAuthorization);
            task.snapshot = ledger.snapshot(task.snapshot.id());
            refreshAuthorizationBlockers();
            return ended;
        } catch (SQLException failure) {
            uncertain(task, "Task completion persistence requires reconciliation");
            return false;
        }
    }

    /**
     * Persists bounded public uncertainty and failure evidence with its observed outcome. / 随已观察结果持久化有界公开不确定性及失败证据。
     * @param result author-provided public result / 作者提供的公开结果
     * @return diagnostic JSON without business payloads / 不含业务载荷的诊断 JSON
     * @throws SQLException if diagnostic encoding fails / 诊断编码失败时
     */
    private String evidence(OperationResult<?> result) throws SQLException {
        var node = json.createObjectNode();
        node.put("outcome", result.outcome().name());
        result.problem().ifPresent(problem -> {
            var reason = node.putObject("problem");
            reason.put("code", problem.code().name());
            reason.put("eventCode", problem.eventCode());
            reason.put("message", problem.message());
            reason.set("context", json.valueToTree(problem.context()));
            problem.diagnosticId().ifPresent(id -> reason.put("diagnosticId", id.toString()));
        });
        try {
            return json.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new SQLException("Task diagnostic encoding failed", failure);
        }
    }

    /**
     * Keeps both resource ownership and an explicit local uncertainty diagnostic. / 同时保留资源归属及明确的本地不确定性诊断。
     * @param task affected task / 受影响任务
     * @param message non-secret host diagnostic / 不含秘密的宿主诊断
     */
    private void uncertain(Execution<?> task, String message) {
        try {
            ledger.uncertain(task.snapshot.id());
        } catch (SQLException retained) {
            /* Keep the local blocker even when the database is unavailable. / 数据库不可用时仍保留本地阻断原因。 */}
        var previous = task.snapshot;
        task.snapshot = new TaskSnapshot(previous.id(), previous.ownerId(), previous.ownerVersion(), previous.type(),
                TaskState.UNKNOWN, Optional.empty(), previous.progressText(), previous.startedAt(), Optional.empty(),
                List.of(message));
    }

    /**
     * Closes cooperatively only after every accepted task has ended. / 仅在全部已接纳任务结束后协作关闭。
     * @throws ApiException if unfinished work or live workers remain / 仍有未结束工作或活动工作线程时
     */
    @Override
    public void close() throws ApiException {
        synchronized (this) {
            sealed = true;
            try {
                if (!ledger.unfinished().isEmpty() || executions.values().stream()
                        .anyMatch(t -> t.reconciling || t.snapshot.state() != TaskState.ENDED))
                    throw error(ProblemCode.BUSY, "TASKS_UNFINISHED",
                            "Tasks still require execution, cleanup or reconciliation");
            } catch (SQLException failure) {
                throw error(ProblemCode.IO_FAILURE, "TASK_SHUTDOWN", "Task shutdown state cannot be verified");
            }
            closing = true;
            for (var binding : bindings)
                binding.revoked = true;
            bindings.clear();
            recoveries.clear();
            workers.shutdown();
        }
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS))
                throw error(ProblemCode.BUSY, "TASK_WORKERS_ACTIVE", "Task callbacks are still returning");
            securityRegistration.close();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw error(ProblemCode.BUSY, "TASK_WAIT_INTERRUPTED", "Task shutdown wait was interrupted");
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error(ProblemCode.IO_FAILURE, "TASK_SECURITY_CLOSE", "Task security registration cleanup failed");
        }
    }

    /**
     * Captures current-user authorization outside the runtime monitor. / 在执行器监视器之外取得当前用户授权。
     * @return independently retained credential scope / 独立保留的凭据作用域
     * @throws ApiException if the desktop is locked or changing security / 桌面锁定或正在变更安全设置时
     */
    private DesktopSecurityService.Task authorize() throws ApiException {
        try {
            return security.beginTask();
        } catch (gold.debug.windowstolinux.app.service.failure.ApplicationServiceException failure) {
            throw error(ProblemCode.ACCESS_DENIED, "TASK_SECURITY_LOCKED",
                    "Unlock the desktop before admitting or reconciling a task");
        }
    }

    /** Publishes keyless unfinished ownership without exposing task locks to security callbacks. / 发布无密钥的未结束归属，不向安全回调暴露任务锁。 */
    private void refreshAuthorizationBlockers() {
        uncredentialedTasks = (int) executions.values().stream()
                .filter(task -> task.authorization == null && task.snapshot.state() != TaskState.ENDED).count();
    }

    /**
     * Builds a public rejection without private exception text. / 构造公开拒绝原因，不包含私有异常原文。
     * @param code public failure category / 公开失败类别
     * @param event stable event identifier / 稳定事件标识
     * @param message non-secret diagnostic / 不含秘密的诊断
     * @return public capability exception / 公开能力异常
     */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /**
     * Creates uncertainty without inventing a terminal failure. / 创建不确定结果，不虚构终态失败。
     * @param <T> optional public payload type / 可选公开载荷类型
     * @param event stable event identifier / 稳定事件标识
     * @param message non-secret diagnostic / 不含秘密的诊断
     * @return unknown result requiring reconciliation / 需要核对的未知结果
     */
    private static <T> OperationResult<T> unknown(String event, String message) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(error(ProblemCode.UNKNOWN, event, message).problem()));
    }

    /** Host policy called while task admission is serialized. / 在任务准入串行化期间调用的宿主策略。 */
    @FunctionalInterface
    public interface Admission {
        /**
         * Validates the running caller and supplies the entire initial resource set. / 验证运行中的调用方并提供完整初始资源集合。
         * @param connection transaction already holding the platform state lock / 已持有平台状态锁的事务
         * @param ownerId exact caller identity / 精确调用方身份
         * @param version exact running release / 精确运行版本
         * @param request author task request / 作者任务请求
         * @return complete host-validated claims / 完整且经宿主校验的申请
         * @throws ApiException if admission is unavailable or rejected / 准入不可用或拒绝时
         * @throws SQLException if platform state cannot be read / 无法读取平台状态时
         */
        List<TaskResource> claims(java.sql.Connection connection, String ownerId, String version, TaskRequest request)
                throws ApiException, SQLException;
    }

    /** Carries a rejected policy through repository rollback without inventing uncertain work. / 跨仓库回滚传递策略拒绝，不虚构未知工作。 */
    private static final class AdmissionRejected extends SQLException {
        /** Original public rejection. / 原始公开拒绝原因。 */
        private final ApiException rejection;

        /**
         * Retains the validation result before any task insert. / 在任何任务插入之前保留校验结果。
         * @param rejection original policy error / 原始策略错误
         */
        private AdmissionRejected(ApiException rejection) {
            super("Task admission rejected", rejection);
            this.rejection = rejection;
        }
    }

    /**
     * Exact recovery implementation binding. / 精确恢复实现绑定。
     * @param owner owning Mod / 所属 Mod
     * @param version original release / 原始发布版本
     * @param type owner-local task type / 所有者内部任务类型
     */
    private record RecoveryKey(String owner, String version, String type) {
    }

    /** Caller-bound author service with immutable ownership and startup mode. / 归属及启动模式不可变的调用方作者服务。 */
    private final class BoundTasks implements TaskService {
        /** Stable caller identity. / 稳定调用方身份。 */
        private final String owner;

        /** Exact release whose classes implement the callbacks. / 提供回调实现的精确发布版本。 */
        private final String version;

        /** Whether only recovery registration and observation are permitted. / 是否只允许恢复登记及观察。 */
        private final boolean recoveryOnly;

        /** Whether the owning activation has invalidated this service object. / 所属启用是否已使此服务对象失效。 */
        private boolean revoked;
        /**
         * Binds trusted host ownership. / 绑定可信宿主归属。
         * @param owner stable Mod identity / 稳定 Mod 身份
         * @param version exact release / 精确发布版本
         * @param recoveryOnly recovery-only mode / 仅恢复模式
         */
        private BoundTasks(String owner, String version, boolean recoveryOnly) {
            this.owner = Objects.requireNonNull(owner);
            this.version = Objects.requireNonNull(version);
            this.recoveryOnly = recoveryOnly;
        }

        /**
         * Persists admission before returning a new task handle. / 返回新任务句柄前持久化准入。
         * @param <T> public result type / 公开结果类型
         * @param request author request / 作者请求
         * @param work one-shot callback / 单次回调
         * @return current-session handle / 当前会话句柄
         * @throws ApiException if admission or persistence fails / 准入或持久化失败时
         */
        @Override
        public <T> TaskHandle<T> submit(TaskRequest request, TaskWork<T> work) throws ApiException {
            Objects.requireNonNull(request);
            Objects.requireNonNull(work);
            var authorization = authorize();
            boolean retained = false;
            try {
                synchronized (PlatformTaskRuntime.this) {
                    if (sealed || recoveryOnly || revoked)
                        throw error(ProblemCode.UNAVAILABLE, "TASK_ADMISSION_CLOSED",
                                "New business tasks are unavailable");
                    if (blockedOwners.containsKey(owner))
                        throw error(ProblemCode.BUSY, "MOD_ADMISSION_CLOSED", blockedOwners.get(owner));
                    UUID id = UUID.randomUUID();
                    try {
                        ledger.enqueue(id, owner, version, request, connection -> {
                            try {
                                var claims = List.copyOf(admission.claims(connection, owner, version, request));
                                for (var claim : claims)
                                    if (claim.kind().equals("MOD") && blockedOwners.containsKey(claim.id()))
                                        throw error(ProblemCode.BUSY, "MOD_DEPENDENCY_CLOSED",
                                                blockedOwners.get(claim.id()));
                                return claims;
                            } catch (ApiException rejected) {
                                throw new AdmissionRejected(rejected);
                            }
                        });
                        var task = new Execution<>(ledger.snapshot(id), work, request.cancellable());
                        task.authorization = authorization;
                        executions.put(id, task);
                        retained = true;
                        dispatch();
                        return task;
                    } catch (AdmissionRejected rejected) {
                        throw rejected.rejection;
                    } catch (SQLException failure) {
                        try {
                            var pending = new Execution<Void>(ledger.snapshot(id), null, request.cancellable());
                            pending.authorization = authorization;
                            pending.returned = true;
                            uncertain(pending, "Task admission could not be confirmed");
                            executions.put(id, pending);
                            retained = true;
                        } catch (SQLException unavailable) {
                            /* The persisted identity remains available after the engine is recovered. / 引擎恢复后仍可通过持久化身份核对。 */}
                        throw error(ProblemCode.IO_FAILURE, "TASK_ADMISSION_PERSISTENCE",
                                "Task admission could not be confirmed; check the host task ledger");
                    }
                }
            } finally {
                if (!retained)
                    authorization.close();
            }
        }

        /**
         * Returns only caller-owned active in-memory snapshots. / 仅返回调用方所属的活动内存快照。
         * @param cursor optional decimal offset / 可选的十进制偏移
         * @param limit bounded page size / 有界页容量
         * @return copied active snapshot page / 活动快照页副本
         * @throws ApiException if pagination is invalid / 分页无效时
         */
        @Override
        public PageSlice<TaskSnapshot> list(Optional<String> cursor, int limit) throws ApiException {
            if (limit < 1 || limit > 200)
                throw error(ProblemCode.INVALID_INPUT, "TASK_PAGE", "Invalid task page size");
            int offset;
            try {
                offset = cursor.map(Integer::parseInt).orElse(0);
                if (offset < 0)
                    throw new NumberFormatException();
            } catch (NumberFormatException failure) {
                throw error(ProblemCode.INVALID_INPUT, "TASK_CURSOR", "Invalid task cursor");
            }
            synchronized (PlatformTaskRuntime.this) {
                if (revoked)
                    throw error(ProblemCode.UNAVAILABLE, "MOD_TASK_BINDING_CLOSED",
                            "The owning Mod activation has closed");
                var all = executions.values().stream().map(t -> t.snapshot)
                        .filter(s -> s.ownerId().equals(owner) && s.state() != TaskState.ENDED).toList();
                int start = Math.min(offset, all.size());
                int end = (int) Math.min((long) start + limit, all.size());
                return new PageSlice<>(all.subList(start, end),
                        end < all.size() ? Optional.of(Integer.toString(end)) : Optional.empty());
            }
        }

        /**
         * Registers an exact original-version recovery callback. / 登记精确原始版本的恢复回调。
         * @param taskType owner-local type / 所有者内部类型
         * @param recovery explicit reconciliation implementation / 显式核对实现
         * @return owned registration / 所属登记
         * @throws ApiException if the type is invalid or already registered / 类型无效或已登记时
         */
        @Override
        public RecoveryRegistration registerRecovery(String taskType, TaskRecovery recovery) throws ApiException {
            if (taskType.isBlank() || taskType.length() > 128)
                throw error(ProblemCode.INVALID_INPUT, "TASK_RECOVERY_TYPE", "Invalid recovery task type");
            var key = new RecoveryKey(owner, version, taskType);
            Objects.requireNonNull(recovery);
            synchronized (PlatformTaskRuntime.this) {
                if (revoked)
                    throw error(ProblemCode.UNAVAILABLE, "MOD_TASK_BINDING_CLOSED",
                            "The owning Mod activation has closed");
                if (closing || recoveries.putIfAbsent(key, recovery) != null)
                    throw error(ProblemCode.BUSY, "TASK_RECOVERY_DUPLICATE",
                            "Recovery is already registered or runtime is closing");
            }
            return () -> {
                synchronized (PlatformTaskRuntime.this) {
                    if (executions.values().stream().anyMatch(t -> t.reconciling
                            && new RecoveryKey(t.snapshot.ownerId(), t.snapshot.ownerVersion(), t.snapshot.type())
                                    .equals(key)))
                        throw error(ProblemCode.BUSY, "TASK_RECOVERY_ACTIVE", "Recovery callback is still running");
                    recoveries.remove(key, recovery);
                }
            };
        }
    }

    /**
     * One accepted execution and its current-session completion observation. / 一次已接纳执行及其当前会话完成观察。
     * @param <T> public result payload type / 公开结果载荷类型
     */
    private final class Execution<T> implements TaskHandle<T> {
        /** Last verified snapshot, or an explicit persistence uncertainty snapshot. / 最近核实的快照或明确的持久化不确定快照。 */
        private volatile TaskSnapshot snapshot;

        /** Original one-shot author work, absent for previous-boot recovery. / 原始单次作者工作，前一启动会话恢复时为空。 */
        private final TaskWork<T> work;

        /** Cooperative cancellation capability. / 协作取消能力。 */
        private final boolean cancellable;

        /** Shared cancellation signal without thread interruption. / 不中断线程的共享取消信号。 */
        private volatile boolean cancelled;

        /** Whether the author callback returned and no longer starts work. / 作者回调是否已返回且不再开始工作。 */
        private boolean returned;

        /** Whether explicit reconciliation currently owns this context. / 显式核对当前是否持有此上下文。 */
        private volatile boolean reconciling;

        /** Whether this execution observed an unreconciled external effect. / 此次执行是否观察到尚未核对的外部副作用。 */
        private boolean uncertainEffect;

        /** Observed result retained while cleanup is pending. / 清理待完成期间保留的已观察结果。 */
        private OperationResult<T> observed;

        /** Original retained key scope, absent after restart or completed memory cleanup. / 原保留密钥作用域，重启或内存清理完成后为空。 */
        private DesktopSecurityService.Task authorization;

        /** Authentic host context associated with this execution. / 与此执行关联的真实宿主上下文。 */
        private final ExecutionContext context = new ExecutionContext(this);

        /** Private completion promise exposed only as a minimal stage. / 仅以最小阶段暴露的私有完成承诺。 */
        private final CompletableFuture<OperationResult<T>> completion = new CompletableFuture<>();
        /**
         * Creates a handle after durable admission or recovery lookup. / 在持久化准入或恢复查找后创建句柄。
         * @param snapshot persisted initial state / 持久化初始状态
         * @param work original callback, absent during recovery / 原始回调，恢复时为空
         * @param cancellable whether cooperative cancellation is supported / 是否支持协作取消
         */
        private Execution(TaskSnapshot snapshot, TaskWork<T> work, boolean cancellable) {
            this.snapshot = snapshot;
            this.work = work;
            this.cancellable = cancellable;
        }

        /** Returns the durable task identity. / 返回持久化任务身份。
         * @return exact task identity / 精确任务身份 */
        @Override
        public UUID id() {
            return snapshot.id();
        }

        /** Returns the latest immutable observation. / 返回最新不可变观察。
         * @return owned snapshot / 所属快照 */
        @Override
        public TaskSnapshot snapshot() {
            return snapshot;
        }

        /** Exposes observation without allowing author completion of the host promise. / 暴露观察，不允许作者完成宿主承诺。
         * @return read-only completion stage / 只读完成阶段 */
        @Override
        public CompletionStage<OperationResult<T>> completion() {
            return completion.minimalCompletionStage();
        }

        /**
         * Requests cancellation without interrupting a thread or releasing resources. / 请求取消，不中断线程或释放资源。
         * @throws ApiException if cancellation or persistence fails / 取消或持久化失败时
         */
        @Override
        public void requestCancel() throws ApiException {
            boolean ended;
            synchronized (PlatformTaskRuntime.this) {
                if (!cancellable)
                    throw error(ProblemCode.UNAVAILABLE, "TASK_NOT_CANCELLABLE", "Task does not support cancellation");
                try {
                    ended = ledger.cancel(id(), this::releaseAuthorization);
                    cancelled = true;
                    snapshot = ledger.snapshot(id());
                    refreshAuthorizationBlockers();
                    if (ended) {
                        returned = true;
                        observed = new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
                    }
                    dispatch();
                } catch (SQLException failure) {
                    if (authorization == null) {
                        returned = true;
                        uncertain(this, "Cancellation finalization requires reconciliation");
                        refreshAuthorizationBlockers();
                    }
                    throw error(ProblemCode.IO_FAILURE, "TASK_CANCEL_PERSISTENCE",
                            "Cancellation request could not be confirmed");
                }
            }
            if (ended)
                completeObserved();
        }

        /** Publishes only the already persisted and fully cleaned result. / 仅发布已持久化且完全收尾的结果。 */
        private void completeObserved() {
            synchronized (PlatformTaskRuntime.this) {
                executions.remove(id(), this);
                refreshAuthorizationBlockers();
            }
            completion.complete(observed);
        }

        /** Releases retained credentials only after business outcome and resource cleanup are known. / 仅在业务结果与资源清理已知后释放保留凭据。 */
        private void releaseAuthorization() {
            var owned = authorization;
            authorization = null;
            refreshAuthorizationBlockers();
            if (owned != null)
                owned.close();
        }
    }

    /** Authentic context used by author code and host adapters. / 作者代码与宿主适配器使用的真实上下文。 */
    private final class ExecutionContext implements TaskContext {
        /** Owning accepted execution. / 所属已接纳执行。 */
        private final Execution<?> execution;
        /**
         * Binds one accepted execution. / 绑定一个已接纳执行。
         * @param execution accepted owner / 已接纳所有者
         */
        private ExecutionContext(Execution<?> execution) {
            this.execution = execution;
        }

        /** Returns the original task identity. / 返回原始任务身份。
         * @return durable task identity / 持久化任务身份 */
        @Override
        public UUID id() {
            return execution.id();
        }

        /** Returns the cooperative cancellation signal. / 返回协作取消信号。
         * @return cancellation observation / 取消观察 */
        @Override
        public CancellationToken cancellation() {
            return () -> execution.cancelled;
        }

        /**
         * Updates bounded progress within an active context. / 在活动上下文中更新有界进度。
         * @param text non-secret progress / 不含秘密的进度
         * @param fraction optional finite fraction from zero to one / 可选的零到一有限比例
         * @throws ApiException if context, progress or persistence fails / 上下文、进度或持久化失败时
         */
        @Override
        public void progress(String text, Optional<Double> fraction) throws ApiException {
            if (text == null || text.length() > 4096 || fraction == null || fraction.isPresent()
                    && (!Double.isFinite(fraction.get()) || fraction.get() < 0 || fraction.get() > 1))
                throw error(ProblemCode.INVALID_INPUT, "TASK_PROGRESS", "Invalid task progress");
            synchronized (PlatformTaskRuntime.this) {
                requireContext(this);
                try {
                    ledger.progress(id(), text);
                    execution.snapshot = ledger.snapshot(id());
                } catch (SQLException failure) {
                    throw error(ProblemCode.IO_FAILURE, "TASK_PROGRESS_PERSISTENCE",
                            "Task progress could not be saved");
                }
            }
        }

        /**
         * Retains an owner-local custom resource without acquiring a server lock. / 保留所有者内部自定义资源，不取得服务器锁。
         * @param kind author-local category / 作者内部类别
         * @param localId author-local identity / 作者内部身份
         * @param description bounded non-secret description / 有界且不含秘密的描述
         * @return explicit close marker / 显式关闭标记
         * @throws ApiException if the identity or registration fails / 身份或登记失败时
         */
        @Override
        public ResourceLease retain(String kind, String localId, String description) throws ApiException {
            if (kind == null || !kind.matches("[a-z][a-z0-9_]{0,63}") || localId == null || localId.isBlank()
                    || localId.length() > 512 || description == null || description.length() > 4096)
                throw error(ProblemCode.INVALID_INPUT, "TASK_RESOURCE_ID", "Invalid local resource identity");
            return PlatformTaskRuntime.this.retain(this, new TaskResource("CUSTOM",
                    execution.snapshot.ownerId() + "/" + kind + "/" + localId, execution.snapshot.ownerId(), false));
        }
    }

    /** Explicit close marker whose failed persistence keeps occupancy and permits retry. / 持久化失败时保留占用并允许重试的显式关闭标记。 */
    private final class OwnedLease implements ResourceLease {
        /** Original execution retaining this resource. / 保留此资源的原始执行。 */
        private final Execution<?> execution;

        /** Durable exact resource identity. / 持久化精确资源身份。 */
        private final UUID id;
        /**
         * Binds a persisted explicit resource. / 绑定已持久化显式资源。
         * @param execution original task / 原始任务
         * @param id exact lease identity / 精确占用身份
         */
        private OwnedLease(Execution<?> execution, UUID id) {
            this.execution = execution;
            this.id = id;
        }

        /** Returns the durable lease identity. / 返回持久化占用身份。
         * @return exact lease identity / 精确占用身份 */
        @Override
        public UUID id() {
            return id;
        }

        /**
         * Records the caller's actual release and retries known-result finishing. / 记录调用方的实际释放，并重试已知结果的收尾。
         * @throws ApiException if release persistence fails / 释放持久化失败时
         */
        @Override
        public void close() throws ApiException {
            boolean ended = false;
            synchronized (PlatformTaskRuntime.this) {
                try {
                    ledger.release(execution.id(), id);
                    if (execution.returned && !execution.reconciling && execution.observed != null
                            && execution.snapshot.state() == TaskState.FINISHING)
                        ended = record(execution, false);
                    else
                        execution.snapshot = ledger.snapshot(execution.id());
                    dispatch();
                } catch (SQLException failure) {
                    throw error(ProblemCode.IO_FAILURE, "TASK_RELEASE_PERSISTENCE",
                            "Resource release could not be confirmed");
                }
            }
            if (ended)
                execution.completeObserved();
        }
    }
}
