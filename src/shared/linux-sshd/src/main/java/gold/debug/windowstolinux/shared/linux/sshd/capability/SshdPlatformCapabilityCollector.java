package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.linux.capability.LinuxPlatformCapabilityCollector;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
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
 * <p>部署主机只读能力契约的 Apache SSHD 实现。
 */
public final class SshdPlatformCapabilityCollector implements LinuxPlatformCapabilityCollector {
    private static final int READ_ONLY_ATTEMPTS = 3;
    private static final Duration READ_ONLY_RETRY_DELAY = Duration.ofMillis(250);
    private final SshCommandExecutor commands;
    private final String hostFingerprint;

    /**
     * Creates a {@code SshdPlatformCapabilityCollector} instance.
     *
     * <p>创建 {@code SshdPlatformCapabilityCollector} 实例。
     */
    public SshdPlatformCapabilityCollector(SshCommandExecutor commands, String hostFingerprint) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.hostFingerprint = Objects.requireNonNull(hostFingerprint, "hostFingerprint");
    }

    /** Performs the {@code collectDeploymentCapabilities} operation. / 执行 {@code collectDeploymentCapabilities} 操作。 */
    @Override
    public LinuxCapabilityFacts collectDeploymentCapabilities() throws LinuxOperationException {
        var result = collectReadOnly(ManagedPlatformCapabilityProbe.render());
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.deploymentCapabilityCollectionFailed",
                    "Failed to collect typed deployment target capabilities: " + result.failureEvidence());
        }
        return fromValues(SshCommandExecutor.lines(result.output()), hostFingerprint);
    }

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
                .map(String::trim).filter(value -> value.matches("3\\.(?:10|11|12|13)"))
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

    private SshCommandExecutor.CommandResult collectReadOnly(String script) throws LinuxOperationException {
        for (int attempt = 1; attempt <= READ_ONLY_ATTEMPTS; attempt++) {
            try {
                return commands.exec(script, Duration.ofSeconds(20), true);
            } catch (LinuxOperationException failure) {
                if (!SshCommandExecutor.isTransientTransportFailure(failure) || attempt == READ_ONLY_ATTEMPTS) {
                    throw failure;
                }
                waitForRetry();
            }
        }
        throw new IllegalStateException("read-only SSH retry loop completed without a result");
    }

    private static void waitForRetry() throws LinuxOperationException {
        try {
            Thread.sleep(READ_ONLY_RETRY_DELAY.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw LinuxOperationException.localized("linux.error.deploymentCapabilityCollectionFailed",
                    "Read-only deployment capability collection retry was interrupted", exception);
        }
    }

    private static Set<Integer> integerVersions(String value) {
        return Arrays.stream(Objects.requireNonNull(value, "value").split(","))
                .map(String::trim).filter(item -> item.matches("[0-9]{1,2}"))
                .map(Integer::valueOf).collect(Collectors.toUnmodifiableSet());
    }

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
            // CentOS Linux ended at 8; Stream 9/10 images commonly omit VARIANT_ID.
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

    private static CpuMicroarchitectureLevel cpuLevel(String value) {
        return switch (normalized(value)) {
            case "x86-64-v1" -> CpuMicroarchitectureLevel.X86_64_V1;
            case "x86-64-v2" -> CpuMicroarchitectureLevel.X86_64_V2;
            case "x86-64-v3" -> CpuMicroarchitectureLevel.X86_64_V3;
            case "x86-64-v4" -> CpuMicroarchitectureLevel.X86_64_V4;
            default -> CpuMicroarchitectureLevel.UNKNOWN;
        };
    }

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

    private static String normalized(String value) {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? "unknown" : value;
    }
    private static java.util.Set<DeploymentProjectType> serviceProjectTypes() {
        return java.util.EnumSet.of(DeploymentProjectType.GO_SERVICE, DeploymentProjectType.RUST_SERVICE,
                DeploymentProjectType.DOTNET_SERVICE, DeploymentProjectType.KOTLIN_SERVICE,
                DeploymentProjectType.PHP_SERVICE, DeploymentProjectType.RUBY_SERVICE);
    }

    private static boolean validServiceVersion(DeploymentProjectType projectType, String value) {
        return switch (projectType) {
            case GO_SERVICE -> value.matches("1\\.(?:22|23|24)");
            case RUST_SERVICE -> value.matches("1\\.(?:7[5-9]|8[0-9]|9[0-9])(?:\\.[0-9]+)?");
            case DOTNET_SERVICE -> value.matches("(?:8|9)\\.0(?:\\.[0-9]+)?");
            case KOTLIN_SERVICE -> value.equals("21");
            case PHP_SERVICE -> value.matches("8\\.(?:2|3|4)");
            case RUBY_SERVICE -> value.matches("3\\.(?:2|3|4)(?:\\.[0-9]+)?");
            default -> false;
        };
    }
}
