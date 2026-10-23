package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentHelperBehaviorTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"test_native_database_behavior.py", "test_managed_storage_behavior.py",
        "test_managed_lifecycle_behavior.py", "test_toolchain_*_behavior.py", "test_release_retention_behavior.py"})
    void currentHelperFunctionsKeepTheirFilesystemAndCommandContracts(String suite) throws Exception {
        Path workspace = Path.of("").toAbsolutePath();
        while (workspace != null && !Files.isDirectory(workspace.resolve("src/mod/deployment/src/test/python")))
            workspace = workspace.getParent();
        assertNotNull(workspace, "The current helper fixture sources must be available");
        Path directory = workspace.resolve("src/mod/deployment/src/test/python");
        Path output = temporary.resolve("helper-output.log");
        var command = new ProcessBuilder(System.getProperty("managed.test.python", "python"), "-m", "unittest",
            "discover", "-s", directory.toString(), "-p", suite, "-v");
        command.directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        command.environment().put("MANAGED_TEST_BASH", System.getProperty("managed.test.bash", "bash"));
        command.environment().put("TEMP", temporary.toAbsolutePath().toString());
        command.environment().put("TMP", temporary.toAbsolutePath().toString());
        command.environment().put("PYTHONUTF8", "1");
        Process process = command.start();
        try {
            assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Current helper behavior suite timed out: " + suite);
            String diagnostic = Files.readString(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), diagnostic);
            assertTrue(java.util.regex.Pattern.compile("(?m)^Ran [1-9][0-9]* tests? in .+$").matcher(diagnostic).find(),
                "The current behavior suite must execute at least one test: " + diagnostic);
            assertTrue(diagnostic.contains("\nOK"), diagnostic);
        } finally {
            if (process.isAlive()) {
                var children = process.descendants().toList();
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned Python validation process must exit");
                for (var child : children)
                    child.onExit().get(5, TimeUnit.SECONDS);
            }
        }
    }
}
