package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;

class DeploymentPythonPreparationSourceTest {
    @Test
    void selectedPythonPreparationShowsTheActualIsolatedWorkerAndLegacyReceiverGuard() throws Exception {
        String helper = DeploymentManagedHelperBundle.renderScript();
        String selected = PreparationSourceRange.helper(helper, "PYTHON", true).stream()
            .map(range -> helper.substring(range.offset(), range.next()))
            .collect(Collectors.joining());

        for (String marker : new String[] {"Python source installation requires the isolated worker",
                "install = install_with_python_isolation", "probe = probe_with_python_isolation",
                "DynamicUser=yes", "PrivateNetwork=yes", "NoNewPrivileges=yes", "BindReadOnlyPaths=",
                "require_isolated(network, mount)", "require_staging_mount(prefix, device, inode)",
                "--with-ensurepip=install", "command(\"/usr/bin/make\", \"install\"", "sys.base_prefix",
                "subprocess.Popen((\"/usr/bin/make\", \"-j2\")", "stream.readline(4097)",
                "PYTHON_MAKE_FAILURES = {", "output-linker-error",
                "ruby_require_settled(unit)", "ruby_publish(stage, destination, executable='python3')",
                "tracked_atomic_json(destination / '.w2l-toolchain.json', record, 0o444)"})
            assertTrue(selected.contains(marker), marker);
        assertFalse(selected.contains("run([str(source / 'configure')"));
        assertFalse(DeploymentManagedHelperBundle.renderPublicationRecovery(DeploymentRemoteLayout.original())
            .contains("install_with_python_isolation"));
    }
}
