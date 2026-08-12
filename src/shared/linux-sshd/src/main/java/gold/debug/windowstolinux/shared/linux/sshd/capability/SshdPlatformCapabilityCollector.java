package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.linux.capability.LinuxPlatformCapabilityOperations;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;

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
public final class SshdPlatformCapabilityCollector implements LinuxPlatformCapabilityOperations {
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

    @Override
    public LinuxCapabilities collectDeploymentCapabilities() throws LinuxOperationException {
        var result = commands.exec(PlatformCapabilityProbeScript.render(), Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.deploymentCapabilityCollectionFailed",
                    "Failed to collect typed deployment target capabilities: " + result.failureEvidence());
        }
        return fromValues(SshCommandExecutor.lines(result.output()), hostFingerprint);
    }

    static LinuxCapabilities fromValues(Map<String, String> values, String hostFingerprint) {
        Objects.requireNonNull(values, "values");
        String id = normalized(values.getOrDefault("DISTRO_ID", "unknown"));
        String variant = normalized(values.getOrDefault("DISTRO_VARIANT", ""));
        String version = normalized(values.getOrDefault("VERSION", "unknown"));
        Set<String> flags = Arrays.stream(values.getOrDefault("CPU_FLAGS", "").split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> value.matches("[a-z0-9_.-]{1,64}"))
                .collect(Collectors.toUnmodifiableSet());
        return new LinuxCapabilities(classify(id, variant, version), version,
                normalized(values.getOrDefault("ARCH", "unknown")), normalized(values.getOrDefault("PACKAGE_MANAGER", "unknown")),
                "1".equals(values.get("SYSTEMD")), "1".equals(values.get("DOCKER_CLIENT")),
                "1".equals(values.get("PODMAN_CLIENT")), "1".equals(values.get("PODMAN_QUADLET")), flags,
                "SSH host fingerprint verified: " + Objects.requireNonNull(hostFingerprint, "hostFingerprint"));
    }

    private static LinuxDistro classify(String id, String variant, String version) {
        if ("ubuntu".equals(id)) {
            return LinuxDistro.UBUNTU;
        }
        if ("centos".equals(id) && "stream".equals(variant)) {
            return "8".equals(version) ? LinuxDistro.LEGACY_CENTOS : LinuxDistro.CENTOS_STREAM;
        }
        if ("centos".equals(id) && ("7".equals(version) || "8".equals(version))) {
            return LinuxDistro.LEGACY_CENTOS;
        }
        return LinuxDistro.OTHER;
    }

    private static String normalized(String value) {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? "unknown" : value;
    }
}
