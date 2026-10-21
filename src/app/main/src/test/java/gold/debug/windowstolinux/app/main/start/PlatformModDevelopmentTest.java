package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.text;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.mod.dev.DevelopmentInfo;
import gold.debug.windowstolinux.app.mod.dev.DevelopmentModFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModDevelopmentTest {
    @TempDir
    Path root;

    @Test
    void independentMavenProjectTestsAndAdmissionThenPhysicalMoveRebuildAndRealLifecycle() throws Exception {
        var fixture = project();
        Path workspace = fixture.workspace;
        Path project = workspace.resolve("src/moddev/sample");
        assertEquals(0, build(workspace, project), () -> log(workspace));
        assertTrue(log(workspace).contains("Tests run: 1, Failures: 0"), () -> log(workspace));
        assertFalse(log(workspace).contains("@ windowstolinux-app-main"));
        assertFalse(log(workspace).contains("@ windowstolinux-mod-deployment"));
        var before = DevelopmentInfo.read(workspace, project.resolve("main/target/wtlmod-development.json"));
        assertEquals(2, before.authorOutputs().size());
        assertEquals(1, before.libraries().size());
        assertTrue(before.buildInputs().contains(workspace.resolve("src/moddev/pom.xml")));
        assertEquals("moddev", Files.readString(before.mainOutput().resolve("location.txt")));
        assertTrue(DevelopmentModFinder.scan(workspace).candidates().isEmpty());
        assertFalse(Files.exists(fixture.trace));
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
        }

        Path source = project.resolve("main/src/main/java/example/development/Mod.java");
        String original = Files.readString(source);
        Files.writeString(source, original.replace("public final class Mod", "public abstract class Mod"));
        assertNotEquals(0, build(workspace, project));
        assertFalse(Files.exists(project.resolve("main/target/wtlmod-development.json")));
        assertTrue(log(workspace).contains("BUILD_DESCRIPTION_UNCONFIRMED"), () -> log(workspace));
        Files.writeString(source, original);
        Path test = project.resolve("main/src/test/java/example/development/ModTest.java");
        String originalTest = Files.readString(test);
        Files.writeString(test, originalTest.replace("REAL MAVEN", "wrong expectation"));
        assertNotEquals(0, build(workspace, project));
        assertTrue(log(workspace).contains("Failures: 1"), () -> log(workspace));
        Files.writeString(test, originalTest);
        assertEquals(0, build(workspace, project), () -> log(workspace));

        Path moved = workspace.resolve("src/mod/sample");
        assertTrue(project.startsWith(workspace));
        assertTrue(moved.startsWith(workspace));
        Files.move(project, moved);
        assertThrows(java.io.IOException.class,
            () -> DevelopmentInfo.read(workspace, moved.resolve("main/target/wtlmod-development.json")));
        assertTrue(DevelopmentModFinder.scan(workspace).candidates().isEmpty());
        write(workspace.resolve("src/mod/pom.xml"), aggregate("mod", "<module>sample</module>", ""));
        write(moved.resolve("main/src/main/resources/location.txt"), "mod");
        assertEquals(0, build(workspace, moved), () -> log(workspace));
        assertFalse(Files.exists(project));
        var after = DevelopmentInfo.read(workspace, moved.resolve("main/target/wtlmod-development.json"));
        assertNotEquals(before.inputDigest(), after.inputDigest());
        assertEquals(moved.resolve("helper/target/classes"), after.authorOutputs().getFirst());
        assertTrue(after.buildInputs().contains(workspace.resolve("src/mod/pom.xml")));
        assertFalse(after.buildInputs().contains(workspace.resolve("src/moddev/pom.xml")));
        assertEquals("mod", Files.readString(after.mainOutput().resolve("location.txt")));
        assertEquals(1, DevelopmentModFinder.scan(workspace).candidates().size());
        assertFalse(Files.exists(fixture.trace));
        try (var runtime = fixture.open(false)) {
            var report = fixture.start(runtime);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of("example.development"), report.running());
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals("1.0.0", text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals(List.of("start:REAL MAVEN"), fixture.actions());
        }
        assertEquals(List.of("start:REAL MAVEN", "stop"), fixture.actions());
    }

    PlatformModFlowTest project() throws Exception {
        var fixture = new PlatformModFlowTest();
        fixture.root = root;
        fixture.workspace();
        Path workspace = fixture.workspace;
        Path repository = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(repository.resolve("docs/reference/File.md")))
            repository = repository.getParent();
        write(workspace.resolve("pom.xml"), aggregate("workspace", "<module>src/mod</module>", ""));
        write(workspace.resolve("src/mod/pom.xml"), aggregate("mod", "", ""));
        write(workspace.resolve("src/moddev/pom.xml"), aggregate("moddev", "", ""));
        write(workspace.resolve("src/moddev/unrelated/pom.xml"), "invalid unrelated project");
        write(workspace.resolve(".mvn/wtlmod-author-parent/pom.xml"), Files.readString(repository.resolve(".mvn/wtlmod-author-parent/pom.xml")));
        Path project = workspace.resolve("src/moddev/sample");
        write(project.resolve("pom.xml"), aggregate("sample", "<module>helper</module><module>extra</module><module>main</module>", ""));
        for (String helper : List.of("helper", "extra")) {
            write(project.resolve(helper + "/pom.xml"), pom("<artifactId>windowstolinux-mod-" + helper + "</artifactId>", ""));
            write(project.resolve(helper + "/src/main/java/example/" + helper + "/Helper.java"),
                "package example." + helper + "; public final class Helper { public static String value() { return \"real maven\"; } }");
        }
        write(project.resolve("main/pom.xml"), pom("""
            <parent><groupId>gold.debug.windowstolinux</groupId><artifactId>wtlmod-author-parent</artifactId><version>0.1.0-SNAPSHOT</version><relativePath>../../../../.mvn/wtlmod-author-parent/pom.xml</relativePath></parent>
            <artifactId>main</artifactId><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding><wtl.authorArtifacts>gold.debug.windowstolinux:windowstolinux-mod-helper:jar:1.0.0,gold.debug.windowstolinux:windowstolinux-mod-extra:jar:1.0.0</wtl.authorArtifacts></properties>
            <dependencies><dependency><groupId>gold.debug.windowstolinux</groupId><artifactId>windowstolinux-appapi</artifactId><scope>provided</scope></dependency>
            <dependency><groupId>gold.debug.windowstolinux</groupId><artifactId>windowstolinux-mod-helper</artifactId><version>1.0.0</version></dependency>
            <dependency><groupId>gold.debug.windowstolinux</groupId><artifactId>windowstolinux-mod-extra</artifactId><version>1.0.0</version></dependency>
            <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version></dependency>
            <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency></dependencies>
            """, ""));
        write(project.resolve("main/mod.json"), """
            {"formatVersion":1,"id":"example.development","name":"Independent development","version":"1.0.0","platformApiRange":"[1.0.0-dev.1,1.0.0)","minJavaVersion":21,"mainJar":"main/main.jar","entryClass":"example.development.Mod"}
            """);
        write(project.resolve("main/src/main/resources/location.txt"), "moddev");
        String trace = new ObjectMapper().writeValueAsString(fixture.trace.toString());
        write(project.resolve("main/src/main/java/example/development/Mod.java"), """
            package example.development;
            import java.nio.file.*;
            import example.helper.Helper;
            public final class Mod implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint {
                public static String value() { return Helper.value(); }
                public void start(gold.debug.windowstolinux.appapi.mod.ModContext c) throws Exception {
                    if(!value().equals(example.extra.Helper.value()))throw new AssertionError("companion mismatch");
                    Files.writeString(Path.of(%s),"start:"+org.apache.commons.lang3.StringUtils.upperCase(value())+"\\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                }
                public void stop(gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext c) throws Exception {
                    Files.writeString(Path.of(%s),"stop\\n",StandardOpenOption.APPEND);
                }
            }
            """.formatted(trace, trace));
        write(project.resolve("main/src/test/java/example/development/ModTest.java"), """
            package example.development;
            class ModTest {
                @org.junit.jupiter.api.Test void realCompanionsAndPrivateLibrary() {
                    org.junit.jupiter.api.Assertions.assertEquals("REAL MAVEN",org.apache.commons.lang3.StringUtils.upperCase(Mod.value()));
                    org.junit.jupiter.api.Assertions.assertEquals(Mod.value(),example.extra.Helper.value());
                }
            }
            """);
        return fixture;
    }

    static String pom(String body, String modules) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>" + body
            + "<groupId>gold.debug.windowstolinux</groupId><version>1.0.0</version>"
            + (body.contains("<properties>") ? "" : "<properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>")
            + modules + "</project>";
    }

    static String aggregate(String artifact, String modules, String body) {
        return pom("<artifactId>" + artifact + "</artifactId><packaging>pom</packaging>" + body,
            "<modules>" + modules + "</modules>");
    }

    int build(Path workspace, Path project) throws Exception {
        Path maven = Path.of(Objects.requireNonNull(System.getProperty("wtl.test.mavenHome")));
        Path bootstrap;
        try (var files = Files.list(maven.resolve("boot"))) {
            bootstrap = files.filter(path -> path.toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Dmaven.home=" + maven, "-Dclassworlds.conf=" + maven.resolve("bin/m2.conf"),
            "-Dmaven.multiModuleProjectDirectory=" + workspace, "-classpath", bootstrap.toString(),
            "org.codehaus.plexus.classworlds.launcher.Launcher", "-B", "-o", "-f", project.resolve("pom.xml").toString(),
            "-Dmaven.repo.local=" + Objects.requireNonNull(System.getProperty("wtl.test.repository")), "verify");
        var process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true)
            .redirectOutput(workspace.resolve("build.log").toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), () -> log(workspace));
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
    }

    static String log(Path workspace) {
        try {
            return Files.readString(workspace.resolve("build.log"));
        } catch (java.io.IOException failure) {
            return failure.toString();
        }
    }

    static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }
}
