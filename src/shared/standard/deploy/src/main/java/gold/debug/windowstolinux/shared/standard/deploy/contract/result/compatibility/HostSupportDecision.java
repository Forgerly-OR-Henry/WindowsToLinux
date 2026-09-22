package gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility;

import java.util.List;
import java.util.Objects;

/**
 * A conservative host-support status with ordered review evidence. / 带有有序审阅证据的保守主机支持状态。
 *
 * @param support exact support level and validation scope / 精确支持等级与验证范围
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record HostSupportDecision(HostSupportStatus support, List<String> evidence) {
    /**
     * Creates an immutable support decision. / 创建不可变支持决定。
     *
     * @param support exact support level and validation scope / 精确支持等级与验证范围
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public HostSupportDecision {
        support = Objects.requireNonNull(support, "support");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    }
}
