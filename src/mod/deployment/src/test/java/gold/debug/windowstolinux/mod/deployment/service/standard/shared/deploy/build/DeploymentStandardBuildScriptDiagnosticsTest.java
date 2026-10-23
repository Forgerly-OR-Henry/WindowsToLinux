package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class DeploymentStandardBuildScriptDiagnosticsTest {
    @Test
    void reportsSilentFailureLineAndPreservesExitWithoutDisclosingCommandOrEnvironment() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        String script = DeploymentStandardBuildRunner.failureDiagnostics()
            + "export PRIVATE_TEST_VALUE=protected-fixture-value\ntest -f /nonexistent-wtl-fixture-file\nprintf 'SHOULD_NOT_RUN\\n'\n";
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, process.exitValue(), output);
            assertEquals("BUILD_FAILURE_LINE=7\n", output);
            assertFalse(output.contains("protected-fixture-value") || output.contains("SHOULD_NOT_RUN"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    void reportsExplicitRejectionInsideAFunctionAndLeavesSuccessfulExitSilent() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        for (boolean rejected : java.util.List.of(true, false)) {
            String script = DeploymentStandardBuildRunner.failureDiagnostics()
                + "export PRIVATE_TEST_VALUE=protected-fixture-value\n"
                + (rejected ? "reject_fixture() { exit 64; }\nreject_fixture\nprintf 'SHOULD_NOT_RUN\\n'\n" : "exit 0\n");
            var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(rejected ? 64 : 0, process.exitValue(), output);
                assertEquals(rejected ? "BUILD_FAILURE_LINE=7\n" : "", output);
                assertFalse(output.contains("protected-fixture-value") || output.contains("SHOULD_NOT_RUN"), output);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
    }
}
