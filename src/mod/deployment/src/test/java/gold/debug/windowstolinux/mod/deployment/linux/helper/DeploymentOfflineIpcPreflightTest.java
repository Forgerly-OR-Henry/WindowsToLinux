package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentOfflineIpcPreflightTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void executesTheProductionPreflightWithControlledIpcAndDenialBoundaries() throws Exception {
        String fragment;
        try (var stream = getClass().getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/linux/helper/workspace/21-workspace-volume.sh")) {
            fragment = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        String opening = "/usr/bin/python3 -I -S -B -c '\n", closing = "\n' \"$host_network\"";
        int first = fragment.indexOf(opening), last = fragment.indexOf(closing, first + opening.length());
        assertTrue(first >= 0 && last > first && fragment.indexOf(opening, first + 1) < 0);
        var source = temporaryDirectory.resolve("production-preflight.py");
        Files.writeString(source, fragment.substring(first + opening.length(), last) + "\n", StandardCharsets.UTF_8);
        var fixture = temporaryDirectory.resolve("offline-preflight-fixture.py");
        try (var stream = getClass().getResourceAsStream("offline-preflight-fixture.py")) {
            Files.copy(stream, fixture);
        }
        var log = temporaryDirectory.resolve("fixture-output.json");
        var builder = new ProcessBuilder(List.of(System.getProperty("managed.test.python", "python"),
            "-I", "-S", "-B", fixture.toString(), source.toString()))
            .directory(temporaryDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        for (String key : List.of("TEMP", "TMP", "TMPDIR"))
            builder.environment().put(key, temporaryDirectory.toString());
        var process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Controlled offline preflight timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("\"actualProductionSource\":true"));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
