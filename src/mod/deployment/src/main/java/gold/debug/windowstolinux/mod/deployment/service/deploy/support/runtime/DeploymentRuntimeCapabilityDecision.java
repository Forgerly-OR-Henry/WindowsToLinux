package gold.debug.windowstolinux.mod.deployment.service.deploy.support.runtime;

import java.util.Objects;
import java.util.Optional;

/**
 * Indicates whether one reviewed runtime matches collected host capabilities. / 表示一个已审阅运行时是否匹配采集到的主机能力。
 *
 * @param supported supported / 受支持
 * @param detail detail / 详情
 * @param missingHostPrerequisite whether the rejection is a host capability gap rather than invalid toolchain identity / 是否为宿主能力缺口而非无效工具链身份
 */
public record DeploymentRuntimeCapabilityDecision(boolean supported, Optional<String> detail,
                                                  boolean missingHostPrerequisite) {
    /** Creates a consistent supported or unsupported runtime decision. / 创建一致的支持或不支持运行时决定。
     * @param supported supported / 受支持
     * @param detail detail / 详情
     * @param missingHostPrerequisite whether evidence proves a repairable host prerequisite gap / 依据是否证明可修复主机前置缺口 */
    public DeploymentRuntimeCapabilityDecision {
        detail = Objects.requireNonNull(detail, "detail");
        if (supported == detail.isPresent() || supported && missingHostPrerequisite) {
            throw new IllegalArgumentException("supported runtime decisions must not contain a rejection detail");
        }
    }

    /**
     * Builds runtime capability decision from the supplied supported runtime inputs.
     * <p>根据所提供受支持运行时输入构建运行时能力决定。
     *
     * @return runtime capability decision from the supplied supported runtime inputs / 根据所提供受支持运行时输入构建运行时能力决定
     */
    static DeploymentRuntimeCapabilityDecision supportedRuntime() {
        return new DeploymentRuntimeCapabilityDecision(true, Optional.empty(), false);
    }

    /**
     * Builds runtime capability decision from the supplied unsupported runtime inputs.
     * <p>根据所提供不支持运行时输入构建运行时能力决定。
     *
     * @param detail detail / 详情
     * @return runtime capability decision from the supplied unsupported runtime inputs / 根据所提供不支持运行时输入构建运行时能力决定
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static DeploymentRuntimeCapabilityDecision unsupportedRuntime(String detail) {
        return new DeploymentRuntimeCapabilityDecision(false, Optional.of(Objects.requireNonNull(detail, "detail")), false);
    }

    /** Distinguishes a proven host prerequisite gap from unsupported source input. / 区分已证实主机前置缺口与不支持的来源输入。
     * @param detail bounded reason from verified host evidence / 来自已核实主机依据的有界理由
     * @return whether a verified host gap explains the rejection / 已核实主机缺口是否解释拒绝 */
    static DeploymentRuntimeCapabilityDecision missingHostPrerequisite(String detail) {
        return new DeploymentRuntimeCapabilityDecision(false, Optional.of(Objects.requireNonNull(detail, "detail")), true);
    }
}
