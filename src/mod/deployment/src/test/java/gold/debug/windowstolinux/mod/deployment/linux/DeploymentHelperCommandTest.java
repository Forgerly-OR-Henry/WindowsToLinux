package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentHelperCommandTest {
    @Test
    void acceptsOnlyTheCompleteCurrentGuard() {
        String invocation = "'" + DeploymentManagedHelperBundle.PATH + "' build-preflight 'ordinary'";
        String command = DeploymentManagedHelperBundle.verifiedCommand(invocation);
        assertEquals(invocation, DeploymentManagedHelperBundle.verifiedBody(command));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedBody(invocation));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedBody(command.replace(DeploymentManagedHelperBundle.EXPECTED_SHA256, "a".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedCommand(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "changed", "owner", "writable", "unavailable"})
    void actualBashRefusesChangedHelperBeforeAnyExecution(String scenario, @TempDir Path directory) throws Exception {
        Path bash = Path.of(System.getProperty("managed.test.bash", "E:/Program/Git/bin/bash.exe"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(bash));
        String root = "/" + Character.toLowerCase(directory.toString().charAt(0)) + directory.toString().substring(2).replace('\\', '/');
        Files.createDirectory(directory.resolve("owned"));
        String body = "#!/bin/bash\nbuiltin printf 'executed\\n' >> '" + root + "/effects'\n";
        Files.writeString(directory.resolve("owned/managed-helper"), scenario.equals("changed") ? body + "# replaced\n" : body);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(directory.resolve("stat-fixture.sh"), "#!/bin/bash\nbuiltin printf '" + (scenario.equals("owner") ? "1000:1000" : "0:0") + "\\n'\n");
        Files.writeString(directory.resolve("find-fixture.sh"), "#!/bin/bash\n" + (scenario.equals("writable") ? "builtin printf 'writable\\n'\n" : scenario.equals("unavailable") ? "exit 1\n" : ":\n"));
        // Windows emulates Unix ownership/permission metadata; the hash and Bash execution are real.
        String command = DeploymentManagedHelperBundle.verifiedCommand("'" + DeploymentManagedHelperBundle.PATH + "' probe")
            .replace("for wtl_helper_directory in / /usr /usr/local /usr/local/lib " + DeploymentManagedHelperBundle.DIRECTORY + "; do",
                "for wtl_helper_directory in '" + root + "/owned'; do")
            .replace(DeploymentManagedHelperBundle.DIRECTORY, root + "/owned")
            .replace(DeploymentManagedHelperBundle.EXPECTED_SHA256, digest)
            .replace("/usr/bin/stat", "/bin/bash '" + root + "/stat-fixture.sh'")
            .replace("/usr/bin/find", "/bin/bash '" + root + "/find-fixture.sh'");
        Path script = directory.resolve("command.sh");
        Files.writeString(script, "/usr/bin/chmod 700 -- '" + root + "/owned/managed-helper'\n" + command);
        Path output = directory.resolve("output");
        Process process = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", script.toString())
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Local guard execution timed out");
            assertEquals(scenario.equals("valid") ? 0 : 64, process.exitValue(), Files.readString(output));
            if (scenario.equals("valid")) assertEquals("executed\n", Files.readString(directory.resolve("effects")));
            else assertFalse(Files.exists(directory.resolve("effects")), "Rejected helper bytes must never execute");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
