package gold.debug.windowstolinux.shared.standard.deploy.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

/**
 * Renders one fixed supported-distribution preparation script from collected facts.
 *
 *  <p>根据已采集事实渲染一个固定受支持发行版准备脚本。
 */
public interface DistributionSetupRenderer {
    /**
     * Returns the only distribution family handled by this preparation. / 返回此准备实现唯一处理的发行版系列。
     *
     * @return the only distribution family handled by this preparation / 此准备实现唯一处理的发行版系列
     */
    LinuxDistroType distro();

    /**
     * Renders a restricted preparation script for the collected host. / 为已采集主机渲染受限准备脚本。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @return render text / 渲染文本
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    String render(LinuxCapabilityFacts capabilities, String username) throws LinuxOperationException;

}
