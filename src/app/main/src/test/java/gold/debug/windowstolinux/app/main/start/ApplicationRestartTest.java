package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.app.config.ConfigLoader;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.main.AppMain;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.app.windows.security.CurrentUserProtection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationRestartTest {
    @TempDir
    Path root;

    @Test
    void capturesOnlyExplicitInputsForClassJarAndApplicationModes() throws Exception {
        Path javaHome = javaHome();
        Path home = workspace();
        Path classes = Files.createDirectories(home.resolve("src/app/main/target/classes"));
        Path library = Files.createDirectories(root.resolve("依赖 with spaces"));
        var classMode = ApplicationRestartLaunch.fromEvidence(layout(RunModeResolver.RunMode.RUN_CLASS, home),
            javaHome, Optional.empty(), classes, classes + File.pathSeparator + library, root, home.toString());
        assertTrue(classMode.javaLauncher());
        assertEquals(List.of("-Dwtl.workspace=" + home.toRealPath(), "-cp",
            classes.toRealPath() + File.pathSeparator + library.toRealPath(), AppMain.class.getName()), classMode.arguments());
        assertEquals(home.toRealPath(), classMode.directory());
        var emptyEntry = ApplicationRestartLaunch.fromEvidence(layout(RunModeResolver.RunMode.RUN_CLASS, home),
            javaHome, Optional.empty(), classes, classes + File.pathSeparator, root, home.toString());
        assertEquals(classes.toRealPath() + File.pathSeparator + root.toRealPath(), emptyEntry.arguments().get(2));

        Path release = Files.createDirectories(root.resolve("JAR 程序"));
        Path mainJar = Files.writeString(release.resolve("main.jar"), "descriptor fixture");
        var jarMode = ApplicationRestartLaunch.fromEvidence(layout(RunModeResolver.RunMode.RUN_JAR, release),
            javaHome, Optional.empty(), mainJar, mainJar.toString(), root, "unused-secret-workspace");
        assertEquals(List.of("-cp", mainJar.toRealPath().toString(), AppMain.class.getName()), jarMode.arguments());
        assertFalse(jarMode.arguments().toString().contains("unused-secret"));
        var wildcard = ApplicationRestartLaunch.fromEvidence(layout(RunModeResolver.RunMode.RUN_JAR, release),
            javaHome, Optional.empty(), mainJar, release + File.separator + "*", root, null);
        assertEquals(release.toRealPath() + File.separator + "*", wildcard.arguments().get(1));

        Path image = Files.createDirectories(root.resolve("APP 程序"));
        Files.createDirectories(image.resolve("app"));
        Files.createDirectories(image.resolve("runtime"));
        Path imageJar = Files.writeString(image.resolve("app/main.jar"), "descriptor fixture");
        Path exe = Files.writeString(image.resolve("WindowsToLinux.exe"), "descriptor fixture");
        var appMode = ApplicationRestartLaunch.fromEvidence(layout(RunModeResolver.RunMode.RUN_APP, image),
            javaHome, Optional.of(exe), imageJar, "not-copied", root, "not-copied");
        assertFalse(appMode.javaLauncher());
        assertEquals(exe.toRealPath(), appMode.executable());
        assertEquals(List.of(), appMode.arguments());
    }

    @Test
    void refusesMissingMainMismatchedWorkspaceAndUnrelatedExecutable() throws Exception {
        Path home = workspace();
        Path classes = Files.createDirectories(home.resolve("src/app/main/target/classes"));
        Path other = Files.createDirectories(root.resolve("other"));
        var layout = layout(RunModeResolver.RunMode.RUN_CLASS, home);
        Path javaHome = javaHome();
        assertThrows(IOException.class, () -> ApplicationRestartLaunch.fromEvidence(layout, javaHome,
            Optional.empty(), classes, other.toString(), root, home.toString()));
        assertThrows(IOException.class, () -> ApplicationRestartLaunch.fromEvidence(layout, javaHome,
            Optional.empty(), classes, classes.toString(), root, other.toString()));
        Path image = Files.createDirectories(root.resolve("image/app"));
        Files.createDirectories(image.getParent().resolve("runtime"));
        Path main = Files.writeString(image.resolve("main.jar"), "fixture");
        Path unrelated = Files.writeString(root.resolve("unrelated.exe"), "fixture");
        assertThrows(IOException.class, () -> ApplicationRestartLaunch.fromEvidence(
            layout(RunModeResolver.RunMode.RUN_APP, image.getParent()), javaHome, Optional.of(unrelated), main,
            main.toString(), root, null));
    }

    @Test
    void closingFailurePreventsProcessCreationAndOwnedInputsAreRemovable() throws Exception {
        var starts = new AtomicInteger();
        var restart = new ApplicationRestart(this::description, () -> {
            throw new IOException("fixture close blocked");
        },
            root, builder -> {
            starts.incrementAndGet();
            return new Child();
        });
        restart.prepare();
        Path directory = temporaryDirectory();
        assertTrue(Files.exists(directory.resolve("launch.args")));
        assertThrows(IOException.class, restart::start);
        assertEquals(0, starts.get());
        assertTrue(restart.retryable());
        restart.close();
        assertFalse(Files.exists(directory));
    }

    @Test
    void activeChildCannotBeReplacedAndReadyReceiptIsNotASecondLaunch() throws Exception {
        var child = new Child();
        var starts = new AtomicInteger();
        var restart = new ApplicationRestart(this::description, () -> {
        }, root, builder -> {
            starts.incrementAndGet();
            assertFalse(builder.environment().containsKey("JAVA_TOOL_OPTIONS"));
            assertFalse(builder.environment().containsKey("JDK_JAVA_OPTIONS"));
            assertFalse(builder.environment().containsKey("_JAVA_OPTIONS"));
            assertEquals(2, builder.command().size());
            return child;
        });
        restart.prepare();
        Path directory = temporaryDirectory();
        var receipt = accepted(directory);
        restart.start();
        assertEquals(PlatformFrame.RestartStatus.WAITING, restart.status());
        assertFalse(restart.retryable());
        restart.close();
        assertTrue(Files.exists(directory));
        assertThrows(IOException.class, restart::prepare);
        assertThrows(IOException.class, restart::start);
        receipt.signal("READY");
        assertEquals(PlatformFrame.RestartStatus.READY, restart.status());
        assertEquals(PlatformFrame.RestartStatus.READY, restart.status());
        assertEquals(1, starts.get());
        assertFalse(Files.exists(directory));
        assertFalse(restart.retryable());
        child.alive = false;
        assertTrue(restart.retryable());
        restart.close();
    }

    @Test
    void failedOrUnacknowledgedOriginalChildMustEndBeforeRetry() throws Exception {
        var child = new Child();
        var restart = new ApplicationRestart(this::description, () -> {
        }, root, builder -> child);
        restart.prepare();
        Path directory = temporaryDirectory();
        var receipt = accepted(directory);
        restart.start();
        receipt.signal("FAILED");
        assertEquals(PlatformFrame.RestartStatus.FAILED, restart.status());
        assertFalse(restart.retryable());
        assertThrows(IOException.class, restart::prepare);
        child.alive = false;
        restart.prepare();
        child.alive = true;
        restart.start();
        directory = temporaryDirectory();
        child.alive = false;
        assertEquals(PlatformFrame.RestartStatus.FAILED, restart.status());
        assertFalse(Files.exists(directory));
        assertThrows(IOException.class, restart::start);
        restart.close();
    }

    @Test
    void startupReceiptRequiresOriginalTokenAndPublishesOnlyOnce() throws Exception {
        var receipt = ApplicationRestartReceipt.create(root);
        var arguments = receipt.arguments();
        assertTrue(ApplicationRestartReceipt.accept(new String[0]).isEmpty());
        assertThrows(IOException.class, () -> ApplicationRestartReceipt.accept(new String[]{arguments.getFirst()}));
        assertThrows(IOException.class, () -> ApplicationRestartReceipt.accept(
            new String[]{arguments.getFirst(), arguments.getFirst(), arguments.getLast()}));
        assertThrows(IOException.class, () -> ApplicationRestartReceipt.accept(
            new String[]{arguments.getFirst(), ApplicationRestartReceipt.TOKEN + UUID.randomUUID()}));
        var accepted = ApplicationRestartReceipt.accept(arguments.toArray(String[]::new)).orElseThrow();
        accepted.signal("READY");
        accepted.signal("FAILED");
        assertEquals(Optional.of("READY"), receipt.result());
        receipt.cleanup();
    }

    @Test
    void partialRequestWriteFailureRemovesItsOwnedFileAndDirectoryWithoutMaskingTheFailure() throws Exception {
        var original = new IOException("fixture partial request write");
        var request = new AtomicReference<Path>();
        var failure = assertThrows(IOException.class, () -> ApplicationRestartReceipt.create(root, (path, content) -> {
            request.set(path);
            Files.writeString(path, content.substring(0, 8), java.nio.file.StandardOpenOption.CREATE_NEW);
            assertTrue(Files.isRegularFile(path));
            throw original;
        }));
        assertSame(original, failure);
        assertNotNull(request.get());
        assertFalse(Files.exists(request.get()));
        assertFalse(Files.exists(request.get().getParent()));
        try (var paths = Files.list(root)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void realStartupFailurePublishesFailedWithoutOpeningTheIsolatedDatabase() throws Exception {
        var fixture = fixture();
        Files.writeString(fixture.home().resolve("src/app/config/target/classes")
            .resolve(ConfigLoader.RESOURCE.substring(1)), "invalid configuration");
        var arguments = new ArrayList<>(fixture.launch().arguments());
        arguments.addFirst("-Djava.awt.headless=true");
        var description = new ApplicationRestartLaunch(fixture.launch().executable(), arguments, fixture.home(), true);
        var child = new AtomicReference<Process>();
        var restart = new ApplicationRestart(() -> description, () -> {
        }, root, builder -> {
            var process = builder.start();
            child.set(process);
            return process;
        });
        try {
            restart.prepare();
            Path directory = temporaryDirectory();
            restart.start();
            assertTrue(child.get().waitFor(15, TimeUnit.SECONDS), "The failed isolated bootstrap did not exit");
            assertEquals(Optional.of("FAILED"), accepted(directory).result());
            assertEquals(PlatformFrame.RestartStatus.FAILED, restart.status());
            assertFalse(Files.exists(fixture.data()));
            assertFalse(Files.exists(directory));
        } finally {
            if (child.get() != null && child.get().isAlive()) {
                child.get().destroyForcibly();
                assertTrue(child.get().waitFor(5, TimeUnit.SECONDS));
            }
            restart.close();
        }
    }

    @Test
    void isolatedClassRestartUsesRealCloseGateAndFreshApplicationBootstrap() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var fixture = fixture();
        Path diagnostics = Files.createDirectories(Path.of("target", "restart-verification", "class-" + UUID.randomUUID()).toAbsolutePath());
        var protection = protection();
        var base = AppConfig.defaultsConfig();
        var configuration = new AppConfig("src/app/db/data", base.ui(), base.defaults(), base.ssh(), base.ai(),
            base.emergencyRecords(), false, Optional.empty());
        var actualLayout = new RunModeResolver.RuntimeLayout(RunModeResolver.RunMode.RUN_CLASS,
            fixture.home(), fixture.data());
        var process = new AtomicReference<Process>();
        var runtime = PlatformRuntime.open(actualLayout, protection, configuration);
        var arguments = new ArrayList<>(fixture.launch().arguments());
        arguments.set(arguments.size() - 1, ChildBootstrap.class.getName());
        var harness = new ApplicationRestartLaunch(fixture.launch().executable(), arguments,
            fixture.home(), true);
        var restart = new ApplicationRestart(() -> harness, runtime, root, builder -> {
            var started = builder.redirectErrorStream(true).redirectOutput(diagnostics.resolve("child.log").toFile()).start();
            process.set(started);
            return started;
        });
        UUID request;
        byte[] pairedAccess;
        try {
            runtime.security().setupAndUnlock("restart-fixture-password".toCharArray(), "restart-fixture-password".toCharArray());
            runtime.started();
            runtime.persistence().preferences().save("ui_locale", "zh-CN");
            PlatformModIntentsTest.add(runtime, "example.restart", false, Map.of(), null);
            var intents = runtime.modIntents();
            request = intents.apply(intents.review("example.restart", PlatformModIntentsTest.choice(true, "1.0.0"), Optional.empty()), false);
            pairedAccess = Files.readAllBytes(fixture.data().resolve("db-access.bin"));
            restart.prepare();
            try (var connection = runtime.persistence().database().openConnection()) {
                assertThrows(Exception.class, restart::start);
                assertNull(process.get(), "An owned connection must prevent process creation");
            }
            restart.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            while (System.nanoTime() < deadline && restart.status() == PlatformFrame.RestartStatus.WAITING)
                Thread.sleep(50);
            assertEquals(PlatformFrame.RestartStatus.READY, restart.status(), "Owned bootstrap diagnostics: " + diagnostics);
            assertNotEquals(ProcessHandle.current().pid(), process.get().pid());
            assertThrows(IOException.class, restart::start);
            Files.writeString(fixture.home().resolve("restart-test.stop"), "stop");
            assertTrue(process.get().waitFor(15, TimeUnit.SECONDS), "The owned fresh application did not close normally");
            assertEquals(0, process.get().exitValue());
            assertTrue(Files.exists(fixture.home().resolve("restart-test.closed")));
            assertArrayEquals(pairedAccess, Files.readAllBytes(fixture.data().resolve("db-access.bin")));
            try (var reopened = PlatformRuntime.open(actualLayout, protection, configuration)) {
                reopened.security().unlock("restart-fixture-password".toCharArray());
                reopened.started();
                assertEquals(Optional.of("zh-CN"), reopened.persistence().preferences().find("ui_locale"));
                assertEquals(request, reopened.modIntents().list().getFirst().operations().getFirst().request().orElseThrow());
            }
        } finally {
            if (process.get() != null && process.get().isAlive()) {
                Files.writeString(fixture.home().resolve("restart-test.stop"), "stop");
                if (!process.get().waitFor(5, TimeUnit.SECONDS)) process.get().destroyForcibly();
                assertTrue(process.get().waitFor(5, TimeUnit.SECONDS), "The owned fixture process remained alive");
            }
            restart.close();
            runtime.close();
            Path logs = fixture.data().resolve("log");
            if (Files.isDirectory(logs)) {
                try (var paths = Files.walk(logs)) {
                    for (Path path : paths.filter(Files::isRegularFile).toList()) {
                        Path destination = diagnostics.resolve("log").resolve(logs.relativize(path));
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private ApplicationRestartLaunch description() throws Exception {
        return new ApplicationRestartLaunch(javaHome().resolve("bin").resolve(
            System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java"),
            List.of("-cp", root.toString(), AppMain.class.getName()), root, true);
    }

    private Path javaHome() throws Exception {
        Path java = root.resolve("java home/bin").resolve(System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java");
        Files.createDirectories(java.getParent());
        if (!Files.exists(java)) Files.writeString(java, "descriptor fixture");
        return java.getParent().getParent();
    }

    private Path workspace() throws Exception {
        Path home = Files.createDirectories(root.resolve("CLASS 工作区"));
        Files.createDirectories(home.resolve("src/app/main"));
        Files.createDirectories(home.resolve("src/app/db"));
        for (String path : List.of("pom.xml", "src/app/main/pom.xml", "src/app/db/pom.xml"))
            Files.writeString(home.resolve(path), "<project/>");
        return home;
    }

    private RunModeResolver.RuntimeLayout layout(RunModeResolver.RunMode mode, Path home) {
        return new RunModeResolver.RuntimeLayout(mode, home, home.resolve("data"));
    }

    private Path temporaryDirectory() throws Exception {
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().startsWith("wtl-restart-")).findFirst().orElseThrow();
        }
    }

    private ApplicationRestartReceipt accepted(Path directory) throws Exception {
        return ApplicationRestartReceipt.accept(new String[]{ApplicationRestartReceipt.DIRECTORY + directory,
            ApplicationRestartReceipt.TOKEN + directory.getFileName().toString().substring("wtl-restart-".length())}).orElseThrow();
    }

    private Fixture fixture() throws Exception {
        Path home = workspace();
        Path mainOrigin = Path.of(AppMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        Path dbOrigin = Path.of(PlatformDatabase.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        Path configOrigin = Path.of(ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        Path main = home.resolve("src/app/main/target/classes");
        Path database = home.resolve("src/app/db/target/classes");
        Path config = home.resolve("src/app/config/target/classes");
        PlatformEntryProcessTest.copy(mainOrigin, main);
        PlatformEntryProcessTest.copy(dbOrigin, database);
        PlatformEntryProcessTest.copy(configOrigin, config);
        Files.writeString(home.resolve("src/app/main/pom.xml"), "<project><artifactId>windowstolinux-app-main</artifactId></project>");
        Files.writeString(home.resolve("src/app/db/pom.xml"), "<project><artifactId>windowstolinux-app-db</artifactId></project>");
        var yaml = new ObjectMapper(new YAMLFactory());
        ObjectNode document;
        try (var input = ConfigLoader.class.getResourceAsStream(ConfigLoader.RESOURCE)) {
            assertNotNull(input);
            document = (ObjectNode) yaml.readTree(input);
        }
        ((ObjectNode) document.path("class").path("database")).put("data-directory", "src/app/db/data");
        ((ObjectNode) document.path("class").path("mod")).put("auto-load", false);
        Files.writeString(config.resolve(ConfigLoader.RESOURCE.substring(1)), yaml.writeValueAsString(document));
        var entries = new ArrayList<String>();
        for (String value : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            Path entry = Path.of(value).toRealPath();
            if (entry.equals(mainOrigin)) entry = main;
            else if (entry.equals(dbOrigin)) entry = database;
            else if (entry.equals(configOrigin)) entry = config;
            entries.add(entry.toString());
        }
        var description = ApplicationRestartLaunch.fromEvidence(layout(RunModeResolver.RunMode.RUN_CLASS, home),
            Path.of(System.getProperty("java.home")), Optional.empty(), main,
            String.join(File.pathSeparator, entries), root, home.toString());
        return new Fixture(home, home.resolve("src/app/db/data"), description);
    }

    private DatabaseProtection protection() {
        var current = new CurrentUserProtection();
        return new DatabaseProtection() {
            public byte[] protect(byte[] clear) {
                return current.protect(clear);
            }

            public byte[] unprotect(byte[] encrypted) {
                return current.unprotect(encrypted);
            }
        };
    }

    private record Fixture(Path home, Path data, ApplicationRestartLaunch launch) {
    }

    private static final class Child extends Process {
        boolean alive = true;

        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        public int waitFor() {
            alive = false;
            return 0;
        }

        public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }

        public void destroy() {
            alive = false;
        }

        public boolean isAlive() {
            return alive;
        }
    }

    /** Uses actual AppMain and normal window close in the isolated child only. / 仅在隔离子进程中使用实际 AppMain 及正常窗口关闭。 */
    public static final class ChildBootstrap {
        /** Boots and closes only this fixture's own application. / 仅启动和关闭此夹具自身应用。
         * @param arguments original non-secret restart inputs / 原非秘密重启输入
         * @throws Exception if bootstrap or normal close fails / 引导或正常关闭失败时 */
        public static void main(String[] arguments) throws Exception {
            Path home = Path.of(System.getProperty("wtl.workspace"));
            Path data = home.resolve("src/app/db/data");
            System.out.println("FIXTURE_DATA " + data + " exists=" + Files.exists(data)
                + " symbolic=" + Files.isSymbolicLink(data) + " directory=" + Files.isDirectory(data, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                + " writable=" + Files.isWritable(data) + " usable=" + Files.getFileStore(data).getUsableSpace());
            try {
                Path probe = Files.createTempFile(data, "restart-write-probe-", ".tmp");
                Files.delete(probe);
                System.out.println("FIXTURE_DATA_WRITE confirmed");
            } catch (IOException failure) {
                System.out.println("FIXTURE_DATA_WRITE " + failure.getClass().getName());
            }
            AppMain.main(arguments);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            while (!Files.exists(home.resolve("restart-test.stop")) && System.nanoTime() < deadline)
                Thread.sleep(50);
            var frame = new AtomicReference<PlatformFrame>();
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows())
                    if (window instanceof PlatformFrame platform && window.isDisplayable()) frame.set(platform);
                if (frame.get() != null)
                    frame.get().dispatchEvent(new WindowEvent(frame.get(), WindowEvent.WINDOW_CLOSING));
            });
            if (frame.get() == null) throw new AssertionError("Fresh AppMain did not show its own frame");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                var displayed = new java.util.concurrent.FutureTask<>(frame.get()::isDisplayable);
                SwingUtilities.invokeAndWait(displayed);
                if (!displayed.get()) {
                    Files.writeString(home.resolve("restart-test.closed"), "closed");
                    return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("Fresh AppMain did not close its actual runtime");
        }
    }
}
