package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.sshd.distro.EcosystemCapabilityChecks;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.util.List;
import java.util.Objects;

/** Immutable, injection-resistant facts owned by one distribution adapter. / 单个发行版适配器持有的不可变、防注入事实。 */
public record DistributionSetupProfile(
        String id,
        String variant,
        String version,
        String packageArchitecture,
        CpuMicroarchitectureLevel requiredCpu,
        List<String> packages,
        EcosystemCapabilityChecks capabilityChecks
) {
    /** Creates an instance of this type. / 创建此类型的实例。 */
    public DistributionSetupProfile {
        id = token(id, "id");
        variant = Objects.requireNonNull(variant, "variant");
        if (!variant.isEmpty()) {
            variant = token(variant, "variant");
        }
        version = token(version, "version");
        packageArchitecture = token(packageArchitecture, "packageArchitecture");
        requiredCpu = Objects.requireNonNull(requiredCpu, "requiredCpu");
        packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
        if (packages.isEmpty() || packages.stream().anyMatch(item ->
                item == null || !item.matches("[a-zA-Z0-9_.+:-]{1,80}"))) {
            throw new IllegalArgumentException("packages must contain only fixed package names");
        }
        capabilityChecks = Objects.requireNonNull(capabilityChecks, "capabilityChecks");
    }

    private static String token(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-zA-Z0-9._-]{1,64}")) {
            throw new IllegalArgumentException(name + " must be a bounded distribution fact");
        }
        return value;
    }
}
