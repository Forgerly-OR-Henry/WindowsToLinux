package gold.debug.windowstolinux.mod.deployment.service.core.application;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationId;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleAction;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentLifecycleResult;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseRecord;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;

/** Admits application page operations with their exact original server scope. / 按精确原服务器范围接纳应用页面操作。 */
public final class DeploymentApplicationsService implements TaskRecovery {
    /** Read-only application query task. / 只读应用查询任务。 */
    public static final String QUERY_TASK = "deployment-applications-query";

    /** Durable original application management task. / 持久原应用管理任务。 */
    public static final String ACTION_TASK = "deployment-applications-action";

    /** Active provider capabilities for page task admission. / 页面任务准入的活动提供方能力。 */
    private final ModContext context;

    /** Explicit internal policy; enabling AI alone does not select independent command review. */
    public enum AgentManagementPolicy { INDEPENDENT_APP }

    private final Optional<AgentManagementPolicy> agentPolicy;

    /** Exact managed lifecycle and complete observations. / 精确受管生命周期及完整观察。 */
    private final DeploymentApplicationRunner manager;

    /** Original retained transitions and verified compensation. / 原保留转换及已核实补偿。 */
    private final DeploymentRetainedReleases releases;

    /** Original adopted runtime registry and action evidence. / 原接管运行登记及动作依据。 */
    private final DeploymentExternalApplications external;

    /** Binds one active deployment generation. / 绑定一个活动部署代次。
     * @param context active context / 活动上下文 */
    public DeploymentApplicationsService(ModContext context) {
        this(context, Optional.empty());
    }

    public DeploymentApplicationsService(ModContext context, AgentManagementPolicy policy) {
        this(context, Optional.of(Objects.requireNonNull(policy)));
    }

    private DeploymentApplicationsService(ModContext context, Optional<AgentManagementPolicy> policy) {
        this.context = Objects.requireNonNull(context);
        agentPolicy = policy;
        manager = new DeploymentApplicationRunner(context);
        releases = new DeploymentRetainedReleases(context);
        external = new DeploymentExternalApplications(context);
    }

    /** Loads saved applications without claiming live state. / 加载已保存应用，不宣称实时状态。
     * @return owned query / 所属查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<List<DeploymentApplicationSnapshot>> list() throws ApiException {
        return query(List.of(), "Application list", task -> {
            var result = new ArrayList<DeploymentApplicationSnapshot>();
            Optional<String> cursor = Optional.empty();
            do {
                var page = manager.list(cursor, 100, task);
                result.addAll(page.items());
                cursor = page.nextCursor();
            } while (cursor.isPresent() && result.size() < 10000);
            if (cursor.isPresent())
                return failed("DEPLOYMENT_APPLICATION_LIST_LIMIT");
            return success(List.copyOf(result));
        });
    }

    /** Loads both saved origins with their original payloads and real business dates. / 加载两类保存来源及其原载荷和真实业务时间。
     * @return original local inventory query / 原本地清单查询
     * @throws ApiException if original task admission fails / 原任务准入失败时 */
    public TaskHandle<List<DeploymentApplicationList.Entry>> inventory() throws ApiException {
        return query(List.of(), "Application inventory",
            task -> success(new DeploymentApplicationList(context).list(task)));
    }

    /** Reads selected exact active usage inside a fresh authorized short query. / 在新授权短查询内读取所选精确活动用法。
     * @param selected original selected snapshot / 原所选快照
     * @return actual owned usage query / 实际所属用法查询
     * @throws ApiException if task admission fails / 任务准入失败时 */
    public TaskHandle<DeploymentApplicationUsage> usage(DeploymentApplicationSnapshot selected) throws ApiException {
        return query(List.of(selected.application().server().id()), "Application usage", task -> {
            var server = context.servers().get(selected.application().server(), task);
            return success(new DeploymentApplicationReader(context.db()).usage(selected, server.host(), task));
        });
    }

    /** Resolves exact business delivery after the caller observed the original host handle ENDED and SUCCEEDED.
     * This read verifies execution, formal release identity and the complete active graph; it cannot infer a removed host result.
     * / 调用方已观察原宿主句柄ENDED且SUCCEEDED后解析精确业务交付；此读取核执行、正式发布身份及完整活动图，不推断已移除的宿主结果。
     * @param originalTaskUUID actual original UUID whose host completion the caller already verified / 调用方已核实宿主完成的实际原UUID
     * @return short query returning eligible original usage or absence / 返回符合条件原用法或无值的短查询
     * @throws ApiException if current caller-bound admission fails / 当前绑定调用方的准入失败时 */
    public TaskHandle<Optional<DeploymentApplicationUsage>> usageForCompletedTask(UUID originalTaskUUID) throws ApiException {
        Objects.requireNonNull(originalTaskUUID);
        return query(List.of(), "Original task delivery", task -> {
            var data = new DeploymentApplicationReader(context.db()).findForCompletedTask(originalTaskUUID, task);
            if (data.isEmpty())
                return success(Optional.empty());
            var original = data.orElseThrow();
            var host = DeploymentApplicationHosts.require(DeploymentApplicationHosts.read(context, task),
                original.snapshot().application().server());
            var usage = DeploymentApplicationReader.projectUsage(original, host);
            return success(usage.components().stream().anyMatch(value -> value.mode() == DeploymentApplicationUsage.Mode.UNVERIFIED)
                ? Optional.empty() : Optional.of(usage));
        });
    }

    /** Loads configured scan targets. / 加载已配置扫描目标。
     * @return target query / 目标查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<List<ServerInfo>> servers() throws ApiException {
        return query(List.of(), "Scan targets", task -> {
            var result = new ArrayList<ServerInfo>();
            Optional<String> cursor = Optional.empty();
            do {
                var page = context.servers().listConfigured(cursor, 200, task);
                result.addAll(page.items());
                cursor = page.nextCursor();
            } while (cursor.isPresent() && result.size() < 10000);
            if (cursor.isPresent())
                return failed("DEPLOYMENT_APPLICATION_SERVER_LIST_LIMIT");
            return success(List.copyOf(result));
        });
    }

    /** Inspects the selected exact application. / 检查所选精确应用。
     * @param application selected application / 所选应用
     * @return original query / 原查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentApplicationSnapshot> inspect(DeploymentApplicationId application) throws ApiException {
        return query(List.of(application.server().id()), "Inspect application",
            task -> agentPolicy.isEmpty() ? manager.inspect(application, task)
                : reviewed(application, Optional.empty(), Optional.empty(), "INSPECT", Optional.empty(), false, task,
                    (runner, review) -> runner.inspect(application, task)));
    }

    /** Submits one exact reviewed lifecycle action. / 提交一个精确已审阅生命周期动作。
     * @param snapshot selected review / 所选审阅
     * @param action selected action / 所选动作
     * @return original action / 原动作
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentLifecycleResult> lifecycle(DeploymentApplicationSnapshot snapshot,
                                                           DeploymentLifecycleAction action) throws ApiException {
        return action(snapshot.application().server(), "Application " + action,
            task -> agentPolicy.isEmpty() ? manager.lifecycle(snapshot.application(), snapshot.revisionSha256(), action, task)
                : reviewed(snapshot.application(), Optional.of(snapshot.revisionSha256()), Optional.empty(), action.name(),
                    Optional.empty(), false, task,
                    (runner, review) -> runner.lifecycle(snapshot.application(), snapshot.revisionSha256(), action,
                        Optional.empty(), Optional.of(review), task)));
    }

    /** Internal managed-component boundary, without extending the public lifecycle API or action names. */
    public TaskHandle<DeploymentLifecycleResult> lifecycleComponent(DeploymentApplicationSnapshot snapshot,
                                                                    String componentId, DeploymentLifecycleAction action) throws ApiException {
        Objects.requireNonNull(snapshot);
        if (agentPolicy.isEmpty() || componentId == null || !componentId.matches("[a-z0-9][a-z0-9-]{0,62}")
            || action != DeploymentLifecycleAction.START && action != DeploymentLifecycleAction.STOP)
            throw new IllegalArgumentException("Independent managed component START or STOP required");
        return action(snapshot.application().server(), "Component " + componentId + " " + action,
            task -> reviewed(snapshot.application(), Optional.of(snapshot.revisionSha256()), Optional.of(componentId),
                action.name(), Optional.empty(), false, task,
                (runner, review) -> runner.lifecycle(snapshot.application(), snapshot.revisionSha256(), action,
                    Optional.of(componentId), Optional.of(review), task)));
    }

    /** Lists selected application's immutable histories. / 列出所选应用不可变历史。
     * @param application exact application / 精确应用
     * @return history query / 历史查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<List<DeploymentReleaseRecord>> history(DeploymentApplicationId application) throws ApiException {
        return query(List.of(application.server().id()), "Release history",
            task -> success(releases.list(application, task)));
    }

    /** Switches or rolls back to the selected exact history. / 切换或回滚至所选精确历史。
     * @param snapshot reviewed application / 已审阅应用
     * @param selectedId selected release / 所选发布
     * @return original action / 原动作
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<String> switchRelease(DeploymentApplicationSnapshot snapshot, UUID selectedId)
        throws ApiException {
        requireDefaultManagement();
        return action(snapshot.application().server(), "Switch retained release",
            task -> releases.switchRelease(snapshot.application(), snapshot.revisionSha256(), selectedId, task));
    }

    /** Scans only the selected authorized target. / 仅扫描所选授权目标。
     * @param server exact target / 精确目标
     * @return scan query / 扫描查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentExternalApplications.Scan> scan(ServerId server) throws ApiException {
        requireDefaultManagement();
        return query(List.of(server.id()), "Scan external applications", task -> external.scan(server, task));
    }

    /** Adopts a freshly rechecked external row. / 接管刚复核的外部行。
     * @param server exact target / 精确目标
     * @param candidate selected discovered row / 所选发现行
     * @return original registration / 原登记
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<String> adopt(ServerId server, DeploymentExternalApplications.Discovered candidate)
        throws ApiException {
        requireDefaultManagement();
        return action(server, "Adopt external application", task -> external.adopt(server, candidate, task));
    }

    /** Reviews the exact original registration and a same-host replacement without changing local rows. / 不改本地行地审阅精确原登记及同主机替换。
     * @param original selected saved registration / 所选保存登记
     * @param candidate fresh selected scan / 新所选扫描
     * @return owned immutable review query / 所属不可变审阅查询
     * @throws ApiException if query admission fails / 查询准入失败时 */
    public TaskHandle<DeploymentExternalApplications.Reattachment> reviewReattachment(
        DeploymentExternalApplications.Registered original, DeploymentExternalApplications.Discovered candidate)
        throws ApiException {
        requireDefaultManagement();
        return query(List.of(original.server().id()), "Review external reattachment",
            task -> external.reviewReattachment(original, candidate, task));
    }

    /** Admits explicit reattachment under the original durable management recovery type. / 在原持久管理恢复类型下接纳明确重新绑定。
     * @param review exact original row and target review / 精确原行及目标审阅
     * @return original recoverable action / 原可恢复动作
     * @throws ApiException if action admission fails / 动作准入失败时 */
    public TaskHandle<String> reattach(DeploymentExternalApplications.Reattachment review) throws ApiException {
        requireDefaultManagement();
        return action(review.original().server(), "Reattach external application",
            task -> external.reattach(review, task));
    }

    /** Lists adopted external rows. / 列出已接管外部行。
     * @return original local query / 原本地查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<List<DeploymentExternalApplications.Registered>> externalApplications() throws ApiException {
        return query(List.of(), "Adopted applications", task -> success(external.list(task)));
    }

    /** Acts on one original adopted runtime. / 操作一个原接管运行身份。
     * @param selected selected registration / 所选登记
     * @param action fixed scanner action / 固定扫描动作
     * @return original operation / 原操作
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentExternalApplications.Discovered> externalAction(
        DeploymentExternalApplications.Registered selected, String action) throws ApiException {
        requireDefaultManagement();
        return action.equals("REFRESH_STATUS")
            ? query(List.of(selected.server().id()), "Inspect adopted application",
            task -> external.execute(selected.id(), action, task))
            : action(selected.server(), "Adopted application " + action,
            task -> external.execute(selected.id(), action, task));
    }

    /** Saves local presentation for an adopted row. / 保存接管行的本地展示。
     * @param selected selected registration / 所选登记
     * @param name display name / 展示名称
     * @param category selected category / 所选类别
     * @param url optional website URL / 可选网站链接
     * @return original local operation / 原本地操作
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<String> editExternal(DeploymentExternalApplications.Registered selected, String name,
                                           String category, Optional<String> url) throws ApiException {
        return query(List.of(selected.server().id()), "Edit adopted presentation",
            task -> external.edit(selected, name, category, url, task));
    }

    /** Loads current managed editor values. / 加载当前受管编辑器值。
     * @param selected reviewed application / 已审阅应用
     * @return original query / 原查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentApplicationPresentation.Values> presentation(DeploymentApplicationSnapshot selected)
        throws ApiException {
        return query(List.of(selected.application().server().id()), "Presentation information",
            task -> success(new DeploymentApplicationPresentation(context).values(selected.application(),
                selected.revisionSha256(), task)));
    }

    /** Edits only local presentation under the visible revision. / 在可见修订下仅编辑本地展示。
     * @param snapshot visible snapshot / 可见快照
     * @param name display name / 展示名称
     * @param taskCategory original category / 原类别
     * @param url optional website link / 可选网站链接
     * @return saved presentation / 已保存展示
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<String> edit(DeploymentApplicationSnapshot snapshot, String name, String taskCategory,
                                   Optional<String> url) throws ApiException {
        return query(List.of(snapshot.application().server().id()), "Edit presentation",
            task -> new DeploymentApplicationPresentation(context).edit(snapshot.application(),
                snapshot.revisionSha256(), name, taskCategory, url, task));
    }

    /** Changes only the selected display name, retaining category and access link. / 仅更改所选展示名称，保留类别及访问链接。
     * @param snapshot visible release / 可见发布
     * @param name selected name / 所选名称
     * @return saved name / 已保存名称
     * @throws ApiException if task admission fails / 任务准入失败时 */
    public TaskHandle<String> editName(DeploymentApplicationSnapshot snapshot, String name) throws ApiException {
        return query(List.of(snapshot.application().server().id()), "Edit name",
            task -> new DeploymentApplicationPresentation(context).editName(snapshot.application(),
                snapshot.revisionSha256(), name, task));
    }

    /** Returns retained page tasks so unknown work remains visible after reopening. / 返回留存页面任务，使未知工作在重开后仍可见。
     * @return bounded original snapshots / 有界原快照
     * @throws ApiException if task query fails / 任务查询失败时 */
    public List<TaskSnapshot> tasks() throws ApiException {
        var result = new ArrayList<TaskSnapshot>();
        Optional<String> cursor = Optional.empty();
        do {
            var page = context.tasks().list(cursor, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().view().pageSize());
            page.items().stream().filter(value -> value.type().equals(ACTION_TASK)).forEach(result::add);
            cursor = page.nextCursor();
        } while (cursor.isPresent() && result.size() < 1000);
        return List.copyOf(result);
    }

    /** Reconciles only the original persisted page action without resubmitting it. / 仅核对原持久页面动作，不再次提交。
     * @param previous original retained task snapshot / 原留存任务快照
     * @param task authenticated original recovery task / 已认证原恢复任务
     * @return verified original conclusion or uncertainty / 已核实原结论或未知
     * @throws ApiException if original rememberAuthorization or evidence fails / 原授权或依据失败时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws ApiException {
        if (!previous.id().equals(task.id()))
            return unknown("DEPLOYMENT_MANAGEMENT_RECOVERY_TASK");
        if (previous.type().equals(QUERY_TASK)) {
            if (!context.tasks().resourcesReleased(task))
                return unknown("DEPLOYMENT_APPLICATION_QUERY_RESOURCES");
            return failed("DEPLOYMENT_APPLICATION_QUERY_INTERRUPTED");
        }
        var stored = new DeploymentManagementActionLog(context).find(task);
        if (stored.isEmpty()) {
            if (!context.tasks().resourcesReleased(task))
                return unknown("DEPLOYMENT_MANAGEMENT_RESOURCES");
            return failed("DEPLOYMENT_MANAGEMENT_NOT_STARTED");
        }
        var entry = stored.orElseThrow();
        if (entry.facts().has(DeploymentLifecycleCleanupLog.FIELD))
            return cleanupOriginalLifecycle(entry, task);
        if (entry.phase().equals("REVIEWED")) {
            if (!context.tasks().resourcesReleased(task))
                return unknown("DEPLOYMENT_MANAGEMENT_RESOURCES");
            return failed("DEPLOYMENT_MANAGEMENT_REVIEW_INTERRUPTED");
        }
        OperationResult<?> result;
        if (entry.action().startsWith("LIFECYCLE_")) {
            var application = new DeploymentApplicationId(entry.applicationId(), entry.server());
            var lifecycle = DeploymentLifecycleAction.valueOf(entry.action().substring("LIFECYCLE_".length()));
            var review = DeploymentManagementActionLog.review(entry);
            result = review.isEmpty() ? manager.lifecycle(application, entry.currentRevision(), lifecycle, task)
                : reviewed(application, Optional.of(entry.currentRevision()), DeploymentManagementActionLog.selected(entry),
                    lifecycle.name(), review, true, task,
                    (runner, captured) -> runner.lifecycle(application, entry.currentRevision(), lifecycle,
                        DeploymentManagementActionLog.selected(entry), Optional.of(captured), task));
            if (result.outcome() == TaskResult.UNKNOWN && lifecycle == DeploymentLifecycleAction.RESTART)
                return cleanupOriginalLifecycle(new DeploymentManagementActionLog(context).find(task).orElseThrow(), task);
        } else if (entry.action().equals("HISTORY_SWITCH")) {
            var application = new DeploymentApplicationId(entry.applicationId(), entry.server());
            result = releases.switchRelease(application, entry.currentRevision(),
                UUID.fromString(entry.facts().path("selectedId").asText()), task);
        } else if (entry.action().equals("EXTERNAL_ADOPT")) {
            result = external.adopt(entry.server(),
                new DeploymentExternalApplications.Discovered(entry.facts().path("kind").asText(),
                    entry.facts().path("identity").asText(), entry.currentRevision(), "", "UNKNOWN", false,
                    false, false),
                task);
        } else if (entry.action().startsWith("EXTERNAL_")) {
            result = external.execute(entry.applicationId(), entry.action().substring("EXTERNAL_".length()), task);
        } else
            return unknown("DEPLOYMENT_MANAGEMENT_RECOVERY_KIND");
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** A real user choice starts a new cleanup, never a replay or successful reconstruction of the original RESTART. */
    private OperationResult<Void> cleanupOriginalLifecycle(DeploymentManagementActionLog.Entry entry, TaskContext task) throws ApiException {
        var application = new DeploymentApplicationId(entry.applicationId(), entry.server());
        try {
            var data = new DeploymentApplicationReader(context.db()).find(application, task);
            var originalTarget = DeploymentManagementActionLog.target(entry, task);
            var target = context.servers().get(entry.server(), task);
            var componentIds = new HashSet<String>();
            entry.facts().path("components").fieldNames().forEachRemaining(componentIds::add);
            if (!data.snapshot().revisionSha256().equals(entry.currentRevision()) || !DeploymentApplicationRunner.originalBindings(data, entry)
                || !componentIds.equals(data.graph().components().stream().map(part -> part.componentId()).collect(java.util.stream.Collectors.toSet()))
                || !target.host().equals(originalTarget.host()) || target.port() != originalTarget.port()
                || !target.username().equals(originalTarget.username())
                || target.trustedFingerprint().isPresent() && !target.trustedFingerprint().equals(originalTarget.fingerprint()))
                return unknown("DEPLOYMENT_ORIGINAL_CLEANUP_IDENTITY_UNCONFIRMED");
            boolean confirmed = entry.facts().has(DeploymentLifecycleCleanupLog.FIELD);
            if (!confirmed) {
                String choice = "STOP " + task.id();
                var answer = context.interaction().requestText("仅停止原应用并结清",
                    "原 RESTART 仍为 UNKNOWN，不能证明原重启成功。\n原任务：" + task.id()
                        + "\n应用：" + application.id() + "\n服务器：" + target.host() + ":" + target.port()
                        + "\n指纹：" + originalTarget.fingerprint().orElseThrow() + "\n修订：" + entry.currentRevision()
                        + "\n所有权：" + data.snapshot().ownershipSha256()
                        + "\n输入 " + choice + "，授权新一轮独立审批及单次许可，仅 STOP 并禁自启原应用。"
                        + "不重跑部署或业务；单独保存清理回执并保留原 UNKNOWN 历史。清理核实后原任务以失败结束。留空保持 UNKNOWN。",
                    false, task);
                if (answer.outcome() != TaskResult.SUCCEEDED || !answer.value().filter(choice::equals).isPresent())
                    return unknown("DEPLOYMENT_ORIGINAL_CLEANUP_NOT_CONFIRMED");
                confirmed = true;
            }
            for (var action : List.of(DeploymentLifecycleAction.STOP, DeploymentLifecycleAction.DISABLE_AUTOSTART)) {
                var journal = new DeploymentLifecycleCleanupLog(context, entry, action.name(), confirmed);
                var prior = journal.find(task);
                var review = prior.flatMap(DeploymentManagementActionLog::review);
                var result = reviewed(application, Optional.of(entry.currentRevision()), Optional.empty(), action.name(),
                    review, true, task, journal,
                    (runner, policy) -> runner.lifecycle(application, entry.currentRevision(), action, Optional.empty(), Optional.of(policy), task));
                if (result.outcome() != TaskResult.SUCCEEDED)
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), result.problem());
                if (result.value().orElseThrow().after() != gold.debug.windowstolinux.mod.deployment.api.ApplicationRunState.STOPPED)
                    return unknown("DEPLOYMENT_ORIGINAL_CLEANUP_UNCONFIRMED");
            }
            if (!context.tasks().resourcesReleased(task)) return unknown("DEPLOYMENT_ORIGINAL_CLEANUP_RESOURCES");
            return failed("DEPLOYMENT_ORIGINAL_UNCONFIRMED_CLEANUP_VERIFIED");
        } catch (ApiException unconfirmed) {
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(unconfirmed.problem()));
        } catch (IllegalArgumentException unconfirmed) {
            return unknown("DEPLOYMENT_ORIGINAL_CLEANUP_UNCONFIRMED");
        } catch (RuntimeException | LinkageError unavailable) {
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                "DEPLOYMENT_ORIGINAL_CLEANUP_RUNTIME_FAILURE", "Cleanup provider or development build is unavailable",
                Map.of("failureType", unavailable.getClass().getName(), "location", unavailable.getStackTrace().length == 0
                    ? "unavailable" : unavailable.getStackTrace()[0].toString()), Optional.empty())));
        }
    }

    private void requireDefaultManagement() throws ApiException {
        if (agentPolicy.isPresent()) throw new ApiException(new Problem(ProblemCode.INCOMPATIBLE,
            "DEPLOYMENT_AGENT_MANAGEMENT_ACTION_UNSUPPORTED", "This Agent management policy does not cover the selected action",
            Map.of(), Optional.empty()));
    }

    @FunctionalInterface
    private interface ReviewedWork<T> {
        OperationResult<T> run(DeploymentApplicationRunner runner, DeploymentManagementActionLog.ReviewPolicy policy) throws ApiException;
    }

    /** Establishes review only inside the real admitted task, and reconstructs it from original v2 recovery facts. */
    private <T> OperationResult<T> reviewed(DeploymentApplicationId application, Optional<String> revision,
                                             Optional<String> selected, String action,
                                             Optional<DeploymentManagementActionLog.ReviewPolicy> original,
                                             boolean recovery, TaskContext task, ReviewedWork<T> work) throws ApiException {
        return reviewed(application, revision, selected, action, original, recovery, task,
            new DeploymentManagementActionLog(context), work);
    }

    private <T> OperationResult<T> reviewed(DeploymentApplicationId application, Optional<String> revision,
                                             Optional<String> selected, String action,
                                             Optional<DeploymentManagementActionLog.ReviewPolicy> original,
                                             boolean recovery, TaskContext task, DeploymentManagementActionLog journal,
                                             ReviewedWork<T> work) throws ApiException {
        try {
            var data = new DeploymentApplicationReader(context.db()).find(application, task);
            if (revision.filter(value -> !value.equals(data.snapshot().revisionSha256())).isPresent())
                return recovery ? unknown("DEPLOYMENT_APPLICATION_REVISION_CHANGED") : failed("DEPLOYMENT_APPLICATION_REVISION_CHANGED");
            if (selected.isPresent() && data.graph().components().stream().noneMatch(part -> part.componentId().equals(selected.orElseThrow())
                && part.runtime().workload().supportsLifecycle()))
                return recovery ? unknown("DEPLOYMENT_MANAGEMENT_COMPONENT_CHANGED") : failed("DEPLOYMENT_MANAGEMENT_COMPONENT_CHANGED");
            var policy = original.isPresent() ? original.orElseThrow()
                : new DeploymentManagementActionLog.ReviewPolicy(context.ai().configurationRevision(AiPurpose.LANGUAGE, task));
            DeploymentFixedCommandApproval.requireRoute(context, task, policy.configurationRevision());
            var target = context.servers().get(application.server(), task);
            var evidence = new ManagementEvidence(application, data, target, selected, action, policy, task, journal);
            String frozen = "application=" + application.id() + "\nrelease=" + data.snapshot().revisionSha256()
                + "\nownership=" + data.snapshot().ownershipSha256() + "\nscope=" + (selected.isPresent() ? "SINGLE_COMPONENT" : "WHOLE_APPLICATION")
                + "\ncomponent=" + selected.orElse("ALL") + "\naction=" + action + "\nmodelRevision=" + policy.configurationRevision()
                + "\ntarget=" + target.ref().id() + " " + target.host() + ":" + target.port() + " " + target.username();
            try (var workflow = recovery
                ? DeploymentWorkflowAuthorization.recover(context, task, application.server(), policy.configurationRevision(), "AUTOMATIC", false, frozen)
                : DeploymentWorkflowAuthorization.open(context, task, application.server(), policy.configurationRevision(), "AUTOMATIC", false, frozen)) {
                var scoped = new DeploymentCommandReviewScope(context, context.interaction(), task, "AUTOMATIC", false,
                    policy::configurationRevision).withCurrentEvidence(evidence);
                return work.run(new DeploymentApplicationRunner(scoped, journal), policy);
            }
        } catch (DeploymentAiJson.CapabilityOutcomeException refusal) {
            return recovery ? unknown("DEPLOYMENT_MANAGEMENT_REVIEW_UNCONFIRMED")
                : new OperationResult<>(refusal.outcome(), Optional.empty(), refusal.problem());
        } catch (ApiException | IllegalArgumentException refusal) {
            if (recovery) return unknown("DEPLOYMENT_MANAGEMENT_ORIGINAL_FACTS_UNCONFIRMED");
            throw refusal;
        }
    }

    /** Bounded immutable management identity; current states and ordinary journal progress are not release identity. */
    private final class ManagementEvidence implements DeploymentCurrentCommandEvidence {
        private final DeploymentApplicationId application;
        private final DeploymentApplicationReader.ApplicationFacts original;
        private final ServerInfo target;
        private final Optional<String> selected;
        private final String action;
        private final DeploymentManagementActionLog.ReviewPolicy policy;
        private final TaskContext task;
        private final DeploymentManagementActionLog journal;
        private com.fasterxml.jackson.databind.JsonNode journalIdentity;

        ManagementEvidence(DeploymentApplicationId application, DeploymentApplicationReader.ApplicationFacts original,
                           ServerInfo target, Optional<String> selected, String action,
                           DeploymentManagementActionLog.ReviewPolicy policy, TaskContext task, DeploymentManagementActionLog journal) {
            this.application = application; this.original = original; this.target = target;
            this.selected = selected; this.action = action; this.policy = policy; this.task = task; this.journal = journal;
        }

        public Map<String, ?> facts(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            verify(intent, request, supplied);
            var value = new LinkedHashMap<String, Object>();
            value.put("formatVersion", 2); value.put("taskId", task.id().toString());
            value.put("applicationId", application.id()); value.put("serverId", application.server().id());
            value.put("applicationReleaseSha256", original.snapshot().revisionSha256());
            value.put("applicationOwnershipSha256", original.snapshot().ownershipSha256());
            value.put("scope", selected.isPresent() ? "SINGLE_COMPONENT" : "WHOLE_APPLICATION");
            value.put("selectedComponentId", selected.orElse("")); value.put("action", action);
            value.put("review", Map.of("approval", "AUTOMATIC", "rememberAuthorization", false,
                "configurationRevision", policy.configurationRevision()));
            value.put("expectedTarget", Map.of("host", target.host(), "port", target.port(), "username", target.username(),
                "previouslyTrustedFingerprint", target.trustedFingerprint().orElse(""), "isWireObservation", false));
            value.put("components", original.graph().components().stream().map(part -> Map.of(
                "componentId", part.componentId(), "unitId", part.unit().id(), "unitOwnershipSha256", part.unit().ownershipManifestSha256(),
                "systemdUnit", part.unit().systemdUnit(), "releaseRoot", part.unit().releaseRoot(),
                "releaseSha256", original.release().bindings().stream().filter(binding -> binding.unitId().equals(part.unit().id()))
                    .findFirst().orElseThrow().releaseSha256())).toList());
            var entry = journal.find(task);
            entry.ifPresent(stored -> value.put("originalActionFacts", stored.facts()));
            if (journal instanceof DeploymentLifecycleCleanupLog cleanup)
                value.put("originalLifecycleCleanup", cleanup.evidence(task));
            return Map.of("managementIdentity", Collections.unmodifiableMap(value));
        }

        public void verify(ActionIntent intent, CommandRequest request, TaskContext supplied) throws ApiException {
            if (supplied != task || !intent.serverIds().equals(List.of(application.server().id()))
                || !intent.revision().equals(original.snapshot().revisionSha256()))
                throw changed();
            DeploymentFixedCommandApproval.requireRoute(context, task, policy.configurationRevision());
            var current = new DeploymentApplicationReader(context.db()).find(application, task);
            var endpoint = context.servers().get(application.server(), task);
            if (!current.snapshot().revisionSha256().equals(original.snapshot().revisionSha256())
                || !current.snapshot().ownershipSha256().equals(original.snapshot().ownershipSha256())
                || !current.graph().equals(original.graph()) || !current.release().bindings().equals(original.release().bindings())
                || !endpoint.host().equals(target.host()) || endpoint.port() != target.port() || !endpoint.username().equals(target.username())
                || target.trustedFingerprint().isPresent() && !endpoint.trustedFingerprint().equals(target.trustedFingerprint()))
                throw changed();
            var entry = journal.find(task);
            if (entry.isPresent()) {
                var stored = entry.orElseThrow();
                if (!stored.applicationId().equals(application.id()) || !stored.server().equals(application.server())
                    || !stored.currentRevision().equals(original.snapshot().revisionSha256())
                    || !stored.action().equals("LIFECYCLE_" + action)
                    || !DeploymentManagementActionLog.review(stored).equals(Optional.of(policy))
                    || !DeploymentManagementActionLog.selected(stored).equals(selected)) throw changed();
                var identity = DeploymentManagementActionLog.immutable(stored.facts());
                if (journalIdentity != null && !journalIdentity.equals(identity)) throw changed();
                journalIdentity = identity;
            } else if (journalIdentity != null) throw changed();
        }

        private ApiException changed() {
            return new ApiException(new Problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_MANAGEMENT_REVIEW_CHANGED",
                "Original management identity changed", Map.of(), Optional.empty()));
        }
    }

    /** Admits one query under exactly its original server scope. / 在精确原服务器范围下接纳一项查询。
     * @param <T> query result type / 查询结果类型
     * @param servers complete original server scope / 完整原服务器范围
     * @param title visible original query title / 可见原查询标题
     * @param work original query callback / 原查询回调
     * @return actual accepted query task / 实际接纳查询任务
     * @throws ApiException if admission fails / 准入失败时 */
    private <T> TaskHandle<T> query(List<String> servers, String title, TaskWork<T> work) throws ApiException {
        return context.tasks().submit(new TaskRequest(QUERY_TASK, title, servers, true), work);
    }

    /** Admits one durable action bound to the exact selected endpoint. / 接纳绑定精确所选端点的一项持久动作。
     * @param <T> action result type / 动作结果类型
     * @param server exact original endpoint / 精确原端点
     * @param title visible original action title / 可见原动作标题
     * @param work original action callback / 原动作回调
     * @return actual accepted action task / 实际接纳动作任务
     * @throws ApiException if admission fails / 准入失败时 */
    private <T> TaskHandle<T> action(ServerId server, String title, TaskWork<T> work) throws ApiException {
        return context.tasks().submit(new TaskRequest(ACTION_TASK, title, List.of(server.id()), true), work);
    }

    /** Returns an actually observed original value. / 返回实际观察到的原值。
     * @param <T> result type / 结果类型
     * @param value verified original value / 已核实原值
     * @return successful original result / 成功原结果 */
    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    /** Returns a known original query or action failure. / 返回已知原查询或动作失败。
     * @param <T> result type / 结果类型
     * @param event stable known failure / 稳定已知失败
     * @return known original failure / 已知原失败 */
    private static <T> OperationResult<T> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
            event, "Application operation did not complete", Map.of(), Optional.empty())));
    }

    /** Retains the original action for authenticated recovery. / 留存原动作供认证恢复。
     * @param <T> result type / 结果类型
     * @param event stable uncertainty identity / 稳定未知身份
     * @return retained original uncertainty / 留存原未知 */
    private static <T> OperationResult<T> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original application operation requires reconciliation", Map.of(), Optional.empty())));
    }
}
