package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in product-entrypoint probe that reports only non-secret deployment capability facts.
 *
 * <p>仅报告无秘密部署能力事实的可选产品入口探测。
 */
@EnabledIfSystemProperty(named = "managed.runtime.capabilities", matches = "true")
class ManagedDeploymentCapabilityInspectionAcceptanceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsTheTypedCapabilityBaselineWithoutTargetMutation() throws Exception {
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("capabilities"))) {
            LinuxCapabilityFacts capabilities = context.inspectDeploymentCapabilities();
            assertNotNull(capabilities.distro());
            assertTrue(!capabilities.version().isBlank());
            assertTrue(!capabilities.architecture().isBlank());
            assertTrue(!capabilities.packageArchitecture().isBlank());
            assertNotNull(capabilities.cpuMicroarchitecture());
            assertNotNull(capabilities.securityPosture());
            System.out.printf("MANAGED_CAPABILITIES distro=%s version=%s architecture=%s packageArchitecture=%s cpu=%s security=%s firewall=%s%n",
                    capabilities.distro(), capabilities.version(), capabilities.architecture(),
                    capabilities.packageArchitecture(), capabilities.cpuMicroarchitecture(),
                    capabilities.securityPosture().state(), capabilities.securityPosture().firewall());
        }
    }
}
