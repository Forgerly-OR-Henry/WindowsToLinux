package gold.debug.windowstolinux.shared.analyze.ecosystem.java;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLanguageInspectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void reportsSourceAndManifestFactsWithoutLoadingClasses() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("src"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Main");
        manifest.getMainAttributes().putValue("Build-Jdk-Spec", "21");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(temporaryDirectory.resolve("app.jar")), manifest)) {
            // A manifest-only archive proves metadata handling without class loading. / 仅含清单的归档可在不加载类的情况下证明元数据处理。
        }
        SourceInspectionFacts source = new SourceInspectionFacts(1, List.of(Path.of("src/App.java"), Path.of("app.jar")), "class App {}");

        var facts = new JavaLanguageInspector().inspect(temporaryDirectory, source);

        assertTrue(facts.ecosystems().contains(LanguageEcosystemType.JAVA));
        assertTrue(facts.sourceLanguages().contains(SourceLanguageType.JAVA));
        assertEquals("example.Main", facts.values().get(LanguageFactKind.JAVA_MAIN_CLASS));
        assertEquals("21", facts.values().get(LanguageFactKind.JAVA_VERSION));
    }
}
