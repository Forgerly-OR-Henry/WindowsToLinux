package gold.debug.windowstolinux.api.task;

/**
 * Task-owned process launch without exposing platform credentials. / 不暴露平台凭据的任务所属进程启动。
 */
public interface ProcessService {

    /**
     * Starts the owned lifecycle without implicitly beginning user business tasks. / 启动所属生命周期，不隐式执行用户业务任务。
     * @param spec registration or launch specification / 登记或启动规格
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    ManagedProcess start(ProcessSpec spec, TaskContext task) throws ApiException;
}
