package gold.debug.windowstolinux.mod.deployment.service.core.execution;

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
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRecord;

/** Admits application page operations with their exact original server scope. / 按精确原服务器范围接纳应用页面操作。 */
public final class DeploymentApplicationsService implements TaskRecovery {
    /** Read-only application query task. / 只读应用查询任务。 */
    public static final String QUERY_TASK = "deployment-applications-query";

    /** Durable original application management task. / 持久原应用管理任务。 */
    public static final String ACTION_TASK = "deployment-applications-action";

    /** Active provider capabilities for page task admission. / 页面任务准入的活动提供方能力。 */
    private final ModContext context;

    /** Exact managed lifecycle and complete observations. / 精确受管生命周期及完整观察。 */
    private final DeploymentApplicationRunner manager;

    /** Original retained transitions and verified compensation. / 原保留转换及已核实补偿。 */
    private final DeploymentRetainedReleases releases;

    /** Original adopted runtime registry and action evidence. / 原接管运行登记及动作依据。 */
    private final DeploymentExternalApplications external;

    /** Binds one active deployment generation. / 绑定一个活动部署代次。
     * @param context active context / 活动上下文 */
    public DeploymentApplicationsService(ModContext context) {
        this.context = Objects.requireNonNull(context);
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
            task -> manager.inspect(application, task));
    }

    /** Submits one exact reviewed lifecycle action. / 提交一个精确已审阅生命周期动作。
     * @param snapshot selected review / 所选审阅
     * @param action selected action / 所选动作
     * @return original action / 原动作
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentLifecycleResult> lifecycle(DeploymentApplicationSnapshot snapshot,
                                                           DeploymentLifecycleAction action) throws ApiException {
        return action(snapshot.application().server(), "Application " + action,
            task -> manager.lifecycle(snapshot.application(), snapshot.revisionSha256(), action, task));
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
        return action(snapshot.application().server(), "Switch retained release",
            task -> releases.switchRelease(snapshot.application(), snapshot.revisionSha256(), selectedId, task));
    }

    /** Scans only the selected authorized target. / 仅扫描所选授权目标。
     * @param server exact target / 精确目标
     * @return scan query / 扫描查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentExternalApplications.Scan> scan(ServerId server) throws ApiException {
        return query(List.of(server.id()), "Scan external applications", task -> external.scan(server, task));
    }

    /** Adopts a freshly rechecked external row. / 接管刚复核的外部行。
     * @param server exact target / 精确目标
     * @param candidate selected discovered row / 所选发现行
     * @return original registration / 原登记
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<String> adopt(ServerId server, DeploymentExternalApplications.Discovered candidate)
        throws ApiException {
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
        return query(List.of(original.server().id()), "Review external reattachment",
            task -> external.reviewReattachment(original, candidate, task));
    }

    /** Admits explicit reattachment under the original durable management recovery type. / 在原持久管理恢复类型下接纳明确重新绑定。
     * @param review exact original row and target review / 精确原行及目标审阅
     * @return original recoverable action / 原可恢复动作
     * @throws ApiException if action admission fails / 动作准入失败时 */
    public TaskHandle<String> reattach(DeploymentExternalApplications.Reattachment review) throws ApiException {
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
            var page = context.tasks().list(cursor, 200);
            page.items().stream().filter(value -> value.type().equals(ACTION_TASK)).forEach(result::add);
            cursor = page.nextCursor();
        } while (cursor.isPresent() && result.size() < 1000);
        return List.copyOf(result);
    }

    /** Reconciles only the original persisted page action without resubmitting it. / 仅核对原持久页面动作，不再次提交。
     * @param previous original retained task snapshot / 原留存任务快照
     * @param task authenticated original recovery task / 已认证原恢复任务
     * @return verified original conclusion or uncertainty / 已核实原结论或未知
     * @throws ApiException if original authorization or evidence fails / 原授权或依据失败时 */
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
        if (entry.phase().equals("REVIEWED")) {
            if (!context.tasks().resourcesReleased(task))
                return unknown("DEPLOYMENT_MANAGEMENT_RESOURCES");
            return failed("DEPLOYMENT_MANAGEMENT_REVIEW_INTERRUPTED");
        }
        OperationResult<?> result;
        if (entry.action().startsWith("LIFECYCLE_")) {
            var application = new DeploymentApplicationId(entry.applicationId(), entry.server());
            result = manager.lifecycle(application, entry.currentRevision(),
                DeploymentLifecycleAction.valueOf(entry.action().substring("LIFECYCLE_".length())), task);
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
