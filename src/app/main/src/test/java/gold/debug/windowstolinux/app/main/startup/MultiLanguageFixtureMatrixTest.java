package gold.debug.windowstolinux.app.main.startup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the independent fixture catalog; actual build and business checks use polyglot_runner.py. */
class MultiLanguageFixtureMatrixTest {
    @Test
    void eightIndependentSuccessProjectsCoverAllTwelveLanguages() throws Exception {
        Path root = repositoryRoot();
        Path fixtures = root.resolve("test/multi-language");
        JsonNode matrix = new ObjectMapper().readTree(fixtures.resolve("matrix.json").toFile());
        assertEquals(8, matrix.size());
        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        Set<String> languages = new HashSet<>();
        int browserProjects = 0;
        int databases = 0;
        for (JsonNode project : matrix) {
            String id = project.required("id").asText();
            assertTrue(ids.add(id), id);
            String path = project.required("path").asText();
            assertEquals("success-" + id, path);
            assertTrue(paths.add(path), path);
            Path directory = fixtures.resolve(path).normalize();
            assertEquals(fixtures, directory.getParent());
            for (String file : Set.of("README.md", "samples/README.md", ".gitignore")) {
                assertTrue(Files.isRegularFile(directory.resolve(file)), path + "/" + file);
            }
            assertEquals("success", project.required("expected").asText());
            assertTrue(project.required("languages").size() >= 2, id);
            project.required("languages").forEach(language -> languages.add(language.asText()));
            for (JsonNode component : project.required("components")) {
                assertTrue(Files.isDirectory(directory.resolve(component.asText())), id);
            }
            for (JsonNode dependency : project.required("dependencyManifests")) {
                assertTrue(Files.size(directory.resolve(dependency.asText())) > 0, id + ": " + dependency);
            }
            assertFalse(project.required("build").isEmpty(), id);
            assertFalse(project.required("run").isEmpty(), id);
            assertTrue(Files.isRegularFile(root.resolve(project.required("acceptance").required("runner").asText())));
            if (project.required("kind").asText().equals("web")) {
                browserProjects++;
                assertTrue(project.required("acceptance").required("browser").asBoolean());
            }
            if (!project.required("sqliteOwner").isNull()) {
                databases++;
                String owner = project.required("sqliteOwner").asText();
                assertTrue(Files.isDirectory(directory.resolve(owner)), id);
            }
        }
        assertEquals(Set.of("JAVA", "KOTLIN", "JAVASCRIPT", "TYPESCRIPT", "GO", "CSHARP",
                "PHP", "PYTHON", "RUBY", "RUST", "CPP", "C"), languages);
        assertEquals(5, browserProjects);
        assertEquals(6, databases);
        try (var directories = Files.list(fixtures)) {
            assertEquals(paths, directories.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        JsonNode singles = new ObjectMapper().readTree(root.resolve("test/single-language/matrix.json").toFile());
        // The single-language catalog may grow independently; its own matrix test verifies every scenario.
        assertTrue(singles.isArray() && !singles.isEmpty());
        for (JsonNode single : singles) {
            assertTrue(Files.isDirectory(root.resolve("test/single-language")
                    .resolve(single.required("path").asText())));
        }
        assertFalse(Files.exists(root.resolve("test/matrix.json")), "Old matrix path must not survive migration");
    }

    private static Path repositoryRoot() {
        for (Path current = Path.of("").toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("test/multi-language/matrix.json"))
                    && Files.isRegularFile(current.resolve("pom.xml"))) return current;
        }
        throw new IllegalStateException("WindowsToLinux repository root was not found");
    }
}
