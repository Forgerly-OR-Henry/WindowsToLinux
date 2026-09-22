package gold.debug.windowstolinux.web.main;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.web.db.runtime.WebStorageLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebMainTest {
    @TempDir
    Path root;

    @Test
    void classesStartWithoutArgumentsOrMasterKeyEnvironmentAndIgnoreWorkingDirectoryForDbResolution() throws Exception {
        Path original = Path.of(WebStorageLocation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path module = root.resolve("db-module"), classes = module.resolve("target/classes");
        copy(Files.isDirectory(original) ? original : original.getParent().resolve("classes"), classes);
        Files.writeString(module.resolve("pom.xml"), "<artifactId>windowstolinux-web-db</artifactId>");
        String classpath = Arrays
                .stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                        .split(java.io.File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().equals(original.normalize())
                        ? classes.toString()
                        : entry)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Path keys = root.resolve("keys");
        for (String working : List.of("unrelated-first", "unrelated-second")) {
            try (var process = WebProcessProbe.launch(List.of("-cp", classpath, WebMain.class.getName()),
                    root.resolve(working), keys)) {
                assertEquals(200, process.get("/").statusCode());
                assertEquals(200, process.get("/api/v1/servers").statusCode());
                assertTrue(Files.isRegularFile(module.resolve("data/windowstolinuxweb.db")));
                assertFalse(Files.exists(root.resolve(working).resolve("data")));
            }
        }
        try (var files = Files.list(keys)) {
            assertEquals(1, files.filter(path -> path.toString().endsWith(".key")).count());
        }
    }

    @Test
    void obsoleteInternalPathConfigurationAndPublicAddressAreRejected() {
        assertThrows(RuntimeException.class, () -> WebTestApplication.start(root,
                Map.of("w2l.storage.upload-directory", root.resolve("elsewhere").toString())));
        assertThrows(RuntimeException.class, () -> WebTestApplication.start(root, Map.of("server.address", "0.0.0.0")));
        assertFalse(Files.exists(root.resolve("data/windowstolinuxweb.db")));
    }

    static void copy(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (Path source : paths.toList()) {
                Path target = to.resolve(from.relativize(source));
                if (Files.isDirectory(source))
                    Files.createDirectories(target);
                else
                    Files.copy(source, target);
            }
        }
    }
}
