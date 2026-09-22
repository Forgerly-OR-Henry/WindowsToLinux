package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentApprovalException;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Product-owned, explicitly authorized system preparation including reboot and reconnect. / 产品执行的显式授权系统准备，包含重启和重连。 */
@EnabledIfSystemProperty(named = "managed.runtime.system-preparation", matches = "true")
class ManagedSystemPreparationAcceptanceTest {
    @TempDir
    Path directory;

    @Test
    void confirmsSystemChangesSeparatelyThenPreparesTheEnvironmentTwice() throws Exception {
        assertTrue(Boolean.getBoolean("managed.system-preparation.confirm"),
                "The target owner must explicitly authorize system changes and reboot before enabling this test");
        try (var context = new LiveTypedDeploymentContext(directory)) {
            var before = context.inspectDeploymentCapabilities();
            assertEquals(LinuxDistroType.CENTOS_STREAM, before.distro());
            AtomicReference<SelinuxPreparationPlan> declined = new AtomicReference<>();
            if (before.securityPosture().state() != LinuxSecurityState.ENFORCING) {
                assertThrows(DeploymentApprovalException.class,
                        () -> context.prepareEnvironmentWithSystemConfirmation(plan -> {
                            declined.set(plan);
                            System.out.println("SYSTEM_PREPARATION_DECLINED " + plan);
                            return false;
                        }));
            }
            var result = context.prepareEnvironmentWithSystemConfirmation(plan -> {
                if (declined.get() != null)
                    assertEquals(declined.get(), plan, "Declined preparation changed target facts");
                System.out.println("SYSTEM_PREPARATION_APPROVED " + plan);
                return true;
            });
            assertEquals(ManagedHelperProtocolVersion.CURRENT, result.capabilities().managedHelperProtocolVersion());
            var after = context.inspectDeploymentCapabilities();
            assertEquals(LinuxSecurityState.ENFORCING, after.securityPosture().state());
            assertEquals(before.securityPosture().firewall(), after.securityPosture().firewall());
            assertEquals(before.securityPosture().firewallState(), after.securityPosture().firewallState());
            var repeated = context.prepareEnvironmentWithSystemConfirmation(
                    plan -> fail("Verified preparation asked for system changes again"));
            assertEquals(ManagedHelperProtocolVersion.CURRENT, repeated.capabilities().managedHelperProtocolVersion());
            System.out.println("SYSTEM_PREPARATION_VERIFIED " + after.distro() + " " + after.version() + " "
                    + after.securityPosture() + " helper=" + repeated.capabilities().managedHelperProtocolVersion());
            assertTrue(context.service.listManagedApplications().isEmpty());
        }
    }
}
