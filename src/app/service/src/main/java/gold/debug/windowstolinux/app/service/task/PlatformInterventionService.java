package gold.debug.windowstolinux.app.service.task;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.intervention.*;

/** Volatile original-task review queue; it never changes the durable task state. / 原任务内存核验队列，不改变持久任务状态。 */
public final class PlatformInterventionService implements AutoCloseable {
    /** Original task identity and execution authority. / 原任务身份及执行授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Admission-ordered unresolved decisions, without persisted form data. / 按准入排序的未决决定，不持久化表单数据。 */
    private final Map<UUID, Request<?>> requests = new LinkedHashMap<>();

    /** Releases that explicitly opted into host review. / 明确启用宿主核验的发布。 */
    private final Set<Release> enabled = new HashSet<>();

    /** Exact tasks currently visible in their original handling pages. / 当前在原处理页面实际可见的精确任务。 */
    private final Set<Identity> visible = new HashSet<>();

    /** Original tasks explicitly deferred in the shared review window. / 在共用核验窗口明确延期的原任务。 */
    private final Set<Identity> deferredTasks = new HashSet<>();

    /** Host display observers notified outside runtime monitors. / 在运行时监视器之外通知的宿主展示观察者。 */
    private final Set<Runnable> observers = new LinkedHashSet<>();

    /** Actual author decision leases whose release is still running or needs retry. / 仍在释放或需要重试的实际作者决定占用。 */
    private final Set<Request<?>> pendingReleases = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Original desktop privacy registration. / 原桌面隐私登记。 */
    private final AutoCloseable privacy;

    /** The one mounted form shared by every request kind. / 全部请求类型共用的唯一已挂载表单。 */
    private HostSession mounted;

    /** Privacy and owner shutdown boundaries. / 隐私及所有者关闭边界。 */
    private boolean locked, closed;

    /** Installs one transient queue under the original runtime. / 在原运行时下安装唯一临时队列。
     * @param tasks original task authority / 原任务授权方 */
    public PlatformInterventionService(PlatformTaskRuntime tasks) {
        this.tasks = Objects.requireNonNull(tasks);
        tasks.installInterventions(this);
        this.privacy = tasks.observeInterventionLock(this::setLocked);
    }

    /** Only explicit capability use opts a release into queued prompts. / 仅明确使用此能力的发布才启用排队提示。
     * @param task original task observation / 原任务观察
     * @return whether its exact release opted in / 其精确发布是否已经启用 */
    public synchronized boolean manages(TaskSnapshot task) {
        return !closed && enabled.contains(new Release(task.ownerId(), task.ownerVersion()));
    }

    /** Checks ordinary callback and exact intervention navigation on a worker; recovery retains its existing presenter.
     * / 在工作线程检查普通回调及精确介入导航资格，恢复保留既有呈现器。
     * @param task original task observation / 原任务观察
     * @return whether the actual ordinary callback can use this queue / 实际普通回调是否可使用此队列 */
    public boolean canPresent(TaskSnapshot task) {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Prompt eligibility requires a worker");
        if (!manages(task)) return false;
        return tasks.interventionNavigable(task);
    }

    /** Reads published execution state for the window without acquiring the ledger monitor. / 不取得账本监视器地读取窗口所需的已发布执行状态。
     * @param originalTask original identity / 原任务标识
     * @return display-only live execution observation / 纯展示存活执行观察 */
    public boolean taskActive(UUID originalTask) {
        return tasks.snapshots().stream().anyMatch(task -> task.id().equals(originalTask)
            && (task.state() == TaskState.RUNNING || task.state() == TaskState.WAITING));
    }

    /** Reads whether the exact original task page is actually visible. / 读取精确原任务页面是否实际可见。
     * @param originalTask original task / 原任务
     * @return actual unlocked visibility without task or database work / 实际解锁可见性，不执行任务或数据库工作 */
    public synchronized boolean taskVisible(UUID originalTask) {
        return !locked && !closed && taskActive(originalTask)
            && visible.stream().anyMatch(identity -> identity.id.equals(originalTask));
    }

    /** Requests original cooperative cancellation before withdrawing that task's questions. / 先请求原任务协作取消，再撤回其问题。
     * @param originalTask original live task / 原存活任务
     * @return actual original cancellation observation / 实际原取消观察
     * @throws ApiException if current access or original cooperative cancellation is unavailable / 当前访问或原协作取消不可用时 */
    public TaskSnapshot cancelTask(UUID originalTask) throws ApiException {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Task cancellation requires a worker");
        var snapshot = tasks.requestCancellation(originalTask);
        List<Request<?>> cancelled;
        synchronized (this) {
            cancelled = requests.values().stream().filter(request -> request.task.id().equals(originalTask)).toList();
        }
        cancelled.forEach(request -> request.cancel());
        return snapshot;
    }

    /** Binds the capability to one explicit activation without lending task ownership. / 将能力绑定到明确启用，不转借任务归属。
     * @param owner exact Mod owner / 精确 Mod 所有者
     * @param version exact loaded release / 精确已加载发布
     * @param active original activation lifetime / 原启用寿命
     * @return release-bound review capability / 发布绑定核验能力 */
    public UiInterventionService scoped(String owner, String version, BooleanSupplier active) {
        var release = new Release(owner, version);
        Objects.requireNonNull(active);
        synchronized (this) {
            if (closed) throw new IllegalStateException("Interventions are closed");
            enabled.add(release);
        }
        return new UiInterventionService() {
            /** Rejects calls after the original activation closes. / 拒绝原启用关闭后的调用。
             * @throws ApiException if the activation is unavailable / 启用不可用时 */
            private void requireActive() throws ApiException {
                if (!active.getAsBoolean())
                    throw failure("TASK_INTERVENTION_CLOSED", "The original activation is unavailable");
                synchronized (PlatformInterventionService.this) {
                    if (closed || !enabled.contains(release))
                        throw failure("TASK_INTERVENTION_CLOSED", "The original activation is unavailable");
                }
            }

            /** Verifies the original task against this bound release. / 对照所属发布核验原任务。
             * @param id original identity / 原任务标识
             * @return authentic current task / 真实当前任务
             * @throws ApiException if ownership or lifetime differs / 归属或寿命不符时 */
            private TaskSnapshot owned(UUID id) throws ApiException {
                requireActive();
                return tasks.requireIntervention(id, owner, version);
            }

            /** Queues author input under the authentic context. / 在真实上下文下排队作者输入。
             * @param <T> decision type / 决定类型
             * @param task authentic context / 真实上下文
             * @param spec non-secret presentation / 非秘密展示
             * @param form fresh form factory / 新表单工厂
             * @return unresolved decision / 未决决定
             * @throws ApiException if identity or lifetime is invalid / 身份或寿命无效时 */
            @Override
            public <T> UiInterventionSession<T> request(TaskContext task, UiInterventionSpec spec,
                                                        UiInterventionFormFactory<T> form) throws ApiException {
                if (SwingUtilities.isEventDispatchThread())
                    throw new IllegalStateException("Task input requires a worker");
                requireActive();
                Objects.requireNonNull(spec);
                Objects.requireNonNull(form);
                var original = tasks.requireExecuting(task, owner, version);
                owned(original.id());
                var lease = task.retain("interaction", "host-review-" + UUID.randomUUID(), "Host task review");
                return register(original, task.cancelToken(), spec, form, active, false, lease);
            }

            /** Publishes exact original-page visibility from the active scope without acquiring the ledger monitor. / 从活动作用域发布精确原页面可见性，不取得账本监视器。
             * @param id original identity / 原任务标识
             * @param value actual visibility / 实际可见性
             * @throws ApiException if the visible task is foreign or ended / 可见任务不符或已结束时 */
            @Override
            public void visible(UUID id, boolean value) throws ApiException {
                if (value) requireActive();
                PlatformInterventionService.this.visible(owner, version, id, value);
            }

            /** Reads current transient presentation without changing the task. / 读取当前临时展示，不改变任务。
             * @param id original identity / 原任务标识
             * @return current count and actual progress / 当前计数及真实进度
             * @throws ApiException if the task is foreign or no longer executing / 任务不符或不再执行时 */
            @Override
            public UiInterventionSnapshot snapshot(UUID id) throws ApiException {
                owned(id);
                return new UiInterventionSnapshot(count(id), tasks.interventionFraction(id, owner, version));
            }

            /** Opens original questions after author navigation, without granting approval. / 作者导航后打开原问题，不授予批准。
             * @param originalTask original task identity / 原任务标识
             * @throws ApiException if original ownership or lifetime differs / 原归属或寿命不符时 */
            @Override
            public void show(UUID originalTask) throws ApiException {
                owned(originalTask);
                showNext(originalTask);
            }

            /** Returns the actual original handle without fabricating execution context or approval. / 返回实际原句柄，不伪造执行上下文或批准。
             * @param originalTask exact owned original UUID / 精确所属原 UUID
             * @return original runtime handle / 原运行时句柄
             * @throws ApiException if original ownership, activation or ordinary execution has expired / 原归属、启用或普通执行失效时 */
            @Override
            public gold.debug.windowstolinux.appapi.task.run.TaskHandle<?> observe(UUID originalTask) throws ApiException {
                if (SwingUtilities.isEventDispatchThread())
                    throw new IllegalStateException("Task observation requires a worker");
                requireActive();
                return tasks.interventionHandle(originalTask, owner, version);
            }
        };
    }

    /** Trusted presenter entry; ownership is rechecked against the actual runtime. / 可信呈现器入口，仍对照真实运行时重新核验归属。
     * @param <T> decision type / 决定类型
     * @param task original trusted observation / 原可信观察
     * @param cancellation original cooperative signal / 原协作取消信号
     * @param spec non-secret presentation / 非秘密展示
     * @param form fresh form factory / 新表单工厂
     * @return unresolved original decision / 原未决决定
     * @throws ApiException if the original scope cannot be confirmed / 无法确认原作用域时 */
    public <T> UiInterventionSession<T> request(TaskSnapshot task, CancelToken cancellation,
                                                UiInterventionSpec spec, UiInterventionFormFactory<T> form) throws ApiException {
        if (!manages(task)) throw failure("TASK_INTERVENTION_NOT_ENABLED", "Queued task review is not enabled");
        var current = tasks.requireIntervention(task.id(), task.ownerId(), task.ownerVersion());
        return register(current, cancellation, spec, form, () -> true, true, null);
    }

    /** Retains transient callbacks only while the original activation is available. / 仅在原启用可用期间保留临时回调。
     * @param <T> decision type / 决定类型
     * @param task authentic observation / 真实观察
     * @param cancellation original cancellation signal / 原取消信号
     * @param spec non-secret presentation / 非秘密展示
     * @param form fresh factory / 新工厂
     * @param active original activation lifetime / 原启用寿命
     * @param hostPrompt whether the host security-generation loop owns this prompt / 是否由宿主安全代次循环持有此提示
     * @param lease original author decision occupancy, absent for already tracked host prompts / 原作者决定占用，已受追踪宿主提示为空
     * @return original unresolved session / 原未决会话
     * @throws ApiException if the activation or task has expired / 启用或任务失效时 */
    private <T> UiInterventionSession<T> register(TaskSnapshot task, CancelToken cancellation,
                                                  UiInterventionSpec spec, UiInterventionFormFactory<T> form, BooleanSupplier active, boolean hostPrompt,
                                                  ResourceLease lease) throws ApiException {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(form);
        Objects.requireNonNull(cancellation);
        var request = new Request<T>(task, cancellation, spec, form, active, hostPrompt, lease);
        try {
            if (cancellation.requested()) throw failure("TASK_INTERVENTION_CANCELLED", "Original task is cancelled");
            if (!active.getAsBoolean())
                throw failure("TASK_INTERVENTION_CLOSED", "The original activation is unavailable");
            boolean refreshHost;
            synchronized (this) {
                if (closed || !enabled.contains(new Release(task.ownerId(), task.ownerVersion())))
                    throw failure("TASK_INTERVENTION_CLOSED", "The original activation is unavailable");
                request.deferred = locked || deferredTasks.contains(Identity.of(task));
                request.showRequested = !request.deferred && visible.contains(Identity.of(task));
                requests.put(request.id, request);
                refreshHost = locked && hostPrompt;
                if (refreshHost) deferredTasks.add(Identity.of(task));
            }
            validate(request);
            if (refreshHost) request.cancel();
        } catch (ApiException | RuntimeException failed) {
            boolean retained;
            synchronized (this) {
                retained = requests.get(request.id) == request;
            }
            if (retained) request.cancel();
            else request.finish(null, true);
            throw failed;
        }
        changed();
        return request;
    }

    /** Matches original-page visibility to published task identity without granting action or input authority.
     * / 将原页面可见性与已发布任务身份匹配，不授予动作或输入权限。
     * @param owner exact original owner / 精确原所有者
     * @param version exact original release / 精确原发布
     * @param id original task / 原任务
     * @param value actual visibility / 实际可见性
     * @throws ApiException if the visible task cannot be authenticated / 无法认证可见任务时 */
    public void visible(String owner, String version, UUID id, boolean value) throws ApiException {
        if (value && tasks.snapshots().stream().noneMatch(task -> task.id().equals(id)
            && task.ownerId().equals(owner) && task.ownerVersion().equals(version)
            && (task.state() == TaskState.RUNNING || task.state() == TaskState.WAITING)))
            throw failure("TASK_INTERVENTION_OWNER", "Visible task must match an active original task and release");
        HostSession hidden;
        synchronized (this) {
            var identity = new Identity(owner, version, id);
            if (value && !closed) {
                visible.add(identity);
                if (!locked) requests.values().stream().filter(request -> Identity.of(request.task).equals(identity)
                    && !request.deferred).forEach(request -> request.showRequested = true);
            } else visible.remove(identity);
            hidden = !value && mounted != null && Identity.of(mounted.request.task).equals(identity) ? mounted : null;
        }
        if (hidden != null) onEdt(hidden::defer);
        changed();
    }

    /** Called only after successful original INTERVENE navigation. / 仅在原 INTERVENE 导航成功后调用。
     * @param originalTask original task selected for intervention / 所选人工介入原任务 */
    public void showNext(UUID originalTask) {
        synchronized (this) {
            deferredTasks.removeIf(identity -> identity.id.equals(originalTask));
            requests.values().stream().filter(request -> request.task.id().equals(originalTask))
                .forEach(request -> {
                    request.deferred = false;
                    request.showRequested = true;
                });
        }
        changed();
    }

    /** Defers an original task even between consecutive preflight questions. / 即使处于连续预检问题之间，也延期原任务。
     * @param originalTask original task shown in the review window / 核验窗口显示的原任务 */
    public void deferTask(UUID originalTask) {
        HostSession current;
        synchronized (this) {
            markDeferred(originalTask);
            current = mounted != null && mounted.request.task.id().equals(originalTask) ? mounted : null;
        }
        if (current != null) onEdt(current::defer);
        changed();
    }

    /** Records task-wide deferral without affecting any other original task. / 记录整项任务延期，不影响其他原任务。
     * @param originalTask original identity / 原任务标识 */
    private void markDeferred(UUID originalTask) {
        visible.stream().filter(identity -> identity.id.equals(originalTask)).forEach(deferredTasks::add);
        requests.values().stream().filter(request -> request.task.id().equals(originalTask)).forEach(request -> {
            deferredTasks.add(Identity.of(request.task));
            request.deferred = true;
            request.showRequested = false;
        });
    }

    /** Reads unresolved requests in their original admission order. / 按原准入顺序读取未决请求。
     * @return immutable non-secret queue metadata / 不可变非秘密队列元数据 */
    public List<Pending> pending() {
        refresh();
        synchronized (this) {
            return requests.values().stream().map(request -> new Pending(request.id, request.task, request.spec,
                !locked && request.showRequested && visible.contains(Identity.of(request.task)))).toList();
        }
    }

    /** Counts pending requests without entering runtime or author code. / 不进入运行时或作者代码地统计未决请求。
     * @param originalTask original task / 原任务
     * @return unresolved request count / 未决请求数 */
    public int count(UUID originalTask) {
        synchronized (this) {
            return (int) requests.values().stream().filter(request -> request.task.id().equals(originalTask)).count();
        }
    }

    /** Subscribes to display changes delivered on the EDT. / 订阅在 EDT 交付的展示变化。
     * @param observer host presentation callback / 宿主展示回调
     * @return removable registration / 可移除登记 */
    public AutoCloseable observe(Runnable observer) {
        Objects.requireNonNull(observer);
        synchronized (this) {
            observers.add(observer);
        }
        return () -> {
            synchronized (PlatformInterventionService.this) {
                observers.remove(observer);
            }
        };
    }

    /** Opens one fresh author form on the EDT; only a visible original task can own it. / 在 EDT 打开新作者表单，仅可由实际可见的原任务持有。
     * @param requestId exact unresolved request / 精确未决请求
     * @return one mounted form lifetime / 单次已挂载表单寿命
     * @throws ApiException if visibility, identity or input creation is unavailable / 可见性、身份或输入创建不可用时 */
    public HostSession open(UUID requestId) throws ApiException {
        edt();
        tasks.requireInterventionAccess();
        Request<?> request;
        synchronized (this) {
            request = requests.get(requestId);
            if (request == null || locked || closed || mounted != null
                || !visible.contains(Identity.of(request.task)))
                throw failure("TASK_INTERVENTION_HIDDEN", "Open the original task before reviewing it");
        }
        validate(request);
        var form = Objects.requireNonNull(request.factory.create());
        try {
            Objects.requireNonNull(form.component());
            validate(request);
            synchronized (this) {
                if (requests.get(request.id) != request || locked || closed || mounted != null
                    || !visible.contains(Identity.of(request.task)))
                    throw failure("TASK_INTERVENTION_EXPIRED", "Original task review has changed");
                request.showRequested = false;
                mounted = new HostSession(request, form);
                return mounted;
            }
        } catch (ApiException | RuntimeException failure) {
            form.close();
            throw failure;
        }
    }

    /** Locking clears input, retaining author decisions while host prompts are rebuilt by their security loop. / 锁定清除输入，保留作者决定，宿主提示由所属安全循环重建。
     * @param value current privacy lock state / 当前隐私锁定状态 */
    public void setLocked(boolean value) {
        HostSession current;
        List<Request<?>> hostPrompts;
        synchronized (this) {
            locked = value;
            current = mounted;
            hostPrompts = value ? requests.values().stream().filter(request -> request.hostPrompt).toList() : List.of();
            if (value) {
                requests.values().forEach(request -> {
                    request.deferred = true;
                    request.showRequested = false;
                });
                requests.values().forEach(request -> deferredTasks.add(Identity.of(request.task)));
            }
        }
        if (value && current != null) onEdtAndWait(current::defer);
        if (!hostPrompts.isEmpty()) onEdtAndWait(() -> hostPrompts.forEach(request -> retire(request, null, true)));
        changed();
    }

    /** Withdraws all callbacks from one closed original activation. / 撤回一个已关闭原启用的全部回调。
     * @param owner original owner / 原所有者
     * @param version original release / 原发布 */
    public void revokeOwner(String owner, String version) {
        List<Request<?>> expired;
        synchronized (this) {
            enabled.remove(new Release(owner, version));
            visible.removeIf(item -> item.owner.equals(owner) && item.version.equals(version));
            deferredTasks.removeIf(item -> item.owner.equals(owner) && item.version.equals(version));
            expired = requests.values().stream().filter(request -> request.task.ownerId().equals(owner)
                && request.task.ownerVersion().equals(version)).toList();
        }
        expired.forEach(request -> request.cancel());
    }

    /** Explicitly withdraws every unresolved in-memory decision. / 明确撤回全部内存未决决定。 */
    public void withdrawAll() {
        List<Request<?>> expired;
        synchronized (this) {
            expired = List.copyOf(requests.values());
        }
        expired.forEach(request -> request.cancel());
    }

    /** Uses immutable runtime observations without calling author code under its monitor. / 使用不可变运行时观察，不在其监视器下调用作者代码。
     * @param snapshots newly published original observations / 新发布的原观察 */
    void taskChanged(List<TaskSnapshot> snapshots) {
        Set<UUID> live = new HashSet<>();
        for (var task : snapshots)
            if (task.state() == TaskState.RUNNING || task.state() == TaskState.WAITING) live.add(task.id());
        List<Request<?>> expired;
        synchronized (this) {
            expired = requests.values().stream().filter(request -> !live.contains(request.task.id())).toList();
            deferredTasks.removeIf(identity -> !live.contains(identity.id));
        }
        expired.forEach(request -> SwingUtilities.invokeLater(() -> retire(request, null, true)));
        changed();
    }

    /** Refreshes display-only lifetime from published observations without waiting on ledger I/O. / 从已发布观察更新纯展示寿命，不等待账本 I/O。 */
    public void refresh() {
        List<Request<?>> current;
        List<Request<?>> releasing;
        synchronized (this) {
            current = List.copyOf(requests.values());
            releasing = List.copyOf(pendingReleases);
        }
        releasing.forEach(Request::retryRelease);
        var live = new HashMap<UUID, TaskSnapshot>();
        for (var task : tasks.snapshots())
            if (task.state() == TaskState.RUNNING || task.state() == TaskState.WAITING) live.put(task.id(), task);
        for (var request : current) {
            var original = live.get(request.task.id());
            if (!request.active.getAsBoolean() || request.cancellation.requested() || original == null
                || !original.ownerId().equals(request.task.ownerId())
                || !original.ownerVersion().equals(request.task.ownerVersion())) request.cancel();
        }
    }

    /** Rechecks task identity, cancellation and activation before accepting input. / 接受输入前重查任务身份、取消及启用。
     * @param request original unresolved request / 原未决请求
     * @throws ApiException if any original boundary has expired / 任一原边界失效时 */
    private void validate(Request<?> request) throws ApiException {
        if (!request.active.getAsBoolean() || request.cancellation.requested())
            throw failure("TASK_INTERVENTION_EXPIRED", "The original task review is no longer active");
        tasks.requireIntervention(request.task.id(), request.task.ownerId(), request.task.ownerVersion());
    }

    /** Schedules detached display notifications without lending monitor ownership. / 调度分离的展示通知，不转借监视器所有权。 */
    private void changed() {
        List<Runnable> listeners;
        synchronized (this) {
            listeners = List.copyOf(observers);
        }
        for (var listener : listeners) SwingUtilities.invokeLater(listener);
    }

    /** Clears the mounted form before completing its original decision stage. / 完成原决定阶段前清除已挂载表单。
     * @param request original unresolved request / 原未决请求
     * @param accepted accepted disposable result / 已接受可释放结果
     * @param cancel whether the original request was withdrawn / 原请求是否被撤回 */
    private void retire(Request<?> request, Object accepted, boolean cancel) {
        edt();
        HostSession current;
        synchronized (this) {
            if (requests.remove(request.id) != request) {
                clear(accepted);
                return;
            }
            current = mounted != null && mounted.request == request ? mounted : null;
            if (current != null) mounted = null;
        }
        try {
            if (current != null) current.form.close();
        } catch (RuntimeException | Error cleanup) {
            clear(accepted);
            request.finish(null, true);
            changed();
            throw cleanup;
        }
        request.finish(accepted, cancel);
        changed();
    }

    /** Releases privacy observation and withdraws unresolved author callbacks. / 释放隐私观察并撤回未决作者回调。 */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            enabled.clear();
            visible.clear();
            deferredTasks.clear();
        }
        withdrawAll();
        tasks.removeInterventions(this);
        try {
            privacy.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Intervention privacy cleanup failed", failure);
        }
    }

    /** Non-secret metadata for the host queue and single window. / 宿主队列及单窗口的非秘密元数据。
     * @param requestId transient request identity / 临时请求标识
     * @param task original authenticated task / 原认证任务
     * @param spec non-secret question presentation / 非秘密问题展示
     * @param ready exact original page is visible and opening was not deferred / 精确原页面可见且打开未被延期 */
    public record Pending(UUID requestId, TaskSnapshot task, UiInterventionSpec spec, boolean ready) {
    }

    /** One disposable EDT-owned opening of an unresolved request. / 未决请求的单次可释放 EDT 打开。 */
    public final class HostSession {
        /** Original unresolved decision. / 原未决决定。 */
        private final Request<?> request;

        /** Fresh input state cleared when this opening ends. / 本次打开结束时清除的新输入状态。 */
        private final UiInterventionForm<?> form;

        /** Binds a fresh form to its original request. / 将新表单绑定到原请求。
         * @param request original decision / 原决定
         * @param form fresh form state / 新表单状态 */
        private HostSession(Request<?> request, UiInterventionForm<?> form) {
            this.request = request;
            this.form = form;
        }

        /** Reads the original mounted question identity. / 读取原已挂载问题身份。
         * @return non-secret mounted metadata / 非秘密已挂载元数据 */
        public Pending pending() {
            return new Pending(request.id, request.task, request.spec, false);
        }

        /** Returns the author component for the shared host window. / 返回用于宿主共用窗口的作者组件。
         * @return one EDT-owned form component / 单个 EDT 表单组件 */
        public JComponent component() {
            edt();
            return form.component();
        }

        /** Checks that this opening still owns the actual visible task. / 检查本次打开是否仍持有实际可见任务。
         * @return whether input can still be considered / 是否仍可考虑输入 */
        public boolean active() {
            synchronized (PlatformInterventionService.this) {
                return mounted == this && requests.get(request.id) == request && !locked && !closed
                    && visible.contains(Identity.of(request.task));
            }
        }

        /** Accepts valid input only after fresh original-task checks. / 仅重新核验原任务后接受有效输入。
         * @throws ApiException if input or the original task is invalid / 输入或原任务无效时 */
        public void submit() throws ApiException {
            edt();
            if (!active()) throw failure("TASK_INTERVENTION_EXPIRED", "The original review window is no longer active");
            try {
                tasks.requireInterventionAccess();
                validate(request);
            } catch (ApiException expired) {
                retire(request, null, true);
                throw expired;
            }
            Object accepted = form.submit();
            try {
                validate(request);
                if (!active()) throw failure("TASK_INTERVENTION_EXPIRED", "Original review has changed");
            } catch (ApiException failure) {
                clear(accepted);
                retire(request, null, true);
                throw failure;
            }
            retire(request, accepted, false);
        }

        /** Discards visible input while preserving the unresolved original future. / 丢弃可见输入并保留原未决未来值。 */
        public void defer() {
            edt();
            synchronized (PlatformInterventionService.this) {
                if (mounted != this) return;
                mounted = null;
                markDeferred(request.task.id());
            }
            try {
                form.close();
            } finally {
                changed();
            }
        }

        /** Explicitly withdraws this original decision. / 明确撤回此原决定。 */
        public void cancel() {
            request.cancel();
        }
    }

    /** Original in-memory callbacks and their private completion promise. / 原内存回调及其私有完成承诺。
     * @param <T> accepted decision type / 已接受决定类型 */
    private final class Request<T> implements UiInterventionSession<T> {
        /** Identity distinct from the original task UUID. / 与原任务 UUID 不同的请求标识。 */
        private final UUID id = UUID.randomUUID();

        /** Authenticated original root task. / 真实原根任务。 */
        private final TaskSnapshot task;

        /** Original task or prompt cancellation signal. / 原任务或提示取消信号。 */
        private final CancelToken cancellation;

        /** Non-secret question metadata. / 非秘密问题元数据。 */
        private final UiInterventionSpec spec;

        /** Factory retained without any mounted secret field state. / 不保留已挂载秘密字段状态的工厂。 */
        private final UiInterventionFormFactory<T> factory;

        /** Exact original activation lifetime. / 精确原启用寿命。 */
        private final BooleanSupplier active;

        /** Host prompts are rebuilt by the original security-generation loop after locking. / 宿主提示在锁定后由原安全代次循环重建。 */
        private final boolean hostPrompt;

        /** Actual original-task occupancy retained across deferred form openings. / 跨延期表单打开保留的实际原任务占用。 */
        private final ResourceLease lease;

        /** Candidate result retained only until actual lease release succeeds. / 仅保留至实际占用释放成功的候选结果。 */
        private Object acceptedValue;

        /** Withdrawal and worker release state. / 撤回及工作线程释放状态。 */
        private boolean rejected, releasing;

        /** Private promise exposed only as a minimal stage. / 仅以最小阶段暴露的私有承诺。 */
        private final CompletableFuture<T> future = new CompletableFuture<>();

        /** A fresh visible request or explicit intervention requested opening. / 新可见请求或明确人工介入已请求打开。 */
        private boolean showRequested;

        /** Earlier input was explicitly deferred or hidden for privacy. / 先前输入被明确延期或因隐私隐藏。 */
        private boolean deferred;

        /** Captures one original decision without creating Swing input. / 捕获一个原决定，不创建 Swing 输入。
         * @param task original task / 原任务
         * @param cancellation original cancellation / 原取消信号
         * @param spec non-secret metadata / 非秘密元数据
         * @param factory fresh form factory / 新表单工厂
         * @param active original activation lifetime / 原启用寿命
         * @param hostPrompt original host security-generation prompt / 原宿主安全代次提示
         * @param lease original author decision occupancy / 原作者决定占用 */
        private Request(TaskSnapshot task, CancelToken cancellation, UiInterventionSpec spec,
                        UiInterventionFormFactory<T> factory, BooleanSupplier active, boolean hostPrompt, ResourceLease lease) {
            this.task = task;
            this.cancellation = cancellation;
            this.spec = spec;
            this.factory = factory;
            this.active = active;
            this.hostPrompt = hostPrompt;
            this.lease = lease;
        }

        /** Returns the transient request identity. / 返回临时请求标识。
         * @return exact request UUID / 精确请求 UUID */
        @Override
        public UUID id() {
            return id;
        }

        /** Observes acceptance without allowing external completion. / 观察接受结果，不允许外部完成承诺。
         * @return read-only completion stage / 只读完成阶段 */
        @Override
        public CompletionStage<T> completion() {
            return future.minimalCompletionStage();
        }

        /** Clears mounted input before withdrawing this request. / 撤回请求前清除已挂载输入。 */
        @Override
        public void cancel() {
            onEdt(() -> retire(this, null, true));
        }

        /** Releases original occupancy after the visible form has been cleared. / 可见表单清除后释放原占用。
         * @param value disposable accepted result / 可释放已接受结果
         * @param cancel whether the decision was withdrawn / 决定是否被撤回 */
        private void finish(Object value, boolean cancel) {
            if (cancel) clear(value);
            if (lease == null) {
                if (cancel) future.cancel(false);
                else accept(value);
                return;
            }
            synchronized (PlatformInterventionService.this) {
                acceptedValue = cancel ? null : value;
                rejected = cancel;
                pendingReleases.add(this);
            }
            retryRelease();
        }

        /** Retries only an actual local lease release, without replaying author input. / 仅重试实际本地占用释放，不重放作者输入。 */
        private void retryRelease() {
            synchronized (PlatformInterventionService.this) {
                if (releasing || !pendingReleases.contains(this)) return;
                releasing = true;
            }
            tasks.interactionCleanup(() -> {
                try {
                    lease.close();
                    Object value;
                    boolean cancel;
                    synchronized (PlatformInterventionService.this) {
                        pendingReleases.remove(this);
                        value = acceptedValue;
                        acceptedValue = null;
                        cancel = rejected;
                    }
                    if (cancel) future.cancel(false);
                    else accept(value);
                    changed();
                } catch (ApiException | RuntimeException failure) {
                    synchronized (PlatformInterventionService.this) {
                        clear(acceptedValue);
                        acceptedValue = null;
                        rejected = true;
                        releasing = false;
                    }
                    if (future.completeExceptionally(failure)) changed();
                }
            });
        }

        /** Completes only the decision created by this same generic factory. / 仅完成由此同一泛型工厂创建的决定。
         * @param value result from the original form / 原表单结果 */
        @SuppressWarnings("unchecked")
        private void accept(Object value) {
            future.complete((T) value);
        }
    }

    /** Exact original activation release. / 精确原启用发布。
     * @param owner Mod owner / Mod 所有者
     * @param version loaded release / 已加载发布 */
    private record Release(String owner, String version) {
        /** Rejects unbound release identities. / 拒绝未绑定发布身份。
         * @param owner exact Mod owner / 精确 Mod 所有者
         * @param version exact loaded release / 精确已加载发布 */
        private Release {
            if (owner == null || owner.isBlank() || version == null || version.isBlank())
                throw new IllegalArgumentException("Exact Mod release required");
        }
    }

    /** Exact original task identity including its release. / 包含所属发布的精确原任务身份。
     * @param owner original owner / 原所有者
     * @param version original release / 原发布
     * @param id original UUID / 原 UUID */
    private record Identity(String owner, String version, UUID id) {
        /** Captures identity from an authentic task observation. / 从真实任务观察捕获身份。
         * @param task original observation / 原观察
         * @return exact task and release identity / 精确任务及发布身份 */
        static Identity of(TaskSnapshot task) {
            return new Identity(task.ownerId(), task.ownerVersion(), task.id());
        }
    }

    /** Confines form operations and secret cleanup to the EDT. / 将表单操作及秘密清理限制到 EDT。 */
    private static void edt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Review forms require the EDT");
    }

    /** Runs detached form cleanup on its owning UI thread. / 在所属界面线程执行分离的表单清理。
     * @param work EDT-owned cleanup / EDT 所属清理 */
    private static void onEdt(Runnable work) {
        if (SwingUtilities.isEventDispatchThread()) work.run();
        else SwingUtilities.invokeLater(work);
    }

    /** Completes form cleanup before the privacy transition can be confirmed. / 隐私转换可确认前完成表单清理。
     * @param work EDT-owned secret cleanup / EDT 所属秘密清理 */
    private static void onEdtAndWait(Runnable work) {
        if (SwingUtilities.isEventDispatchThread()) work.run();
        else try {
            SwingUtilities.invokeAndWait(work);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Review privacy cleanup was interrupted");
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new IllegalStateException("Review privacy cleanup failed", failure.getCause());
        }
    }

    /** Clears host text input if acceptance was invalidated. / 接受失效时清除宿主文字输入。
     * @param value disposable candidate result / 可释放候选结果 */
    private static void clear(Object value) {
        if (value instanceof char[] chars) Arrays.fill(chars, '\0');
    }

    /** Creates a safe failure without author input or sensitive details. / 创建不含作者输入及秘密细节的安全失败。
     * @param event stable failure identity / 稳定失败标识
     * @param message bounded safe description / 有界安全描述
     * @return public capability failure / 公开能力失败 */
    private static ApiException failure(String event, String message) {
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE, event, message, Map.of(), Optional.empty()));
    }
}
