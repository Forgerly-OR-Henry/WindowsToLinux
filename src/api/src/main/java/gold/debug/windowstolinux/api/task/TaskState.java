package gold.debug.windowstolinux.api.task;

/**
 * Task progress distinct from cancellation and business outcome. / 区分取消请求与业务结果的任务进度状态。
 */
public enum TaskState {
    /**
     * Admitted and waiting for execution. / 已接纳并等待执行。
     */
    QUEUED,
    /**
     * Author work is executing. / 作者任务逻辑正在执行。
     */
    RUNNING,
    /**
     * Cancellation was requested but cleanup is not yet confirmed. / 已请求取消，尚未确认清理完成。
     */
    CANCEL_REQUESTED,
    /**
     * Business work returned while owned resources are still draining. / 业务逻辑已返回，所属资源仍在释放。
     */
    FINISHING,
    /**
     * The effect or completion cannot yet be confirmed. / 副作用或完成状态尚无法确认。
     */
    UNKNOWN,
    /**
     * A definite result and actual resource release are both confirmed. / 明确结果与实际资源释放均已确认。
     */
    ENDED
}
