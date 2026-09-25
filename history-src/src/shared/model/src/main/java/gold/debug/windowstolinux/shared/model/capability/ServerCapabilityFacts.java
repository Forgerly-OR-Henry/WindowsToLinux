package gold.debug.windowstolinux.shared.model.capability;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;

/**
 * Facts collected from a target host before any managed-deployment candidate is created.
 *
 *  <p>创建任何受管部署候选项之前从目标主机采集的事实。
 *
 * @param operatingSystem operating system / 操作系统
 * @param architecture observed machine architecture / 观测到的机器架构
 * @param systemdAvailable whether systemd is present / 是否存在 systemd
 * @param java21Available java 21 available / Java21可用
 * @param mavenAvailable whether Maven is present / 是否存在 Maven
 * @param tarAvailable tar available / tar可用
 * @param curlAvailable curl available / curl可用
 * @param socketInspectionAvailable socket inspection available / 套接字检查可用
 * @param buildLimitToolsAvailable build limit tools available / 构建限制工具集合可用
 * @param nonInteractiveSudoAvailable non interactive sudo available / 非InteractiveSudo可用
 * @param managedHelperProtocolVersion observed managed-helper protocol version, or zero when unavailable / 观察到的受管 helper 协议版本，不可用时为零
 * @param availableBytes available bytes / 可用字节
 * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
 */
public record ServerCapabilityFacts(String operatingSystem, String architecture, boolean systemdAvailable,
        boolean java21Available, boolean mavenAvailable, boolean tarAvailable, boolean curlAvailable,
        boolean socketInspectionAvailable, boolean buildLimitToolsAvailable, boolean nonInteractiveSudoAvailable,
        int managedHelperProtocolVersion, long availableBytes, String evidence) {
    /**
     * Validates and binds the inputs required by server capability facts.
     * <p>校验并绑定服务器能力事实所需输入。
     *
     * @param operatingSystem operating system / 操作系统
     * @param architecture observed machine architecture / 观测到的机器架构
     * @param systemdAvailable whether systemd is present / 是否存在 systemd
     * @param java21Available java 21 available / Java21可用
     * @param mavenAvailable whether Maven is present / 是否存在 Maven
     * @param tarAvailable tar available / tar可用
     * @param curlAvailable curl available / curl可用
     * @param socketInspectionAvailable socket inspection available / 套接字检查可用
     * @param buildLimitToolsAvailable build limit tools available / 构建限制工具集合可用
     * @param nonInteractiveSudoAvailable non interactive sudo available / 非InteractiveSudo可用
     * @param managedHelperProtocolVersion observed managed-helper protocol version, or zero when unavailable / 观察到的受管 helper 协议版本，不可用时为零
     * @param availableBytes available bytes / 可用字节
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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
     *  <p>检查 {@code supportsManagedDeployment} 表示的条件。
     *
     * @param sourceUsesMavenWrapper source uses maven wrapper / 源码使用集合MavenWrapper
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return whether the operation condition is satisfied / 操作条件是否满足
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public boolean supportsManagedDeployment(boolean sourceUsesMavenWrapper, HealthCheck healthCheck) {
        Objects.requireNonNull(healthCheck, "healthCheck");
        boolean healthToolsAvailable = switch (healthCheck) {
            case HealthCheck.Http ignored -> curlAvailable && socketInspectionAvailable;
            case HealthCheck.Tcp ignored -> socketInspectionAvailable;
            case HealthCheck.Udp ignored -> socketInspectionAvailable;
            case HealthCheck.Process ignored -> true;
            case HealthCheck.Command ignored -> true;
        };
        boolean supportedOperatingSystem = supportedOperatingSystem();
        return supportedOperatingSystem && architecture.equals("x86_64") && systemdAvailable && java21Available
                && tarAvailable && buildLimitToolsAvailable && nonInteractiveSudoAvailable
                && managedHelperProtocolVersion == ManagedHelperProtocolVersion.CURRENT && healthToolsAvailable
                && (mavenAvailable || sourceUsesMavenWrapper);
    }

    /**
     * Tests the supported operating system predicate against the supplied evidence.
     * <p>根据所提供证据检查受支持操作系统条件。
     *
     * @return true when supported operating system predicate against the supplied evidence, false otherwise / 根据所提供证据检查受支持操作系统条件时为 true，否则为 false
     */
    private boolean supportedOperatingSystem() {
        return operatingSystem.contains("Ubuntu 22.04") || operatingSystem.contains("Ubuntu 24.04")
                || operatingSystem.contains("Debian GNU/Linux 13") || operatingSystem.contains("CentOS Stream 9")
                || operatingSystem.contains("CentOS Stream 10") || operatingSystem.contains("Rocky Linux 9.8")
                || operatingSystem.contains("Rocky Linux 10.2") || operatingSystem.contains("AlmaLinux 9.8")
                || operatingSystem.contains("AlmaLinux 10.2") || operatingSystem.contains("Oracle Linux Server 9.7")
                || operatingSystem.contains("Oracle Linux Server 10.2");
    }

    /**
     * Validates and produces non blank for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的非空白。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return non blank text / 非空白文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String nonBlank(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
