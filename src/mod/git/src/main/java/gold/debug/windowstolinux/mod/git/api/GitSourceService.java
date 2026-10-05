package gold.debug.windowstolinux.mod.git.api;

import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Read-only Git source service within the caller's original managed task. / 调用方原受管任务内的只读 Git 来源服务。 */
public interface GitSourceService {
    /** Executes only a new durably recorded intent and publishes platform-owned material. / 只执行新持久意图并发布平台持有素材。
     * @param request original reviewed intent / 原已审阅意图
     * @param task authentic original task / 真实原任务
     * @return exact fixed-source value / 精确固定来源值
     * @throws ApiException if admission, physical work or durable result is unconfirmed / 准入、物理工作或持久结果未确认时 */
    GitSourceResult prepare(GitSourceRequest request, TaskContext task) throws ApiException;

    /** Observes original evidence without creating a worker, fetching or republishing. / 不创建 worker、抓取或重新发布，仅观察原依据。
     * @param operationId original operation identity / 原操作身份
     * @param materialId original material identity / 原素材身份
     * @param task authentic original execution or recovery task / 真实原执行或恢复任务
     * @return proven original result or explicit uncertainty / 已证明原结果或明确未知
     * @throws ApiException if original authority or durable evidence is unavailable / 原授权或持久依据不可用时 */
    OperationResult<GitSourceResult> reconcile(UUID operationId, UUID materialId, TaskContext task) throws ApiException;
}
