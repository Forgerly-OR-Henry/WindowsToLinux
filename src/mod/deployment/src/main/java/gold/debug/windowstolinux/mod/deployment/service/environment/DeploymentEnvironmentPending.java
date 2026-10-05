package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Stops further sends while retaining exact original operation evidence. / 停止后续发送并保留精确原操作依据。 */
final class DeploymentEnvironmentPending extends Exception {
    /** Stable safe diagnostic. / 稳定安全诊断。 */
    private final String event;

    /** Binds a safe event without remote output or credentials. / 绑定不含远端输出或凭据的安全事件。
     * @param event stable event / 稳定事件 */
    DeploymentEnvironmentPending(String event) {
        this.event = event;
    }

    /** Returns retained uncertainty instead of claiming rollback or success. / 返回保留未知，不声称回滚或成功。
     * @return original uncertain outcome / 原未知结果 */
    OperationResult<Void> result() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original environment preparation needs exact reconciliation; remote changes were not rolled back",
            Map.of(), Optional.empty())));
    }
}
