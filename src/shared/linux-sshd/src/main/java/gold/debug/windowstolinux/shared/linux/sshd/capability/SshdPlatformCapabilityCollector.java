package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.linux.capability.LinuxPlatformCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.linux.sshd.capability.ManagedPlatformCapabilityProbe;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Apache SSHD implementation of the read-only typed deployment host capability contract.
 *
 *  <p>部署主机只读能力契约的 Apache SSHD 实现。
 */
public final class SshdPlatformCapabilityCollector implements LinuxPlatformCapabilityCollector {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;
    /**
     * Host fingerprint.
     * <p>主机指纹。
     */
    private final String hostFingerprint;

    /**
     * Validates and binds the inputs required by sshd platform capability collector.
     * <p>校验并绑定Sshd平台能力Collector所需输入。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param hostFingerprint host fingerprint / 主机指纹
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshdPlatformCapabilityCollector(SshCommandExecutor commands, String hostFingerprint) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.hostFingerprint = Objects.requireNonNull(hostFingerprint, "hostFingerprint");
    }

    /**
     * Collects deployment capabilities.
     * <p>采集部署能力。
     *
     * @return constructed or resolved linux capability facts / 构造或解析得到的Linux能力事实
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public LinuxCapabilityFacts collectDeploymentCapabilities() throws LinuxOperationException {
        var result = CapabilityReadExecutor.collect(commands, LinuxOperationFailureType.DEPLOYMENT_CAPABILITY_COLLECTION_FAILED, ManagedPlatformCapabilityProbe.render());
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DEPLOYMENT_CAPABILITY_COLLECTION_FAILED,
                    "Failed to collect typed deployment target capabilities: " + result.failureEvidence());
        }
        return fromValues(SshCommandExecutor.lines(result.output()), hostFingerprint);
    }

    /**
     * Parses allowlisted platform probe fields into typed Linux capabilities while preserving unknown or absent evidence.
     * <p>将白名单平台探测字段解析为类型化 Linux 能力，并保留未知或缺失证据。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param hostFingerprint host fingerprint / 主机指纹
     * @return allowlisted platform probe fields into typed Linux capabilities while preserving unknown or absent evidence / 将白名单平台探测字段解析为类型化 Linux 能力，并保留未知或缺失证据
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static LinuxCapabilityFacts fromValues(Map<String, String> values, String hostFingerprint) {
        Objects.requireNonNull(values, "values");
        String id = normalized(values.getOrDefault("DISTRO_ID", "unknown"));
        String variant = normalized(values.getOrDefault("DISTRO_VARIANT", ""));
        String version = normalized(values.getOrDefault("VERSION", "unknown"));
        Set<String> flags = Arrays.stream(values.getOrDefault("CPU_FLAGS", "").split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> value.matches("[a-z0-9_.-]{1,64}"))
                .collect(Collectors.toUnmodifiableSet());
        Set<Integer> javaMajors = integerVersions(values.getOrDefault("JAVA_MAJORS", ""));
        Set<Integer> nodeMajors = integerVersions(values.getOrDefault("NODE_MAJORS", ""));
        Set<String> pythonVersions = Arrays.stream(values.getOrDefault("PYTHON_VERSIONS", "").split(","))
                .map(String::trim).filter(value -> value.matches("[0-9]{1,3}\\.[0-9]{1,3}"))
                .collect(Collectors.toUnmodifiableSet());
        java.util.EnumMap<DeploymentProjectType, Set<String>> serviceVersions =
                new java.util.EnumMap<>(DeploymentProjectType.class);
        for (DeploymentProjectType projectType : serviceProjectTypes()) {
            String observed = values.getOrDefault("SERVICE_" + projectType.name().replace("_SERVICE", ""), "").trim();
            if (validServiceVersion(projectType, observed)) {
                serviceVersions.put(projectType, Set.of(observed));
            }
        }
        java.util.EnumMap<EcosystemToolType, Set<String>> ecosystemTools =
                new java.util.EnumMap<>(EcosystemToolType.class);
        for (EcosystemToolType tool : EcosystemToolType.values()) {
            Set<String> observed = Arrays.stream(values.getOrDefault("TOOL_" + tool.name(), "").split(","))
                    .map(String::trim).filter(candidate -> candidate.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}"))
                    .collect(Collectors.toUnmodifiableSet());
            if (!observed.isEmpty()) ecosystemTools.put(tool, observed);
        }
        String architecture = normalized(values.getOrDefault("ARCH", "unknown"));
        String packageManager = normalized(values.getOrDefault("PACKAGE_MANAGER", "unknown"));
        String packageArchitecture = normalized(values.getOrDefault("PACKAGE_ARCH", "unknown"));
        CpuMicroarchitectureLevel cpuLevel = cpuLevel(values.getOrDefault("CPU_LEVEL", "unknown"));
        LinuxSecurityPosture security = security(values);
        String evidence = "SSH host fingerprint verified: " + Objects.requireNonNull(hostFingerprint, "hostFingerprint")
                + "; distro=" + id + "; version=" + version + "; arch=" + architecture
                + "; package-manager=" + packageManager + "; package-arch=" + packageArchitecture
                + "; cpu-level=" + cpuLevel.name().toLowerCase(Locale.ROOT)
                + "; security=" + security.module().name().toLowerCase(Locale.ROOT) + "/"
                + security.state().name().toLowerCase(Locale.ROOT)
                + "; firewall=" + security.firewall().name().toLowerCase(Locale.ROOT) + "/"
                + security.firewallState().name().toLowerCase(Locale.ROOT)
                + "; docker=" + values.getOrDefault("DOCKER_OPERATIONAL", "0")
                + "; podman=" + values.getOrDefault("PODMAN_OPERATIONAL", "0");
        return new LinuxCapabilityFacts(classify(id, variant, version), version, architecture, packageManager,
                packageArchitecture,
                "1".equals(values.get("SYSTEMD")), "1".equals(values.get("DOCKER_CLIENT")),
                "1".equals(values.get("PODMAN_CLIENT")), "1".equals(values.get("PODMAN_QUADLET")),
                javaMajors, nodeMajors, "1".equals(values.get("NPM")), "1".equals(values.get("MAVEN")), pythonVersions,
                "1".equals(values.get("PYTHON3")), serviceVersions, ecosystemTools,
                "1".equals(values.get("DOCKER_OPERATIONAL")),
                "1".equals(values.get("PODMAN_OPERATIONAL")), cpuLevel, flags, security, evidence);
    }



    /**
     * Collects valid one- or two-digit versions from a comma-separated capability field.
     * <p>从逗号分隔的能力字段采集有效的一至两位数字版本。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved set / 构造或解析得到的集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static Set<Integer> integerVersions(String value) {
        return Arrays.stream(Objects.requireNonNull(value, "value").split(","))
                .map(String::trim).filter(item -> item.matches("[0-9]{1,2}"))
                .map(Integer::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Classifies the Linux distribution from its observed identifier, variant and version.
     * <p>根据已观测标识、变体及版本对 Linux 发行版分类。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param variant variant / 变体
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @return constructed or resolved linux distro type / 构造或解析得到的Linux发行版类型
     */
    private static LinuxDistroType classify(String id, String variant, String version) {
        if ("ubuntu".equals(id)) {
            return LinuxDistroType.UBUNTU;
        }
        if ("debian".equals(id)) {
            return LinuxDistroType.DEBIAN;
        }
        if ("centos".equals(id)) {
            if ("7".equals(version) || "8".equals(version)) {
                return LinuxDistroType.LEGACY_CENTOS;
            }
            // CentOS Linux ended at 8; Stream 9/10 images commonly omit VARIANT_ID. / CentOS Linux 止于第 8 版，Stream 9 和 10 镜像通常省略 VARIANT_ID。
            // CentOS Linux 在 8 结束；Stream 9/10 镜像通常省略 VARIANT_ID。
            if ("9".equals(version) || "10".equals(version)) {
                return LinuxDistroType.CENTOS_STREAM;
            }
        }
        if ("rocky".equals(id)) {
            return LinuxDistroType.ROCKY_LINUX;
        }
        if ("almalinux".equals(id)) {
            return LinuxDistroType.ALMALINUX;
        }
        if ("ol".equals(id)) {
            return LinuxDistroType.ORACLE_LINUX;
        }
        return LinuxDistroType.OTHER;
    }

    /**
     * Maps a normalized CPU capability token to the supported microarchitecture classification.
     * <p>将规范化 CPU 能力令牌映射为受支持微架构分类。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved cpu microarchitecture level / 构造或解析得到的CpuMicroarchitecture级别
     */
    private static CpuMicroarchitectureLevel cpuLevel(String value) {
        return switch (normalized(value)) {
            case "x86-64-v1" -> CpuMicroarchitectureLevel.X86_64_V1;
            case "x86-64-v2" -> CpuMicroarchitectureLevel.X86_64_V2;
            case "x86-64-v3" -> CpuMicroarchitectureLevel.X86_64_V3;
            case "x86-64-v4" -> CpuMicroarchitectureLevel.X86_64_V4;
            default -> CpuMicroarchitectureLevel.UNKNOWN;
        };
    }

    /**
     * Parses SELinux, AppArmor and related platform security observations into the typed security posture.
     * <p>将 SELinux、AppArmor 及相关平台安全观测解析为类型化安全状态。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return sELinux, AppArmor and related platform security observations into the typed security posture / 将 SELinux、AppArmor 及相关平台安全观测解析为类型化安全状态
     */
    private static LinuxSecurityPosture security(Map<String, String> values) {
        LinuxSecurityModuleType module = switch (normalized(values.getOrDefault("SECURITY_MODULE", "unknown"))) {
            case "apparmor" -> LinuxSecurityModuleType.APPARMOR;
            case "selinux" -> LinuxSecurityModuleType.SELINUX;
            case "none" -> LinuxSecurityModuleType.NONE;
            default -> LinuxSecurityModuleType.UNKNOWN;
        };
        LinuxSecurityState state = switch (normalized(values.getOrDefault("SECURITY_STATE", "unknown"))) {
            case "enforcing" -> LinuxSecurityState.ENFORCING;
            case "permissive" -> LinuxSecurityState.PERMISSIVE;
            case "enabled" -> LinuxSecurityState.ENABLED;
            case "disabled" -> LinuxSecurityState.DISABLED;
            default -> LinuxSecurityState.UNKNOWN;
        };
        LinuxFirewallKind firewall = switch (normalized(values.getOrDefault("FIREWALL", "unknown"))) {
            case "firewalld" -> LinuxFirewallKind.FIREWALLD;
            case "ufw" -> LinuxFirewallKind.UFW;
            case "nftables" -> LinuxFirewallKind.NFTABLES;
            case "none" -> LinuxFirewallKind.NONE;
            default -> LinuxFirewallKind.UNKNOWN;
        };
        LinuxFirewallState firewallState = switch (normalized(values.getOrDefault("FIREWALL_STATE", "unknown"))) {
            case "active" -> LinuxFirewallState.ACTIVE;
            case "inactive" -> LinuxFirewallState.INACTIVE;
            default -> LinuxFirewallState.UNKNOWN;
        };
        return new LinuxSecurityPosture(module, state, firewall, firewallState);
    }

    /**
     * Normalizes the supplied contents according to the owning contract.
     * <p>按所属契约规范化所提供内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return normalized text / 规范化文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String normalized(String value) {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? "unknown" : value;
    }
    /**
     * Returns service project types.
     * <p>返回服务项目类型集合。
     *
     * @return service project types / 服务项目类型集合
     */
    private static java.util.Set<DeploymentProjectType> serviceProjectTypes() {
        return java.util.EnumSet.of(DeploymentProjectType.GO_SERVICE, DeploymentProjectType.RUST_SERVICE,
                DeploymentProjectType.DOTNET_SERVICE, DeploymentProjectType.KOTLIN_SERVICE,
                DeploymentProjectType.PHP_SERVICE, DeploymentProjectType.RUBY_SERVICE);
    }

    /**
     * Tests the valid service version predicate against the supplied evidence.
     * <p>根据所提供证据检查有效服务版本条件。
     *
     * @param projectType supported project deployment category / 受支持的项目部署类别
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return true when valid service version predicate against the supplied evidence, false otherwise / 根据所提供证据检查有效服务版本条件时为 true，否则为 false
     */
    private static boolean validServiceVersion(DeploymentProjectType projectType, String value) {
        return switch (projectType) {
            case GO_SERVICE -> value.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,95}");
            case RUST_SERVICE -> value.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,95}");
            case DOTNET_SERVICE -> value.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,95}");
            case KOTLIN_SERVICE -> value.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,95}");
            case PHP_SERVICE -> value.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,95}");
            case RUBY_SERVICE -> value.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,95}");
            default -> false;
        };
    }
}
