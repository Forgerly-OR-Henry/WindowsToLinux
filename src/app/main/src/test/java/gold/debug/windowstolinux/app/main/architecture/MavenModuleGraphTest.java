package gold.debug.windowstolinux.app.main.architecture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenModuleGraphTest {
    @TempDir
    Path root;

    @Test
    void defaultReactorExcludesDevelopmentButStaticInventoryIncludesBothRegions() throws Exception {
        write("pom.xml", "workspace", "src/mod");
        write("src/mod/pom.xml", "mod", "debug");
        write("src/mod/debug/pom.xml", "debug");
        write("src/moddev/pom.xml", "moddev", "sample");
        write("src/moddev/sample/pom.xml", "sample", "main", "helper");
        write("src/moddev/sample/main/pom.xml", "main");
        write("src/moddev/sample/helper/pom.xml", "helper");
        write("src/moddev/independent/pom.xml", "independent", "main", "helper");
        write("src/moddev/independent/main/pom.xml", "independent-main");
        write("src/moddev/independent/helper/pom.xml", "independent-helper");
        assertEquals(Set.of("", "mod", "mod/debug"), paths(MavenModuleGraph.readDefault(root)));
        assertEquals(Set.of("", "mod", "mod/debug", "moddev", "moddev/sample", "moddev/sample/main", "moddev/sample/helper", "moddev/independent", "moddev/independent/main", "moddev/independent/helper"), paths(MavenModuleGraph.read(root)));
        var actual = ArchitectureSourceChecker.projectRoot();
        assertFalse(MavenModuleGraph.readDefault(actual).stream().anyMatch(m -> m.path().startsWith("moddev")));
        assertTrue(MavenModuleGraph.read(actual).stream().anyMatch(m -> m.path().equals("moddev")));
    }

    void write(String path, String artifact, String... modules) throws Exception {
        Path pom = root.resolve(path);
        Files.createDirectories(pom.getParent());
        String children = Arrays.stream(modules).map(value -> "<module>" + value + "</module>").collect(java.util.stream.Collectors.joining());
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion><artifactId>" + artifact + "</artifactId>"
            + (modules.length == 0 ? "" : "<packaging>pom</packaging><modules>" + children + "</modules>") + "</project>");
    }

    static Set<String> paths(List<MavenModuleGraph.Module> modules) {
        return modules.stream().map(MavenModuleGraph.Module::path).collect(java.util.stream.Collectors.toSet());
    }
}
