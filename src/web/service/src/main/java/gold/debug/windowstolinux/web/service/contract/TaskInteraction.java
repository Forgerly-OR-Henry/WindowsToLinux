package gold.debug.windowstolinux.web.service.contract;

import tools.jackson.databind.JsonNode;

/**
 * Defines business progress and bounded decisions independently of HTTP and task execution.
 * <p>定义独立于 HTTP 及任务执行的业务进度与有界决策。
 */
public interface TaskInteraction {
    /**
     * Publishes bounded progress information through the task interaction contract.
     * <p>通过任务交互契约发布有界进度信息。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param safeDetails safe details / 安全详情
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    void progress(String code, JsonNode safeDetails) throws Exception;

    /**
     * Presents a safe typed decision prompt and waits for the task's answer or cancellation.
     * <p>展示安全的类型化决策提示，并等待任务回答或取消。
     *
     * @param kind selected member of the supported kind set / 受支持种类集合中的所选项
     * @param safePrompt safe prompt / 安全提示
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    JsonNode decide(String kind, JsonNode safePrompt) throws Exception;

    /**
     * Checks cancelled.
     * <p>检查已取消。
     *
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    void checkCancelled() throws InterruptedException;

    /**
     * Records the business completion state for the task executor.
     * <p>为任务执行器记录业务完成状态。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     */
    void completion(OperationCompletionState state);
}
