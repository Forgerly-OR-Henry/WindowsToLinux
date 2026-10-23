package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.count;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.app.main.runtime.RunModeResolver.RunMode;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver.RuntimeLayout;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentModBuildTest {
    @TempDir
    Path root;

    @Test
    void classStartupRebuildsBeforeDiscoveringOrRunningAnyMod() throws Exception {
        var fixture = fixture();
        fixture.module(PlatformModFlowTest.P, Map.of(), true);
        try (var runtime = fixture.open(false)) {
            var calls = new ArrayList<Path>();
            var report = ApplicationLauncher.startMods(runtime, layout(fixture, runtime, RunMode.RUN_CLASS),
                fixture.pages(true), workspace -> {
                    calls.add(workspace);
                    assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
                    assertFalse(Files.exists(fixture.trace));
                    fixture.module(PlatformModFlowTest.P, Map.of(), false);
                });
            assertEquals(List.of(fixture.workspace.toRealPath()), calls);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(PlatformModFlowTest.P), report.running());
        }
    }

    @Test
    void compilationFailureDoesNotLoadAnExistingValidOutput() throws Exception {
        var fixture = fixture();
        fixture.module(PlatformModFlowTest.P, Map.of(), false);
        var problem = new Problem(ProblemCode.UNAVAILABLE, "MOD_DEVELOPMENT_BUILD_FAILED", "compiler failed",
            Map.of("log", fixture.workspace.resolve("target/mod-startup-build.log").toString()), Optional.of(UUID.randomUUID()));
        try (var runtime = fixture.open(false)) {
            var report = ApplicationLauncher.startMods(runtime, layout(fixture, runtime, RunMode.RUN_CLASS),
                fixture.pages(true), workspace -> {
                    throw new ApiException(problem);
                });
            assertTrue(report.running().isEmpty());
            assertEquals(List.of(problem), report.problems());
            assertFalse(Files.exists(fixture.trace));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
            assertTrue(runtime.modContexts().isEmpty());
        }
    }

    @Test
    void disabledClassAndReleaseModesNeverInvokeCompiler() throws Exception {
        var fixture = fixture();
        var disabled = new ConfiguredRuntimeTest().config();
        var classLayout = new RuntimeLayout(RunMode.RUN_CLASS, fixture.workspace, root.resolve("disabled-data"));
        try (var runtime = PlatformRuntime.open(classLayout, PlatformStartupTest.PROTECTION, disabled)) {
            var report = ApplicationLauncher.startMods(runtime, classLayout, null, workspace -> fail("must not compile"));
            assertTrue(report.problems().isEmpty());
            assertTrue(report.running().isEmpty());
        }
        for (var mode : List.of(RunMode.RUN_JAR, RunMode.RUN_APP)) {
            try (var runtime = fixture.open(false)) {
                ApplicationLauncher.startMods(runtime, layout(fixture, runtime, mode), fixture.pages(true),
                    workspace -> fail("release mode must not compile"));
            }
        }
    }

    @Test
    void resolvesMavenAndPreservesPathsAsLiteralArguments() throws Exception {
        Path maven = Files.createDirectories(root.resolve("Maven 安装 & tools"));
        Files.createDirectories(maven.resolve("boot"));
        Files.createDirectories(maven.resolve("bin"));
        Files.writeString(maven.resolve("boot/plexus-classworlds-test.jar"), "bootstrap");
        Files.writeString(maven.resolve("bin/m2.conf"), "configuration");
        Files.writeString(maven.resolve("bin/mvn.cmd"), "launcher");
        assertEquals(maven.toRealPath(), DevelopmentModBuild.mavenHome(null, Map.of("MAVEN_HOME", maven.toString())));
        assertEquals(maven.toRealPath(), DevelopmentModBuild.mavenHome(null, Map.of("PATH", maven.resolve("bin").toString())));
        assertEquals(maven.toRealPath(), DevelopmentModBuild.mavenHome(null, Map.of("Path", maven.resolve("bin").toString())));
        assertThrows(java.io.IOException.class, () -> DevelopmentModBuild.mavenHome(null, Map.of()));
        Path workspace = root.resolve("中文 & workspace");
        var command = DevelopmentModBuild.command(workspace, Path.of(System.getProperty("java.home")), maven);
        assertTrue(command.contains(workspace.resolve("pom.xml").toString()));
        assertTrue(command.contains("-Dmaven.multiModuleProjectDirectory=" + workspace));
        assertEquals(List.of("-DskipTests", "process-test-classes"), command.subList(command.size() - 2, command.size()));
        assertThrows(java.io.IOException.class, () -> DevelopmentModBuild.command(workspace, root, maven));
    }

    @Test
    void actualMavenRecompilesChangedSourcesAndResourcesWithoutRunningTests() throws Exception {
        Path workspace = Files.createDirectories(root.resolve("真实 & build"));
        Path source = workspace.resolve("src/main/java/example/Mod.java");
        Path resource = workspace.resolve("src/main/resources/mod.txt");
        write(workspace.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
            <groupId>example</groupId><artifactId>development-build-fixture</artifactId><version>1</version>
            <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
            <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin></plugins></build></project>
            """);
        write(source, "package example; public class Mod { public static String value() { return \"old\"; } }");
        write(resource, "old resource");
        write(workspace.resolve("src/test/java/example/BuildTest.java"),
            "package example; class BuildTest { static { throwIfRun(); } static void throwIfRun() { throw new AssertionError(\"test must not run\"); } }");
        build(workspace);
        byte[] original = Files.readAllBytes(workspace.resolve("target/classes/example/Mod.class"));
        write(source, "package example; public class Mod { public static String value() { return \"changed\"; } }");
        write(resource, "changed resource");
        build(workspace);
        assertFalse(java.util.Arrays.equals(original, Files.readAllBytes(workspace.resolve("target/classes/example/Mod.class"))));
        assertEquals("changed resource", Files.readString(workspace.resolve("target/classes/mod.txt")));
        assertTrue(Files.exists(workspace.resolve("target/test-classes/example/BuildTest.class")));
        assertFalse(Files.exists(workspace.resolve("target/surefire-reports")));
        write(source, "broken Java source");
        var failure = assertThrows(ApiException.class, () -> build(workspace));
        assertEquals("MOD_DEVELOPMENT_BUILD_FAILED", failure.problem().eventCode());
        assertTrue(Files.readString(workspace.resolve("build.log")).contains("BUILD FAILURE"));
    }

    @Test
    void closingStartupStopsItsBuildProcess() throws Exception {
        Path source = root.resolve("WaitingBuild.java");
        Path pid = root.resolve("build.pid");
        write(source, """
            class WaitingBuild {
                public static void main(String[] args) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(60000);
                }
            }
            """);
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java" + suffix).toString(),
            source.toString(), pid.toString());
        var failure = assertThrows(ApiException.class, () -> DevelopmentModBuild.execute(new ProcessBuilder(command),
            root.resolve("cancelled.log"), () -> Files.exists(pid)));
        assertTrue(failure.problem().message().contains("cancelled"));
        assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(pid))).map(ProcessHandle::isAlive).orElse(false));
    }

    void build(Path workspace) throws Exception {
        var command = new ArrayList<>(DevelopmentModBuild.command(workspace, Path.of(System.getProperty("java.home")),
            Path.of(System.getProperty("wtl.test.mavenHome"))));
        command.add(command.size() - 1, "-o");
        command.add(command.size() - 1, "-Dmaven.repo.local=" + System.getProperty("wtl.test.repository"));
        DevelopmentModBuild.execute(new ProcessBuilder(command).directory(workspace.toFile()), workspace.resolve("build.log"));
    }

    PlatformModFlowTest fixture() throws Exception {
        var fixture = new PlatformModFlowTest();
        fixture.root = root;
        fixture.workspace();
        return fixture;
    }

    static RuntimeLayout layout(PlatformModFlowTest fixture, PlatformRuntime runtime, RunMode mode) {
        return new RuntimeLayout(mode, fixture.workspace, runtime.persistence().database().root());
    }

    static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }
}
