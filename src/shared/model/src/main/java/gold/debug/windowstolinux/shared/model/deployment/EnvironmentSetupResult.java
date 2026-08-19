package gold.debug.windowstolinux.shared.model.deployment;

import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;

import java.util.Objects;

/**
 * A successful fixed-toolset preparation, followed by fresh target capability evidence.
 *
 * <p>固定工具集准备成功及其后重新采集的目标能力证据。
 *
 * @param capabilities the {@code capabilities} value / {@code capabilities} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record EnvironmentSetupResult(
        ServerCapabilityFacts capabilities,
        String evidence
) {
    /**
     * Creates a {@code EnvironmentSetupResult} instance.
     *
     * <p>创建 {@code EnvironmentSetupResult} 实例。
     *
     * @param capabilities the {@code capabilities} value / {@code capabilities} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public EnvironmentSetupResult {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        evidence = Objects.requireNonNull(evidence, "evidence").trim();
        if (evidence.isBlank()) {
            throw new IllegalArgumentException("evidence must not be blank");
        }
    }
}
