package gold.debug.windowstolinux.shared.linux.protocol;

import java.util.Objects;

/**
 * Sanitized result from a named, fixed Linux operation.
 *
 * <p>具名固定 Linux 操作产生的已净化结果。
 *
 * @param succeeded the {@code succeeded} value / {@code succeeded} 值
 * @param timedOut the {@code timedOut} value / {@code timedOut} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record RemoteStepResult(boolean succeeded, boolean timedOut, String evidence) {
    /**
     * Creates a {@code RemoteStepResult} instance.
     *
     * <p>创建 {@code RemoteStepResult} 实例。
     *
     * @param succeeded the {@code succeeded} value / {@code succeeded} 值
     * @param timedOut the {@code timedOut} value / {@code timedOut} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public RemoteStepResult {
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (succeeded && timedOut) {
            throw new IllegalArgumentException("a successful remote step cannot time out");
        }
    }
}
