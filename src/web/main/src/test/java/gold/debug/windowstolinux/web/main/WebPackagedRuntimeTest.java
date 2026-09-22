package gold.debug.windowstolinux.web.main;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import java.util.jar.*;

import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebPackagedRuntimeTest {
    @TempDir
    Path root;

    @Test
    void externalDbJarStartsRestartsAndRelocatesWithYamlOnly() throws Exception {
        Path distribution = Path.of("target/web").toAbsolutePath();
        assertTrue(Files.isRegularFile(distribution.resolve("web.jar")));
        assertFalse(Files.exists(distribution.resolve("lib/data")), "Distribution build must contain no runtime data");
        Path first = root.resolve("first/web"), keys = root.resolve("keys");
        WebMainTest.copy(distribution, first);
        String id;
        try (var process = WebProcessProbe.launch(List.of("-jar", first.resolve("web.jar").toString()),
                root.resolve("unrelated-one"), keys)) {
            assertEquals(200, process.get("/").statusCode());
            var saved = process.json("POST", "/api/v1/servers",
                    "{\"name\":\"Packaged\",\"host\":\"test.invalid\",\"port\":22,\"username\":\"root\",\"password\":\"synthetic-only\"}");
            assertEquals(201, saved.statusCode(), saved.body());
            id = WebJsonCodec.read(saved.body()).path("id").asText();
            var update = process.json("PUT", "/api/v1/servers/" + id,
                    "{\"name\":\"Updated\",\"host\":\"test.invalid\",\"port\":22,\"username\":\"root\",\"version\":1}");
            assertEquals(200, update.statusCode(), update.body());
            assertEquals(2, WebJsonCodec.read(update.body()).path("version").asLong());
            assertTrue(Files.isRegularFile(first.resolve("lib/data/windowstolinuxweb.db")));
            assertFalse(Files.exists(root.resolve("unrelated-one/data")));
        }
        Path moved = root.resolve("relocated");
        moveStoppedDistribution(first, moved);
        renameDbJar(moved);
        for (String working : List.of("unrelated-two", "unrelated-three")) {
            try (var process = WebProcessProbe.launch(List.of("-jar", moved.resolve("web.jar").toString()),
                    root.resolve(working), keys)) {
                var servers = WebJsonCodec.read(process.get("/api/v1/servers").body());
                assertEquals(id, servers.get(0).path("id").asText());
                assertEquals("Updated", servers.get(0).path("name").asText());
                assertTrue(Files.isDirectory(moved.resolve("lib/data/files")));
                assertTrue(Files.isDirectory(moved.resolve("lib/data/backups")));
            }
        }
        try (var files = Files.list(keys)) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".key")).count());
        }
    }

    private static void moveStoppedDistribution(Path source, Path destination) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                Files.move(source, destination);
                return;
            } catch (AccessDeniedException failure) {
                // Windows can briefly deny this move even after the child has exited; persistent denial still fails. / 子进程退出后 Windows 仍可能暂拒目录移动；持续拒绝仍使测试失败。
                if (!System.getProperty("os.name").startsWith("Windows") || System.nanoTime() >= deadline)
                    throw failure;
                Thread.sleep(100);
            }
        }
    }

    private static void renameDbJar(Path distribution) throws Exception {
        Path old;
        try (var files = Files.list(distribution.resolve("lib"))) {
            old = files.filter(path -> path.getFileName().toString().startsWith("windowstolinux-web-db-")).findFirst()
                    .orElseThrow();
        }
        Path renamed = old.resolveSibling("windowstolinux-web-db-alternate-version.jar");
        Files.move(old, renamed);
        Path launcher = distribution.resolve("web.jar"), updated = distribution.resolve("updated.jar");
        try (var original = new JarFile(launcher.toFile())) {
            var manifest = original.getManifest();
            var attributes = manifest.getMainAttributes();
            attributes.putValue("Class-Path", attributes.getValue("Class-Path").replace(old.getFileName().toString(),
                    renamed.getFileName().toString()));
            try (var output = new JarOutputStream(Files.newOutputStream(updated), manifest)) {
                for (var entry : original.stream().toList()) {
                    if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF"))
                        continue;
                    output.putNextEntry(new JarEntry(entry.getName()));
                    if (!entry.isDirectory())
                        try (var input = original.getInputStream(entry)) {
                            input.transferTo(output);
                        }
                    output.closeEntry();
                }
            }
        }
        Files.move(updated, launcher, StandardCopyOption.REPLACE_EXISTING);
    }
}
