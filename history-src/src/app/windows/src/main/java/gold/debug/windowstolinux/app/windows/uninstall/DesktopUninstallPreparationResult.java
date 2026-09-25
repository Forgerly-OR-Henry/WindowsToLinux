package gold.debug.windowstolinux.app.windows.uninstall;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Result of validating the decision and stopping main-process-owned tasks. / 校验决定并停止主进程任务的结果。
 *
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param events ordered progress or transaction events / 有序进度或事务事件
 * @param handoff handoff / 交接
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 */
public record DesktopUninstallPreparationResult(OperationIdentity operationIdentity,
        DesktopUninstallPreparationStatus status, List<DesktopUninstallEvent> events,
        Optional<DesktopUninstallHandoff> handoff, Optional<FailureDescriptor> failure) {
    /**
     * Validates one exact preparation outcome. / 校验一个精确准备结果。
     *
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param handoff handoff / 交接
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUninstallPreparationResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        handoff = Objects.requireNonNull(handoff, "handoff");
        failure = Objects.requireNonNull(failure, "failure");
        if (events.isEmpty())
            throw new IllegalArgumentException("uninstall preparation requires events");
        if (status == DesktopUninstallPreparationStatus.READY_FOR_HANDOFF) {
            if (handoff.isEmpty() || failure.isPresent()
                    || !handoff.orElseThrow().operationIdentity().equals(operationIdentity)) {
                throw new IllegalArgumentException("ready uninstall preparation requires its exact handoff");
            }
        } else if (handoff.isPresent() || failure.isEmpty()) {
            throw new IllegalArgumentException("rejected uninstall preparation requires one structured failure");
        }
        if (failure.isPresent() && !failure.orElseThrow().operationIdentity().equals(operationIdentity)) {
            throw new IllegalArgumentException("uninstall preparation failure must use its operation identity");
        }
    }
}
