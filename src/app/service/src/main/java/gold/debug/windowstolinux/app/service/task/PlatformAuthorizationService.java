package gold.debug.windowstolinux.app.service.task;

import java.sql.SQLException;
import java.util.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.AuthorizationMode;
import gold.debug.windowstolinux.appapi.task.action.CommandReviewMode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.action.WorkflowPolicyState;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.appapi.task.run.ResourceLease;

/** Executes captured business authorization modes inside authentic ephemeral workflow scopes. / 在真实临时流程作用域内执行捕获的业务授权模式。 */
public final class PlatformAuthorizationService implements AutoCloseable {
    /** Actual original task persistence. / 实际原任务持久化。 */
    private final PlatformStorage persistence;

    /** Original task identity and server authority. / 原任务身份及服务器授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Exact contexts only; copied task identifiers confer no workflow authority. / 仅使用精确上下文；复制任务标识不产生流程权限。 */
    private final Map<TaskContext, Scope> scopes = new IdentityHashMap<>();

    /** Prevents reopening an original delegated task after its handoff was closed. / 防止原委托任务关闭交接后重新开启。
     * Completed tasks are removed without reviving any permission. / 已完成任务会移除，不恢复任何权限。 */
    private final Set<TaskContext> delegated = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Revokes new platform work after shutdown. / 关闭后撤销新平台工作。 */
    private volatile boolean closed;

    /** Binds actual platform storage and task identity without opening a workflow. / 绑定实际平台存储及任务身份，不打开流程。
     * @param persistence original platform storage / 原平台存储
     * @param tasks original task authority / 原任务授权方 */
    public PlatformAuthorizationService(PlatformStorage persistence, PlatformTaskRuntime tasks) {
        this.persistence = Objects.requireNonNull(persistence);
        this.tasks = Objects.requireNonNull(tasks);
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
        return review(owner, version, intent, task, human, false);
    }

    /** Applies ordinary scope validation with an unconditional explicit human decision. / 在常规作用域校验中要求无条件明确人工决定。
     * @param owner owner / 所有者
     * @param version release / 发布版本
     * @param intent exact intent / 精确意图
     * @param task original task / 原任务
     * @param human actual review / 实际审核
     * @param forceHuman whether every call requires a fresh human confirmation / 是否每次都要求新人工确认
     * @return original scope decision / 原作用域决定
     * @throws ApiException if validation fails / 校验失败时 */
    Permit review(String owner, String version, ActionIntent intent, TaskContext task, HumanReview human, boolean forceHuman)
        throws ApiException {
        return review(owner, version, intent, task, human, forceHuman, false);
    }

    /** Distinguishes ordinary effects from questions required by a delegated business policy. / 区分普通副作用与委托业务策略要求的问题。
     * @param owner bound owner / 绑定所有者
     * @param version exact release / 精确发布
     * @param intent actual effect / 实际副作用
     * @param task original context / 原上下文
     * @param human actual presenter / 实际呈现器
     * @param forceHuman ordinary unconditional confirmation / 普通无条件确认
     * @param required business requires this question / 业务要求此问题
     * @return original host permit / 原宿主许可
     * @throws ApiException if authority or decision fails / 权限或确认失败时 */
    Permit review(String owner, String version, ActionIntent intent, TaskContext task, HumanReview human, boolean forceHuman, boolean required)
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
            var servers = semantics.targets().stream().map(target -> target.serverId())
                .collect(java.util.stream.Collectors.toSet());
            if (!servers.equals(Set.copyOf(intent.serverIds())) || (scope.lease == null
                ? !scope.targets.containsAll(semantics.targets()) : !scope.servers.containsAll(servers)))
                throw failure(ProblemCode.ACCESS_DENIED, "AUTHORIZATION_TARGET",
                    "Operation targets differ from the launched workflow");
            var key = new SemanticKey(intent.type(), semantics);
            // Reviewed workflows do not delegate unrelated business confirmations.
            boolean approved = scope.reviewMode != null ? human.approve() : forceHuman ? human.approve()
                : scope.mode == AuthorizationMode.FULL
                || scope.mode == AuthorizationMode.GENERAL && scope.approved.contains(key) || human.approve();
            scope.validate(task, owner, version);
            if (approved && scope.reviewMode == null && !forceHuman && scope.mode == AuthorizationMode.GENERAL)
                scope.approved.add(key);
            return new Permit(task, scope, approved);
        }
    }

    /** Admits fresh task-local authority after exact root and server checks. / 精确核验原根及服务器后接纳新任务内权限。
     * @param owner exact workflow owner / 精确流程所有者
     * @param version owner release / 所有者发布版本
     * @param intent frozen workflow intent / 冻结流程意图
     * @param mode captured review policy / 已捕获审核策略
     * @param rememberAuthorization whether to retain actual human approvals / 是否保留实际人工批准
     * @param task original root task / 原根任务
     * @param recovering whether this is original reconciliation / 是否为原任务核对
     * @param human real startup confirmation callback / 真实启动确认回调
     * @return owned workflow grant or human refusal / 所属流程许可或人工拒绝
     * @throws ApiException if identity, admission or cleanup fails / 身份、准入或清理失败时 */
    Optional<WorkflowGrant> openReviewed(String owner, String version, ActionIntent intent, CommandReviewMode mode,
                                         boolean rememberAuthorization, TaskContext task, boolean recovering, HumanReview human) throws ApiException {
        worker();
        requireOpen();
        if (mode == null)
            throw failure(ProblemCode.INVALID_INPUT, "WORKFLOW_REVIEW_MODE", "Review mode is required");
        var servers = PlatformWorkflowAdmission.servers(persistence, tasks, task, owner, version, recovering);
        if (!servers.equals(Set.copyOf(intent.serverIds())))
            throw failure(ProblemCode.ACCESS_DENIED, "WORKFLOW_SERVER_SET", "Delegation must cover the complete original server set");
        if (recovering) {
            Scope prior;
            synchronized (this) {
                prior = scopes.get(task);
            }
            if (prior != null && prior.recovering && !prior.ended)
                throw failure(ProblemCode.BUSY, "WORKFLOW_SCOPE_EXISTS", "Original recovery already owns a fresh workflow");
            cleanupDelegation(task, owner, version);
            if (!tasks.recoveries(task, owner, version, "CUSTOM", "workflow-delegation-").isEmpty())
                throw failure(ProblemCode.UNKNOWN, "WORKFLOW_PREVIOUS_OCCUPANCY", "Original workflow occupancy is not confirmed released");
        }
        final Scope scope;
        synchronized (this) {
            requireOpen();
            var active = tasks.snapshots().stream().map(gold.debug.windowstolinux.appapi.task.model.TaskSnapshot::id).collect(java.util.stream.Collectors.toSet());
            delegated.removeIf(original -> !active.contains(original.id()));
            if (scopes.containsKey(task) || !recovering && delegated.contains(task))
                throw failure(ProblemCode.BUSY, "WORKFLOW_SCOPE_EXISTS", "Original task already owns a workflow");
            var lease = tasks.retain(task, new gold.debug.windowstolinux.app.db.task.TaskResource("CUSTOM", "workflow-delegation-" + UUID.randomUUID(), owner, false),
                new gold.debug.windowstolinux.app.db.task.ResourceRecovery("workflow.v1", Map.of("taskId", task.id().toString(), "owner", owner, "version", version, "bootId", persistence.database().bootId().toString())), true);
            scope = new Scope(task, owner, version, mode, rememberAuthorization, servers, lease, recovering, intent);
            scopes.put(task, scope);
            delegated.add(task);
        }
        try {
            synchronized (scope) {
                boolean approved = mode != CommandReviewMode.FULL_CONTROL || human.approve();
                PlatformWorkflowAdmission.servers(persistence, tasks, task, owner, version, recovering);
                if (approved) {
                    scope.ready = true;
                    return Optional.of(scope);
                }
            }
            scope.close();
            return Optional.empty();
        } catch (ApiException | RuntimeException failure) {
            try {
                scope.close();
            } catch (ApiException cleanup) {
                cleanup.addSuppressed(failure);
                throw cleanup;
            }
            throw failure;
        }
    }

    /** Updates the original live scope without closing or reopening its delegation. / 不关闭或重新开启委托，仅更新原活动作用域。 */
    Optional<WorkflowPolicyState> updateReviewed(String owner, String version, WorkflowGrant grant,
                                                 long expectedRevision, CommandReviewMode mode, TaskContext task,
                                                 PolicyReview human) throws ApiException {
        worker();
        tasks.requireExecuting(task, owner, version);
        if (mode == null || expectedRevision < 0)
            throw failure(ProblemCode.INVALID_INPUT, "WORKFLOW_POLICY_INVALID", "Policy and nonnegative revision are required");
        Scope scope;
        synchronized (this) {
            requireOpen();
            scope = scopes.get(task);
        }
        if (scope == null || scope != grant || scope.reviewMode == null)
            throw failure(ProblemCode.ACCESS_DENIED, "WORKFLOW_POLICY_SCOPE", "Exact original reviewed workflow grant is required");
        synchronized (scope) {
            scope.validate(task, owner, version);
            if (scope.policyRevision != expectedRevision)
                throw failure(ProblemCode.BUSY, "WORKFLOW_POLICY_REVISION", "Workflow policy revision changed");
            if (scope.reviewMode == mode) return Optional.of(new WorkflowPolicyState(mode, scope.policyRevision));
            if (scope.policyRevision == Long.MAX_VALUE)
                throw failure(ProblemCode.BUSY, "WORKFLOW_POLICY_REVISION", "Workflow policy revision is exhausted");
            if (mode == CommandReviewMode.FULL_CONTROL) {
                var original = scope.workflowIntent;
                var change = new ActionIntent("deployment.workflow.policy-update", original.serverIds(),
                    original.target(), Long.toString(expectedRevision), original.exactAction(),
                    "Change this active workflow approval policy to FULL_CONTROL");
                if (!human.approve(change)) return Optional.empty();
                scope.validate(task, owner, version);
            }
            scope.reviewMode = mode;
            scope.policyRevision++;
            scope.remembered.clear();
            scope.approved.clear();
            return Optional.of(new WorkflowPolicyState(mode, scope.policyRevision));
        }
    }

    /** Applies current policy after an independent review; remembered bounds waive human questions only. / 独立审核后应用当前策略；记忆参数仅免人工问题。
     * @param owner exact workflow owner / 精确流程所有者
     * @param version owner release / 所有者发布版本
     * @param intent unchanged reviewed action / 未改变的经审核动作
     * @param command actual command request or non-command absence / 实际命令请求或非命令缺省
     * @param requiresUserConfirmation whether review found an unresolved tradeoff / 审核是否发现未决取舍
     * @param task original accepted task / 原接纳任务
     * @param human actual human decision callback / 实际人工决定回调
     * @return single-use permit bound to the original action / 绑定原动作的单次许可
     * @throws ApiException if scope, targets or request identity differ / 作用域、目标或请求身份不一致时 */
    Permit reviewAction(String owner, String version, ActionIntent intent, Optional<CommandRequest> command,
                        boolean requiresUserConfirmation, TaskContext task, HumanReview human) throws ApiException {
        worker();
        tasks.requireExecuting(task, owner, version);
        Scope scope;
        synchronized (this) {
            requireOpen();
            scope = scopes.get(task);
        }
        if (scope == null || scope.reviewMode == null)
            throw failure(ProblemCode.ACCESS_DENIED, "REVIEWED_SCOPE_REQUIRED", "Original reviewed workflow is required");
        synchronized (scope) {
            scope.validate(task, owner, version);
            var semantics = intent.semantics().orElseThrow(() -> failure(ProblemCode.ACCESS_DENIED,
                "REVIEWED_ACTION_SEMANTICS", "Reviewed action requires exact resource meaning"));
            var servers = semantics.targets().stream().map(ActionTarget::serverId).collect(java.util.stream.Collectors.toSet());
            if (!servers.equals(Set.copyOf(intent.serverIds())) || !scope.servers.containsAll(servers))
                throw failure(ProblemCode.ACCESS_DENIED, "AUTHORIZATION_TARGET", "Reviewed action differs from admitted servers");
            if (intent.type().equals("platform.ssh.execute") != command.isPresent())
                throw failure(ProblemCode.ACCESS_DENIED, "REVIEWED_COMMAND_REQUIRED", "Actual command binding is required");
            String payload;
            java.time.Duration timeout = java.time.Duration.ZERO;
            long output = 0;
            if (command.isPresent()) {
                var request = command.orElseThrow();
                if (intent.serverIds().size() != 1 || !RemoteActions.command(new ServerId(intent.serverIds().getFirst()),
                    intent.revision(), request).withSemantics(semantics).equals(intent))
                    throw failure(ProblemCode.ACCESS_DENIED, "REVIEWED_COMMAND_CHANGED", "Exact command and intent differ");
                byte[] input = request.stdin();
                try {
                    payload = hash(request.script().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ":" + input.length + ":" + hash(input);
                } finally {
                    Arrays.fill(input, (byte) 0);
                }
                timeout = request.timeout();
                output = request.outputLimitBytes();
            } else payload = hash(intent.exactAction().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var key = new ReviewedKey(intent.type(), Set.copyOf(intent.serverIds()), intent.target(), intent.revision(),
                intent.effectSummary(), semantics, payload);
            final var requestedTimeout = timeout;
            final long requestedOutput = output;
            boolean needsHuman = scope.reviewMode == CommandReviewMode.MANUAL_REVIEW
                || scope.reviewMode == CommandReviewMode.AUTOMATIC && requiresUserConfirmation;
            boolean remembered = scope.rememberAuthorization && scope.remembered.stream().anyMatch(value ->
                value.key().equals(key) && requestedTimeout.compareTo(value.timeout()) <= 0 && requestedOutput <= value.output());
            boolean humanApproved = needsHuman && !remembered && human.approve();
            scope.validate(task, owner, version);
            boolean approved = !needsHuman || remembered || humanApproved;
            if (humanApproved && scope.rememberAuthorization)
                scope.remembered.add(new RememberedAction(key, timeout, output));
            return new Permit(task, scope, approved);
        }
    }

    /** Hashes request identity without retaining its bytes. / 散列请求身份，不保留其字节。
     * @param bytes identity input / 身份输入
     * @return lowercase SHA-256 digest / 小写 SHA-256 摘要 */
    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    /** Captures the exact independently reviewed effect identity. / 捕获独立审核的精确副作用身份。
     * @param type action type / 动作类型
     * @param servers captured server identities / 已捕获服务器身份
     * @param target exact target / 精确目标
     * @param revision original business revision / 原业务修订
     * @param effectSummary unchanged effect description / 未改变的副作用描述
     * @param semantics fixed operation meaning / 固定操作含义
     * @param payload request or action digest identity / 请求或动作摘要身份 */
    private record ReviewedKey(String type, Set<String> servers, String target, String revision,
                               String effectSummary, ActionSemantics semantics, String payload) {
    }

    /** Retains a real human approval with its original resource limits. / 保留实际人工批准及其原资源上限。
     * @param key exact effect identity / 精确副作用身份
     * @param timeout approved timeout bound / 已批准超时上限
     * @param output approved output bound / 已批准输出上限 */
    private record RememberedAction(ReviewedKey key, java.time.Duration timeout, long output) {
    }

    /** Cleans expired delegation occupancy only during authentic original-root reconciliation. / 仅真实原根核对期间清理已过期委托占用。
     * @param task authentic original recovery context / 真实原恢复上下文
     * @param owner exact root owner / 精确根所有者
     * @param version exact root release / 精确根发布
     * @throws ApiException if scope revocation or original release cannot be verified / 无法核验作用域撤销或原释放时 */
    void cleanupDelegation(TaskContext task, String owner, String version) throws ApiException {
        var original = tasks.requireCleanup(task, owner, version);
        if (!tasks.reconciling(task) || !original.ownerId().equals(owner) || !original.ownerVersion().equals(version))
            throw failure(ProblemCode.ACCESS_DENIED, "WORKFLOW_RECOVERY_ROOT", "Only original root reconciliation can clean delegation");
        Scope scope;
        synchronized (this) {
            scope = scopes.get(task);
        }
        if (scope != null) scope.close();
        try {
            new gold.debug.windowstolinux.app.db.task.WorkflowRecoveryStore(persistence.database()).releasePrevious(task.id(), owner, version);
        } catch (SQLException uncertain) {
            tasks.reportUncertainEffect(task);
            throw failure(ProblemCode.UNKNOWN, "WORKFLOW_RECOVERY_RELEASE", "Original expired delegation release is unconfirmed");
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
                if (permit.policyRevision != permit.scope.policyRevision)
                    throw failure(ProblemCode.ACCESS_DENIED, "WORKFLOW_POLICY_CHANGED", "Approval belongs to an earlier workflow policy");
            }
    }

    /** Validated user-selected task inputs captured by the host start control. / 宿主启动控件捕获的已校验用户选定任务输入。
     * @param <T> task result type / 任务结果类型
     * @param request complete task admission request / 完整任务准入请求
     * @param targets complete resolved business targets / 完整已解析业务目标
     * @param authorizationMode captured business mode / 捕获的业务模式
     * @param work actual business callback / 实际业务回调 */
    public record Launch<T>(TaskRequest request, Set<ActionTarget> targets, AuthorizationMode authorizationMode,
                            TaskWork<T> work) {
        /** Copies the explicit scope and requires its exact admitted servers. / 复制明确作用域并要求其精确准入服务器。
         * @param request original task request / 原任务请求
         * @param targets actual business targets / 实际业务目标
         * @param authorizationMode explicit business-frozen mode / 明确业务冻结模式
         * @param work actual business callback / 实际业务回调 */
        public Launch {
            Objects.requireNonNull(request);
            Objects.requireNonNull(work);
            Objects.requireNonNull(authorizationMode);
            targets = Set.copyOf(targets);
            if (targets.isEmpty() || targets.size() > 128 || !targets.stream().map(target -> target.serverId())
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

        /** Uses the launch's explicit captured mode only for its submitted workflow callback. / 仅为本次提交流程回调使用启动输入明确捕获的模式。
         * @param <T> actual result type / 实际结果类型
         * @param launch user-selected inputs captured by the host control / 宿主控件捕获的用户选定输入
         * @return original central task handle / 原中央任务句柄
         * @throws Exception if task admission fails / 任务准入失败时 */
        public synchronized <T> TaskHandle<T> submit(Launch<T> launch) throws Exception {
            worker();
            requireOpen();
            Objects.requireNonNull(launch);
            if (stopped)
                throw failure(ProblemCode.UNAVAILABLE, "WORKFLOW_LAUNCHER_CLOSED", "Workflow launcher is closed");
            var mode = launch.authorizationMode();
            return service.submit(launch.request(), task -> {
                var scope = new Scope(task, owner, version, mode, launch.targets());
                synchronized (PlatformAuthorizationService.this) {
                    requireOpen();
                    tasks.requireExecuting(task, owner, version);
                    for (var target : launch.targets())
                        tasks.requireServer(task, owner, version, target.serverId());
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
    private final class Scope implements WorkflowGrant {
        /** Exact original context. / 精确原上下文。 */
        private final TaskContext task;

        /** Exact activation identity. / 精确启用身份。 */
        private final String owner, version;

        /** Mode captured at user launch. / 用户启动时捕获的模式。 */
        private final AuthorizationMode mode;

        /** User-selected resolved targets. / 用户选定的已解析目标。 */
        private final Set<ActionTarget> targets;

        /** Complete original server admission for dynamic delegated business targets. / 动态委托业务目标的完整原服务器准入。 */
        private final Set<String> servers;

        /** Actual delegated occupancy, absent for the existing exact-target launcher. / 实际委托占用，既有精确目标启动器时不存在。 */
        private final ResourceLease lease;

        /** Reuses only genuine questions, never ordinary delegated effects. / 仅复用真实问题，不授予普通委托副作用。 */
        private CommandReviewMode reviewMode;

        private long policyRevision;

        private final ActionIntent workflowIntent;

        /** Retains only genuine human approvals within this task scope. / 仅在本任务作用域内保留实际人工批准。 */
        private final boolean rememberAuthorization;

        /** Exact reviewed effects and bounds actually approved by a human. / 人工实际批准的精确经审核副作用及上限。 */
        private final Set<RememberedAction> remembered = new HashSet<>();

        /** True only for a new explicitly requested original recovery scope. / 仅明确请求的新原恢复作用域时为真。 */
        private final boolean recovering;

        /** Becomes usable only after the original handoff is approved. / 仅原交接获准后可用。 */
        private boolean ready;

        /** Confirms durable release separately from permission revocation. / 独立于权限撤销确认持久释放。 */
        private boolean released;

        /** Only explicitly approved operation meanings in this scope. / 仅本作用域内明确已批准的操作含义。 */
        private final Set<SemanticKey> approved = new HashSet<>();

        /** Prevents grants outliving the actual workflow callback. / 防止授权超过实际流程回调寿命。 */
        private volatile boolean ended;

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
            this.targets = Set.copyOf(targets);
            this.servers = Set.of();
            this.lease = null;
            this.reviewMode = null;
            this.workflowIntent = null;
            this.rememberAuthorization = false;
            this.recovering = false;
            ready = true;
        }

        /** Captures one reviewed workflow's original admission and decision policy. / 捕获经审核流程的原准入及决定策略。
         * @param task authentic original task / 真实原任务
         * @param owner exact owner / 精确所有者
         * @param version owner release / 所有者发布版本
         * @param mode captured review mode / 已捕获审核模式
         * @param rememberAuthorization whether real approvals may be retained / 是否可保留实际批准
         * @param servers admitted server set / 已准入服务器集合
         * @param lease original scope occupancy / 原作用域占用
         * @param recovering original recovery admission / 原恢复准入 */
        private Scope(TaskContext task, String owner, String version, CommandReviewMode mode, boolean rememberAuthorization,
                      Set<String> servers, ResourceLease lease, boolean recovering, ActionIntent workflowIntent) {
            this.task = task;
            this.owner = owner;
            this.version = version;
            this.mode = null;
            this.targets = Set.of();
            this.servers = Set.copyOf(servers);
            this.lease = lease;
            this.reviewMode = mode;
            this.workflowIntent = workflowIntent;
            this.rememberAuthorization = rememberAuthorization;
            this.recovering = recovering;
        }

        /** Checks current physical callback ownership before every permission use. / 每次权限使用前核对当前实际回调归属。
         * @param task exact caller context / 精确调用方上下文
         * @param owner exact caller owner / 精确调用方所有者
         * @param version exact caller release / 精确调用方发布版本
         * @throws ApiException if the workflow is absent, ended or foreign / 流程不存在、已结束或属于他方时 */
        private void validate(TaskContext task, String owner, String version) throws ApiException {
            var original = tasks.requireContext(task);
            if (ended || !ready || this.task != task || lease != null && (!this.owner.equals(owner) || !this.version.equals(version)) || !this.owner.equals(original.ownerId())
                || !this.version.equals(original.ownerVersion()))
                throw failure(ProblemCode.ACCESS_DENIED, "WORKFLOW_SCOPE_ENDED",
                    "Original workflow scope is unavailable");
            tasks.requireExecuting(task, owner, version);
            if (lease != null) {
                if (recovering && !tasks.reconciling(task))
                    throw failure(ProblemCode.ACCESS_DENIED, "WORKFLOW_RECOVERY_ENDED", "Fresh workflow belongs only to original reconciliation");
                if (!recovering) tasks.callbacks().requireOrdinaryExecuting(task, owner, version);
            }
        }

        /** Expires every reusable permission before the callback returns to the central runtime. / 回调返回中央运行时前使每个可复用权限失效。
         * @throws ApiException if original physical release cannot be confirmed / 原物理释放无法确认时 */
        @Override
        public synchronized void close() throws ApiException {
            if (released) return;
            ended = true;
            approved.clear();
            remembered.clear();
            if (lease != null) {
                try {
                    lease.close();
                } catch (ApiException uncertain) {
                    try {
                        tasks.reportUncertainEffect(task);
                    } catch (ApiException retained) {
                        uncertain.addSuppressed(retained);
                    }
                    throw failure(ProblemCode.UNKNOWN, "WORKFLOW_RELEASE_UNCONFIRMED", "Original delegation is revoked but its release remains uncertain");
                }
            }
            released = true;
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

        private final long policyRevision;

        /** Retains the actual decision identities. / 保留实际决定身份。
         * @param task original task / 原任务
         * @param scope optional original scope / 可选原作用域
         * @param approved actual decision / 实际决定 */
        private Permit(TaskContext task, Scope scope, boolean approved) {
            this.task = task;
            this.scope = scope;
            this.approved = approved;
            this.policyRevision = scope == null ? -1 : scope.policyRevision;
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

    interface PolicyReview {
        boolean approve(ActionIntent intent) throws ApiException;
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
