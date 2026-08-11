package gold.debug.windowstolinux.app.main.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunModeDetectorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesMavenClassesToModuleDataDirectory() throws Exception {
        Path moduleHome = temporaryDirectory.resolve("module");
        Path classes = Files.createDirectories(moduleHome.resolve("target/classes"));

        RunModeDetector.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(classes));

        assertEquals(RunModeDetector.RunMode.RUN_CLASS, layout.mode());
        assertEquals(moduleHome.toAbsolutePath(), layout.applicationHome());
        assertEquals(moduleHome.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesMavenTestClassesToModuleDataDirectory() throws Exception {
        Path moduleHome = temporaryDirectory.resolve("module");
        Path testClasses = Files.createDirectories(moduleHome.resolve("target/test-classes"));

        RunModeDetector.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(testClasses));

        assertEquals(RunModeDetector.RunMode.RUN_CLASS, layout.mode());
        assertEquals(moduleHome.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesValidatedIdeClassOutputToTheAppMainModule() throws Exception {
        Path repository = temporaryDirectory.resolve("repository");
        Path moduleHome = Files.createDirectories(repository.resolve("src/app/main"));
        Files.writeString(
                moduleHome.resolve("pom.xml"),
                "<project><artifactId>windowstolinux-app-main</artifactId></project>"
        );
        Path ideClasses = Files.createDirectories(repository.resolve("out/production/app-main"));

        RunModeDetector.RuntimeLayout layout = RunModeDetector.resolveFromEvidence(
                Optional.empty(),
                Optional.of(ideClasses),
                Optional.of(repository)
        ).orElseThrow();

        assertEquals(RunModeDetector.RunMode.RUN_CLASS, layout.mode());
        assertEquals(moduleHome.toAbsolutePath(), layout.applicationHome());
        assertEquals(moduleHome.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesExecutableJarToSiblingDataDirectory() throws Exception {
        Path distribution = Files.createDirectories(temporaryDirectory.resolve("distribution"));
        Path jar = Files.createFile(distribution.resolve("windowstolinux.jar"));

        RunModeDetector.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(jar));

        assertEquals(RunModeDetector.RunMode.RUN_JAR, layout.mode());
        assertEquals(distribution.toAbsolutePath(), layout.applicationHome());
        assertEquals(distribution.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesJpackageExecutableToAppImageDataDirectory() throws Exception {
        Path appImage = createJpackageLayout("desktop-app");
        Path executable = Files.createFile(appImage.resolve("WindowsToLinux.exe"));

        RunModeDetector.RuntimeLayout layout = resolve(
                Optional.of(executable),
                Optional.of(temporaryDirectory.resolve("ignored/target/classes"))
        );

        assertEquals(RunModeDetector.RunMode.RUN_APP, layout.mode());
        assertEquals(appImage.toAbsolutePath(), layout.applicationHome());
        assertEquals(appImage.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesJarInsideJpackageImageAsAppMode() throws Exception {
        Path appImage = createJpackageLayout("jar-app");
        Path jar = Files.createFile(appImage.resolve("app/windowstolinux.jar"));

        RunModeDetector.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(jar));

        assertEquals(RunModeDetector.RunMode.RUN_APP, layout.mode());
        assertEquals(appImage.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void rejectsUnknownLayoutInsteadOfUsingWorkingDirectory() throws Exception {
        Path unknown = Files.createDirectories(temporaryDirectory.resolve("classes"));

        assertTrue(RunModeDetector.resolveFromEvidence(
                Optional.empty(),
                Optional.of(unknown)
        ).isEmpty());
    }

    @Test
    void normalizesResolvedPaths() throws Exception {
        Path moduleHome = temporaryDirectory.resolve("module");
        Files.createDirectories(moduleHome.resolve("target/classes"));
        Path nonNormalized = moduleHome.resolve("target/../target/classes");

        RunModeDetector.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(nonNormalized));

        assertEquals(moduleHome.toAbsolutePath().normalize(), layout.applicationHome());
        assertEquals(moduleHome.resolve("data").toAbsolutePath().normalize(), layout.dataDirectory());
    }

    @Test
    void exposesNoDataDirectoryOverrideProperty() {
        assertThrows(
                NoSuchFieldException.class,
                () -> RunModeDetector.class.getField("DATA_DIRECTORY_PROPERTY")
        );
    }

    @Test
    void publicDetectionRecognizesTheMavenTestRuntime() {
        assertEquals(
                RunModeDetector.RunMode.RUN_CLASS,
                RunModeDetector.detect(RunModeDetectorTest.class)
        );
        assertTrue(RunModeDetector.resolveDataDirectory(RunModeDetectorTest.class)
                .endsWith(Path.of("src", "app", "main", "data")));
    }

    private Path createJpackageLayout(String name) throws Exception {
        Path root = temporaryDirectory.resolve(name);
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("runtime"));
        return root;
    }

    private static RunModeDetector.RuntimeLayout resolve(
            Optional<Path> processCommand,
            Optional<Path> codeSource
    ) {
        return RunModeDetector.resolveFromEvidence(processCommand, codeSource).orElseThrow();
    }
}
