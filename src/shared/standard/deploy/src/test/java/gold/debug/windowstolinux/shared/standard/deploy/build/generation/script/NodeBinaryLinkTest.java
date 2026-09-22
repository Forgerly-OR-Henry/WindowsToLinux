package gold.debug.windowstolinux.shared.standard.deploy.build.generation.script;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Executes the production dependency normalization with real filesystem links. / 使用真实文件系统链接执行生产依赖转换。 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class NodeBinaryLinkTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesArgumentsAndTargetLocationAfterReleaseRelocation() throws Exception {
        Path source = temporaryDirectory.resolve("candidate");
        Path target = source.resolve("node_modules/package with ' quote/bin/tool");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "#!/bin/sh\nprintf '%s:%s' \"$(cat \"$(dirname -- \"$0\")/message\")\" \"$1\"\n");
        assertTrue(target.toFile().setExecutable(true));
        Files.writeString(target.resolveSibling("message"), "original-target");
        Path link = source.resolve("node_modules/.bin/tool");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, link.getParent().relativize(target));
        assertEquals(0, normalize(source));
        assertFalse(Files.isSymbolicLink(link));
        Path release = temporaryDirectory.resolve("release");
        Files.move(source, release);
        Process process = new ProcessBuilder(release.resolve("node_modules/.bin/tool").toString(),
                "argument with ' quote").redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("original-target:argument with ' quote",
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive())
                process.destroyForcibly().waitFor();
        }
    }

    @Test
    void rejectsOutsideDanglingAndCyclicBinaryLinks() throws Exception {
        for (String kind : java.util.List.of("outside", "dangling", "cycle")) {
            Path source = temporaryDirectory.resolve(kind);
            Path link = source.resolve("node_modules/.bin/tool");
            Files.createDirectories(link.getParent());
            Path destination = kind.equals("cycle") ? link : temporaryDirectory.resolve(kind + "-target");
            if (kind.equals("outside")) {
                Files.writeString(destination, "#!/bin/sh\nexit 0\n");
                assertTrue(destination.toFile().setExecutable(true));
            }
            Files.createSymbolicLink(link, destination);
            assertNotEquals(0, normalize(source), kind);
            assertTrue(Files.isSymbolicLink(link), kind);
        }
    }

    private static int normalize(Path source) throws Exception {
        Process process = new ProcessBuilder("/bin/bash", "-s").directory(source.toFile()).redirectErrorStream(true)
                .start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(("set -eu\nrun() { \"$@\"; }\n" + NodePackageBuildScript.normalizeBinaryLinks())
                        .getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            return process.exitValue();
        } finally {
            if (process.isAlive())
                process.destroyForcibly().waitFor();
        }
    }
}
