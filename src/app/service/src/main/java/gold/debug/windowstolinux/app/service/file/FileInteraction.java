package gold.debug.windowstolinux.app.service.file;

import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Host interactions; implementations must preserve caller-bound approval and task waiting. / 保持调用方审批和任务等待的平台交互。 */
public interface FileInteraction {
    /** Chooses the directory collision layer. / 选择目录冲突层策略。 */
    FileChoice.Directory chooseDirectory(FileInfo source, FileInfo target, TaskContext task) throws ApiException;
    /** Chooses the file or link collision layer. / 选择文件或链接冲突层策略。 */
    FileChoice.Conflict chooseFile(FileInfo source, FileInfo target, TaskContext task) throws ApiException;
    /** Obtains exact approval; delete always requires ordinary user confirmation. / 取得精确审批，常规删除始终人工确认。 */
    OperationResult<ApprovalGrant> approve(ActionIntent intent, FileInfo object, boolean delete, String explanation, TaskContext task) throws ApiException;
    /** Waits without open I/O or transactions; true means explicitly continue, false terminate. / 无活动 I/O 或事务地等待，真为继续、假为终止。 */
    boolean awaitContinue(String reason, TaskContext task) throws ApiException;
    /** Optional first-language-model explanation according to the persistent preference. / 根据持久设置可选调用首语言模型说明。 */
    String explain(FileInfo object, TaskContext task) throws ApiException;
    /** Reads the original platform-issued grant without consuming it. / 读取原平台签发许可而不消费。 */
    ActionIntent reviewed(ApprovalGrant grant, TaskContext task) throws ApiException;
    /** Consumes the exact original grant before dispatch. / 发出副作用前消费精确原许可。 */
    void consume(ApprovalGrant grant, ActionIntent intent, TaskContext task) throws ApiException;
    /** Consumes a deletion permit only with its required independent AI and workflow evidence. / 只在独立 AI 及流程证据满足时消费前置删除许可。 */
    void consumePermit(ApprovalGrant grant, ActionIntent intent, TaskContext task) throws ApiException;
}
