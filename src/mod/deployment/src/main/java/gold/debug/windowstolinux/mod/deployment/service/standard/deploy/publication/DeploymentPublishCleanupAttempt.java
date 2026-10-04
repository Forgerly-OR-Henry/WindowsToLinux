package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Persists one original cleanup attempt while distinguishing host refusal from uncertain dispatch. / 持久化一项原清理尝试，同时区分宿主拒绝与发送未确认。 */
final class DeploymentPublishCleanupAttempt {
    /** Prevents utility construction. / 防止构造工具类。 */
    private DeploymentPublishCleanupAttempt() {
    }

    /** A fixed original command under the original review. / 原审阅下的固定原命令。 */
    @FunctionalInterface
    interface Command {
        /** Sends the command at most once. / 命令至多发送一次。
         * @return actual observation / 真实观察
         * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
        OperationResult<?> run() throws ApiException;
    }

    /** Advances before dispatch and restores the prior phase only on proof that no command was sent. / 发送前推进，仅在证实未发送时恢复先前阶段。
     * @param journal exact original component / 精确原组件
     * @param pending durable possibly-sent stage / 持久可能已发送阶段
     * @param command fixed original command / 固定原命令
     * @return whether a complete success was observed / 是否观察到完整成功
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    static boolean run(DeploymentPublishCleanupActionLog journal, String pending, Command command)
        throws ApiException {
        String previous = journal.facts().path("phase").asText();
        if (!journal.mark(pending))
            return false;
        try {
            var observed = command.run();
            if ((observed.outcome() == TaskResult.FAILED || observed.outcome() == TaskResult.CANCELLED)
                && observed.value().isEmpty())
                journal.mark(previous);
            return observed.outcome() == TaskResult.SUCCEEDED;
        } catch (ApiException rejected) {
            if (rejected.problem().code() == ProblemCode.ACCESS_DENIED
                || rejected.problem().code() == ProblemCode.INVALID_INPUT)
                journal.mark(previous);
            throw rejected;
        }
    }
}
