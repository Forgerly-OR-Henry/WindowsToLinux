package gold.debug.windowstolinux.mod.deployment.linux.mirror.locked;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.mod.deployment.linux.mirror.DeploymentMirrorRuntime;

final class LockedRepositoryTransportTest {
    @Test
    void fixedLockTransportAndScopedCredentialsWorkWithTwoLocalHttpsRepositories() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve(".ai-workspace"))) root = root.getParent();
        if (root == null) throw new IOException("Project workspace unavailable");
        Path directory = Files.createDirectories(root.resolve(".ai-workspace/tmp/deployment-mirrors-20261104/locked-transport/j-" + UUID.randomUUID().toString().substring(0, 8)));
        Process process = null;
        try {
            Path runtime = directory.resolve("runtime.py"), test = directory.resolve("test.py"), output = directory.resolve("output.log");
            Files.writeString(runtime, DeploymentMirrorRuntime.runtimeSource(), StandardCharsets.UTF_8);
            try (var input = getClass().getResourceAsStream("locked_transport_test.py")) {
                if (input == null) throw new IOException("Locked transport test resource absent");
                Files.copy(input, test);
            }
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            var command = windows ? List.of("py", "-3", test.toString(), runtime.toString(), "E:\\Program\\Git\\mingw64\\bin\\openssl.exe")
                : List.of("python3", test.toString(), runtime.toString(), "/usr/bin/openssl");
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            for (String key : List.of("TEMP", "TMP", "TMPDIR")) builder.environment().put(key, directory.toString());
            process = builder.start();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Local HTTPS transport proof timed out");
            String result = Files.readString(output);
            assertEquals(0, process.exitValue(), result);
            assertTrue(result.contains("Ran 7 tests"), result);
            assertTrue(result.contains("OK"), result);
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                process.waitFor();
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
