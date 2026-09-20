package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.release;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ContainerRuntimeArguments;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerProtocolContractTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporaryDirectory;

    @Test
    void firstRollbackHandlesRejectionBeforeDirectoriesExistAndRejectsConflictingContainers() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        String helper = ManagedHelperBundle.renderScript();
        String rollback = helper.substring(helper.indexOf("rollback_container_first() {"), helper.indexOf("lifecycle_container() {"));
        for (String conflict : List.of("none", "docker", "podman")) {
            String root = temporaryDirectory.resolve(conflict).toString().replace('\\', '/');
            String script = """
                    set -eu
                    require_app() { :; }; require_digest() { :; }
                    container_name() { printf 'windowstolinux-demo'; }
                    reject() { printf 'REJECT=%s\\n' "$1"; exit 64; }
                    assert_root_owned_directory() { [ -d "$1" ] && [ ! -L "$1" ] || reject directory; }
                    app_root() { printf '%s' "$test_root"; }
                    podman_quadlet_path() { printf '%s/quadlet' "$test_root"; }
                    docker() { [ "$conflict" = docker ]; }
                    podman() { [ "$conflict" = podman ]; }
                    """ + "test_root=" + gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor.quote(root)
                    + "\nconflict=" + conflict + "\n" + rollback + "\nrollback_container_first demo digest owner\n";
            Process process = new ProcessBuilder(bash, "-s").redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(conflict.equals("none") ? 0 : 64, process.exitValue(), output);
                assertTrue(output.contains(conflict.equals("none") ? "ROLLED_BACK=1" : "REJECT=current-container"), output);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void rendersDeterministicEnginePortsAndNamedVolumesOnly() {
        DeploymentRuntimeSpecification.Container runtime = new DeploymentRuntimeSpecification.Container(
                DeploymentRuntimeSpecification.ContainerEngineType.PODMAN, Map.of(9000, 9001, 8080, 8081),
                List.of(new DeploymentRuntimeSpecification.ManagedVolume("windowstolinux-demo-cache", "/cache", true),
                        new DeploymentRuntimeSpecification.ManagedVolume("windowstolinux-demo-data", "/data", false)),
                new HealthCheck.Tcp(8080, 5, 1), gold.debug.windowstolinux.shared.model.project.RuntimeIdentityMode.CONTAINER_NON_ROOT,
                new gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload(
                    gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload.ExecutionMode.DAEMON, true,
                    gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand.primary(), "", List.of(
                        new gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint("tcp", gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ProtocolType.TCP,"0.0.0.0",8080,8081,gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ExposureType.EXTERNAL,""),
                        new gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint("udp", gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ProtocolType.UDP,"0.0.0.0",9000,9001,gold.debug.windowstolinux.shared.model.project.application.ApplicationEndpoint.ExposureType.EXTERNAL,"")),
                    java.util.Optional.empty(),"",java.util.Optional.empty(),List.of(),List.of()));

        assertEquals(List.of("podman", "2", "tcp", "0.0.0.0", "8080", "8081", "udp", "0.0.0.0", "9000", "9001", "2",
                "windowstolinux-demo-cache", "/cache", "1", "windowstolinux-demo-data", "/data", "0"),
                ContainerRuntimeArguments.from(runtime));
    }

    @Test
    void helperHasDedicatedDockerAndQuadletPathsWithoutPrivilegedEscapes() {
        String helper = ManagedHelperBundle.renderScript();

        assertTrue(helper.contains("publish-container) publish_container"));
        assertTrue(helper.contains("snapshot-container) snapshot_container"));
        assertTrue(helper.contains("observe-deployment) observe_deployment"));
        assertTrue(helper.contains("save_container_parameters"));
        assertTrue(helper.contains("load_container_parameters"));
        assertTrue(helper.contains("save_deployment_parameters"));
        assertTrue(helper.contains("load_deployment_parameters"));
        assertTrue(helper.contains("printf 'HELPER=1\\nPROTOCOL=%s\\n'"));
        String shell = helper.substring(0,helper.indexOf("native_database()"))
                + helper.substring(helper.indexOf("\nWTL_NATIVE_DB_PY\n}")+"\nWTL_NATIVE_DB_PY\n}".length());
        assertFalse(shell.contains("\\\\n"));
        java.util.regex.Matcher templates = java.util.regex.Pattern.compile("'\\{\\{[^\\r\\n]*?}}'").matcher(shell);
        while (templates.find()) assertFalse(templates.group().contains("\\\""), templates.group());
        assertTrue(helper.contains("Volume=%s:%s"));
        assertTrue(helper.contains("update --restart unless-stopped"));
        assertTrue(helper.contains("set_podman_quadlet_autostart"));
        assertTrue(helper.contains("WantedBy=multi-user.target"));
        assertTrue(helper.contains("podman-cni-forward) podman_cni_forward"));
        assertTrue(helper.contains("ExecStartPost=/usr/local/lib/windowstolinux/managed-helper podman-cni-forward"));
        assertTrue(helper.contains("CNI-ADMIN"));
        assertTrue(helper.contains("iptables -w -I CNI-ADMIN"));
        assertTrue(helper.contains("podman-cni-forward|podman-cni-clear) ;;"));
        assertFalse(helper.contains("systemctl enable \"windowstolinux-$app.service\""));
        assertFalse(helper.contains("systemctl disable \"windowstolinux-$app.service\""));
        assertFalse(helper.contains("--privileged"));
        assertFalse(helper.contains("/var/run/docker.sock"));
        assertFalse(helper.contains("--pid=host"));
    }
}
