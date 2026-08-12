package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;

import java.util.List;

/** One distribution's independently reviewable compatibility boundary. / 单个发行版可独立审阅的兼容性边界。 */
interface DistributionCompatibilityPolicy {
    /** Evaluates collected facts without target mutation. / 在不修改目标的情况下评估采集事实。 */
    HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence);
}
