package gold.debug.windowstolinux.appapi.mod;

/**
 * Author lifecycle entry with partial-start cleanup obligations. / 作者生命周期入口及部分启动失败的清理责任。
 */
public interface ModEntrypoint {

    /**
     * Starts the owned lifecycle without implicitly beginning user business tasks. / 启动所属生命周期，不隐式执行用户业务任务。
     * @param context caller context for this capability / 此能力使用的调用方上下文
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void start(ModContext context) throws Exception;

    /**
     * Releases partial or complete startup state without blocking the EDT. / 释放部分或完整启动状态，不阻塞事件分发线程。
     * @param context caller context for this capability / 此能力使用的调用方上下文
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void stop(StopContext context) throws Exception;
}
