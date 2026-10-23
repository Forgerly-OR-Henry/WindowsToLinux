package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts;
import org.junit.jupiter.api.Test;

class PreviewLanguageMarkerCatalogTest {
    @Test
    void leavesExistingEcosystemsToTheirLanguageInspectors() {
        var paths = List.of("App.java", "app.js", "app.py", "main.c", "api.h", "main.cpp", "api.hpp", "main.go",
            "go.mod", "main.rs", "Cargo.toml", "Program.cs", "app.csproj", "Main.kt", "build.gradle.kts",
            "index.php", "composer.json", "main.rb", "Gemfile");
        var source = new SourceCheckFacts(paths.size(), paths.stream().map(Path::of).toList(), "", "reviewed-source");
        var facts = new PreviewLanguageMarkerCatalog().inspect(source);
        assertTrue(facts.ecosystems().isEmpty());
        assertTrue(facts.sourceLanguages().isEmpty());
        assertTrue(facts.evidence().isEmpty());
    }

    @Test
    void preservesLanguagesThatHaveNoDedicatedEcosystem() {
        var source = new SourceCheckFacts(3,
            List.of(Path.of("main.scala"), Path.of("script.lua"), Path.of("main.swift")), "", "reviewed-source");
        assertEquals(Set.of(SourceLanguageType.SCALA, SourceLanguageType.LUA, SourceLanguageType.SWIFT),
            new PreviewLanguageMarkerCatalog().inspect(source).sourceLanguages());
    }
}
