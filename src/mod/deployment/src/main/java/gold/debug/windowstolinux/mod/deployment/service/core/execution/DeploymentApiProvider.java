package gold.debug.windowstolinux.mod.deployment.service.core.execution;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.query.PageSlice;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.api.*;

/** Deployment-owned synchronous API; every call remains under the authenticated root task. / 部署所属同步API，每次调用均留在已认证根任务下。 */
public final class DeploymentApiProvider implements DeploymentApi {
    /** Current provider capabilities under the original caller scope. / 原调用方范围下的当前提供方能力。 */
    private final ModContext context;

    /** Complete managed application observation and lifecycle. / 完整受管应用观察及生命周期。 */
    private final DeploymentApplicationRunner applications;

    /** Original source collection and verified restoration. / 原来源采集及已核实恢复。 */
    private final DeploymentCollectionFlow collection;

    /** Original target preparation, activation and cleanup. / 原目标准备、激活及清理。 */
    private final DeploymentRestoreFlow restore;

    /** Authenticated immutable request and recovery facts. / 已认证不可变请求及恢复事实。 */
    private final DeploymentHandoffLedger ledger;

    /** Uses only the provider's owned capabilities; no caller grant is transferable. / 仅使用提供方所属能力，调用方授权不可转授。
     * @param context active deployment provider / 活动部署提供方 */
    public DeploymentApiProvider(ModContext context) {
        this.context = Objects.requireNonNull(context);
        applications = new DeploymentApplicationRunner(context);
        collection = new DeploymentCollectionFlow(context);
        restore = new DeploymentRestoreFlow(context);
        ledger = new DeploymentHandoffLedger(context);
    }

    /** Returns bounded saved snapshots under the authenticated caller. / 在已认证调用方下返回有界已存快照。
     * @param cursor previous page position / 前页位置
     * @param limit bounded page size / 有界页大小
     * @param task original root task / 原根任务
     * @return saved page / 已存页
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public PageSlice<DeploymentApplicationSnapshot> listApplications(Optional<String> cursor, int limit,
                                                                     TaskContext task) throws ApiException {
        authenticate(task);
        return applications.list(cursor, limit, task);
    }

    /** Rechecks complete current ownership, release and health. / 复核完整当前归属、发布及健康。
     * @param application exact application / 精确应用
     * @param task original root task / 原根任务
     * @return verified observation / 已核实观察
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentApplicationSnapshot> inspect(DeploymentApplicationId application,
                                                                  TaskContext task) throws ApiException {
        authenticate(task);
        context.servers().get(application.server(), task);
        return applications.inspect(application, task);
    }

    /** Performs or reconciles one original reviewed lifecycle action. / 执行或核对一项原已审阅生命周期动作。
     * @param application exact application / 精确应用
     * @param revision reviewed graph digest / 已审阅图摘要
     * @param action fixed lifecycle action / 固定生命周期动作
     * @param task original root task / 原根任务
     * @return verified original effect / 已核实原效果
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentLifecycleResult> lifecycle(DeploymentApplicationId application, String revision,
                                                                DeploymentLifecycleAction action, TaskContext task) throws ApiException {
        authenticate(task);
        context.servers().get(application.server(), task);
        return applications.lifecycle(application, revision, action, task);
    }

    /** Records a source review before collection effects. / 在采集效果前登记来源审阅。
     * @param request selected original source and bindings / 所选原来源与绑定
     * @param task original root task / 原根任务
     * @return durable reviewed handoff / 持久已审阅交接
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentHandoff> reviewCollection(DeploymentCollectionRequest request, TaskContext task)
        throws ApiException {
        authenticate(task);
        context.servers().get(request.application().server(), task);
        return collection.review(request, task);
    }

    /** Collects original sealed artifacts without replaying unknown exports. / 采集原封印产物，不重放未知导出。
     * @param handoff original handoff identity / 原交接身份
     * @param task original root task / 原根任务
     * @return complete artifacts or retained uncertainty / 完整产物或留存未知
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentCollection> collect(UUID handoff, TaskContext task) throws ApiException {
        authenticate(task);
        return collection.collect(handoff, task);
    }

    /** Fences original producers and restores only this handoff source state. / 关闭原生产者并仅恢复本交接来源状态。
     * @param handoff original handoff identity / 原交接身份
     * @param task original root task / 原根任务
     * @return verified original cleanup / 已核实原清理
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentHandoff> finishCollection(UUID handoff, TaskContext task) throws ApiException {
        authenticate(task);
        return collection.finish(handoff, task);
    }

    /** Reviews actual target facts independently of the archived source endpoint. / 不依赖归档来源端点地审阅真实目标事实。
     * @param archived complete immutable source archive / 完整不可变来源归档
     * @param target actual target endpoint / 实际目标端点
     * @param archiveSha verified whole archive digest / 已核实整份归档摘要
     * @param task original root task / 原根任务
     * @return target review digest / 目标审阅摘要
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<String> reviewRestoreTarget(DeploymentApplicationSnapshot archived, ServerId target,
                                                       String archiveSha, TaskContext task) throws ApiException {
        authenticate(task);
        return restore.review(archived, target, archiveSha, task);
    }

    /** Prepares all original component and database candidates. / 准备全部原组件和数据库候选。
     * @param request complete archive and reviewed target / 完整归档及已审阅目标
     * @param task original root task / 原根任务
     * @return complete preparation or retained uncertainty / 完整准备或留存未知
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentHandoff> prepareRestore(DeploymentRestoreRequest request, TaskContext task)
        throws ApiException {
        authenticate(task);
        return restore.prepare(request, task);
    }

    /** Activates or completes known cleanup of the original prepared graph. / 激活原已准备应用图或完成已知清理。
     * @param handoff original handoff identity / 原交接身份
     * @param task original root task / 原根任务
     * @return verified activation and cleanup / 已核实激活与清理
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentActivation> activate(UUID handoff, TaskContext task) throws ApiException {
        authenticate(task);
        return restore.activate(handoff, task);
    }

    /** Discards original unactivated material after producer and identity checks. / 经生产者及身份核对后丢弃原未激活材料。
     * @param handoff original handoff identity / 原交接身份
     * @param task original root task / 原根任务
     * @return verified original discard / 已核实原丢弃
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentHandoff> discard(UUID handoff, TaskContext task) throws ApiException {
        authenticate(task);
        return restore.discard(handoff, task);
    }

    /** Reads exact original receipts and advances only confirmed handoff facts. / 读取精确原回执且仅推进已确证交接事实。
     * @param handoff original handoff identity / 原交接身份
     * @param task original root task / 原根任务
     * @return known phase or retained uncertainty / 已知阶段或留存未知
     * @throws ApiException if authentic task scope or original evidence is unavailable / 真实任务范围或原依据不可用时 */
    @Override
    public OperationResult<DeploymentHandoff> reconcile(UUID handoff, TaskContext task) throws ApiException {
        authenticate(task);
        var original = ledger.find(handoff, task)
            .orElseThrow(() -> new ApiException(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_HANDOFF_ABSENT",
                "Original handoff is absent", Map.of(), Optional.empty())));
        return original.request().path("kind").asText().equals("COLLECTION")
            ? collection.reconcile(handoff, task)
            : restore.reconcile(handoff, task);
    }

    /** Requires the actual host-certified root task within the current provider call. / 要求当前提供方调用内实际宿主认证根任务。
     * @param task original caller task / 原调用方任务
     * @throws ApiException if original task scope is unavailable / 原任务范围不可用时 */
    private void authenticate(TaskContext task) throws ApiException {
        context.tasks().inspect(Objects.requireNonNull(task));
    }
}
