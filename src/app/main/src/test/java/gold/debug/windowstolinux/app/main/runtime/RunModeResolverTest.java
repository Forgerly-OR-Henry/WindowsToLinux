package gold.debug.windowstolinux.app.main.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunModeResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesMavenDbClassesToModuleDataDirectory() throws Exception {
        Path moduleHome = temporaryDirectory.resolve("repository/src/app/db");
        Path classes = Files.createDirectories(moduleHome.resolve("target/classes"));

        RunModeResolver.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(classes));

        assertEquals(RunModeResolver.RunMode.RUN_CLASS, layout.mode());
        assertEquals(moduleHome.toAbsolutePath(), layout.applicationHome());
        assertEquals(moduleHome.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesMavenDbTestClassesToModuleDataDirectory() throws Exception {
        Path moduleHome = temporaryDirectory.resolve("repository/src/app/db");
        Path testClasses = Files.createDirectories(moduleHome.resolve("target/test-classes"));

        RunModeResolver.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(testClasses));

        assertEquals(RunModeResolver.RunMode.RUN_CLASS, layout.mode());
        assertEquals(moduleHome.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesValidatedIdeClassOutputToTheDbModuleFromTheMainWorkingDirectory() throws Exception {
        Path repository = temporaryDirectory.resolve("repository");
        Path moduleHome = Files.createDirectories(repository.resolve("src/app/db"));
        Files.writeString(
                moduleHome.resolve("pom.xml"),
                "<project><artifactId>windowstolinux-app-db</artifactId></project>"
        );
        Path mainModule = Files.createDirectories(repository.resolve("src/app/main"));
        Files.writeString(mainModule.resolve("pom.xml"),
                "<project><artifactId>windowstolinux-app-main</artifactId></project>");
        Path ideClasses = Files.createDirectories(repository.resolve("out/production/app-db"));

        RunModeResolver.RuntimeLayout layout = RunModeResolver.resolveFromEvidence(
                Optional.empty(),
                Optional.of(ideClasses),
                Optional.of(mainModule)
        ).orElseThrow();

        assertEquals(RunModeResolver.RunMode.RUN_CLASS, layout.mode());
        assertEquals(moduleHome.toAbsolutePath(), layout.applicationHome());
        assertEquals(moduleHome.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesDbJarToSiblingDataDirectoryWhenTheMainJarIsElsewhere() throws Exception {
        Path distribution = Files.createDirectories(temporaryDirectory.resolve("distribution"));
        Files.createFile(distribution.resolve("Main.jar"));
        Path dbDirectory = Files.createDirectories(distribution.resolve("lib"));
        Path jar = Files.createFile(dbDirectory.resolve("DB.jar"));

        RunModeResolver.RuntimeLayout layout = RunModeResolver.resolveFromEvidence(
                Optional.empty(), Optional.of(jar), Optional.of(distribution)).orElseThrow();

        assertEquals(RunModeResolver.RunMode.RUN_JAR, layout.mode());
        assertEquals(dbDirectory.toAbsolutePath(), layout.applicationHome());
        assertEquals(dbDirectory.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void acceptsTheVersionedDbModuleJarName() throws Exception {
        Path dbDirectory = Files.createDirectories(temporaryDirectory.resolve("lib"));
        Path jar = Files.createFile(dbDirectory.resolve("windowstolinux-app-db-0.1.0-SNAPSHOT.jar"));

        RunModeResolver.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(jar));

        assertEquals(RunModeResolver.RunMode.RUN_JAR, layout.mode());
        assertEquals(dbDirectory.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void rejectsIdeFallbackWhenOnlyTheMainModuleIsAvailable() throws Exception {
        Path repository = temporaryDirectory.resolve("repository");
        Path mainModule = Files.createDirectories(repository.resolve("src/app/main"));
        Files.writeString(mainModule.resolve("pom.xml"),
                "<project><artifactId>windowstolinux-app-main</artifactId></project>");
        Path ideClasses = Files.createDirectories(repository.resolve("out/production/app-db"));

        assertTrue(RunModeResolver.resolveFromEvidence(
                Optional.empty(), Optional.of(ideClasses), Optional.of(mainModule)).isEmpty());
    }

    @Test
    void prefersDbCodeSourceOverAnotherWorkingDirectory() throws Exception {
        Path moduleHome = temporaryDirectory.resolve("repository/src/app/db");
        Path classes = Files.createDirectories(moduleHome.resolve("target/classes"));
        Path otherModule = Files.createDirectories(temporaryDirectory.resolve("other/src/app/db"));
        Files.writeString(otherModule.resolve("pom.xml"),
                "<project><artifactId>windowstolinux-app-db</artifactId></project>");

        RunModeResolver.RuntimeLayout layout = RunModeResolver.resolveFromEvidence(
                Optional.empty(), Optional.of(classes), Optional.of(otherModule)).orElseThrow();

        assertEquals(moduleHome.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesJpackageExecutableToAppImageDataDirectory() throws Exception {
        Path appImage = createJpackageLayout("desktop-app");
        Path executable = Files.createFile(appImage.resolve("WindowsToLinux.exe"));
        Path dbDirectory = Files.createDirectories(appImage.resolve("app/lib"));
        Path dbJar = Files.createFile(dbDirectory.resolve("DB.jar"));

        RunModeResolver.RuntimeLayout layout = resolve(
                Optional.of(executable),
                Optional.of(dbJar)
        );

        assertEquals(RunModeResolver.RunMode.RUN_APP, layout.mode());
        assertEquals(appImage.toAbsolutePath(), layout.applicationHome());
        assertEquals(appImage.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void resolvesDbJarInsideJpackageImageAsAppMode() throws Exception {
        Path appImage = createJpackageLayout("jar-app");
        Path jar = Files.createFile(appImage.resolve("app/DB.jar"));

        RunModeResolver.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(jar));

        assertEquals(RunModeResolver.RunMode.RUN_APP, layout.mode());
        assertEquals(appImage.resolve("data").toAbsolutePath(), layout.dataDirectory());
    }

    @Test
    void rejectsUnknownLayoutInsteadOfUsingWorkingDirectory() throws Exception {
        Path unknown = Files.createDirectories(temporaryDirectory.resolve("classes"));

        assertTrue(RunModeResolver.resolveFromEvidence(
                Optional.empty(),
                Optional.of(unknown)
        ).isEmpty());
    }

    @Test
    void normalizesResolvedPaths() throws Exception {
        Path moduleHome = temporaryDirectory.resolve("module");
        Files.createDirectories(moduleHome.resolve("target/classes"));
        Path nonNormalized = moduleHome.resolve("target/../target/classes");

        RunModeResolver.RuntimeLayout layout = resolve(Optional.empty(), Optional.of(nonNormalized));

        assertEquals(moduleHome.toAbsolutePath().normalize(), layout.applicationHome());
        assertEquals(moduleHome.resolve("data").toAbsolutePath().normalize(), layout.dataDirectory());
    }

    @Test
    void exposesNoDataDirectoryOverrideProperty() {
        assertThrows(
                NoSuchFieldException.class,
                () -> RunModeResolver.class.getField("DATA_DIRECTORY_PROPERTY")
        );
    }

    @Test
    void publicDetectionUsesTheDbModuleEvenWhenCalledFromMainTests() {
        assertEquals(
                RunModeResolver.RunMode.RUN_CLASS,
                RunModeResolver.detect()
        );
        assertTrue(RunModeResolver.resolveDataDirectory()
                .endsWith(Path.of("src", "app", "db", "data")));
    }

    private Path createJpackageLayout(String name) throws Exception {
        Path root = temporaryDirectory.resolve(name);
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("runtime"));
        return root;
    }

    private static RunModeResolver.RuntimeLayout resolve(
            Optional<Path> processCommand,
            Optional<Path> codeSource
    ) {
        return RunModeResolver.resolveFromEvidence(processCommand, codeSource).orElseThrow();
    }
}
