package gold.debug.windowstolinux.shared.deploy.support.distro;

import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

import java.util.List;

/** One distribution's independently reviewable compatibility boundary. / 单个发行版可独立审阅的兼容性边界。 */
interface DistributionSupportPolicy {
    /** Evaluates collected facts without target mutation. / 在不修改目标的情况下评估采集事实。 */
    HostSupportStatus evaluate(LinuxCapabilityFacts capabilities, List<String> evidence);
}
