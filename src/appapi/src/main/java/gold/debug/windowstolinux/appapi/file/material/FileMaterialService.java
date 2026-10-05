package gold.debug.windowstolinux.appapi.file.material;

import java.util.UUID;
import java.util.List;

import gold.debug.windowstolinux.appapi.file.work.FileWorkspace;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Original-task custody and consumer access; handles never cross Mod service boundaries. / 原任务接管及消费方访问，句柄不得跨 Mod 服务边界。
 */
public interface FileMaterialService {
    /** Takes custody of a genuine owned workspace only after its producer has stopped writing. / 仅在生产方停止写入后接管真实所属工作目录。
     * @param originalWorkspace original activation-owned handle / 原启用所属句柄
     * @param publication pre-recorded identities and complete names / 预记身份及完整名称
     * @param task authentic original task / 真实原任务
     * @return small published identity / 已发布小型身份
     * @throws ApiException if custody or physical stability is unconfirmed / 接管或物理稳定未确认时 */
    MaterialRef publish(FileWorkspace originalWorkspace, MaterialPublication publication, TaskContext task) throws ApiException;

    /** Persists a root consumer read hold before issuing its own selection. / 发放根消费方自己的选择前持久保存读占用。
     * @param original exact published reference / 精确发布引用
     * @param task authentic original root task / 真实原根任务
     * @return consumer-owned local hold / 消费方所属本地持有
     * @throws ApiException if exact graph, material or consumer differs / 精确图、素材或消费方不同时 */
    FileMaterialHold acquire(MaterialRef original, TaskContext task) throws ApiException;

    /** Restores all exact old-boot holds only for original root recovery cleanup; restored selections cannot start business reads. / 仅为原根恢复清理重绑全部精确旧启动持有，恢复选择不得开始业务读取。
     * @param original exact original published reference / 精确原发布引用
     * @param task authentic original root recovery / 真实原根恢复
     * @return original close-only hold handles / 原仅清理持有句柄
     * @throws ApiException if any original hold or physical fact is uncertain / 任一原持有或物理事实不确定时 */
    List<FileMaterialHold> recoverHolds(MaterialRef original, TaskContext task) throws ApiException;

    /** Finds only original persisted facts, without starting work or recreating missing material. / 仅查原持久事实，不启动工作或重建缺失素材。
     * @param materialId pre-recorded material identity / 预记素材身份
     * @param operationId pre-recorded operation identity / 预记操作身份
     * @param task authentic original task / 真实原任务
     * @return verified original published identity / 已核验原发布身份
     * @throws ApiException if original evidence is absent or uncertain / 原证据缺失或不确定时 */
    MaterialRef find(UUID materialId, UUID operationId, TaskContext task) throws ApiException;

    /** Inspects only original root or producer facts without granting reads or starting work. / 仅检查原根或生产方事实，不授予读取或启动工作。
     * @param materialId original material identity / 原素材身份
     * @param operationId original operation identity / 原操作身份
     * @param task authentic original task / 真实原任务
     * @return exact read-only original state / 精确只读原状态
     * @throws ApiException if original identity or evidence is uncertain / 原身份或证据不确定时 */
    MaterialInspection inspect(UUID materialId, UUID operationId, TaskContext task) throws ApiException;

    /** Abandons only original unpublished custody after confirmed producer cleanup; published material is returned unchanged. / 在生产方已确认收尾后仅放弃原未发布接管，已发布素材原样返回。
     * @param materialId original material identity / 原素材身份
     * @param operationId original operation identity / 原操作身份
     * @param task authentic original root cleanup / 真实原根清理
     * @return exact resulting original state without inventing a reference / 精确结果原状态，不虚构引用
     * @throws ApiException if capture, processes, physical cleanup or persistence remain uncertain / 捕获、进程、物理清理或持久保存仍不确定时 */
    MaterialInspection releaseUnpublished(UUID materialId, UUID operationId, TaskContext task) throws ApiException;

    /** Releases root custody after known business cleanup and all read holds have closed; no TTL applies. / 业务已知收尾且全部读持有关闭后释放根接管，不设期限。
     * @param original exact original reference / 精确原引用
     * @param task authentic original root task / 真实原根任务
     * @throws ApiException if holds, physical deletion or durable release remain / 仍有持有、物理删除或持久释放未完成时 */
    void release(MaterialRef original, TaskContext task) throws ApiException;
}
