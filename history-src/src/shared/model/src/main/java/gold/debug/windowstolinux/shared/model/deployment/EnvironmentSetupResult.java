package gold.debug.windowstolinux.shared.model.deployment;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;

/**
 * A successful fixed-toolset preparation, followed by fresh target capability evidence.
 *
 *  <p>固定工具集准备成功及其后重新采集的目标能力证据。
 *
 * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record EnvironmentSetupResult(ServerCapabilityFacts capabilities, String evidence) {
    /**
     * Validates and binds the inputs required by environment setup result.
     * <p>校验并绑定环境Setup结果所需输入。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public EnvironmentSetupResult {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        evidence = Objects.requireNonNull(evidence, "evidence").trim();
        if (evidence.isBlank()) {
            throw new IllegalArgumentException("evidence must not be blank");
        }
    }
}
