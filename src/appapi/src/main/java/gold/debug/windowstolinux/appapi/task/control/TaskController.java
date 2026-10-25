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
}
