package gold.debug.windowstolinux.shared.linux.runtime;

import java.util.Objects;

/**
 * The result of a full managed-deployment health strategy, not merely a process check.
 *
 * <p>完整受管部署健康检查策略的结果，而不只是进程检查。
 *
 * @param healthy the {@code healthy} value / {@code healthy} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record HealthCheckResult(boolean healthy, String evidence) {
    /**
     * Creates a {@code HealthCheckResult} instance.
     *
     * <p>创建 {@code HealthCheckResult} 实例。
     *
     * @param healthy the {@code healthy} value / {@code healthy} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public HealthCheckResult {
        evidence = Objects.requireNonNull(evidence, "evidence");
    }
}
