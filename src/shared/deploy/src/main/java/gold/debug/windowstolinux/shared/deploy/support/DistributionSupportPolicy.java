package gold.debug.windowstolinux.shared.deploy.support;

import gold.debug.windowstolinux.shared.deploy.support.HostSupport;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;

import java.util.List;

/** One distribution's independently reviewable compatibility boundary. / 单个发行版可独立审阅的兼容性边界。 */
interface DistributionSupportPolicy {
    /** Evaluates collected facts without target mutation. / 在不修改目标的情况下评估采集事实。 */
    HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence);
}
