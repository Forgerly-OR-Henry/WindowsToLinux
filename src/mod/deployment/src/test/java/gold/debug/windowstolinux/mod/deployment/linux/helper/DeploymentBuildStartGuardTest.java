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
            rm -- "$candidate/.build-started"
            mkdir -- "$candidate/.build-started"
            if (prepare_build_identity demo candidate); then exit 91; else [ "$?" -eq 64 ]; fi
            if (%s); then exit 92; fi
            printf 'GUARD_PASS\\n'
            """.formatted(SafeBuildScriptEnvelope.shellQuote(directory.toString().replace('\\', '/')), prepare, reset, reset);
        Path script = directory.resolve("guard.sh");
        Files.writeString(script, fixture);
        Process process = new ProcessBuilder(bash, script.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("GUARD_PASS"), output);
        assertEquals(2, output.lines().filter(line -> line.equals("REJECT=build-already-started")).count());
    }
}
