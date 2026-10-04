package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.config.ConfigLoader;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.main.AppMain;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlatformEntryProcessTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualEntryUsesIsolatedClassAndJarAnchorsAndExitsThroughWindowClose(boolean jarMode) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var program = Files.createDirectories(root.resolve("程序目录 with spaces"));
        var mainOrigin = Path.of(AppMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        var dbOrigin = Path.of(PlatformDatabase.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        var configOrigin = Path.of(ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        Path mainOutput;
        Path dbOutput;
        Path data;
        if (jarMode) {
            mainOutput = program.resolve("main.jar");
            dbOutput = Files.createDirectories(program.resolve("separate db module")).resolve("db.jar");
            archive(mainOrigin, mainOutput);
            archive(dbOrigin, dbOutput);
            data = program.resolve("data");
        } else {
            Files.writeString(program.resolve("pom.xml"), "<project/>");
            var mainModule = Files.createDirectories(program.resolve("src/app/main"));
            var dbModule = Files.createDirectories(program.resolve("src/app/db"));
            Files.writeString(mainModule.resolve("pom.xml"),
                "<project><artifactId>windowstolinux-app-main</artifactId></project>");
            Files.writeString(dbModule.resolve("pom.xml"),
                "<project><artifactId>windowstolinux-app-db</artifactId></project>");
            mainOutput = mainModule.resolve("target/classes");
            dbOutput = dbModule.resolve("target/classes");
            copy(mainOrigin, mainOutput);
            copy(dbOrigin, dbOutput);
            data = dbModule.resolve("data");
        }
        var yaml = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        com.fasterxml.jackson.databind.JsonNode configuration;
        try (var input = ConfigLoader.class.getResourceAsStream(ConfigLoader.RESOURCE)) {
            assertNotNull(input);
            configuration = yaml.readTree(input);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) configuration.path("common").path("database"))
            .put("data-directory", "data");
        ((com.fasterxml.jackson.databind.node.ObjectNode) configuration.path("class").path("database"))
            .put("data-directory", "src/app/db/data");
        var configClasses = jarMode ? root.resolve("config-classes")
            : program.resolve("src/app/config/target/classes");
        copy(configOrigin, configClasses);
        Files.writeString(configClasses.resolve(ConfigLoader.RESOURCE.substring(1)), yaml.writeValueAsString(configuration));
        var configOutput = jarMode ? program.resolve("config.jar") : configClasses;
        if (jarMode) archive(configClasses, configOutput);
        var classpath = new ArrayList<String>();
        boolean replacedMain = false;
        boolean replacedDb = false;
        boolean replacedConfig = false;
        for (String value : System.getProperty("java.class.path")
            .split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path entry = Path.of(value).toAbsolutePath().normalize();
            if (entry.equals(mainOrigin)) {
                entry = mainOutput;
                replacedMain = true;
            }
            if (entry.equals(dbOrigin)) {
                entry = dbOutput;
                replacedDb = true;
            }
            if (entry.equals(configOrigin)) {
                entry = configOutput;
                replacedConfig = true;
            }
            classpath.add(entry.toString());
        }
        assertTrue(replacedMain && replacedDb && replacedConfig);
        var unrelated = Files.createDirectories(root.resolve("unrelated working directory/mod"));
        Files.writeString(unrelated.resolve("config.yaml"), "invalid unrelated configuration");
        var output = root.resolve(jarMode ? "jar-entry.log" : "class-entry.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-Djava.io.tmpdir=" + root, "-cp", String.join(File.pathSeparator, classpath),
            EntryHarness.class.getName()).directory(unrelated.toFile()).redirectErrorStream(true)
            .redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "AppMain did not exit after the window close action");
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertTrue(Files.readString(output).contains("APP_MAIN_CLOSED"));
            assertTrue(Files.isRegularFile(data.resolve("windowstolinux.mv.db")));
            assertTrue(Files.isRegularFile(data.resolve("db-access.bin")));
            assertFalse(Files.exists(unrelated.resolve("data")));
            assertFalse(Files.exists(data.resolve("windowstolinux.db")));
            assertFalse(Files.exists(program.resolve("config.yaml")));
            if (jarMode) assertFalse(Files.exists(dbOutput.getParent().resolve("data")));
            if (jarMode) {
                Files.delete(mainOutput);
                Files.delete(dbOutput);
                Files.delete(configOutput);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Owned entry process did not exit");
            }
        }
    }

    static void copy(Path origin, Path target) throws Exception {
        if (Files.isRegularFile(origin)) {
            try (var archive = FileSystems.newFileSystem(origin)) {
                copy(archive.getPath("/"), target);
            }
            return;
        }
        try (var paths = Files.walk(origin)) {
            for (Path path : paths.toList()) {
                var destination = target.resolve(origin.relativize(path).toString());
                if (Files.isDirectory(path))
                    Files.createDirectories(destination);
                else
                    Files.copy(path, destination);
            }
        }
    }

    static void archive(Path origin, Path output) throws Exception {
        if (Files.isRegularFile(origin)) {
            Files.copy(origin, output);
            return;
        }
        try (var archive = new JarOutputStream(Files.newOutputStream(output)); var paths = Files.walk(origin)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                archive.putNextEntry(new JarEntry(origin.relativize(path).toString().replace('\\', '/')));
                Files.copy(path, archive);
                archive.closeEntry();
            }
        }
    }

    public static final class EntryHarness {
        public static void main(String[] args) throws Exception {
            AppMain.main(args);
            var frame = new CompletableFuture<PlatformFrame>();
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows())
                    if (window instanceof PlatformFrame platform && window.isShowing())
                        frame.complete(platform);
                if (!frame.isDone())
                    frame.completeExceptionally(new AssertionError("AppMain did not show the platform"));
            });
            var opened = frame.get(10, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> assertTrue(PlatformWindowTest.descendants(opened)
                .anyMatch(component -> "platform.transfers".equals(component.getName()))));
            SwingUtilities.invokeAndWait(() -> assertTrue(PlatformWindowTest.descendants(opened)
                .anyMatch(component -> "platform.tasks".equals(component.getName()))));
            SwingUtilities
                .invokeAndWait(() -> opened.dispatchEvent(new WindowEvent(opened, WindowEvent.WINDOW_CLOSING)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                var visible = new FutureTask<>(opened::isDisplayable);
                SwingUtilities.invokeAndWait(visible);
                if (!visible.get()) {
                    System.out.println("APP_MAIN_CLOSED");
                    return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("Platform resources prevented normal close");
        }
    }
}
