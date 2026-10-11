package gold.debug.windowstolinux.mod.deployment.service.environment;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentEnvironmentDirectoryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void environmentStagesUseFrozenRootsAndCheckHostBeforeEffects(boolean custom) throws Exception {
        var layout = custom ? new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/srv/wtl.helper",
            "/srv/wtl.apps", "/srv/wtl.state", "/srv/wtl.config", "/srv/wtl.data")) : DeploymentRemoteLayout.original();
        var target = new RemoteTarget(new ServerId("test"), UUID.randomUUID(), "127.0.0.1", 22, "root",
            Optional.of("SHA256:" + "B".repeat(43)));
        var state = new DeploymentEnvironmentState(1, target, UUID.randomUUID(), DeploymentEnvironmentScripts.bodyDigest(layout),
            "INTENT", "none", Optional.empty(), layout);
        var observed = new DeploymentEnvironmentObservation("ubuntu-24.04", UUID.randomUUID(), "none", "none",
            "none", "NOT_APPLICABLE", "ABSENT", "none", "none", "none", "none");
        assertEquals(layout, state.next("OBSERVED", "none", observed).layout());
        assertTrue(DeploymentEnvironmentScripts.body(layout).contains(layout.environmentOperationsRoot()));
        assertTrue(DeploymentEnvironmentScripts.body(layout).contains("/var/lib/windowstolinux/system-preparation/selinux"));
        if (custom) assertNotEquals(DeploymentEnvironmentScripts.bodyDigest(), state.bodySha());
        for (String stage : List.of("probe", "verify", "packages", "prepare", "enforce", "commit")) {
            try (var request = DeploymentEnvironmentScripts.request(state, stage, DeploymentConfig.Environment.original())) {
                String script = new String(request.stdin(), StandardCharsets.UTF_8);
                assertFalse(script.contains("@wtl:"));
                assertFalse(script.contains("@STATE_ANCESTORS@"));
                assertTrue(script.startsWith("request_sha='" + DeploymentEnvironmentScripts.requestDigest(state, stage) + "'"));
                if (List.of("probe", "verify").contains(stage)) assertFalse(script.contains("host-layout-conflict"));
                else assertTrue(script.indexOf("host-layout-conflict") < script.lastIndexOf("\nenvironment\n"));
                syntax(script);
            }
        }
    }

    private static void syntax(String script) throws Exception {
        var process = new ProcessBuilder(System.getProperty("managed.test.bash", "/bin/bash"),
            "--noprofile", "--norc", "-n", "-s").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) { input.write(script.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        }
    }
}
