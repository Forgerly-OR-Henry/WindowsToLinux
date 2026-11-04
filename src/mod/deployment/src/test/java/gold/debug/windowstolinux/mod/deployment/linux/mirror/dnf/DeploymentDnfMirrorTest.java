package gold.debug.windowstolinux.mod.deployment.linux.mirror.dnf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeploymentDnfMirrorTest {
    @Test void executesChecksumBoundDnfAdapterAgainstLocalRepositoriesAndNativeProcessFixture() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve(".ai-workspace"))) root = root.getParent();
        if (root == null) throw new IOException("Project task workspace unavailable");
        Path directory = Files.createDirectories(root.resolve(".ai-workspace/tmp/deployment-mirrors-20261104/dnf/junit-" + UUID.randomUUID()));
        try {
            var runtime = new StringBuilder();
            String resources = "/gold/debug/windowstolinux/mod/deployment/linux/mirror/";
            for (String file : List.of("10-transfer.py", "20-dependencies.py", "36-dnf.py")) {
                try (var input = getClass().getResourceAsStream(resources + file)) {
                    if (input == null) throw new IOException("DNF runtime resource missing: " + file);
                    runtime.append(new String(input.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
            Path source = directory.resolve("runtime.py");
            Files.writeString(source, runtime);
            Path test = directory.resolve("dnf_test.py");
            try (var input = getClass().getResourceAsStream("dnf_test.py")) {
                if (input == null) throw new IOException("DNF verification resource missing");
                Files.copy(input, test);
            }
            var command = new ArrayList<String>();
            if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("py", "-3"));
            else command.add("python3");
            command.addAll(List.of(test.toString(), source.toString()));
            Path log = directory.resolve("process.log");
            var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("TEMP", directory.toString());
            builder.environment().put("TMP", directory.toString());
            builder.environment().put("TMPDIR", directory.toString());
            Process process = builder.start();
            try {
                boolean finished = process.waitFor(60, TimeUnit.SECONDS);
                String output = Files.readString(log, StandardCharsets.UTF_8);
                assertTrue(finished, "DNF fixture exceeded bounded wait\n" + output);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("OK"), output);
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                if (process.isAlive()) process.destroyForcibly();
                process.waitFor();
            }
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
