package gold.debug.windowstolinux.mod.deployment.linux.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;
import org.junit.jupiter.api.Test;

class DeploymentCargoBuildInputTest {
    @Test
    void separatesUtf8DataFromTheOriginalOfflineShellAndRejectsAlteredRuntime() {
        var task = UUID.randomUUID();
        String digest = "a".repeat(64);
        String lock = "组件/cli/Cargo.lock";
        String build = "printf '构建\\n'\n";
        String input = DeploymentCargoBuildInput.render(build, digest, lock, "", task);
        var phases = DeploymentCargoBuildInput.phases(input).orElseThrow();
        assertEquals(phases.header() + phases.prefetch() + build, input);
        assertEquals(DeploymentCargoBuildInput.HEADER + phases.prefetch().getBytes(StandardCharsets.UTF_8).length + "\n",
            phases.header());
        assertEquals(build, phases.build());
        var data = DeploymentCargoBuildInput.prefetch(phases, task);
        assertEquals(task, data.task());
        assertEquals(digest, data.archiveSha256());
        assertEquals(lock, data.lockMember());
        assertEquals("", data.policy());
        assertEquals(DeploymentMirrorRuntime.runtimeSource(), data.runtime());
        assertFalse(phases.prefetch().contains(build));
        var confused = new DeploymentCargoBuildInput.Phases(phases.header(),
            phases.prefetch().replace("class MirrorFailure", "class MirrorRefusal"), build);
        assertThrows(IllegalArgumentException.class, () -> DeploymentCargoBuildInput.prefetch(confused, task));
        assertThrows(IllegalArgumentException.class, () -> DeploymentCargoBuildInput.prefetch(phases, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentCargoBuildInput.phases(
            DeploymentCargoBuildInput.HEADER + "1\n中文\n" + build));
    }

    @Test
    void exactPinnedSourcesShowDataOnlyPrefetchBeforeEnforcedOfflineBuild() {
        var layout = DeploymentRemoteLayout.original();
        var source = DeploymentManagedHelperBundle.cargoBuildPrefetchFacts(layout);
        assertEquals(DeploymentManagedHelperBundle.buildEntrySha256(layout), source.get("buildEntrySha256"));
        String dispatch = source.get("phaseDispatchSource").toString();
        assertTrue(dispatch.contains("[ \"$engine\" = ordinary-offline ]"));
        String launch = source.get("unitLaunchSource").toString();
        assertTrue(launch.contains("/usr/bin/env -i PATH=/usr/bin:/bin"));
        assertTrue(launch.contains("cargo-prefetch \"$remaining\""));
        assertTrue(launch.indexOf("stop_build_unit") < launch.indexOf("freeze_candidate_build"));
        assertTrue(launch.contains("exit \"$status\""));
        String entry = source.get("prefetchEntrySource").toString();
        assertTrue(entry.contains(DeploymentMirrorRuntime.runtimeSha256()));
        assertTrue(entry.contains("hashlib.sha256(runtime).hexdigest() != expected_runtime"));
        assertTrue(entry.contains("mirror_transfer(policy, 'RUST_CRATE'"));
        assertFalse(entry.contains("subprocess"));
        assertFalse(entry.contains("cargo fetch"));
        assertTrue(DeploymentManagedHelperBundle.offlineBuildIsolationFacts(layout).get("propertiesSource")
            .toString().contains("PrivateNetwork=yes"));
    }
}
