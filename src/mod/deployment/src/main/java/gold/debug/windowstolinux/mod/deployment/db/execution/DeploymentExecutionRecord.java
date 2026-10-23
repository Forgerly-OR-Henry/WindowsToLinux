package gold.debug.windowstolinux.mod.deployment.db.execution;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Read-only durable execution evidence, including uninterpreted future JSON versions. / 只读持久执行证据，包括不解释的未来JSON版本。
 * @param identity original immutable identity / 原不可变身份
 * @param phase last persisted operation stage / 最近持久操作阶段
 * @param outcome known or explicitly unknown business result / 已知或明确未知的业务结果
 * @param requestJson original immutable request document / 原不可变请求文档
 * @param recoveryJson latest recovery evidence / 最新恢复证据
 * @param resultJson optional formal result document / 可选正式结果文档
 * @param businessCommitted whether business publication committed in the same transaction / 业务发布是否已在同一事务提交
 * @param cleanupComplete whether known cleanup was explicitly confirmed / 已知收尾是否明确确认
 * @param createdAt original creation time / 原创建时间
 * @param updatedAt latest persisted evidence time / 最新持久证据时间 */
public record DeploymentExecutionRecord(DeploymentExecutionIdentity identity, String phase,
                                        Optional<TaskResult> outcome,
                                        String requestJson, String recoveryJson, Optional<String> resultJson,
                                        boolean businessCommitted,
                                        boolean cleanupComplete, Instant createdAt, Instant updatedAt) {
    /** Retains original immutable evidence rather than projecting an assumed final result. / 保留原不可变证据，不投射假定终态。
     * @param identity original immutable identity / 原不可变身份
     * @param phase last persisted operation stage / 最近持久操作阶段
     * @param outcome known or explicitly unknown business result / 已知或明确未知的业务结果
     * @param requestJson original immutable request document / 原不可变请求文档
     * @param recoveryJson latest recovery evidence / 最新恢复证据
     * @param resultJson optional formal result document / 可选正式结果文档
     * @param businessCommitted whether publication committed atomically / 发布是否原子提交
     * @param cleanupComplete whether cleanup was confirmed / 收尾是否已确认
     * @param createdAt original creation time / 原创建时间
     * @param updatedAt latest evidence time / 最新证据时间 */
    public DeploymentExecutionRecord {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(requestJson);
        Objects.requireNonNull(recoveryJson);
        Objects.requireNonNull(resultJson);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        if (updatedAt.isBefore(createdAt) || businessCommitted && resultJson.isEmpty()
            || cleanupComplete && (outcome.isEmpty() || outcome.get() == TaskResult.UNKNOWN))
            throw new IllegalArgumentException("Inconsistent execution evidence");
    }
}
