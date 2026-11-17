package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentBuildStartGuardTest {
    @TempDir
    Path directory;

    @Test
    void managerRestartCannotClaimTheSameBuildAgainAndNewInvocationCanResetOnlyGuardedMarker() throws Exception {
        String fragment;
        try (var input = DeploymentManagedHelperBundle.class.getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/linux/helper/workspace/22-restricted-build.sh")) {
            assertNotNull(input);
            fragment = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String prepare = fragment.substring(fragment.indexOf("prepare_build_identity()"), fragment.indexOf("stop_candidate_build()"));
        String reset = fragment.substring(fragment.indexOf("    if [ -e \"$candidate/.build-started\" ]"),
            fragment.indexOf("    if [ -e \"$candidate/.build-script\" ]"));
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        String fixture = """
            set -euo pipefail
            candidate=%s
            require_app() { :; }
            require_candidate() { :; }
            candidate_root() { printf '%%s' "$candidate"; }
            assert_workspace_volume() { :; }
            build_identity_name() { printf fixture; }
            id() { printf 1001; }
            mount() { :; }
            chown() { printf 'identity-effect\\n' >> "$candidate/effects"; }
            chmod() { :; }
            prepare_build_mirror_material() { :; }
            reject() { printf 'REJECT=%%s\\n' "$1"; exit 64; }
            assert_root_owned_regular() { [ -f "$1" ] && [ ! -L "$1" ] || reject marker-identity; }
            %s
            prepare_build_identity demo candidate
            [ "$(wc -l < "$candidate/effects")" -eq 2 ]
            if (prepare_build_identity demo candidate); then exit 90; else [ "$?" -eq 64 ]; fi
            [ "$(wc -l < "$candidate/effects")" -eq 2 ]
            %s
            prepare_build_identity demo candidate
            [ "$(wc -l < "$candidate/effects")" -eq 4 ]
            prepare_build_identity demo candidate cargo-prefetch
            [ "$(wc -l < "$candidate/effects")" -eq 6 ]
            if (prepare_build_identity demo candidate cargo-prefetch); then exit 93; else [ "$?" -eq 64 ]; fi
            [ "$(wc -l < "$candidate/effects")" -eq 6 ]
            rm -- "$candidate/.build-started"
            mkdir -- "$candidate/.build-started"
            if (prepare_build_identity demo candidate); then exit 91; else [ "$?" -eq 64 ]; fi
            if (%s); then exit 92; fi
            printf 'GUARD_PASS\\n'
            """.formatted(SafeBuildScriptEnvelope.shellQuote(directory.toString().replace('\\', '/')), prepare, reset, reset);
        Path script = directory.resolve("guard.sh");
        Files.writeString(script, fixture);
        Process process = new ProcessBuilder(bash, script.toString()).directory(directory.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("GUARD_PASS"), output);
            assertEquals(3, output.lines().filter(line -> line.equals("REJECT=build-already-started")).count());
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    void cargoRetryClearsOnlyStoppedCandidateScratchAndRejectsMalformedReplacement() throws Exception {
        String reset = DeploymentManagedHelperBundle.cargoBuildPrefetchFacts(
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()).get("scratchResetSource").toString();
        String fixture = """
            set -euo pipefail
            mutable=%s/mutable
            mkdir -- "$mutable"
            printf 'frozen-source' >"$mutable/source.tar.gz"
            mkdir -- "$mutable/source"
            printf 'previous-output' >"$mutable/source/artifact"
            assert_root_owned_directory() { [ "$1" = "$mutable" ] && [ -d "$1" ] && [ ! -L "$1" ]; }
            reject() { printf 'REJECT=%%s\\n' "$1"; exit 64; }
            systemctl() { printf '%%s\\n' "$unit_state"; }
            findmnt() { printf '%%s\\n' "$mutable"; if [ "$has_submount" = yes ]; then printf '%%s\\n' "$mutable/home/submount"; fi; }
            %s
            reset_fixture() {
                assert_cargo_build_scratch "$mutable" project-unit prefetch-unit
                reset_cargo_build_scratch "$mutable"
            }
            unit_state=not-found has_submount=no
            reset_fixture
            mkdir -p -- "$mutable/home/wtl-cargo-registry" "$mutable/tmp" "$mutable/run"
            printf 'previous-index' >"$mutable/home/wtl-cargo-registry/stale"
            printf 'previous-temp' >"$mutable/tmp/stale"
            printf 'previous-runtime' >"$mutable/run/stale"
            unit_state=loaded
            if (reset_fixture); then exit 90; fi
            unit_state=not-found has_submount=yes
            if (reset_fixture); then exit 91; fi
            has_submount=no
            test -f "$mutable/home/wtl-cargo-registry/stale"
            mv -- "$mutable/tmp" "$mutable/preserved"
            printf 'malformed' >"$mutable/tmp"
            if (reset_fixture); then exit 92; fi
            test -f "$mutable/home/wtl-cargo-registry/stale"
            test -f "$mutable/run/stale"
            test "$(cat "$mutable/preserved/stale")" = previous-temp
            rm -- "$mutable/tmp"
            mv -- "$mutable/preserved" "$mutable/tmp"
            reset_fixture
            test ! -e "$mutable/home" && test ! -e "$mutable/tmp" && test ! -e "$mutable/run"
            test "$(cat "$mutable/source.tar.gz")" = frozen-source
            test "$(cat "$mutable/source/artifact")" = previous-output
            printf 'CARGO_RETRY_RESET_PASS\\n'
            """.formatted(SafeBuildScriptEnvelope.shellQuote(directory.toString().replace('\\', '/')), reset);
        Path script = directory.resolve("cargo-retry.sh");
        Files.writeString(script, fixture);
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        Process process = new ProcessBuilder(bash, script.toString()).directory(directory.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("REJECT=cargo-scratch-unit-state"), output);
            assertTrue(output.contains("REJECT=cargo-scratch-mount"), output);
            assertTrue(output.contains("REJECT=cargo-scratch-path"), output);
            assertTrue(output.contains("CARGO_RETRY_RESET_PASS"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
