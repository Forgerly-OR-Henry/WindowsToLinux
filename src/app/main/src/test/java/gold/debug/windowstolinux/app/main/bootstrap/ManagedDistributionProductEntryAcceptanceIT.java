package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit opt-in product-entrypoint acceptance for one exact non-Ubuntu distribution target.
 *
 * <p>一个精确非 Ubuntu 发行版目标的显式可选产品入口验收。
 */
@EnabledIfSystemProperty(named = "managed.runtime.distribution-acceptance", matches = "true")
class ManagedDistributionProductEntryAcceptanceIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesTheExactTargetTwicePreservesSecurityAndRunsTheSharedDeploymentTransaction() throws Exception {
        ManagedDistributionAcceptanceProfile profile = ManagedDistributionAcceptanceProfile.requiredFromSystemProperties();
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("preparation"))) {
            LinuxCapabilities baseline = context.inspectDeploymentCapabilities();
            profile.assertExactBaseline(baseline);
            assertTrue(context.service.listManagedApplications().isEmpty(),
                    "environment preparation must not begin with a local deployment record");

            if (!profile.expectsPreparationSuccess()) {
                assertThrows(LinuxOperationException.class, context::prepareEnvironment,
                        "the explicit negative matrix target must reject automatic preparation");
                LinuxCapabilities rejected = context.inspectDeploymentCapabilities();
                profile.assertExactBaseline(rejected);
                profile.assertSecurityAndFirewallPreserved(baseline, rejected);
                assertTrue(context.service.listManagedApplications().isEmpty(),
                        "rejected preparation must not create a deployment record");
                return;
            }

            EnvironmentPreparationResult first = context.prepareEnvironment();
            assertPreparationOnly(first, "first preparation");
            LinuxCapabilities afterFirst = context.inspectDeploymentCapabilities();
            profile.assertExactBaseline(afterFirst);
            profile.assertSecurityAndFirewallPreserved(baseline, afterFirst);
            assertTrue(context.service.listManagedApplications().isEmpty(),
                    "preparation must not upload, build, or publish an application");

            EnvironmentPreparationResult second = context.prepareEnvironment();
            assertPreparationOnly(second, "second preparation");
            LinuxCapabilities afterSecond = context.inspectDeploymentCapabilities();
            profile.assertExactBaseline(afterSecond);
            profile.assertSecurityAndFirewallPreserved(afterFirst, afterSecond);
            assertTrue(context.service.listManagedApplications().isEmpty(),
                    "repeated preparation must not create a deployment record");
        }

        ManagedMultiComponentDeploymentAcceptance.exercise(temporaryDirectory.resolve("transaction"),
                profile.applicationPrefix());
    }

    private static void assertPreparationOnly(EnvironmentPreparationResult result, String stage) {
        assertEquals(ManagedHelperProtocolVersion.CURRENT, result.capabilities().managedHelperProtocolVersion(),
                () -> stage + " did not expose the exact helper protocol: " + result.capabilities().evidence());
        assertTrue(result.capabilities().supportsManagedDeployment(false, new HealthCheck.Http(
                        URI.create("http://127.0.0.1:18080/actuator/health"), 200, 20)),
                () -> stage + " did not create the reviewed HTTP deployment baseline: "
                        + result.capabilities().evidence());
        String evidence = result.evidence().toLowerCase(Locale.ROOT);
        assertTrue(evidence.contains("fixed distribution toolset"),
                () -> stage + " lacks controlled preparation evidence: " + result.evidence());
        assertFalse(evidence.contains("upload") || evidence.contains("build") || evidence.contains("publish"),
                () -> stage + " must not claim application deployment: " + result.evidence());
    }
}
