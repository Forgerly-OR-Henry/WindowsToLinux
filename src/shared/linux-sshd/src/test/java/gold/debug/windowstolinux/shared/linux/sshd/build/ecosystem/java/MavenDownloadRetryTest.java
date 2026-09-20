package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MavenDownloadRetryTest {
    @TempDir Path root;

    @Test
    void retriesInterruptedArtifactTransfersWithinTheSameCandidateOnly() throws Exception {
        check("transient", 0, 2, true);
        check("persistent", 17, 2, true);
        check("compile", 23, 1, false);
    }

    private void check(String mode, int exit, int attempts, boolean retry) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assumeTrue(Files.isExecutable(Path.of(bash)), "Bash required for actual retry execution");
        Path directory = Files.createDirectory(root.resolve(mode));
        Path log = directory.resolve("output.txt");
        String script = "set -euo pipefail\nmutable=\"$PWD\"\n" + MavenBuildRenderer.downloadRetry() + """
                fake_maven() {
                  printf 'attempt\\n' >> attempts
                  if [ "$MODE" = compile ]; then printf '[ERROR] Compilation failure\\n'; return 23; fi
                  if [ "$MODE" = transient ] && [ "$(wc -l < attempts)" -eq 2 ]; then printf 'BUILD SUCCESS\\n'; return 0; fi
                  printf '[ERROR] Could not transfer artifact sqlite-jdbc: Premature end of Content-Length delimited message body\\n'
                  return 17
                }
                maven_package fake_maven
                """;
        var builder = new ProcessBuilder(bash, "-s").directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("MODE", mode);
        Process process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertEquals(exit, process.exitValue(), output);
            assertEquals(attempts, Files.readAllLines(directory.resolve("attempts")).size(), output);
            assertEquals(retry, output.contains("BUILD_DOWNLOAD_RETRY="), output);
            assertFalse(Files.exists(directory.resolve("maven-download-attempt.log")));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        }
    }
}
