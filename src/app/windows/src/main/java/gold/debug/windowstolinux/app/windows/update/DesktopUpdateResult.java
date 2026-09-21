package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal update result with paired program/database recovery evidence. / 带程序及数据库成对恢复证据的更新终态。
 *
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param requestedVersion requested version / 已请求版本
 * @param events ordered progress or transaction events / 有序进度或事务事件
 * @param rollbackBackupToken rollback backup token / 回滚备份令牌
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 */
public record DesktopUpdateResult(
        OperationIdentity operationIdentity,
        DesktopUpdateStatus status,
        DesktopReleaseVersion requestedVersion,
        List<DesktopUpdateEvent> events,
        Optional<String> rollbackBackupToken,
        Optional<FailureDescriptor> failure
) {
    /**
     * Validates status-specific evidence. / 校验终态对应证据。
     *
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param requestedVersion requested version / 已请求版本
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param rollbackBackupToken rollback backup token / 回滚备份令牌
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopUpdateResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        requestedVersion = Objects.requireNonNull(requestedVersion, "requestedVersion");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        rollbackBackupToken = Objects.requireNonNull(rollbackBackupToken, "rollbackBackupToken");
        failure = Objects.requireNonNull(failure, "failure");
        if (events.isEmpty()) throw new IllegalArgumentException("update result requires events");
        if (status == DesktopUpdateStatus.SUCCEEDED) {
            if (rollbackBackupToken.isEmpty() || failure.isPresent()) {
                throw new IllegalArgumentException("successful update requires retained rollback backup and no failure");
            }
        } else if (failure.isEmpty()) {
            throw new IllegalArgumentException("failed update requires a structured failure");
        }
        if (failure.isPresent() && !failure.orElseThrow().operationIdentity().equals(operationIdentity)) {
            throw new IllegalArgumentException("update failure must use the result operation identity");
        }
    }
}
