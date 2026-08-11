package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.linux.capability.LinuxPhaseTwoCapabilityOperations;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.server.PhaseTwoLinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.PhaseTwoLinuxDistro;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Apache SSHD implementation of the read-only Phase Two host capability contract.
 *
 * <p>二期主机只读能力契约的 Apache SSHD 实现。
 */
public final class SshdPhaseTwoCapabilityCollector implements LinuxPhaseTwoCapabilityOperations {
    private final SshCommandExecutor commands;
    private final String hostFingerprint;

    /**
     * Creates a {@code SshdPhaseTwoCapabilityCollector} instance.
     *
     * <p>创建 {@code SshdPhaseTwoCapabilityCollector} 实例。
     */
    public SshdPhaseTwoCapabilityCollector(SshCommandExecutor commands, String hostFingerprint) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.hostFingerprint = Objects.requireNonNull(hostFingerprint, "hostFingerprint");
    }

    @Override
    public PhaseTwoLinuxCapabilities collectPhaseTwoCapabilities() throws LinuxOperationException {
        var result = commands.exec(PhaseTwoCapabilityProbeScript.render(), Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.phaseTwoCapabilityCollectionFailed",
                    "Failed to collect Phase Two target capabilities: " + result.failureEvidence());
        }
        return fromValues(SshCommandExecutor.lines(result.output()), hostFingerprint);
    }

    static PhaseTwoLinuxCapabilities fromValues(Map<String, String> values, String hostFingerprint) {
        Objects.requireNonNull(values, "values");
        String id = normalized(values.getOrDefault("DISTRO_ID", "unknown"));
        String variant = normalized(values.getOrDefault("DISTRO_VARIANT", ""));
        String version = normalized(values.getOrDefault("VERSION", "unknown"));
        Set<String> flags = Arrays.stream(values.getOrDefault("CPU_FLAGS", "").split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> value.matches("[a-z0-9_.-]{1,64}"))
                .collect(Collectors.toUnmodifiableSet());
        return new PhaseTwoLinuxCapabilities(classify(id, variant, version), version,
                normalized(values.getOrDefault("ARCH", "unknown")), normalized(values.getOrDefault("PACKAGE_MANAGER", "unknown")),
                "1".equals(values.get("SYSTEMD")), "1".equals(values.get("DOCKER_CLIENT")),
                "1".equals(values.get("PODMAN_CLIENT")), "1".equals(values.get("PODMAN_QUADLET")), flags,
                "SSH host fingerprint verified: " + Objects.requireNonNull(hostFingerprint, "hostFingerprint"));
    }

    private static PhaseTwoLinuxDistro classify(String id, String variant, String version) {
        if ("ubuntu".equals(id)) {
            return PhaseTwoLinuxDistro.UBUNTU;
        }
        if ("centos".equals(id) && "stream".equals(variant)) {
            return "8".equals(version) ? PhaseTwoLinuxDistro.LEGACY_CENTOS : PhaseTwoLinuxDistro.CENTOS_STREAM;
        }
        if ("centos".equals(id) && ("7".equals(version) || "8".equals(version))) {
            return PhaseTwoLinuxDistro.LEGACY_CENTOS;
        }
        return PhaseTwoLinuxDistro.OTHER;
    }

    private static String normalized(String value) {
        value = Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
        return value.isBlank() ? "unknown" : value;
    }
}
