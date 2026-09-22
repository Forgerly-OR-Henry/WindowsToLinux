package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;

/**
 * Immutable, non-secret target contract for one opt-in distribution acceptance execution.
 *
 * <p>一次可选发行版验收执行所使用的不可变、无秘密目标契约。
 */
record ManagedDistributionAcceptanceProfile(LinuxDistroType distro, String version, String packageArchitecture,
        CpuMicroarchitectureLevel requiredCpu, SetupExpectationKind preparationExpectation) {
    private static final String DISTRIBUTION = "managed.distro.expected";

    private static final String VERSION = "managed.distro.expected-version";

    private static final String PACKAGE_ARCHITECTURE = "managed.distro.expected-package-architecture";

    private static final String REQUIRED_CPU = "managed.distro.expected-cpu";

    private static final String PREPARATION = "managed.distro.preparation-expectation";

    enum SetupExpectationKind {
        /** Represents the {@code SUCCEEDS} value. / 表示 {@code SUCCEEDS} 值。 */
        SUCCEEDS,
        /** Represents the {@code REJECTS} value. / 表示 {@code REJECTS} 值。 */
        REJECTS
    }

    /** Reads the explicit, non-secret matrix selectors from system properties. / 从系统属性读取显式且无秘密的矩阵选择器。 */
    static ManagedDistributionAcceptanceProfile requiredFromSystemProperties() {
        Map<String, String> values = new HashMap<>();
        for (String property : java.util.List.of(DISTRIBUTION, VERSION, PACKAGE_ARCHITECTURE, REQUIRED_CPU,
                PREPARATION)) {
            values.put(property, System.getProperty(property));
        }
        return from(values);
    }

    static ManagedDistributionAcceptanceProfile from(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        String distribution = required(values, DISTRIBUTION);
        String version = required(values, VERSION);
        String packageArchitecture = required(values, PACKAGE_ARCHITECTURE);
        CpuMicroarchitectureLevel requiredCpu = cpu(required(values, REQUIRED_CPU));
        SetupExpectationKind expectation = preparation(required(values, PREPARATION));
        return switch (distribution) {
            case "debian" -> exact(LinuxDistroType.DEBIAN, version, packageArchitecture, requiredCpu, expectation, "13",
                    "amd64", CpuMicroarchitectureLevel.X86_64_V1, SetupExpectationKind.SUCCEEDS);
            case "centos", "centos-stream" -> centosStream(version, packageArchitecture, requiredCpu, expectation);
            case "rocky", "rocky-linux" -> rocky(version, packageArchitecture, requiredCpu, expectation);
            case "almalinux", "alma-linux" -> alma(version, packageArchitecture, requiredCpu, expectation);
            case "oracle", "oracle-linux" -> oracle(version, packageArchitecture, requiredCpu, expectation);
            default -> throw new IllegalArgumentException(
                    DISTRIBUTION + " must be debian, centos-stream, rocky-linux, almalinux, or oracle-linux");
        };
    }

    /** Verifies exact target identity plus the documented minimum CPU level before any mutation. / 在任何修改前验证精确目标身份及文档化的最低 CPU 级别。 */
    void assertExactBaseline(LinuxCapabilityFacts capabilities) {
        assertEquals(distro, capabilities.distro(), () -> "unexpected distribution: " + capabilities.evidence());
        assertEquals(version, capabilities.version(),
                () -> "unexpected distribution version: " + capabilities.evidence());
        assertEquals("x86_64", capabilities.architecture(), () -> "expected x86_64: " + capabilities.evidence());
        assertEquals(expectedPackageManager(), capabilities.packageManager(),
                () -> "unexpected package manager: " + capabilities.evidence());
        assertEquals(packageArchitecture, capabilities.packageArchitecture(),
                () -> "unexpected package architecture: " + capabilities.evidence());
        assertTrue(capabilities.cpuMicroarchitecture().supports(requiredCpu), () -> "expected at least " + requiredCpu
                + ", observed " + capabilities.cpuMicroarchitecture() + ": " + capabilities.evidence());
        assertSecurityWasObserved(capabilities.securityPosture(), capabilities.evidence());
        if (requiresEnforcingSelinux()) {
            assertEquals(LinuxSecurityModuleType.SELINUX, capabilities.securityPosture().module(),
                    () -> "enterprise preparation requires SELinux: " + capabilities.evidence());
            assertEquals(LinuxSecurityState.ENFORCING, capabilities.securityPosture().state(),
                    () -> "enterprise preparation requires enforcing SELinux: " + capabilities.evidence());
        }
    }

    /** Verifies that preparation did not change observed mandatory-access-control or firewall facts. / 验证环境准备未改变观测到的强制访问控制或防火墙事实。 */
    void assertSecurityAndFirewallPreserved(LinuxCapabilityFacts before, LinuxCapabilityFacts after) {
        assertSecurityWasObserved(after.securityPosture(), after.evidence());
        assertEquals(before.securityPosture(), after.securityPosture(), () -> "security or firewall changed from "
                + before.securityPosture() + " to " + after.securityPosture());
    }

    boolean expectsPreparationSuccess() {
        return preparationExpectation == SetupExpectationKind.SUCCEEDS;
    }

    String applicationPrefix() {
        return "distro-" + distro.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static ManagedDistributionAcceptanceProfile rocky(String version, String packageArchitecture,
            CpuMicroarchitectureLevel requiredCpu, SetupExpectationKind expectation) {
        return switch (version) {
            case "9.8" -> exact(LinuxDistroType.ROCKY_LINUX, version, packageArchitecture, requiredCpu, expectation,
                    "9.8", "x86_64", CpuMicroarchitectureLevel.X86_64_V1, SetupExpectationKind.SUCCEEDS);
            case "10.2" -> exact(LinuxDistroType.ROCKY_LINUX, version, packageArchitecture, requiredCpu, expectation,
                    "10.2", "x86_64", CpuMicroarchitectureLevel.X86_64_V3, SetupExpectationKind.SUCCEEDS);
            default -> throw new IllegalArgumentException("Rocky Linux target version must be 9.8 or 10.2");
        };
    }

    private static ManagedDistributionAcceptanceProfile centosStream(String version, String packageArchitecture,
            CpuMicroarchitectureLevel requiredCpu, SetupExpectationKind expectation) {
        if ("9".equals(version)) {
            return exact(LinuxDistroType.CENTOS_STREAM, version, packageArchitecture, requiredCpu, expectation, "9",
                    "x86_64", CpuMicroarchitectureLevel.X86_64_V2, SetupExpectationKind.SUCCEEDS);
        }
        if ("10".equals(version) && requiredCpu == CpuMicroarchitectureLevel.X86_64_V3) {
            return exact(LinuxDistroType.CENTOS_STREAM, version, packageArchitecture, requiredCpu, expectation, "10",
                    "x86_64", CpuMicroarchitectureLevel.X86_64_V3, SetupExpectationKind.SUCCEEDS);
        }
        if ("10".equals(version) && (requiredCpu == CpuMicroarchitectureLevel.X86_64_V1
                || requiredCpu == CpuMicroarchitectureLevel.X86_64_V2)) {
            return exact(LinuxDistroType.CENTOS_STREAM, version, packageArchitecture, requiredCpu, expectation, "10",
                    "x86_64", requiredCpu, SetupExpectationKind.REJECTS);
        }
        throw new IllegalArgumentException(
                "CentOS Stream target version must be 9 or 10 with its documented CPU baseline");
    }

    private static ManagedDistributionAcceptanceProfile oracle(String version, String packageArchitecture,
            CpuMicroarchitectureLevel requiredCpu, SetupExpectationKind expectation) {
        return switch (version) {
            case "9.7" -> exact(LinuxDistroType.ORACLE_LINUX, version, packageArchitecture, requiredCpu, expectation,
                    "9.7", "x86_64", CpuMicroarchitectureLevel.X86_64_V1, SetupExpectationKind.SUCCEEDS);
            case "10.2" -> exact(LinuxDistroType.ORACLE_LINUX, version, packageArchitecture, requiredCpu, expectation,
                    "10.2", "x86_64", CpuMicroarchitectureLevel.X86_64_V3, SetupExpectationKind.SUCCEEDS);
            default -> throw new IllegalArgumentException("Oracle Linux target version must be 9.7 or 10.2");
        };
    }

    private static ManagedDistributionAcceptanceProfile alma(String version, String packageArchitecture,
            CpuMicroarchitectureLevel requiredCpu, SetupExpectationKind expectation) {
        if ("9.8".equals(version)) {
            return exact(LinuxDistroType.ALMALINUX, version, packageArchitecture, requiredCpu, expectation, "9.8",
                    "x86_64", CpuMicroarchitectureLevel.X86_64_V1, SetupExpectationKind.SUCCEEDS);
        }
        if ("10.2".equals(version) && requiredCpu == CpuMicroarchitectureLevel.X86_64_V2) {
            return exact(LinuxDistroType.ALMALINUX, version, packageArchitecture, requiredCpu, expectation, "10.2",
                    "x86_64", CpuMicroarchitectureLevel.X86_64_V2, SetupExpectationKind.REJECTS);
        }
        return exact(LinuxDistroType.ALMALINUX, version, packageArchitecture, requiredCpu, expectation, "10.2",
                "x86_64", CpuMicroarchitectureLevel.X86_64_V3, SetupExpectationKind.SUCCEEDS);
    }

    private static ManagedDistributionAcceptanceProfile exact(LinuxDistroType distro, String version,
            String packageArchitecture, CpuMicroarchitectureLevel requiredCpu, SetupExpectationKind expectation,
            String expectedVersion, String expectedPackageArchitecture, CpuMicroarchitectureLevel expectedCpu,
            SetupExpectationKind expectedExpectation) {
        if (!expectedVersion.equals(version) || !expectedPackageArchitecture.equals(packageArchitecture)
                || requiredCpu != expectedCpu || expectation != expectedExpectation) {
            throw new IllegalArgumentException(
                    "explicit distribution matrix selectors do not match " + distro + " " + expectedVersion + " "
                            + expectedPackageArchitecture + " " + expectedCpu + " " + expectedExpectation);
        }
        return new ManagedDistributionAcceptanceProfile(distro, version, packageArchitecture, requiredCpu, expectation);
    }

    private String expectedPackageManager() {
        return distro == LinuxDistroType.DEBIAN ? "apt" : "dnf";
    }

    private boolean requiresEnforcingSelinux() {
        return distro == LinuxDistroType.CENTOS_STREAM || distro == LinuxDistroType.ROCKY_LINUX
                || distro == LinuxDistroType.ALMALINUX || distro == LinuxDistroType.ORACLE_LINUX;
    }

    private static void assertSecurityWasObserved(LinuxSecurityPosture posture, String evidence) {
        assertNotEquals(LinuxSecurityModuleType.UNKNOWN, posture.module(),
                () -> "security module was not observed: " + evidence);
        assertNotEquals(LinuxSecurityState.UNKNOWN, posture.state(),
                () -> "security state was not observed: " + evidence);
        assertNotEquals(LinuxFirewallKind.UNKNOWN, posture.firewall(),
                () -> "firewall manager was not observed: " + evidence);
        if (posture.firewall() != LinuxFirewallKind.NONE) {
            assertNotEquals(LinuxFirewallState.UNKNOWN, posture.firewallState(),
                    () -> "firewall state was not observed: " + evidence);
        }
    }

    private static CpuMicroarchitectureLevel cpu(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "x86-64-v1" -> CpuMicroarchitectureLevel.X86_64_V1;
            case "x86-64-v2" -> CpuMicroarchitectureLevel.X86_64_V2;
            case "x86-64-v3" -> CpuMicroarchitectureLevel.X86_64_V3;
            case "x86-64-v4" -> CpuMicroarchitectureLevel.X86_64_V4;
            default -> throw new IllegalArgumentException(REQUIRED_CPU + " must be x86-64-v1 through x86-64-v4");
        };
    }

    private static SetupExpectationKind preparation(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "succeeds" -> SetupExpectationKind.SUCCEEDS;
            case "rejects" -> SetupExpectationKind.REJECTS;
            default -> throw new IllegalArgumentException(PREPARATION + " must be succeeds or rejects");
        };
    }

    private static String required(Map<String, String> values, String property) {
        String value = values.get(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " is required");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
