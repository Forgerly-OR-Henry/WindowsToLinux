package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the complete validated assembly of distribution preparation implementations.
 *
 * <p>持有完整且已验证的发行版准备实现装配。
 */
public final class DistributionSetupRegistry {
    private final Map<LinuxDistro, DistributionSetup> preparations;

    /** Creates the fixed supported-distribution assembly. / 创建固定的受支持发行版装配。 */
    public static DistributionSetupRegistry defaults() {
        return new DistributionSetupRegistry(DistributionSetupProfiles.defaults());
    }

    /** Validates unique preparation ownership for supported distributions. / 验证受支持发行版的唯一准备归属。 */
    public DistributionSetupRegistry(List<DistributionSetup> preparations) {
        EnumMap<LinuxDistro, DistributionSetup> indexed = new EnumMap<>(LinuxDistro.class);
        for (DistributionSetup preparation : Objects.requireNonNull(preparations, "preparations")) {
            preparation = Objects.requireNonNull(preparation, "preparation");
            if (indexed.putIfAbsent(preparation.distro(), preparation) != null) {
                throw new IllegalArgumentException("each distribution requires exactly one preparation implementation");
            }
        }
        this.preparations = Map.copyOf(indexed);
    }

    /** Renders the registered preparation for the collected distribution. / 渲染已采集发行版的注册准备。 */
    public String render(LinuxCapabilities capabilities, String username) throws LinuxOperationException {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        DistributionSetup preparation = preparations.get(capabilities.distro());
        if (preparation == null) {
            throw LinuxOperationException.localized("linux.error.environmentUnsupportedDistro",
                    "The target distribution is outside the managed deployment preparation matrix");
        }
        try {
            return preparation.render(capabilities, Objects.requireNonNull(username, "username"));
        } catch (IllegalArgumentException exception) {
            throw LinuxOperationException.localized("linux.error.environmentUnsupportedDistro",
                    "The collected distribution version is outside the managed deployment preparation matrix");
        }
    }

}
