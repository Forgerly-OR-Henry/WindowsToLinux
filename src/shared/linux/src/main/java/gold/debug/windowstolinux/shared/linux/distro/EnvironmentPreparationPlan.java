package gold.debug.windowstolinux.shared.linux.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

/** Caller-selected project toolchain installation strategy. / 调用方选择的项目工具链安装策略。 */
@FunctionalInterface
public interface EnvironmentPreparationPlan {
    /** Renders standard preparation without owning SSH or helper installation. / 渲染标准准备，不持有 SSH 或 helper 安装实现。
     * @param facts observed host facts / 已观察主机事实
     * @param username reviewed account / 已审核账户
     * @return complete standard preparation script / 完整标准准备脚本
     * @throws LinuxOperationException when the declared platform is unsupported / 声明平台不受支持时
     */
    String render(LinuxCapabilityFacts facts, String username) throws LinuxOperationException;
}
