package gold.debug.windowstolinux.mod.deployment.linux.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
        assertEquals(DeploymentMirrorRuntime.cargoRuntimeSource(), data.runtime());
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
        String scratch = source.get("scratchResetSource").toString();
        assertTrue(scratch.contains("[ \"$state\" = not-found ]"));
        assertTrue(scratch.contains("assert_root_owned_directory \"$mutable\""));
        assertTrue(scratch.contains("findmnt -Rrn -M"));
        assertTrue(scratch.contains("rm -rf --one-file-system --preserve-root=all"));
        String scratchDispatch = source.get("scratchDispatchSource").toString();
        assertTrue(scratchDispatch.indexOf("reset_cargo_build_scratch") < scratchDispatch.indexOf("install -d"));
        String launch = source.get("unitLaunchSource").toString();
        assertTrue(launch.contains("/usr/bin/env -i PATH=/usr/bin:/bin"));
        assertTrue(launch.contains("cargo-prefetch \"$remaining\""));
        assertTrue(launch.indexOf("stop_build_unit") < launch.indexOf("freeze_candidate_build"));
        assertTrue(launch.contains("exit \"$status\""));
        String entry = source.get("prefetchEntrySource").toString();
        assertTrue(entry.contains(DeploymentMirrorRuntime.cargoRuntimeSha256()));
        assertTrue(entry.contains("hashlib.sha256(runtime).hexdigest() != expected_runtime"));
        assertTrue(entry.contains("mirror_transfer(policy, 'RUST_CRATE'"));
        assertFalse(entry.contains("subprocess"));
        assertFalse(entry.contains("cargo fetch"));
        assertTrue(DeploymentManagedHelperBundle.offlineBuildIsolationFacts(layout).get("propertiesSource")
            .toString().contains("PrivateNetwork=yes"));
    }

    @Test
    void completeCargoRuntimeExecutesLockTransfersAndCredentialChecks() throws Exception {
        Path temporary = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "cargo-runtime-");
        try {
            checkCargoRuntime(temporary);
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private void checkCargoRuntime(Path temporary) throws Exception {
        Path runtime = temporary.resolve("runtime.py");
        Files.writeString(runtime, DeploymentMirrorRuntime.cargoRuntimeSource(), StandardCharsets.UTF_8);
        String root = "/gold/debug/windowstolinux/mod/deployment/linux/";
        Path registry = copyResource(temporary, root + "helper/workspace/cargo-local-registry.py", "registry.py");
        Path worker = copyResource(temporary, root + "helper/workspace/cargo-prefetch.py", "prefetch.py");
        Path test = copyResource(temporary, "cargo_runtime_test.py", "cargo_runtime_test.py");
        Path log = temporary.resolve("output.log");
        String python = System.getProperty("managed.test.python", "python3");
        var builder = new ProcessBuilder(List.of(python, "-I", "-B", test.toString(), runtime.toString(),
            registry.toString(), worker.toString(), temporary.toString())).directory(temporary.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("TEMP", temporary.toString());
        builder.environment().put("TMP", temporary.toString());
        Process process = builder.start();
        try {
            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            String output = Files.readString(log);
            System.out.println(output);
            assertTrue(finished, output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Ran 3 tests"), output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            process.waitFor();
            assertFalse(process.isAlive());
        }
    }

    private Path copyResource(Path temporary, String name, String filename) throws Exception {
        Path target = temporary.resolve(filename);
        try (var stream = getClass().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Cargo runtime fixture resource absent: " + name);
            Files.copy(stream, target);
        }
        return target;
    }
}
