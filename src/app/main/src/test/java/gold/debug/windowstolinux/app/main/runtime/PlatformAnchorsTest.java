package gold.debug.windowstolinux.app.main.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformAnchorsTest {
    @TempDir
    Path root;

    @Test
    void jarProgramRootUsesMainWhileDataUsesDbAcrossUnrelatedWorkingDirectories() throws Exception {
        var program = Files.createDirectories(root.resolve("程序 with spaces"));
        var libraries = Files.createDirectories(root.resolve("separate database module"));
        var main = Files.createFile(program.resolve("main.jar"));
        var database = Files.createFile(libraries.resolve("db.jar"));
        var unrelated = Files.createDirectories(root.resolve("unrelated/mod"));
        var layout = RunModeResolver.resolveStartupFromEvidence(Optional.empty(), Optional.of(database),
            Optional.of(main), Optional.of(unrelated)).orElseThrow();
        assertEquals(RunModeResolver.RunMode.RUN_JAR, layout.mode());
        assertEquals(program, layout.applicationHome());
        assertEquals(libraries.resolve("data"), layout.dataDirectory());
        assertTrue(RunModeResolver.resolveStartupFromEvidence(Optional.empty(), Optional.of(database), Optional.empty(),
            Optional.of(program)).isEmpty());
    }

    @Test
    void appProgramAndDataFollowExecutable() throws Exception {
        var program = Files.createDirectories(root.resolve("WindowsToLinux APP"));
        Files.createDirectories(program.resolve("app"));
        Files.createDirectories(program.resolve("runtime"));
        var exe = Files.createFile(program.resolve("WindowsToLinux.exe"));
        var layout = RunModeResolver
            .resolveStartupFromEvidence(Optional.of(exe), Optional.empty(), Optional.empty(), Optional.of(root))
            .orElseThrow();
        assertEquals(RunModeResolver.RunMode.RUN_APP, layout.mode());
        assertEquals(program, layout.applicationHome());
        assertEquals(program.resolve("data"), layout.dataDirectory());
    }

    @Test
    void classProgramRootRequiresActualWorkspaceAndMatchingMainOutput() throws Exception {
        var workspace = Files.createDirectories(root.resolve("repository"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        var db = Files.createDirectories(workspace.resolve("src/app/db"));
        Files.writeString(db.resolve("pom.xml"), "<project><artifactId>windowstolinux-app-db</artifactId></project>");
        var main = Files.createDirectories(workspace.resolve("src/app/main"));
        Files.writeString(main.resolve("pom.xml"),
            "<project><artifactId>windowstolinux-app-main</artifactId></project>");
        var dbClasses = Files.createDirectories(db.resolve("target/classes"));
        var mainClasses = Files.createDirectories(main.resolve("target/classes"));
        var layout = RunModeResolver.resolveStartupFromEvidence(Optional.empty(), Optional.of(dbClasses),
            Optional.of(mainClasses), Optional.of(root)).orElseThrow();
        assertEquals(workspace, layout.applicationHome());
        assertEquals(db.resolve("data"), layout.dataDirectory());
        var foreign = Files.createDirectories(root.resolve("other-repository/target/classes"));
        assertTrue(RunModeResolver.resolveStartupFromEvidence(Optional.empty(), Optional.of(dbClasses),
            Optional.of(foreign), Optional.of(workspace)).isEmpty());
        Files.delete(main.resolve("pom.xml"));
        assertTrue(RunModeResolver.resolveStartupFromEvidence(Optional.empty(), Optional.of(dbClasses),
            Optional.of(mainClasses), Optional.of(workspace)).isEmpty());
    }
}
