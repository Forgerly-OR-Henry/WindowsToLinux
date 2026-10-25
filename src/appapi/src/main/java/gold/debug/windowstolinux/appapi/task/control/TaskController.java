package gold.debug.windowstolinux.appapi.task.control;

import java.util.UUID;
import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Optional exact-release controls. Observations must be bounded and must not perform business work.
 * / 可选的精确版本控制；观察须有界且不执行业务。持久化和恢复规则仍由 Mod 持有。 */
public interface TaskController {
    TaskControlSnapshot snapshot(UUID originalTask) throws ApiException;

    /** Revalidate the original boundary before acting; intervention grants no approval.
     * / 执行前复核原边界；介入不授予批准。 */
    void request(UUID originalTask, TaskCommand command) throws ApiException;

    /** Reads only this Mod's bounded original diagnostics. / 仅读取本 Mod 的有界原任务诊断。 */
    default gold.debug.windowstolinux.appapi.task.log.LogReadResult readLogs(UUID originalTask) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "TASK_LOGS_UNAVAILABLE",
            "Original Mod task logs are unavailable", java.util.Map.of(), java.util.Optional.empty()));
    }
}
