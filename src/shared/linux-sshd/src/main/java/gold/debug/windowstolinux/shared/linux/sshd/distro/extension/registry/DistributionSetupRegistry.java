package gold.debug.windowstolinux.shared.linux.sshd.distro.extension.registry;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.ManagedEnvironmentExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.distro.extension.registry.DistributionSetupCatalog;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the complete validated assembly of distribution preparation implementations.
 *
 *  <p>持有完整且已验证的发行版准备实现装配。
 */
public final class DistributionSetupRegistry implements ManagedEnvironmentExecutor.PreparationResolver {
    /**
     * Preparations.
     * <p>准备集合。
     */
    private final Map<LinuxDistroType, DistributionSetupRenderer> preparations;

    /**
     * Creates the fixed supported-distribution assembly. / 创建固定的受支持发行版装配。
     *
     * @return the fixed supported-distribution assembly / 固定的受支持发行版装配
     */
    public static DistributionSetupRegistry defaults() {
        return new DistributionSetupRegistry(DistributionSetupCatalog.defaults());
    }

    /**
     * Validates unique preparation ownership for supported distributions. / 验证受支持发行版的唯一准备归属。
     *
     * @param preparations preparations / 准备集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DistributionSetupRegistry(List<DistributionSetupRenderer> preparations) {
        EnumMap<LinuxDistroType, DistributionSetupRenderer> indexed = new EnumMap<>(LinuxDistroType.class);
        for (DistributionSetupRenderer preparation : Objects.requireNonNull(preparations, "preparations")) {
            preparation = Objects.requireNonNull(preparation, "preparation");
            if (indexed.putIfAbsent(preparation.distro(), preparation) != null) {
                throw new IllegalArgumentException("each distribution requires exactly one preparation implementation");
            }
        }
        this.preparations = Map.copyOf(indexed);
    }

    /**
     * Renders the registered preparation for the collected distribution. / 渲染已采集发行版的注册准备。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @return render text / 渲染文本
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    @Override public String render(LinuxCapabilityFacts capabilities, String username) throws LinuxOperationException {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        DistributionSetupRenderer preparation = preparations.get(capabilities.distro());
        if (preparation == null) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_UNSUPPORTED_DISTRO,
                    "The target distribution is outside the managed deployment preparation matrix");
        }
        try {
            return preparation.render(capabilities, Objects.requireNonNull(username, "username"));
        } catch (IllegalArgumentException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_UNSUPPORTED_DISTRO,
                    "The collected distribution version is outside the managed deployment preparation matrix");
        }
    }

}
