package gold.debug.windowstolinux.app.service.task;

import java.util.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;

/** Owns caller-bound human review and single-use grants without persisting secret input. / 持有调用方绑定的人工审核及单次授权，不持久化秘密输入。 */
public final class PlatformInteractionService implements InteractionService, AutoCloseable {
    /** Authentic task and resource authority. / 真实任务及资源授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Exact Mod identity bound by host composition. / 宿主装配绑定的精确 Mod 身份。 */
    private final String owner;

    /** Exact release whose business requests are reviewed. / 审核其业务请求的精确发布版本。 */
    private final String version;

    /** Human presenter guarded by current-user lock generation. / 由当前用户锁定代次保护的人工呈现器。 */
    private final Prompts prompts;

    /** Current-user privacy gate shared with the platform window. / 与平台窗口共用的当前用户隐私门禁。 */
    private final DesktopSecurityService security;

    /** Current workflow policy independent of AI review. / 独立于 AI 审批的当前流程策略。 */
    private final PlatformAuthorizationService authorization;

    /** Object-identity grants, never authority derived from a copied UUID. / 对象身份授权，不从复制的 UUID 推导权限。 */
    private final Map<ApprovalGrant, Reviewed> grants = new IdentityHashMap<>();

    /** Physically finished prompts whose durable release must be retried. / 物理已结束但持久释放仍须重试的提示。 */
    private final Set<ResourceLease> pendingReleases = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Revokes further interaction after its owning activation closes. / 所属启用关闭后撤销进一步交互。 */
    private volatile boolean closed;

    /**
     * Binds one Mod's review capability to the platform privacy boundary. / 将一个 Mod 的审核能力绑定到平台隐私边界。
     * @param tasks current task authority / 当前任务授权方
     * @param security current-user interaction gate / 当前用户交互门禁
     * @param authorization host workflow permission owner / 宿主流程权限所有者
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version exact release / 精确发布版本
     * @param prompts host-owned presenter / 宿主持有的呈现器
     */
    public PlatformInteractionService(PlatformTaskRuntime tasks, DesktopSecurityService security,
            PlatformAuthorizationService authorization, String owner, String version, Prompts prompts) {
        this.tasks = Objects.requireNonNull(tasks);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        this.security = Objects.requireNonNull(security);
        this.authorization = Objects.requireNonNull(authorization);
        this.prompts = Objects.requireNonNull(prompts);
    }

    /**
     * Reviews every exact field before issuing an in-memory capability. / 在签发内存能力之前审核全部精确字段。
     * @param intent exact business action / 精确业务动作
     * @param task original active task / 原始活动任务
     * @return reviewed single-use grant or explicit cancellation / 已审核单次授权或明确取消
     * @throws ApiException if caller, resources or review are unavailable / 调用方、资源或审核不可用时
     */
    @Override
    public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) throws ApiException {
        worker();
        validate(intent, task);
        var lease = tasks.retain(task, new gold.debug.windowstolinux.app.db.task.TaskResource("CUSTOM",
                "interaction-" + UUID.randomUUID(), owner, false));
        try {
            final PlatformAuthorizationService.Permit permit;
            try {
                var cancellation = cancellation(task);
                permit = authorization.review(owner, version, intent, task, () -> security.human(
                        () -> prompts.approve(intent, tasks.requireExecuting(task, owner, version), cancellation),
                        cancellation));
            } catch (java.util.concurrent.CancellationException cancelled) {
                return cancelled();
            } catch (RuntimeException failure) {
                throw error(ProblemCode.UNAVAILABLE, "APPROVAL_UNAVAILABLE", "Human review did not complete");
            }
            validate(intent, task);
            if (!permit.approved())
                return cancelled();
            authorization.validate(permit, task);
            synchronized (this) {
                requireOpen();
                var activeTasks = tasks.snapshots().stream().filter(snapshot -> snapshot.state() != TaskState.ENDED)
                        .map(TaskSnapshot::id).collect(java.util.stream.Collectors.toSet());
                grants.entrySet().removeIf(entry -> !activeTasks.contains(entry.getValue().task().id()));
                var grant = new ApprovalGrant(UUID.randomUUID());
                grants.put(grant, new Reviewed(task, intent, permit));
                return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(grant), Optional.empty());
            }
        } finally {
            release(lease);
        }
    }

    /** Presents a file conflict independently of any reusable operation authorization. / 独立于任何可复用操作授权呈现文件冲突。
     * @param conflict exact observed file conflict / 精确已观察文件冲突
     * @param server original admitted server / 原准入服务器
     * @param task original active task / 原活动任务
     * @return explicit conflict choice, or termination after cancellation / 明确冲突选择，取消后终止
     * @throws ApiException if identity, presentation or resource release fails / 身份、呈现或资源释放失败时 */
    public TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server, TaskContext task)
            throws ApiException {
        worker();
        requireOpen();
        tasks.requireServer(task, owner, version, server.id());
        var lease = tasks.retain(task, new gold.debug.windowstolinux.app.db.task.TaskResource("CUSTOM",
                "interaction-" + UUID.randomUUID(), owner, false));
        try {
            var cancellation = cancellation(task);
            var choice = security.human(() -> prompts.transferConflict(conflict, server,
                    tasks.requireExecuting(task, owner, version), cancellation), cancellation);
            requireOpen();
            tasks.requireServer(task, owner, version, server.id());
            return Objects.requireNonNull(choice);
        } catch (java.util.concurrent.CancellationException cancelled) {
            return TransferConflictChoice.TERMINATE;
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw error(ProblemCode.UNAVAILABLE, "TRANSFER_CHOICE", "File conflict choice did not complete");
        } finally {
            release(lease);
        }
    }

    /** Presents a stalled transfer only after its physical attempt has stopped. / 仅在物理尝试停止后呈现停滞传输。
     * @param stall exact observed file stall / 精确已观察停滞
     * @param server original admitted server / 原准入服务器
     * @param task original active task / 原活动任务
     * @return explicit retry choice, or termination after cancellation / 明确重试选择，取消后终止
     * @throws ApiException if identity, presentation or resource release fails / 身份、呈现或资源释放失败时 */
    public TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskContext task)
            throws ApiException {
        worker();
        requireOpen();
        tasks.requireServer(task, owner, version, server.id());
        var lease = tasks.retain(task, new gold.debug.windowstolinux.app.db.task.TaskResource("CUSTOM",
                "interaction-" + UUID.randomUUID(), owner, false));
        try {
            var cancellation = cancellation(task);
            var choice = security.human(() -> prompts.transferStalled(stall, server,
                    tasks.requireExecuting(task, owner, version), cancellation), cancellation);
            requireOpen();
            tasks.requireServer(task, owner, version, server.id());
            return Objects.requireNonNull(choice);
        } catch (java.util.concurrent.CancellationException cancelled) {
            return TransferStallChoice.TERMINATE;
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw error(ProblemCode.UNAVAILABLE, "TRANSFER_STALL_CHOICE", "Transfer retry choice did not complete");
        } finally {
            release(lease);
        }
    }

    /** Publishes localized physical transfer progress to the original task. / 向原任务发布本地化物理传输进度。
     * @param progress actual stage observation / 实际阶段观测
     * @param task original active task / 原活动任务
     * @throws ApiException if task publication fails / 任务发布失败时 */
    public void transferProgress(TransferProgress progress, TaskContext task) throws ApiException {
        requireOpen();
        tasks.requireExecuting(task, owner, version);
        task.progress(prompts.transferProgress(progress), progress.fraction());
    }

    /**
     * Returns text only after the privacy gate accepts the same unlock generation. / 仅在隐私门禁确认同一解锁代次后返回文本。
     * @param title human-readable input title / 可读输入标题
     * @param prompt complete input question / 完整输入问题
     * @param secret whether the presenter must mask input / 呈现器是否必须遮蔽输入
     * @param task original active task / 原始活动任务
     * @return entered text or explicit cancellation / 输入文本或明确取消
     * @throws ApiException if the input operation is unavailable / 输入操作不可用时
     */
    @Override
    public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task)
            throws ApiException {
        worker();
        requireOpen();
        tasks.requireExecuting(task, owner, version);
        if (title == null || title.isBlank() || title.length() > 4096 || prompt == null || prompt.length() > 65536)
            throw error(ProblemCode.INVALID_INPUT, "INPUT_PROMPT", "Invalid human input description");
        var lease = tasks.retain(task, new gold.debug.windowstolinux.app.db.task.TaskResource("CUSTOM",
                "interaction-" + UUID.randomUUID(), owner, false));
        char[] value = null;
        try {
            var cancellation = cancellation(task);
            value = security.human(() -> prompts.text(title, prompt, secret,
                    tasks.requireExecuting(task, owner, version), cancellation), cancellation);
            requireOpen();
            tasks.requireExecuting(task, owner, version);
            if (value == null)
                return cancelled();
            if (value.length > 65536)
                throw error(ProblemCode.INVALID_INPUT, "INPUT_TOO_LARGE", "Human input exceeds its limit");
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new String(value)), Optional.empty());
        } catch (java.util.concurrent.CancellationException cancelled) {
            return cancelled();
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw error(ProblemCode.UNAVAILABLE, "INPUT_UNAVAILABLE", "Human input did not complete");
        } finally {
            if (value != null)
                Arrays.fill(value, '\0');
            release(lease);
        }
    }

    /**
     * Combines non-blocking task cancellation with activation revocation. / 合并非阻塞任务取消与启用撤销。
     * @param task authentic original task / 真实原始任务
     * @return cancellation used by the gate and actual dialog / 门禁及实际对话框使用的取消信号
     */
    private CancellationToken cancellation(TaskContext task) {
        var cancellation = tasks.operationCancellation(task);
        return () -> closed || cancellation.requested();
    }

    /**
     * Retains a failed ledger release after the dialog has actually returned. / 对话框实际返回后保留失败账本释放。
     * @param lease actual finished prompt's resource claim / 实际已结束提示的资源申请
     * @throws ApiException if durable release is uncertain / 持久释放不确定时
     */
    private synchronized void release(ResourceLease lease) throws ApiException {
        pendingReleases.add(lease);
        lease.close();
        pendingReleases.remove(lease);
    }

    /**
     * Retries only physically completed prompt releases without closing live dialogs early. / 仅重试物理已完成提示的释放，不提前释放活动对话框。
     * @throws ApiException if any durable release still fails / 任一持久释放仍失败时
     */
    public synchronized void retryFinishedReleases() throws ApiException {
        for (var lease : List.copyOf(pendingReleases))
            release(lease);
    }

    /**
     * Reads a retained grant for an adapter to reconstruct its exact expected action. / 读取保留授权，供适配器重建精确预期动作。
     * @param grant original host-created grant object / 原始宿主授权对象
     * @param task original active task / 原始活动任务
     * @return immutable reviewed intent / 不可变已审核意图
     * @throws ApiException if the grant is forged, consumed or belongs to another task / 授权伪造、已消费或属于其他任务时
     */
    public synchronized ActionIntent reviewed(ApprovalGrant grant, TaskContext task) throws ApiException {
        requireOpen();
        tasks.requireExecuting(task, owner, version);
        var reviewed = grants.get(grant);
        if (reviewed == null || reviewed.task() != task)
            throw error(ProblemCode.ACCESS_DENIED, "APPROVAL_IDENTITY",
                    "Approval is foreign, expired or already consumed");
        authorization.validate(reviewed.permit(), task);
        return reviewed.intent();
    }

    /** Checks reconstructed transport facts with the business meaning already reviewed. / 使用已审核业务含义核对重建的传输事实。
     * @param grant original exact grant / 原精确授权
     * @param actual complete operation reconstructed by the transport / 传输层重建的完整操作
     * @param task original task / 原任务
     * @throws ApiException if any actual effect field differs / 任一实际副作用字段不符时 */
    public synchronized void consumeRemote(ApprovalGrant grant, ActionIntent actual, TaskContext task)
            throws ApiException {
        var semantics = reviewed(grant, task).semantics();
        consume(grant, new ActionIntent(actual.type(), actual.serverIds(), actual.target(), actual.revision(),
                actual.exactAction(), actual.effectSummary(), semantics), task);
    }

    /**
     * Consumes an exact matching grant before dispatching any external effect. / 在发出任何外部副作用之前消费精确匹配的授权。
     * @param grant original grant object / 原始授权对象
     * @param expected complete action reconstructed by a trusted adapter / 可信适配器重建的完整动作
     * @param task original active task / 原始活动任务
     * @throws ApiException if any field or identity differs / 任一字段或身份不符时
     */
    public synchronized void consume(ApprovalGrant grant, ActionIntent expected, TaskContext task) throws ApiException {
        if (!reviewed(grant, task).equals(expected))
            throw error(ProblemCode.ACCESS_DENIED, "APPROVAL_ACTION",
                    "The actual operation differs from the reviewed action");
        validate(expected, task);
        grants.remove(grant);
    }

    /**
     * Checks bounded review material and every initially admitted server. / 检查有界审核材料及每个初始准入服务器。
     * @param intent complete review request / 完整审核请求
     * @param task original active task / 原始活动任务
     * @throws ApiException if the review request is invalid or unauthorized / 审核请求无效或未获授权时
     */
    private void validate(ActionIntent intent, TaskContext task) throws ApiException {
        requireOpen();
        tasks.requireExecuting(task, owner, version);
        if (intent == null || intent.type().isBlank() || intent.type().length() > 128 || intent.target().isBlank()
                || intent.target().length() > 4096 || intent.revision().isBlank() || intent.revision().length() > 512
                || intent.effectSummary().isBlank() || intent.effectSummary().length() > 65536
                || intent.exactAction().length() > 17 * 1024 * 1024
                || new HashSet<>(intent.serverIds()).size() != intent.serverIds().size())
            throw error(ProblemCode.INVALID_INPUT, "APPROVAL_INTENT", "Invalid exact review description");
        for (var server : intent.serverIds())
            tasks.requireServer(task, owner, version, server);
    }

    /**
     * Rejects revoked capability objects. / 拒绝已撤销能力对象。
     * @throws ApiException if the owning activation has closed / 所属启用已关闭时
     */
    private synchronized void requireOpen() throws ApiException {
        if (closed)
            throw error(ProblemCode.UNAVAILABLE, "INTERACTION_CLOSED", "The owning review capability has closed");
    }

    /** Revokes grants without claiming that pending business effects have ended. / 撤销授权，不声称待定业务副作用已经结束。
     * @throws ApiException if finished prompts still need persistent release / 已结束提示仍需持久释放时 */
    @Override
    public synchronized void close() throws ApiException {
        closed = true;
        grants.clear();
        retryFinishedReleases();
    }

    /**
     * Rejects synchronous dialogs on the Swing event thread. / 拒绝在 Swing 事件线程同步请求对话。
     * @throws ApiException if called on the event thread / 从事件线程调用时
     */
    private static void worker() throws ApiException {
        if (SwingUtilities.isEventDispatchThread())
            throw error(ProblemCode.CONTRACT_VIOLATION, "INTERACTION_REQUIRES_BACKGROUND",
                    "Interaction requires a background task");
    }

    /**
     * Represents an explicit human cancellation without task completion side effects. / 表示明确人工取消，不附带任务完成副作用。
     * @param <T> public result type / 公开结果类型
     * @return cancelled operation result / 已取消操作结果
     */
    private static <T> OperationResult<T> cancelled() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional
                .of(error(ProblemCode.CANCELLED, "HUMAN_CANCELLED", "Human review or input was cancelled").problem()));
    }

    /**
     * Builds diagnostics without action or secret contents. / 构建不含动作或秘密内容的诊断。
     * @param code public problem category / 公开问题类别
     * @param event stable event identifier / 稳定事件标识
     * @param message safe description / 安全说明
     * @return public checked failure / 公开受检失败
     */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /**
     * Retains the original task object and unchanged review text. / 保留原始任务对象及未改变的审核文本。
     * @param task original accepted context / 原始已接纳上下文
     * @param intent complete reviewed intent / 完整已审核意图
     * @param permit original live-scope policy decision / 原活动作用域策略决定
     */
    private record Reviewed(TaskContext task, ActionIntent intent, PlatformAuthorizationService.Permit permit) {
    }

    /** Host-owned human presentation; implementations marshal their controls to the EDT. / 宿主持有的人工呈现；实现将控件操作调度至 EDT。 */
    public interface Prompts {
        /** Resolves an actual same-name conflict without inferring consent from authorization mode. / 解决实际同名冲突，不从授权模式推断同意。
         * @param conflict exact file conflict / 精确文件冲突
         * @param server selected server / 选定服务器
         * @param task original task identity / 原任务身份
         * @param cancellation cooperative prompt cancellation / 协作提示取消
         * @return explicit business choice / 明确业务选择 */
        TransferConflictChoice transferConflict(TransferConflict conflict, ServerRef server, TaskSnapshot task,
                CancellationToken cancellation);

        /** Presents retry or termination after the old transfer physically stops. / 原传输物理停止后呈现重试或终止。
         * @param stall last effective progress and original request / 最后有效进展及原请求
         * @param server original server / 原服务器
         * @param task original task identity / 原任务身份
         * @param cancellation cooperative prompt cancellation / 协作提示取消
         * @return explicit user choice / 明确用户选择 */
        TransferStallChoice transferStalled(TransferStall stall, ServerRef server, TaskSnapshot task,
                CancellationToken cancellation);

        /** Formats a physical stage without accessing window controls. / 不访问窗口控件地格式化物理阶段。
         * @param progress actual stage and byte counts / 实际阶段及字节数
         * @return localized task progress / 本地化任务进度 */
        String transferProgress(TransferProgress progress);

        /**
         * Presents the complete effect for explicit review. / 呈现完整副作用供明确审核。
         * @param intent exact immutable action / 精确不可变动作
         * @param task non-secret current task identity / 不含秘密的当前任务身份
         * @param cancellation cooperative prompt cancellation / 协作提示取消
         * @return explicit approval / 明确同意
         */
        boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation);

        /**
         * Returns a disposable character buffer, or null for cancellation. / 返回可清除的字符缓冲区，取消时返回 null。
         * @param title input title / 输入标题
         * @param prompt complete question / 完整问题
         * @param secret whether input is masked / 是否遮蔽输入
         * @param task original task identity / 原始任务身份
         * @param cancellation cooperative prompt cancellation / 协作提示取消
         * @return input buffer cleared by the host / 由宿主清除的输入缓冲区
         */
        char[] text(String title, String prompt, boolean secret, TaskSnapshot task, CancellationToken cancellation);
    }
}
