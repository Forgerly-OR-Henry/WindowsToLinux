package gold.debug.windowstolinux.shared.linux.build;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet;
import java.util.Objects;

/** Prepared exact identities and independently collected system capability facts. / 已准备的精确身份及独立采集的系统能力事实。 */
public record ToolchainPreparationResult(ResolvedToolchainSet toolchains, LinuxCapabilityFacts capabilities) {
    public ToolchainPreparationResult { Objects.requireNonNull(toolchains); Objects.requireNonNull(capabilities); }
}
