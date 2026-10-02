package gold.debug.windowstolinux.appapi.task;

/**
 * Cooperative cancellation request without forced interruption. / 不强制中断执行的协作取消请求。
 */
public interface CancelToken {

    /**
     * Reports whether cooperative cancellation has been requested. / 报告是否已请求协作取消。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    boolean requested();
}
