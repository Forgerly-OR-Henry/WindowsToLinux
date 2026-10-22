package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;

/** Real platform tasks and H2 across JVMs; scoped to controller recovery, not Agent build/publication acceptance. */
class DeploymentAgentControllerRecoveryRestartTest extends DeploymentRuntimeFixture {
    @ParameterizedTest
    @CsvSource({"PREPARE,TRACKED", "BIND,TRACKED", "PREPARE,CLEANUP_LOST", "PREPARE,FAILED"})
    void originalControllerEvidenceSurvivesBootAndOnlyItsOwnedEffectsCanSettle(String boundary, String scenario) throws Exception {
        scenario(boundary, scenario);
    }

    @ParameterizedTest
    @CsvSource({"PREPARE,LEGACY", "BIND,FOREIGN"})
    void legacyProofAndForeignFingerprintRemainBlockedAcrossBoot(String boundary, String scenario) throws Exception {
        scenario(boundary, scenario);
    }

    private void scenario(String boundary, String scenario) throws Exception {
        worker("produce", boundary, scenario);
        worker("recover", boundary, scenario);
        if (scenario.equals("CLEANUP_LOST")) worker("recover-again", boundary, scenario);
        if (!scenario.equals("LEGACY") && !scenario.equals("FOREIGN")) worker("admit", boundary, scenario);
    }

    private void worker(String phase, String boundary, String scenario) throws Exception {
        Path output = root.resolve(phase + ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Djdk.net.unixdomain.tmpdir=" + root, "-cp", System.getProperty("java.class.path"),
            DeploymentAgentControllerRecoveryWorker.class.getName(), phase, root.toString(), boundary, scenario)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Controller worker timed out: " + phase);
            String diagnostic = Files.readString(output);
            assertEquals(0, process.exitValue(), diagnostic);
            assertTrue(diagnostic.contains("CONTROLLER_" + phase.toUpperCase(java.util.Locale.ROOT) + "_VERIFIED"), diagnostic);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
