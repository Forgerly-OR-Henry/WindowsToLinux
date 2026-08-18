package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Validates the explicit non-secret distribution acceptance matrix selectors. / 验证显式且无秘密的发行版验收矩阵选择器。 */
class ManagedDistributionAcceptanceProfileTest {
    @Test
    void acceptsTheDebian13Target() {
        ManagedDistributionAcceptanceProfile profile = ManagedDistributionAcceptanceProfile.from(Map.of(
                "managed.distro.expected", "debian",
                "managed.distro.expected-version", "13",
                "managed.distro.expected-package-architecture", "amd64",
                "managed.distro.expected-cpu", "x86-64-v1",
                "managed.distro.preparation-expectation", "succeeds"
        ));

        assertEquals(LinuxDistro.DEBIAN, profile.distro());
        assertEquals(CpuMicroarchitectureLevel.X86_64_V1, profile.requiredCpu());
    }

    @Test
    void acceptsCentosStreamNineAndKeepsStreamTenCpuRejectionExplicit() {
        ManagedDistributionAcceptanceProfile nine = ManagedDistributionAcceptanceProfile.from(target(
                "centos-stream", "9", "x86-64-v1"));
        ManagedDistributionAcceptanceProfile tenV2 = ManagedDistributionAcceptanceProfile.from(Map.of(
                "managed.distro.expected", "centos-stream",
                "managed.distro.expected-version", "10",
                "managed.distro.expected-package-architecture", "x86_64",
                "managed.distro.expected-cpu", "x86-64-v2",
                "managed.distro.preparation-expectation", "rejects"
        ));

        assertEquals(LinuxDistro.CENTOS_STREAM, nine.distro());
        assertEquals(ManagedDistributionAcceptanceProfile.SetupExpectation.SUCCEEDS,
                nine.preparationExpectation());
        assertEquals(ManagedDistributionAcceptanceProfile.SetupExpectation.REJECTS,
                tenV2.preparationExpectation());
    }

    @Test
    void keepsAlmaLinux10V2AsAnExplicitNegativePreparationCase() {
        ManagedDistributionAcceptanceProfile profile = ManagedDistributionAcceptanceProfile.from(Map.of(
                "managed.distro.expected", "almalinux",
                "managed.distro.expected-version", "10.2",
                "managed.distro.expected-package-architecture", "x86_64",
                "managed.distro.expected-cpu", "x86-64-v2",
                "managed.distro.preparation-expectation", "rejects"
        ));

        assertEquals(ManagedDistributionAcceptanceProfile.SetupExpectation.REJECTS,
                profile.preparationExpectation());
    }

    @Test
    void acceptsEveryPositiveEnterpriseTargetInTheStaticMatrix() {
        List<Map<String, String>> targets = List.of(
                target("rocky-linux", "9.8", "x86-64-v1"),
                target("rocky-linux", "10.2", "x86-64-v3"),
                target("almalinux", "9.8", "x86-64-v1"),
                target("almalinux", "10.2", "x86-64-v3"),
                target("oracle-linux", "9.7", "x86-64-v1"),
                target("oracle-linux", "10.2", "x86-64-v3")
        );

        for (Map<String, String> target : targets) {
            assertEquals(ManagedDistributionAcceptanceProfile.SetupExpectation.SUCCEEDS,
                    ManagedDistributionAcceptanceProfile.from(target).preparationExpectation(), target::toString);
        }
    }

    @Test
    void rejectsAnUnsafeAlmaLinux10V2SuccessClaim() {
        assertThrows(IllegalArgumentException.class, () -> ManagedDistributionAcceptanceProfile.from(Map.of(
                "managed.distro.expected", "almalinux",
                "managed.distro.expected-version", "10.2",
                "managed.distro.expected-package-architecture", "x86_64",
                "managed.distro.expected-cpu", "x86-64-v2",
                "managed.distro.preparation-expectation", "succeeds"
        )));
    }

    @Test
    void rejectsADistributionOutsideTheLiveMatrix() {
        assertThrows(IllegalArgumentException.class, () -> ManagedDistributionAcceptanceProfile.from(Map.of(
                "managed.distro.expected", "ubuntu",
                "managed.distro.expected-version", "24.04",
                "managed.distro.expected-package-architecture", "amd64",
                "managed.distro.expected-cpu", "x86-64-v1",
                "managed.distro.preparation-expectation", "succeeds"
        )));
    }

    private static Map<String, String> target(String distribution, String version, String cpu) {
        return Map.of(
                "managed.distro.expected", distribution,
                "managed.distro.expected-version", version,
                "managed.distro.expected-package-architecture", "x86_64",
                "managed.distro.expected-cpu", cpu,
                "managed.distro.preparation-expectation", "succeeds"
        );
    }
}
