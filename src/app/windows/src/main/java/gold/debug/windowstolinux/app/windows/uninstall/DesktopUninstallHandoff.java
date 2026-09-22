package gold.debug.windowstolinux.app.windows.uninstall;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Explicit uninstall decision and quiesce evidence handed to an external worker. / 交给外部执行器的显式卸载决定与停收证据。
 *
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
 * @param preparationEvents preparation events / 准备事件集合
 */
public record DesktopUninstallHandoff(OperationIdentity operationIdentity, DesktopUninstallRequest request,
        List<DesktopUninstallEvent> preparationEvents) {
    /**
     * Requires a decision and successful task quiesce before process exit. / 要求进程退出前已有决定且任务停收成功。
     *
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param preparationEvents preparation events / 准备事件集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUninstallHandoff {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        request = Objects.requireNonNull(request, "request");
        preparationEvents = List.copyOf(Objects.requireNonNull(preparationEvents, "preparationEvents"));
        if (request.decision().isEmpty() || preparationEvents.size() != 2
                || preparationEvents.get(0).state() != DesktopUninstallState.DECISION_VALIDATED
                || preparationEvents.get(1).state() != DesktopUninstallState.TASKS_STOPPED
                || preparationEvents.stream().anyMatch(event -> !event.succeeded())) {
            throw new IllegalArgumentException(
                    "uninstall handoff requires an explicit decision and stopped owned tasks");
        }
    }
}
