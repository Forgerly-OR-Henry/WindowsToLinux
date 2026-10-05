package gold.debug.windowstolinux.app.service.file.check;

import gold.debug.windowstolinux.appapi.file.action.FileChoice;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Host interactions; implementations must preserve caller-bound approval and task waiting. / 保持调用方审批和任务等待的平台交互。 */
public interface FileInteraction {
    /** Chooses the directory collision layer. / 选择目录冲突层策略。
     * @param source original observed source object / 原观测来源对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    FileChoice.Directory chooseDirectory(FileInfo source, FileInfo target, TaskContext task) throws ApiException;

    /** Chooses the file or link collision layer. / 选择文件或链接冲突层策略。
     * @param source original observed source object / 原观测来源对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    FileChoice.Conflict chooseFile(FileInfo source, FileInfo target, TaskContext task) throws ApiException;

    /** Obtains exact approval; ordinary deletion lets the user select an available effect. / 取得精确审批，普通删除由用户选择可用效果。
     * @param intent exact proposed external effect, or the default offered deletion effect / 精确拟执行外部副作用或默认可选删除效果
     * @param object exact observed deletion object / 精确观测删除对象
     * @param delete whether unconditional ordinary deletion confirmation applies / 是否适用无条件普通删除确认
     * @param explanation optional metadata-only file explanation / 可选仅元数据文件说明
     * @param task authentic original executing task / 真实原执行任务
     * @return grant for the actual selected effect, or refusal / 用户实际选择效果的许可或拒绝
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    OperationResult<ApprovalGrant> approve(ActionIntent intent, FileInfo object, boolean delete, String explanation, TaskContext task) throws ApiException;

    /** Waits without open I/O or transactions; true means explicitly continue, false terminate. / 无活动 I/O 或事务地等待，真为继续、假为终止。
     * @param reason safe explicit wait or result reason / 安全明确等待或结果原因
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    boolean awaitContinue(String reason, TaskContext task) throws ApiException;

    /** Optional first-language-model explanation according to the persistent preference. / 根据持久设置可选调用首语言模型说明。
     * @param object exact observed deletion object / 精确观测删除对象
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    String explain(FileInfo object, TaskContext task) throws ApiException;

    /** Reads the original platform-issued grant without consuming it. / 读取原平台签发许可而不消费。
     * @param grant genuine originally reviewed grant / 真实原审核许可
     * @param task authentic original executing task / 真实原执行任务
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    ActionIntent reviewed(ApprovalGrant grant, TaskContext task) throws ApiException;

    /** Consumes the exact original grant before dispatch. / 发出副作用前消费精确原许可。
     * @param grant genuine originally reviewed grant / 真实原审核许可
     * @param intent exact proposed external effect / 精确拟执行外部副作用
     * @param task authentic original executing task / 真实原执行任务
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void consume(ApprovalGrant grant, ActionIntent intent, TaskContext task) throws ApiException;

    /** Consumes a deletion permit only with its required independent AI and workflow evidence. / 只在独立 AI 及流程证据满足时消费前置删除许可。
     * @param grant genuine originally reviewed grant / 真实原审核许可
     * @param intent exact proposed external effect / 精确拟执行外部副作用
     * @param task authentic original executing task / 真实原执行任务
     * @throws ApiException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    void consumePermit(ApprovalGrant grant, ActionIntent intent, TaskContext task) throws ApiException;
}
