package gold.debug.windowstolinux.app.linux.recovery;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;

/** Reports a browser-stage failure without copying page, command or terminal contents. / 报告浏览器阶段失败，不复制页面、命令或终端内容。 */
public final class BrowserRecoveryException extends RuntimeException {
    /** Stable browser stage, independent of deployment failure types. / 稳定浏览器阶段，独立于部署错误类型。 */
    public enum Reason {
        /** Browser operation could not complete. / 浏览器操作未能完成。 */
        OPERATION_FAILED,
        /** Bound terminal observation could not complete. / 已绑定终端观测未能完成。 */
        OBSERVATION_FAILED,
        /** Actual browser or worker cleanup is still unconfirmed. / 实际浏览器或工作线程清理仍未确认。 */
        CLEANUP_FAILED,
        /** The waiting caller was interrupted. / 等待调用方已中断。 */
        INTERRUPTED
    }

    /** Exact failed stage. / 精确失败阶段。 */
    private final Reason reason;

    /** Safe platform diagnostic, without raw provider exceptions. / 安全平台诊断，不含原始提供方异常。 */
    private final Problem problem;

    /** Retains the safe stage and original diagnostic identity. / 保留安全阶段及原始诊断身份。
     * @param reason actual failed stage / 实际失败阶段
     * @param cause internal original failure / 内部原始失败 */
    public BrowserRecoveryException(Reason reason, Throwable cause) {
        super("Isolated browser operation failed", cause);
        this.reason = java.util.Objects.requireNonNull(reason);
        String event = switch (reason) {
            case OPERATION_FAILED -> "linux.browser.operation-failed";
            case OBSERVATION_FAILED -> "linux.browser.observation-failed";
            case CLEANUP_FAILED -> "linux.browser.cleanup-failed";
            case INTERRUPTED -> "linux.browser.interrupted";
        };
        problem = new Problem(
                reason == Reason.CLEANUP_FAILED || reason == Reason.INTERRUPTED
                        ? ProblemCode.UNKNOWN
                        : ProblemCode.IO_FAILURE,
                event, "Isolated browser operation failed", Map.of(), Optional.of(UUID.randomUUID()));
    }

    /** Returns the actual failed browser stage. / 返回实际失败浏览器阶段。
     * @return stable stage / 稳定阶段 */
    public Reason reason() {
        return reason;
    }

    /** Returns safe structured evidence without inferring a remote result. / 返回安全结构化证据，不推断远端结果。
     * @return immutable diagnostic / 不可变诊断 */
    public Problem problem() {
        return problem;
    }
}
