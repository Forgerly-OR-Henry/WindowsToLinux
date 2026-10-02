package gold.debug.windowstolinux.mod.deployment.api;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.PageSlice;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.util.Optional;
import java.util.UUID;



/** Public deployment snapshot, lifecycle and backup handoff service. / 公开部署快照、生命周期与备份交接服务。
 * <p>The original caller must retain its own root checkpoint across the complete handoff sequence,
 * close it only after FINISHED, and retain it on UNKNOWN for original-task reconciliation.
 * The provider never acquires another task or uses the caller's checkpoint capability.
 * / 原调用方必须用自己的根检查点贯穿完整交接，仅在FINISHED后关闭；UNKNOWN时保留供原任务核对。
 * 提供方不创建另一任务，也不使用调用方的检查点能力。 */
public interface DeploymentApi {
    /** Reviews the authenticated current target without preparing or uploading. / 无准备或上传副作用地审阅已认证当前目标。
     * @param archived archived source facts / 归档来源事实
     * @param target actual target server / 实际目标服务器
     * @param archiveSha256 caller-verified complete archive digest / 调用方核实的完整归档摘要
     * @param task original caller task / 原调用任务
     * @return target revision bound to the exact archive and current host trust / 绑定精确归档及当前主机信任的目标修订
     * @throws ApiException if scope or observed target is unavailable / 范围或已观察目标不可用时 */
    OperationResult<String> reviewRestoreTarget(DeploymentApplicationSnapshot archived,
                                                gold.debug.windowstolinux.appapi.server.ServerRef target, String archiveSha256, TaskContext task)
        throws ApiException;

    /** Lists persisted application summaries. / 列出持久应用摘要。
     * @param cursor continuation cursor / 续页游标
     * @param limit page limit / 页大小
     * @param task original caller task / 原调用任务
     * @return one page / 一页结果
     * @throws ApiException if access fails / 访问失败时 */
    PageSlice<DeploymentApplicationSnapshot> listApplications(Optional<String> cursor, int limit, TaskContext task)
        throws ApiException;

    /** Refreshes exact remote identity and status. / 刷新精确远端身份与状态。
     * @param application exact application / 精确应用
     * @param task original caller task / 原调用任务
     * @return current observation / 当前观察
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentApplicationSnapshot> inspect(DeploymentApplicationRef application, TaskContext task)
        throws ApiException;

    /** Performs one approved managed lifecycle action. / 执行一次已批准受管生命周期动作。
     * @param application exact application / 精确应用
     * @param expectedRevisionSha256 reviewed revision / 已审阅修订
     * @param action explicit action / 明确动作
     * @param task original caller task / 原调用任务
     * @return verified action result / 已验证动作结果
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentLifecycleResult> lifecycle(DeploymentApplicationRef application,
                                                         String expectedRevisionSha256, DeploymentLifecycleAction action, TaskContext task)
        throws ApiException;

    /** Reviews collection without a remote effect. / 无远端副作用地审阅采集。
     * @param request selected bindings / 所选绑定
     * @param task original caller task / 原调用任务
     * @return durable handoff / 持久交接
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentHandoff> reviewCollection(DeploymentCollectionRequest request, TaskContext task)
        throws ApiException;

    /** Collects the reviewed source bindings. / 采集已审阅来源绑定。
     * @param handoffId original handoff / 原交接
     * @param task original caller task / 原调用任务
     * @return complete remote artifacts / 完整远端产物
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentCollection> collect(UUID handoffId, TaskContext task)
        throws ApiException;

    /** Restores the source state changed for collection. / 恢复采集期间明确改变的来源状态。
     * @param handoffId original handoff / 原交接
     * @param task original caller task / 原调用任务
     * @return durable recovery result / 持久恢复结果
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentHandoff> finishCollection(UUID handoffId, TaskContext task) throws ApiException;

    /** Prepares a reviewed target candidate from verified files. / 由已验证文件准备已审阅目标候选。
     * @param request exact archive and target / 精确归档与目标
     * @param task original caller task / 原调用任务
     * @return durable preparation; repeating the exact original request after uncertain preparation returns the original handoff without reading local files or replaying effects / 持久准备；未知准备后精确原请求重查返回原交接，不读取本地文件或重放效果
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentHandoff> prepareRestore(DeploymentRestoreRequest request, TaskContext task)
        throws ApiException;

    /** Activates a prepared target under its original review. / 在原审阅下激活已准备目标。
     * @param handoffId original handoff / 原交接
     * @param task original caller task / 原调用任务
     * @return verified activation / 已验证激活
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentActivation> activate(UUID handoffId, TaskContext task)
        throws ApiException;

    /** Discards only an original unactivated candidate. / 仅丢弃原未激活候选。
     * @param handoffId original handoff / 原交接
     * @param task original caller task / 原调用任务
     * @return durable cleanup result / 持久清理结果
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentHandoff> discard(UUID handoffId, TaskContext task) throws ApiException;

    /** Observes an unknown handoff without replaying the effect. / 观察未知交接且不重放副作用。
     * @param handoffId original handoff / 原交接
     * @param task original caller task / 原调用任务
     * @return observed handoff / 已观察交接
     * @throws ApiException if access fails / 访问失败时 */
    OperationResult<DeploymentHandoff> reconcile(UUID handoffId, TaskContext task) throws ApiException;
}
