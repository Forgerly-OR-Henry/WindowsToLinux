package gold.debug.windowstolinux.shared.analyze.ecosystem.java.jar;

import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaJarManifestInspectorTest {
    @TempDir Path root;
    private final JavaJarManifestInspector inspector = new JavaJarManifestInspector();

    @Test
    void readsEntryAndVersionWithoutLoadingClasses() throws Exception {
        writeJar("example.Main", "21");
        var facts = inspector.inspect(root, Path.of("app.jar"));
        assertEquals("example.Main", facts.values().get(LanguageFactKind.JAVA_MAIN_CLASS));
        assertEquals("21", facts.values().get(LanguageFactKind.JAVA_VERSION));
        assertEquals(2, facts.evidence().size());
        assertTrue(facts.evidence().stream().allMatch(item -> item.source().equals("app.jar!META-INF/MANIFEST.MF")));
        assertTrue(facts.sourceLanguages().isEmpty());
    }

    @Test
    void retainsFutureVersionWhileRejectingMalformedEntryAndArchive() throws Exception {
        writeJar("example.Main; command", "99");
        assertEquals(java.util.Map.of(LanguageFactKind.JAVA_VERSION, "99"), inspector.inspect(root, Path.of("app.jar")).values());
        Files.writeString(root.resolve("app.jar"), "invalid archive");
        assertTrue(inspector.inspect(root, Path.of("app.jar")).values().isEmpty());
    }

    private void writeJar(String entry, String version) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, entry);
        manifest.getMainAttributes().putValue("Build-Jdk-Spec", version);
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(root.resolve("app.jar")), manifest)) {
            // Manifest-only fixture: no class can be loaded. / 仅有清单，无可加载的类。
        }
    }
}
