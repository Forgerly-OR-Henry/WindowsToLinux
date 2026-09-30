package gold.debug.windowstolinux.app.windows.update;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.Problem;

/**
 * Result of the main-process-only update preparation stage. / 仅由主进程执行的更新准备阶段结果。
 *
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param requestedVersion requested version / 已请求版本
 * @param events ordered progress or transaction events / 有序进度或事务事件
 * @param handoff handoff / 交接
 * @param problem structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 */
public record DesktopUpdatePreparationResult(UUID operationIdentity, DesktopUpdatePreparationStatus status,
        DesktopReleaseVersion requestedVersion, List<DesktopUpdateEvent> events, Optional<DesktopUpdateHandoff> handoff,
        Optional<Problem> problem) {
    /**
     * Validates mutually exclusive handoff and problem outcomes. / 校验互斥的交接与失败结果。
     *
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param requestedVersion requested version / 已请求版本
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param handoff handoff / 交接
     * @param problem structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdatePreparationResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        requestedVersion = Objects.requireNonNull(requestedVersion, "requestedVersion");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        handoff = Objects.requireNonNull(handoff, "handoff");
        problem = Objects.requireNonNull(problem, "problem");
        if (events.isEmpty())
            throw new IllegalArgumentException("update preparation requires events");
        if (status == DesktopUpdatePreparationStatus.READY_FOR_HANDOFF) {
            if (handoff.isEmpty() || problem.isPresent()
                    || !handoff.orElseThrow().operationIdentity().equals(operationIdentity)) {
                throw new IllegalArgumentException(
                        "ready update preparation requires its exact handoff and no problem");
            }
        } else if (handoff.isPresent() || problem.isEmpty()) {
            throw new IllegalArgumentException("rejected update preparation requires one structured problem");
        }
        if (problem.isPresent() && !problem.orElseThrow().diagnosticId().equals(Optional.of(operationIdentity))) {
            throw new IllegalArgumentException("update preparation problem must use its operation identity");
        }
    }
}
