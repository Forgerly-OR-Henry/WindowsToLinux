package gold.debug.windowstolinux.web.task.scheduler;

import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;

import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.entity.StoredTask;
import gold.debug.windowstolinux.web.db.persistence.repository.WebTaskRepository;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.task.model.TaskState;
import gold.debug.windowstolinux.web.task.model.WebTaskPolicy;
import org.springframework.dao.DataAccessException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates bounded background work, target exclusion, durable decisions and conservative restart recovery.
 * <p>协调有界后台工作、目标互斥、持久化决策及保守重启恢复。
 */
public final class WebTaskScheduler implements AutoCloseable {
    /**
     * ACTIVE.
     * <p>活跃。
     */
    private static final Set<String> ACTIVE = Set.of("QUEUED", "ANALYZING", "RUNNING", "WAITING_DECISION", "CANCELLING");
    /**
     * Bound web task repository collaborator for persistence boundary for the owned records.
     * <p>处理所属记录的持久化边界的Web任务仓库协作对象。
     */
    private final WebTaskRepository repository;
    /**
     * Bound executor service collaborator for workers.
     * <p>处理工作线程集合的执行器服务协作对象。
     */
    private final ExecutorService workers;
    /**
     * Running.
     * <p>运行中。
     */
    private final Map<String, RunningTask> running = new ConcurrentHashMap<>();
    /**
     * Shared operation locks indexed by target identity.
     * <p>按目标身份索引的共享操作锁。
     */
    private final Map<String, java.util.concurrent.locks.ReentrantLock> locks = new ConcurrentHashMap<>();
    /**
     * Bound web task policy collaborator for explicit validation and resource-bound policy.
     * <p>处理显式校验及资源边界策略的Web任务策略协作对象。
     */
    private final WebTaskPolicy policy;
    /**
     * Closed.
     * <p>已关闭。
     */
    private boolean closed;

    /**
     * Binds the supplied dependencies and state for web task scheduler.
     * <p>为Web任务Scheduler绑定传入的依赖及状态。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param policy explicit validation and resource-bound policy / 显式校验及资源边界策略
     */
    public WebTaskScheduler(WebTaskRepository repository, WebTaskPolicy policy) {
        this.repository = repository; this.policy = policy;
        repository.recoverInterrupted();
        workers = Executors.newFixedThreadPool(policy.workers(), Thread.ofPlatform().name("web-task-", 0).factory());
    }

    /**
     * Submits web task scheduler.
     * <p>提交Web任务Scheduler。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param operation operation / 操作
     * @return submit text / 提交文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public synchronized String submit(WebRequestContext context, PreparedWebOperation operation) {
        if (closed || running.size() >= policy.maxActive()) throw new IllegalStateException("Task queue is unavailable or full");
        String id = UUID.randomUUID().toString(); ResourceScope scope = scope(context);
        repository.create(scope, id, operation.kind(), WebJsonCodec.write(operation.request()), operation.serverIds(), operation.mutating(),
                operation.sourceId(), operation.applicationId(), operation.backupId());
        var task = new RunningTask(scope, id); running.put(key(scope, id), task);
        task.future = workers.submit(() -> execute(task, operation)); return id;
    }

    /**
     * Lists json node.
     * <p>列出JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    public JsonNode list(WebRequestContext context) {
        var result = new ArrayList<JsonNode>();
        for (var row : repository.list(scope(context))) result.add(snapshot(scope(context), row));
        return WebJsonCodec.tree(result);
    }
    /**
     * Returns json node.
     * <p>返回JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return json node / JSON节点
     */
    public JsonNode get(WebRequestContext context, String id) { return snapshot(scope(context), require(scope(context), id)); }
    /**
     * Reads recorded events in the order required by the caller's cursor.
     * <p>按调用方游标要求的顺序读取已记录事件。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param after after / 之后
     * @return recorded events in the order required by the caller's cursor / 按调用方游标要求的顺序读取已记录事件
     */
    public JsonNode events(WebRequestContext context, String id, long after) {
        ResourceScope scope = scope(context); require(scope, id);
        return WebJsonCodec.tree(repository.events(scope, id, after).stream().map(event -> Map.of("sequence", event.sequence(), "kind", event.kind(),
                "message", event.message(), "details", WebJsonCodec.read(event.detailJson()), "createdAt", event.createdAt())).toList());
    }

    /**
     * Cancels web task scheduler.
     * <p>取消Web任务Scheduler。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public void cancel(WebRequestContext context, String id) {
        ResourceScope scope = scope(context); StoredTask row = require(scope, id);
        if (TaskState.valueOf(row.state()).terminal()) return;
        RunningTask task = running.get(key(scope, id));
        if (task == null) throw new IllegalStateException("Task requires revalidation");
        task.cancelled.set(true);
        repository.transition(scope, id, Set.of("QUEUED", "ANALYZING", "RUNNING", "WAITING_DECISION"), "CANCELLING", null, null);
        synchronized (task) {
            task.notifyAll();
            if (task.thread != null) task.thread.interrupt();
        }
    }

    /**
     * Records the answer for web task scheduler.
     * <p>记录以下交互的回答：Web任务Scheduler。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param taskId task id / 任务标识
     * @param decisionId decision id / 决定标识
     * @param answer answer / 回答
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public void answer(WebRequestContext context, String taskId, String decisionId, JsonNode answer) {
        ResourceScope scope = scope(context); require(scope, taskId);
        RunningTask task = running.get(key(scope, taskId));
        if (task == null || answer == null || !answer.isObject() || WebJsonCodec.write(answer).length() > 32768)
            throw new IllegalArgumentException("Decision unavailable or invalid");
        synchronized (task) {
            validateAnswer(task.decisionKind, task.decisionPrompt, answer);
            if (!decisionId.equals(task.decisionId) || task.answer != null
                    || !repository.answer(scope, taskId, decisionId, WebJsonCodec.write(answer), Instant.now()))
                throw new IllegalStateException("Decision expired or already answered");
            task.answer = answer.deepCopy(); task.notifyAll();
        }
    }

    /**
     * Validates an answer against the pending decision's type, exact field set and safe input constraints.
     * <p>根据待处理决策的类型、精确字段集合及安全输入约束校验回答。
     *
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param prompt prompt / 提示
     * @param answer answer / 回答
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void validateAnswer(String kind, JsonNode prompt, JsonNode answer) {
        if (kind == null) throw new IllegalStateException("No pending decision");
        switch (kind) {
            case "HOST_KEY", "CONFIRM" -> {
                WebRequestValidator.fields(answer, "accepted");
                if (!answer.path("accepted").isBoolean()) throw new IllegalArgumentException("Expected boolean decision");
            }
            case "SECRET" -> {
                WebRequestValidator.fields(answer, "secretId");
                ResourceScope.identifier(WebRequestValidator.text(answer, "secretId", 64));
            }
            case "DATABASE_REPLACEMENT" -> {
                WebRequestValidator.fields(answer, "backupConfirmed", "downtimeConfirmed", "replacementConfirmed");
                for (String field : List.of("backupConfirmed", "downtimeConfirmed", "replacementConfirmed"))
                    if (!answer.path(field).isBoolean()) throw new IllegalArgumentException("Expected explicit boolean acknowledgements");
            }
            case "INPUTS" -> {
                WebRequestValidator.fields(answer, "values");
                var names = new ArrayList<String>();
                for (var field : prompt.path("fields")) names.add(field.path("id").asText());
                var values = answer.path("values"); WebRequestValidator.fields(values, names.toArray(String[]::new));
                WebRequestValidator.validateNonSecretInputs(values);
                for (var field : prompt.path("fields")) {
                    var value=values.path(field.path("id").asText());
                    if (!value.isTextual() || value.asText().length()>4096 || value.asText().indexOf('\0')>=0)
                        throw new IllegalArgumentException("Invalid field value");
                    if (!field.path("choices").isEmpty()) {
                        boolean allowed=false;
                        for (var choice : field.path("choices")) if (choice.equals(value)) allowed=true;
                        if (!allowed) throw new IllegalArgumentException("Unexpected field choice");
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported decision");
        }
    }

    /**
     * Acquires target locks, runs the prepared operation and persists its terminal state; interruption of mutations requires revalidation before retry.
     * <p>获取目标锁、运行已准备操作并持久化终态；变更操作被中断时须在重试前重新验证。
     *
     * @param task task / 任务
     * @param operation operation / 操作
     */
    private void execute(RunningTask task, PreparedWebOperation operation) {
        var held = new ArrayList<java.util.concurrent.locks.ReentrantLock>();
        synchronized (task) { task.thread = Thread.currentThread(); }
        try {
            task.checkCancelled();
            if (operation.mutating()) for (String target : operation.lockKeys().stream().distinct().sorted().toList()) {
                var lock = locks.computeIfAbsent(target, ignored -> new java.util.concurrent.locks.ReentrantLock(true));
                lock.lockInterruptibly(); held.add(lock); task.checkCancelled();
            }
            if (!repository.transition(task.scope, task.id, Set.of("QUEUED"), "RUNNING", null, null)) task.checkCancelled();
            JsonNode result = operation.work().execute(task);
            task.checkCancelled();
            repository.transition(task.scope, task.id, Set.of("RUNNING", "ANALYZING"), task.completion.name(), WebJsonCodec.write(result),
                    task.completion == OperationCompletionState.SUCCEEDED ? null : "BUSINESS_OPERATION_FAILED");
        } catch (Exception failure) {
            boolean interrupted = task.cancelled.get() || Thread.currentThread().isInterrupted() || failure instanceof InterruptedException || failure instanceof CancellationException;
            Thread.interrupted();
            try {
                // Interrupted mutations need a fresh remote observation before another attempt. / 已中断变更在再次尝试前需要新的远端观测。
                String state = task.completion == OperationCompletionState.REVALIDATION_REQUIRED ? "REVALIDATION_REQUIRED"
                        : interrupted ? (operation.mutating() ? "REVALIDATION_REQUIRED" : "CANCELLED") : "FAILED";
                String code = interrupted ? "OPERATION_CANCELLED" : failure instanceof TimeoutException ? "DECISION_EXPIRED"
                        : failure instanceof IllegalArgumentException ? "INPUT_REJECTED" : "OPERATION_FAILED";
                repository.transition(task.scope, task.id, ACTIVE, state, null, code);
            } catch (DataAccessException persistenceFailure) { System.err.println("Web task state could not be persisted; restart revalidation required."); }
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlock();
            running.remove(key(task.scope, task.id));
            synchronized (task) { task.thread = null; }
        }
    }

    /**
     * Validates and returns stored task.
     * <p>校验并返回已存储任务。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved stored task / 构造或解析得到的已存储任务
     */
    private StoredTask require(ResourceScope scope, String id) {
        return repository.find(scope, id).orElseThrow(() -> new NoSuchElementException("Task not found"));
    }
    /**
     * Builds the bounded task response from persisted state, safe result and any pending decision.
     * <p>根据持久化状态、安全结果及待处理决策构建有界任务响应。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param task task / 任务
     * @return the bounded task response from persisted state, safe result and any pending decision / 根据持久化状态、安全结果及待处理决策构建有界任务响应
     */
    private JsonNode snapshot(ResourceScope scope, StoredTask task) {
        var json = WebJsonCodec.object().put("id", task.id()).put("kind", task.kind()).put("state", task.state())
                .put("createdAt", task.createdAt()).put("updatedAt", task.updatedAt()).put("errorCode", task.errorCode());
        json.set("result", task.resultJson() == null ? null : WebJsonCodec.read(task.resultJson()));
        var decision = repository.pendingDecision(scope, task.id());
        if (!decision.isEmpty()) json.set("decision", WebJsonCodec.object().put("id", decision.get("id")).put("kind", decision.get("kind"))
                .put("expiresAt", decision.get("expiresAt")).set("prompt", WebJsonCodec.read(decision.get("prompt"))));
        return json;
    }
    /**
     * Resolves the ownership scope supplied by the trusted caller.
     * <p>解析可信调用方提供的归属作用域。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return the ownership scope supplied by the trusted caller / 可信调用方提供的归属作用域
     */
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(), context.userId()); }
    /**
     * Combines workspace and task identifiers into the in-memory scheduler lookup key.
     * <p>将工作区及任务标识组合为内存调度器查找键。
     *
     * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return key text / 键文本
     */
    private static String key(ResourceScope scope, String id) { return scope.workspaceId() + "/" + id; }

    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     *
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    @Override public void close() {
        synchronized (this) { closed = true; }
        for (var task : running.values()) {
            task.cancelled.set(true);
            synchronized (task) { task.notifyAll(); if (task.thread != null) task.thread.interrupt(); }
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(policy.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
                if (!workers.awaitTermination(policy.forcedShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) throw new IllegalStateException("Web tasks did not stop");
            }
        } catch (InterruptedException interrupted) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    /**
     * Owns one task's worker, decisions, cancellation and final completion state.
     * <p>持有一个任务的工作线程、决定、取消及最终完成状态。
     */
    private final class RunningTask implements TaskInteraction {
        /**
         * Ownership or configuration scope of the operation.
         * <p>操作的归属或配置作用域。
         */
        private final ResourceScope scope;
        /**
         * Stable identifier within the owning registry.
         * <p>所属登记表内的稳定标识。
         */
         private final String id;
        /**
         * Cancelled.
         * <p>已取消。
         */
         private final AtomicBoolean cancelled = new AtomicBoolean();
        /**
         * Future.
         * <p>异步结果。
         */
        private Future<?> future;
        /**
         * Thread.
         * <p>线程。
         */
         private Thread thread;
        /**
         * Decision id.
         * <p>决定标识。
         */
         private String decisionId;
        /**
         * Answer.
         * <p>回答。
         */
         private JsonNode answer;
        /**
         * Ordered progress or transaction events.
         * <p>有序进度或事务事件。
         */
         private int events;
        /**
         * Decision kind.
         * <p>决定种类。
         */
        private String decisionKind;
        /**
         * Decision prompt.
         * <p>决定提示。
         */
         private JsonNode decisionPrompt;
        /**
         * Completion.
         * <p>完成。
         */
        private OperationCompletionState completion = OperationCompletionState.SUCCEEDED;
        /**
         * Binds the supplied dependencies and state for running task.
         * <p>为运行中任务绑定传入的依赖及状态。
         *
         * @param scope ownership or configuration scope of the operation / 操作的归属或配置作用域
         * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
         */
        private RunningTask(ResourceScope scope, String id) { this.scope = scope; this.id = id; }
        /**
         * Records the business completion state for the task executor.
         * <p>为任务执行器记录业务完成状态。
         *
         * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        @Override public void completion(OperationCompletionState state) { completion = Objects.requireNonNull(state); }
        /**
         * Checks cancelled.
         * <p>检查已取消。
         *
         * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
         */
        @Override public void checkCancelled() throws InterruptedException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Task cancelled");
        }
        /**
         * Publishes bounded progress information through the task interaction contract.
         * <p>通过任务交互契约发布有界进度信息。
         *
         * @param code stable machine-readable classification code / 稳定的机器可读分类码
         * @param details details / 详情
         * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        @Override public void progress(String code, JsonNode details) throws Exception {
            checkCancelled();
            if (!code.matches("[A-Za-z0-9_.:-]{1,120}") || ++events > policy.maxEvents()) throw new IllegalArgumentException("Invalid task progress");
            repository.event(scope, id, "PROGRESS", code, WebJsonCodec.write(details));
        }
        /**
         * Persists a new bounded decision, publishes WAITING_DECISION and waits for an answer, timeout or cancellation.
         * <p>持久化新的有界决策、发布 WAITING_DECISION，并等待回答、超时或取消。
         *
         * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
         * @param prompt prompt / 提示
         * @return constructed or resolved json node / 构造或解析得到的JSON节点
         * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
         * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
         */
        @Override public synchronized JsonNode decide(String kind, JsonNode prompt) throws Exception {
            checkCancelled(); decisionId = UUID.randomUUID().toString(); answer = null;
            decisionKind=kind; decisionPrompt=prompt.deepCopy();
            Instant expires = Instant.now().plus(policy.decisionTimeout());
            repository.createDecision(scope, id, decisionId, kind, WebJsonCodec.write(prompt), expires);
            if (!repository.transition(scope, id, Set.of("RUNNING", "ANALYZING"), "WAITING_DECISION", null, null)) {
                checkCancelled(); throw new IllegalStateException("Task cannot ask for a decision");
            }
            while (answer == null) {
                checkCancelled(); long remaining = Duration.between(Instant.now(), expires).toMillis();
                if (remaining <= 0) throw new TimeoutException("Decision expired");
                wait(Math.min(1000, remaining));
            }
            checkCancelled();
            if (!repository.transition(scope, id, Set.of("WAITING_DECISION"), "RUNNING", null, null)) {
                checkCancelled(); throw new IllegalStateException("Task cannot continue");
            }
            return answer.deepCopy();
        }
    }
}
