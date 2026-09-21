package gold.debug.windowstolinux.shared.backup.execution.migration;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Terminal preparation result that never claims an external switch or source deletion. / 绝不声称外部切流或删除源端的迁移准备终态。
 *
 * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param events ordered progress or transaction events / 有序进度或事务事件
 * @param sourceWritesStopped source writes stopped / 源码写入集合已停止
 * @param targetCandidateReady target candidate ready / 目标候选就绪
 * @param externalTrafficSwitched external traffic switched / 外部流量Switched
 * @param sourceRetained source retained / 源码已保留
 * @param targetCandidateId target candidate id / 目标候选标识
 * @param sourceRecoveryToken source recovery token / 源码恢复令牌
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 */
public record OfflineMigrationResult(
        OperationIdentity operationIdentity,
        OfflineMigrationStatus status,
        List<OfflineMigrationEvent> events,
        boolean sourceWritesStopped,
        boolean targetCandidateReady,
        boolean externalTrafficSwitched,
        boolean sourceRetained,
        Optional<String> targetCandidateId,
        Optional<String> sourceRecoveryToken,
        Optional<FailureDescriptor> failure
) {
    /**
     * Enforces safe status-specific evidence. / 强制安全的终态对应证据。
     *
     * @param operationIdentity correlation identity of the enclosing user operation / 外层用户操作的关联标识
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param sourceWritesStopped source writes stopped / 源码写入集合已停止
     * @param targetCandidateReady target candidate ready / 目标候选就绪
     * @param externalTrafficSwitched external traffic switched / 外部流量Switched
     * @param sourceRetained source retained / 源码已保留
     * @param targetCandidateId target candidate id / 目标候选标识
     * @param sourceRecoveryToken source recovery token / 源码恢复令牌
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public OfflineMigrationResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        targetCandidateId = Objects.requireNonNull(targetCandidateId, "targetCandidateId");
        sourceRecoveryToken = Objects.requireNonNull(sourceRecoveryToken, "sourceRecoveryToken");
        failure = Objects.requireNonNull(failure, "failure");
        if (events.isEmpty()) throw new IllegalArgumentException("migration result requires events");
        if (externalTrafficSwitched) {
            throw new IllegalArgumentException("offline migration preparation cannot claim an external traffic switch");
        }
        if (!sourceRetained) {
            throw new IllegalArgumentException("offline migration preparation must retain the source application");
        }
        if (status == OfflineMigrationStatus.READY_FOR_MANUAL_TRAFFIC_SWITCH) {
            if (!sourceWritesStopped || !targetCandidateReady || targetCandidateId.isEmpty()
                    || sourceRecoveryToken.isEmpty() || failure.isPresent()) {
                throw new IllegalArgumentException("ready migration requires stopped source and verified target evidence");
            }
        } else if (failure.isEmpty() || targetCandidateReady || targetCandidateId.isPresent()) {
            throw new IllegalArgumentException("failed migration requires a failure and no ready target candidate");
        }
        if (failure.isPresent()
                && !failure.orElseThrow().operationIdentity().equals(operationIdentity)) {
            throw new IllegalArgumentException("migration failure must use the result operation identity");
        }
    }
}
