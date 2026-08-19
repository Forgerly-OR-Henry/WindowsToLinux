package gold.debug.windowstolinux.shared.model.capability;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;

import java.util.Objects;

/**
 * Facts collected from a target host before any managed-deployment candidate is created.
 *
 * <p>创建任何受管部署候选项之前从目标主机采集的事实。
 *
 * @param operatingSystem the {@code operatingSystem} value / {@code operatingSystem} 值
 * @param architecture the {@code architecture} value / {@code architecture} 值
 * @param systemdAvailable the {@code systemdAvailable} value / {@code systemdAvailable} 值
 * @param java21Available the {@code java21Available} value / {@code java21Available} 值
 * @param mavenAvailable the {@code mavenAvailable} value / {@code mavenAvailable} 值
 * @param tarAvailable the {@code tarAvailable} value / {@code tarAvailable} 值
 * @param curlAvailable the {@code curlAvailable} value / {@code curlAvailable} 值
 * @param socketInspectionAvailable the {@code socketInspectionAvailable} value / {@code socketInspectionAvailable} 值
 * @param buildLimitToolsAvailable the {@code buildLimitToolsAvailable} value / {@code buildLimitToolsAvailable} 值
 * @param nonInteractiveSudoAvailable the {@code nonInteractiveSudoAvailable} value / {@code nonInteractiveSudoAvailable} 值
 * @param managedHelperProtocolVersion observed managed-helper protocol version, or zero when unavailable / 观察到的受管 helper 协议版本，不可用时为零
 * @param availableBytes the {@code availableBytes} value / {@code availableBytes} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record ServerCapabilityFacts(
        String operatingSystem,
        String architecture,
        boolean systemdAvailable,
        boolean java21Available,
        boolean mavenAvailable,
        boolean tarAvailable,
        boolean curlAvailable,
        boolean socketInspectionAvailable,
        boolean buildLimitToolsAvailable,
        boolean nonInteractiveSudoAvailable,
        int managedHelperProtocolVersion,
        long availableBytes,
        String evidence
) {
    /**
     * Creates a {@code ServerCapabilityFacts} instance.
     *
     * <p>创建 {@code ServerCapabilityFacts} 实例。
     *
     * @param operatingSystem the {@code operatingSystem} value / {@code operatingSystem} 值
     * @param architecture the {@code architecture} value / {@code architecture} 值
     * @param systemdAvailable the {@code systemdAvailable} value / {@code systemdAvailable} 值
     * @param java21Available the {@code java21Available} value / {@code java21Available} 值
     * @param mavenAvailable the {@code mavenAvailable} value / {@code mavenAvailable} 值
     * @param tarAvailable the {@code tarAvailable} value / {@code tarAvailable} 值
     * @param curlAvailable the {@code curlAvailable} value / {@code curlAvailable} 值
     * @param socketInspectionAvailable the {@code socketInspectionAvailable} value / {@code socketInspectionAvailable} 值
     * @param buildLimitToolsAvailable the {@code buildLimitToolsAvailable} value / {@code buildLimitToolsAvailable} 值
     * @param nonInteractiveSudoAvailable the {@code nonInteractiveSudoAvailable} value / {@code nonInteractiveSudoAvailable} 值
     * @param availableBytes the {@code availableBytes} value / {@code availableBytes} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ServerCapabilityFacts {
        operatingSystem = nonBlank(operatingSystem, "operatingSystem");
        architecture = nonBlank(architecture, "architecture");
        if (managedHelperProtocolVersion < 0 || managedHelperProtocolVersion > 999) {
            throw new IllegalArgumentException("managedHelperProtocolVersion must be a bounded non-negative version");
        }
        if (availableBytes < 0) {
            throw new IllegalArgumentException("availableBytes must not be negative");
        }
        evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /**
     * Checks the condition represented by {@code supportsManagedDeployment}.
     *
     * <p>检查 {@code supportsManagedDeployment} 表示的条件。
     *
     * @param sourceUsesMavenWrapper the {@code sourceUsesMavenWrapper} value / {@code sourceUsesMavenWrapper} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @return whether the operation condition is satisfied / 操作条件是否满足
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public boolean supportsManagedDeployment(boolean sourceUsesMavenWrapper, HealthCheck healthCheck) {
        Objects.requireNonNull(healthCheck, "healthCheck");
        boolean healthToolsAvailable = switch (healthCheck) {
            case HealthCheck.Http ignored -> curlAvailable && socketInspectionAvailable;
            case HealthCheck.Tcp ignored -> socketInspectionAvailable;
        };
        boolean supportedOperatingSystem = supportedOperatingSystem();
        return supportedOperatingSystem
                && architecture.equals("x86_64")
                && systemdAvailable
                && java21Available
                && tarAvailable
                && buildLimitToolsAvailable
                && nonInteractiveSudoAvailable
                && managedHelperProtocolVersion == ManagedHelperProtocolVersion.CURRENT
                && healthToolsAvailable
                && (mavenAvailable || sourceUsesMavenWrapper);
    }

    private boolean supportedOperatingSystem() {
        return operatingSystem.contains("Ubuntu 22.04")
                || operatingSystem.contains("Ubuntu 24.04")
                || operatingSystem.contains("Debian GNU/Linux 13")
                || operatingSystem.contains("CentOS Stream 9")
                || operatingSystem.contains("CentOS Stream 10")
                || operatingSystem.contains("Rocky Linux 9.8")
                || operatingSystem.contains("Rocky Linux 10.2")
                || operatingSystem.contains("AlmaLinux 9.8")
                || operatingSystem.contains("AlmaLinux 10.2")
                || operatingSystem.contains("Oracle Linux Server 9.7")
                || operatingSystem.contains("Oracle Linux Server 10.2");
    }

    private static String nonBlank(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
