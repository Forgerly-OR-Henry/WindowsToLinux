package gold.debug.windowstolinux.shared.linux.runtime;

import java.util.Objects;

/**
 * The result of a full managed-deployment health strategy, not merely a process check.
 *
 *  <p>完整受管部署健康检查策略的结果，而不只是进程检查。
 *
 * @param healthy healthy / 健康
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record HealthCheckResult(boolean healthy, String evidence) {
    /**
     * Validates and binds the inputs required by health check result.
     * <p>校验并绑定健康检查结果所需输入。
     *
     * @param healthy healthy / 健康
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public HealthCheckResult {
        evidence = Objects.requireNonNull(evidence, "evidence");
    }
}
