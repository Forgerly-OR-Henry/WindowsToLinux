package gold.debug.windowstolinux.shared.linux.build;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet;

/**
 * Prepared exact identities and independently collected system capability facts. / 已准备的精确身份及独立采集的系统能力事实。
 *
 * @param toolchains toolchains / 工具链集合
 * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
 */
public record ToolchainPreparationResult(ResolvedToolchainSet toolchains, LinuxCapabilityFacts capabilities) {
    /**
     * Validates and binds the inputs required by toolchain preparation result.
     * <p>校验并绑定工具链准备结果所需输入。
     *
     * @param toolchains toolchains / 工具链集合
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ToolchainPreparationResult {
        Objects.requireNonNull(toolchains);
        Objects.requireNonNull(capabilities);
    }
}
