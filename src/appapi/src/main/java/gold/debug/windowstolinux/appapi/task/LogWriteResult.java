package gold.debug.windowstolinux.appapi.task;

import java.util.Optional;
import java.util.UUID;

/**
 * Observed diagnostic persistence and truncation result. / 已观察的诊断持久化与截断结果。
 * Disabled DEBUG events return NOT_SAVED without a problem; this is intentional filtering.
 * / 未启用 DEBUG 时返回 NOT_SAVED 且没有 problem，表示主动过滤。
 * @param logId stable identity of the written diagnostic / 写入诊断的稳定身份
 * @param storage observed diagnostic storage destination / 已观察的诊断存储位置
 * @param truncated whether output exceeded its retained limit / 输出是否超过保留上限
 * @param problem structured failure or uncertainty evidence / 结构化的失败或不确定性证据
 */
public record LogWriteResult(UUID logId, LogStorage storage, boolean truncated, Optional<Problem> problem) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param logId stable identity of the written diagnostic / 写入诊断的稳定身份
     * @param storage observed diagnostic storage destination / 已观察的诊断存储位置
     * @param truncated whether output exceeded its retained limit / 输出是否超过保留上限
     * @param problem structured failure or uncertainty evidence / 结构化的失败或不确定性证据
     */
    public LogWriteResult {
        java.util.Objects.requireNonNull(logId, "logId");
        java.util.Objects.requireNonNull(storage, "storage");
        java.util.Objects.requireNonNull(problem, "problem");
    }
}
