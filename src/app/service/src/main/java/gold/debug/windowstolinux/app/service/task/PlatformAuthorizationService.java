package gold.debug.windowstolinux.app.service.task;

import java.sql.SQLException;
import java.util.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.PlatformPersistence;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.task.*;

/** Owns persisted permission preference and ephemeral scopes for host-launched workflows. / 持有持久权限偏好及宿主启动流程的临时作用域。 */
public final class PlatformAuthorizationService implements AutoCloseable {
    /** Separate from business AI-review preferences. / 独立于业务 AI 审批偏好。 */
    private static final String MODE_KEY = "platform_authorization_mode";

    /** Actual platform preference store. / 实际平台偏好存储。 */
    private final PlatformPersistence persistence;

    /** Current-user preference authorization. / 当前用户偏好授权。 */
    private final DesktopSecurityService security;

    /** Original task identity and server authority. / 原任务身份及服务器授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Exact contexts only; copied task identifiers confer no workflow authority. / 仅使用精确上下文；复制任务标识不产生流程权限。 */
    private final Map<TaskContext, Scope> scopes = new IdentityHashMap<>();

    /** Revokes new platform work after shutdown. / 关闭后撤销新平台工作。 */
    private volatile boolean closed;

    /** Binds actual platform storage and task identity without opening a workflow. / 绑定实际平台存储及任务身份，不打开流程。
     * @param persistence platform preference owner / 平台偏好所有者
     * @param security current-user boundary / 当前用户边界
     * @param tasks original task authority / 原任务授权方 */
    public PlatformAuthorizationService(PlatformPersistence persistence, DesktopSecurityService security,
            PlatformTaskRuntime tasks) {
        this.persistence = Objects.requireNonNull(persistence);
        this.security = Objects.requireNonNull(security);
        this.tasks = Objects.requireNonNull(tasks);
    }

    /** Reads the current preference without reviving any previous workflow grant. / 读取当前偏好，不恢复任何前次流程授权。
     * @return saved mode, or GENERAL for an absent preference / 已存模式，偏好缺失时为 GENERAL
     * @throws SQLException if persisted data cannot be interpreted / 无法解释持久数据时 */
    public AuthorizationMode mode() throws SQLException {
        worker();
        requireOpen();
        try (var operation = security.enterOperation()) {
            try {
                return AuthorizationMode.valueOf(persistence.preferences().find(MODE_KEY).orElse("GENERAL"));
            } catch (IllegalArgumentException invalid) {
                throw new SQLException("Stored platform authorization mode is invalid", invalid);
            }
        }
    }

    /** Saves only a preference for subsequently launched workflows. / 仅保存供随后启动流程使用的偏好。
     * @param mode selected platform mode / 选定平台模式
     * @return confirmed saved mode / 确认已保存模式
     * @throws SQLException if persistence fails / 持久化失败时 */
    public AuthorizationMode saveMode(AuthorizationMode mode) throws SQLException {
        worker();
        requireOpen();
        Objects.requireNonNull(mode);
        try (var operation = security.enterOperation()) {
            persistence.preferences().save(MODE_KEY, mode.name());
            return mode;
        }
    }

    /** Creates an internal host launcher for one actual Mod activation. / 为一次实际 Mod 启用创建内部宿主启动器。
     * @param owner exact owning Mod / 精确所属 Mod
     * @param version exact activation release / 精确启用发布版本
     * @return launcher used by the host-owned start control / 宿主所属启动控件使用的启动器 */
    public Launcher launcher(String owner, String version) {
        requireOpen();
        return new Launcher(owner, version, tasks.bind(owner, version, false));
    }

    /** Resolves one platform decision while preserving explicit business scope. / 保留明确业务作用域地解决一次平台决定。
     * @param owner current capability owner / 当前能力所有者
     * @param version exact capability release / 精确能力发布版本
     * @param intent complete exact intent / 完整精确意图
     * @param task authentic original task / 真实原任务
     * @param human actual host review when required / 必要时的实际宿主审核
     * @return one observed decision tied to its original scope / 绑定原作用域的一次观测决定
     * @throws ApiException if ownership, scope or human review fails / 归属、作用域或人工审核失败时 */
    Permit review(String owner, String version, ActionIntent intent, TaskContext task, HumanReview human)
            throws ApiException {
        tasks.requireExecuting(task, owner, version);
        Scope scope;
        synchronized (this) {
            requireOpen();
            scope = scopes.get(task);
        }
        if (scope == null)
            return new Permit(task, null, human.approve());
        synchronized (scope) {
            scope.validate(task, owner, version);
            if (intent.semantics().isEmpty())
                return new Permit(task, scope, human.approve());
            var semantics = intent.semantics().orElseThrow();
            var servers = semantics.targets().stream().map(target -> target.server().id())
                    .collect(java.util.stream.Collectors.toSet());
            if (!servers.equals(Set.copyOf(intent.serverIds())) || !scope.targets.containsAll(semantics.targets()))
                throw failure(ProblemCode.ACCESS_DENIED, "AUTHORIZATION_TARGET",
                        "Operation targets differ from the launched workflow");
            var key = new SemanticKey(intent.type(), semantics);
            boolean approved = scope.mode == AuthorizationMode.FULL
                    || scope.mode == AuthorizationMode.GENERAL && scope.approved.contains(key) || human.approve();
            scope.validate(task, owner, version);
            if (approved && scope.mode == AuthorizationMode.GENERAL)
                scope.approved.add(key);
            return new Permit(task, scope, approved);
        }
    }

    /** Rejects an exact token after its originating workflow has closed. / 原流程关闭后拒绝精确令牌。
     * @param permit original platform decision / 原平台决定
     * @param task exact original task / 精确原任务
     * @throws ApiException if the original scope is no longer live / 原作用域不再活动时 */
    void validate(Permit permit, TaskContext task) throws ApiException {
        if (!permit.approved || permit.task != task)
            throw failure(ProblemCode.ACCESS_DENIED, "AUTHORIZATION_IDENTITY",
                    "Permission belongs to another operation");
        if (permit.scope != null)
            synchronized (permit.scope) {
                permit.scope.validate(task, permit.scope.owner, permit.scope.version);
            }
    }

    /** Validated user-selected task inputs captured by the host start control. / 宿主启动控件捕获的已校验用户选定任务输入。
     * @param <T> task result type / 任务结果类型
     * @param request complete task admission request / 完整任务准入请求
     * @param targets complete resolved business targets / 完整已解析业务目标
     * @param work actual business callback / 实际业务回调 */
    public record Launch<T>(TaskRequest request, Set<ActionTarget> targets, TaskWork<T> work) {
        /** Copies the explicit scope and requires its exact admitted servers. / 复制明确作用域并要求其精确准入服务器。
         * @param request original task request / 原任务请求
         * @param targets actual business targets / 实际业务目标
         * @param work actual business callback / 实际业务回调 */
        public Launch {
            Objects.requireNonNull(request);
            Objects.requireNonNull(work);
            targets = Set.copyOf(targets);
            if (targets.isEmpty() || targets.size() > 128 || !targets.stream().map(target -> target.server().id())
                    .collect(java.util.stream.Collectors.toSet()).equals(Set.copyOf(request.serverIds())))
                throw new IllegalArgumentException("Workflow targets must match the complete admitted server set");
        }
    }

    /** Internal host launch authority, absent from the ordinary author TaskService. / 内部宿主启动授权，不在普通作者 TaskService 中提供。 */
    public final class Launcher implements AutoCloseable {
        /** Exact owning activation. / 精确所属启用。 */
        private final String owner, version;

        /** Bound central task service. / 已绑定中央任务服务。 */
        private final TaskService service;

        /** Prevents new launches after owning activation teardown. / 所属启用拆除后阻止新启动。 */
        private volatile boolean stopped;

        /** Binds a host start control to one actual owner. / 将宿主启动控件绑定一个实际所有者。
         * @param owner exact owner / 精确所有者
         * @param version exact release / 精确发布版本
         * @param service owner-bound task admission / 所有者绑定任务准入 */
        private Launcher(String owner, String version, TaskService service) {
            this.owner = Objects.requireNonNull(owner);
            this.version = Objects.requireNonNull(version);
            this.service = service;
        }

        /** Captures saved mode and scopes only this submitted workflow callback. / 捕获已存模式，并仅为本次提交的流程回调建立作用域。
         * @param <T> actual result type / 实际结果类型
         * @param launch user-selected inputs captured by the host control / 宿主控件捕获的用户选定输入
         * @return original central task handle / 原中央任务句柄
         * @throws Exception if preference reading or task admission fails / 偏好读取或任务准入失败时 */
        public synchronized <T> TaskHandle<T> submit(Launch<T> launch) throws Exception {
            worker();
            requireOpen();
            Objects.requireNonNull(launch);
            if (stopped)
                throw failure(ProblemCode.UNAVAILABLE, "WORKFLOW_LAUNCHER_CLOSED", "Workflow launcher is closed");
            var mode = mode();
            return service.submit(launch.request(), task -> {
                var scope = new Scope(task, owner, version, mode, launch.targets());
                synchronized (PlatformAuthorizationService.this) {
                    requireOpen();
                    tasks.requireExecuting(task, owner, version);
                    for (var target : launch.targets())
                        tasks.requireServer(task, owner, version, target.server().id());
                    if (scopes.putIfAbsent(task, scope) != null)
                        throw failure(ProblemCode.CONTRACT_VIOLATION, "WORKFLOW_SCOPE_EXISTS",
                                "Original task already owns a workflow");
                }
                try (scope) {
                    return launch.work().run(task);
                }
            });
        }

        /** Seals future launches while accepted tasks retain their original lifetime. / 封闭后续启动，已接纳任务保留原寿命。 */
        @Override
        public synchronized void close() {
            stopped = true;
        }
    }

    /** One current callback's transient scope; never reconstructed from task IDs. / 一个当前回调的临时作用域，不从任务 ID 重建。 */
    private final class Scope implements AutoCloseable {
        /** Exact original context. / 精确原上下文。 */
        private final TaskContext task;

        /** Exact activation identity. / 精确启用身份。 */
        private final String owner, version;

        /** Mode captured at user launch. / 用户启动时捕获的模式。 */
        private final AuthorizationMode mode;

        /** User-selected resolved targets. / 用户选定的已解析目标。 */
        private final Set<ActionTarget> targets;

        /** Only explicitly approved operation meanings in this scope. / 仅本作用域内明确已批准的操作含义。 */
        private final Set<SemanticKey> approved = new HashSet<>();

        /** Prevents grants outliving the actual workflow callback. / 防止授权超过实际流程回调寿命。 */
        private boolean ended;

        /** Captures immutable task and scope facts. / 捕获不可变任务及作用域事实。
         * @param task original context / 原上下文
         * @param owner exact owner / 精确所有者
         * @param version exact release / 精确发布版本
         * @param mode captured policy / 已捕获策略
         * @param targets resolved targets / 已解析目标 */
        private Scope(TaskContext task, String owner, String version, AuthorizationMode mode,
                Set<ActionTarget> targets) {
            this.task = task;
            this.owner = owner;
            this.version = version;
            this.mode = mode;
            this.targets = targets;
        }

        /** Checks current physical callback ownership before every permission use. / 每次权限使用前核对当前实际回调归属。
         * @param task exact caller context / 精确调用方上下文
         * @param owner exact caller owner / 精确调用方所有者
         * @param version exact caller release / 精确调用方发布版本
         * @throws ApiException if the workflow is absent, ended or foreign / 流程不存在、已结束或属于他方时 */
        private void validate(TaskContext task, String owner, String version) throws ApiException {
            var original = tasks.requireContext(task);
            if (ended || this.task != task || !this.owner.equals(original.ownerId())
                    || !this.version.equals(original.ownerVersion()))
                throw failure(ProblemCode.ACCESS_DENIED, "WORKFLOW_SCOPE_ENDED",
                        "Original workflow scope is unavailable");
            tasks.requireExecuting(task, owner, version);
        }

        /** Expires every reusable permission before the callback returns to the central runtime. / 回调返回中央运行时前使每个可复用权限失效。 */
        @Override
        public synchronized void close() {
            ended = true;
            approved.clear();
            synchronized (PlatformAuthorizationService.this) {
                scopes.remove(task, this);
            }
        }
    }

    /** Exact operation family and resolved target key. / 精确操作类别及已解析目标键。
     * @param type actual platform capability category / 实际平台能力类别
     * @param semantics business-validated operation meaning / 经业务校验的操作含义 */
    private record SemanticKey(String type, ActionSemantics semantics) {
    }

    /** Original in-memory decision associated with a single exact grant. / 与单个精确授权关联的原内存决定。 */
    static final class Permit {
        /** Original authentic task. / 原真实任务。 */
        private final TaskContext task;

        /** Originating workflow, absent for ordinary individually reviewed tasks. / 原流程，普通逐次审核任务时不存在。 */
        private final Scope scope;

        /** Whether approval was established. / 是否已建立批准。 */
        private final boolean approved;
        /** Retains the actual decision identities. / 保留实际决定身份。
         * @param task original task / 原任务
         * @param scope optional original scope / 可选原作用域
         * @param approved actual decision / 实际决定 */
        private Permit(TaskContext task, Scope scope, boolean approved) {
            this.task = task;
            this.scope = scope;
            this.approved = approved;
        }

        /** Returns the actual policy decision. / 返回实际策略决定。
         * @return approved permission / 已批准权限 */
        boolean approved() {
            return approved;
        }
    }

    /** Host-owned human review invoked only when required by platform policy. / 仅在平台策略要求时调用的宿主人工审核。 */
    @FunctionalInterface
    interface HumanReview {
        /** Performs one actual review. / 执行一次实际审核。
         * @return explicit human approval / 明确人工批准
         * @throws ApiException if review fails / 审核失败时 */
        boolean approve() throws ApiException;
    }

    /** Rejects new use after host shutdown. / 宿主关闭后拒绝新使用。 */
    private void requireOpen() {
        if (closed)
            throw new IllegalStateException("Platform authorization is closed");
    }

    /** Keeps persistence and human waits off the EDT. / 将持久化及人工等待放在 EDT 之外。 */
    private static void worker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Authorization requires a worker");
    }

    /** Creates bounded diagnostics without action contents. / 创建不含动作内容的有界诊断。
     * @param code public category / 公开类别
     * @param event stable event identifier / 稳定事件标识
     * @param message safe description / 安全说明
     * @return checked platform failure / 平台受检失败 */
    private static ApiException failure(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Refuses to discard any still-running workflow at shutdown. / 关闭时拒绝丢弃仍在运行的流程。
     * @throws ApiException if an actual scope still exists / 实际作用域仍存在时 */
    @Override
    public synchronized void close() throws ApiException {
        if (!scopes.isEmpty())
            throw failure(ProblemCode.BUSY, "WORKFLOW_RUNNING", "Workflows must finish before platform closure");
        closed = true;
    }
}
