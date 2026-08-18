package gold.debug.windowstolinux.shared.linux.sshd.distro.registry;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.apt.debian.DebianSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.apt.ubuntu.UbuntuSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.almalinux.AlmaLinuxSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.centosstream.CentosStreamSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.oraclelinux.OracleLinuxSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.rocky.RockyLinuxSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.spi.DistributionSetup;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityModule;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityState;

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
        return new DistributionSetupRegistry(List.of(
                preparation(LinuxDistro.UBUNTU, (facts, user) -> UbuntuSetup.renderScript(user, facts.version())),
                preparation(LinuxDistro.DEBIAN, (facts, user) -> DebianSetup.renderScript(user, facts.version())),
                preparation(LinuxDistro.CENTOS_STREAM, (facts, user) -> {
                    requireEnterpriseSecurity(facts);
                    return CentosStreamSetup.renderScript(user, facts.version());
                }),
                preparation(LinuxDistro.ROCKY_LINUX, (facts, user) -> {
                    requireEnterpriseSecurity(facts);
                    return RockyLinuxSetup.renderScript(user, facts.version());
                }),
                preparation(LinuxDistro.ALMALINUX, (facts, user) -> {
                    requireEnterpriseSecurity(facts);
                    return AlmaLinuxSetup.renderScript(user, facts.version(), facts.packageArchitecture());
                }),
                preparation(LinuxDistro.ORACLE_LINUX, (facts, user) -> {
                    requireEnterpriseSecurity(facts);
                    return OracleLinuxSetup.renderScript(user, facts.version());
                })));
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

    private static DistributionSetup preparation(LinuxDistro distro, Renderer renderer) {
        return new DistributionSetup() {
            /** Performs the {@code distro} operation. / 执行 {@code distro} 操作。 */
            @Override public LinuxDistro distro() { return distro; }
            /** Renders the controlled output. / 渲染受控输出。 */
            @Override public String render(LinuxCapabilities capabilities, String username) throws LinuxOperationException {
                return renderer.render(capabilities, username);
            }
        };
    }

    private static void requireEnterpriseSecurity(LinuxCapabilities capabilities) throws LinuxOperationException {
        if (capabilities.securityPosture().module() != LinuxSecurityModule.SELINUX
                || capabilities.securityPosture().state() != LinuxSecurityState.ENFORCING) {
            throw LinuxOperationException.localized("linux.error.environmentUnsupportedDistro",
                    "Enterprise Linux automatic preparation requires collected SELinux enforcing evidence");
        }
    }

    @FunctionalInterface
    private interface Renderer {
        /** Renders the controlled output. / 渲染受控输出。 */
        String render(LinuxCapabilities capabilities, String username) throws LinuxOperationException;
    }
}
