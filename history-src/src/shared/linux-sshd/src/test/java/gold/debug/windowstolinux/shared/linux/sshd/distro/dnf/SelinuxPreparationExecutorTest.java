package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelinuxPreparationExecutorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedPreparationScriptUsesLinuxLineEndingsAndParsesWithoutExecution() throws Exception {
        String script = SelinuxPreparationExecutor.loadScript();
        assertFalse(script.contains("\r"), "Windows checkouts must not send CRLF to Bash");
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assumeTrue(Files.isExecutable(Path.of(bash)), "Bash is required for syntax validation");
        Path errors = temporaryDirectory.resolve("bash-errors.txt");
        Process process = new ProcessBuilder(bash, "-n").redirectErrorStream(true).redirectOutput(errors.toFile())
                .start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return Files.readString(errors);
                } catch (Exception failure) {
                    return failure.toString();
                }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }
}
