package gold.debug.windowstolinux.appapi.task;

import java.util.Optional;
import java.util.UUID;

/**
 * Diagnostic event that must not contain credential material. / 不得包含凭据材料的诊断事件。
 * @param level diagnostic severity / 诊断严重程度
 * @param eventCode stable diagnostic event code / 稳定的诊断事件码
 * @param message human-readable non-secret diagnostic message / 可读且不含秘密的诊断消息
 * @param details optional non-secret diagnostic details / 可选的不含秘密的诊断详情
 * @param taskId optional identity of the associated task / 可选的关联任务身份
 * @param operationId stable operation identity used for recovery / 恢复使用的稳定操作身份
 * @param outcome observed business result independent of resource release / 独立于资源释放的已观察业务结果
 */
public record LogEvent(LogLevel level, String eventCode, String message, Optional<String> details,
                       Optional<UUID> taskId, Optional<UUID> operationId, Optional<Outcome> outcome) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param level diagnostic severity / 诊断严重程度
     * @param eventCode stable diagnostic event code / 稳定的诊断事件码
     * @param message human-readable non-secret diagnostic message / 可读且不含秘密的诊断消息
     * @param details optional non-secret diagnostic details / 可选的不含秘密的诊断详情
     * @param taskId optional identity of the associated task / 可选的关联任务身份
     * @param operationId stable operation identity used for recovery / 恢复使用的稳定操作身份
     * @param outcome observed business result independent of resource release / 独立于资源释放的已观察业务结果
     */
    public LogEvent {
        java.util.Objects.requireNonNull(level, "level");
        java.util.Objects.requireNonNull(eventCode, "eventCode");
        java.util.Objects.requireNonNull(message, "message");
        java.util.Objects.requireNonNull(details, "details");
        java.util.Objects.requireNonNull(taskId, "taskId");
        java.util.Objects.requireNonNull(operationId, "operationId");
        java.util.Objects.requireNonNull(outcome, "outcome");
    }
}
