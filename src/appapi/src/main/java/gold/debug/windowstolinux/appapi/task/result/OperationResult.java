package gold.debug.windowstolinux.appapi.task.result;

import java.util.Optional;

/**
 * Known business outcome with explicit evidence for failure or uncertainty. / 失败或不确定时具有明确证据的业务结果。
 * @param <T> public payload or service contract type / 公开载荷或服务契约类型
 * @param outcome observed business result independent of resource release / 独立于资源释放的已观察业务结果
 * @param value optional observed result payload / 可选的已观察结果载荷
 * @param problem structured failure or uncertainty evidence / 结构化的失败或不确定性证据
 */
public record OperationResult<T>(TaskResult outcome, Optional<T> value, Optional<Problem> problem) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param outcome observed business result independent of resource release / 独立于资源释放的已观察业务结果
     * @param value optional observed result payload / 可选的已观察结果载荷
     * @param problem structured failure or uncertainty evidence / 结构化的失败或不确定性证据
     */
    public OperationResult {
        java.util.Objects.requireNonNull(outcome, "outcome");
        java.util.Objects.requireNonNull(value, "value");
        java.util.Objects.requireNonNull(problem, "problem");
        if (outcome == TaskResult.SUCCEEDED && problem.isPresent())
            throw new IllegalArgumentException("Successful results cannot contain a problem");
        if ((outcome == TaskResult.FAILED || outcome == TaskResult.UNKNOWN) && problem.isEmpty())
            throw new IllegalArgumentException("Failed and unknown results require a problem");
    }
}
