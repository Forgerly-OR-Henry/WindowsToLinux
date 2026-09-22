package gold.debug.windowstolinux.shared.standard.deploy.support.distro;

import java.util.List;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportStatus;

/**
 * One distribution's independently reviewable compatibility boundary. / 单个发行版可独立审阅的兼容性边界。
 */
interface DistributionSupportPolicy {
    /**
     * Evaluates collected facts without target mutation. / 在不修改目标的情况下评估采集事实。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved host support status / 构造或解析得到的主机支持状态
     */
    HostSupportStatus evaluate(LinuxCapabilityFacts capabilities, List<String> evidence);
}
