package gold.debug.windowstolinux.app.windows.update;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.Problem;

/**
 * Terminal update result with paired program/database recovery evidence. / 带程序及数据库成对恢复证据的更新终态。
 *
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param requestedVersion requested version / 已请求版本
 * @param events ordered progress or transaction events / 有序进度或事务事件
 * @param rollbackBackupToken rollback backup token / 回滚备份令牌
 * @param problem structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 */
public record DesktopUpdateResult(UUID operationIdentity, DesktopUpdateStatus status,
                                  DesktopReleaseVersion requestedVersion, List<DesktopUpdateEvent> events,
                                  Optional<String> rollbackBackupToken,
                                  Optional<Problem> problem) {
    /**
     * Validates status-specific evidence. / 校验终态对应证据。
     *
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param requestedVersion requested version / 已请求版本
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param rollbackBackupToken rollback backup token / 回滚备份令牌
     * @param problem structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdateResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        requestedVersion = Objects.requireNonNull(requestedVersion, "requestedVersion");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        rollbackBackupToken = Objects.requireNonNull(rollbackBackupToken, "rollbackBackupToken");
        problem = Objects.requireNonNull(problem, "problem");
        if (events.isEmpty())
            throw new IllegalArgumentException("update result requires events");
        if (status == DesktopUpdateStatus.SUCCEEDED) {
            if (rollbackBackupToken.isEmpty() || problem.isPresent()) {
                throw new IllegalArgumentException(
                    "successful update requires retained rollback backup and no problem");
            }
        } else if (problem.isEmpty()) {
            throw new IllegalArgumentException("failed update requires a structured problem");
        }
        if (problem.isPresent() && !problem.orElseThrow().diagnosticId().equals(Optional.of(operationIdentity))) {
            throw new IllegalArgumentException("update problem must use the result operation identity");
        }
    }
}
