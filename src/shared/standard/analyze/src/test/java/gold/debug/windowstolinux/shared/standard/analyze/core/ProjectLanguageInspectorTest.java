package gold.debug.windowstolinux.shared.standard.analyze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProjectLanguageInspectorTest {
    @TempDir
    Path root;

    private final ProjectLanguageInspector inspector = new ProjectLanguageInspector();

    @ParameterizedTest
    @CsvSource({"src/App.java, JAVA, JAVA", "src/app.js, NODE_JS, JAVASCRIPT", "src/app.ts, NODE_JS, TYPESCRIPT",
            "src/app.py, PYTHON, PYTHON", "src/main.c, NATIVE, C", "include/api.h, NATIVE, C",
            "src/main.cc, NATIVE, CPP", "src/main.CPP, NATIVE, CPP", "src/main.cxx, NATIVE, CPP",
            "include/api.hpp, NATIVE, CPP", "src/main.go, GO, GO", "nested/GO.MOD, GO, GO", "src/main.rs, RUST, RUST",
            "nested/Cargo.toml, RUST, RUST", "src/Program.cs, DOTNET, CSHARP", "nested/App.csproj, DOTNET, CSHARP",
            "src/Main.kt, KOTLIN, KOTLIN", "build.gradle.kts, KOTLIN, KOTLIN", "public/index.php, PHP, PHP",
            "nested/composer.json, PHP, PHP", "src/main.rb, RUBY, RUBY", "nested/Gemfile, RUBY, RUBY"})
    void recognizesEveryExistingEcosystemWithoutRequiringBuildDefinitions(String file, LanguageEcosystemType ecosystem,
            SourceLanguageType language) throws Exception {
        var source = new SourceInspectionFacts(1, List.of(Path.of(file)), "must not execute");
        var facts = inspector.inspect(root, source);
        assertEquals(Set.of(ecosystem), facts.ecosystems());
        assertEquals(Set.of(language), facts.sourceLanguages());
        assertTrue(facts.values().isEmpty());
        assertEquals(1, facts.evidence().size());
        assertEquals(Path.of(file).toString(), facts.evidence().getFirst().source());
    }

    @Test
    void mergesMixedLanguagesAndKeepsOneMarkerPerLanguage() throws Exception {
        var source = new SourceInspectionFacts(7, List.of(Path.of("main.c"), Path.of("other.c"), Path.of("main.cpp"),
                Path.of("go.mod"), Path.of("main.go"), Path.of("script.lua"), Path.of("README.md")), "");
        var facts = inspector.inspect(root, source);
        assertEquals(
                Set.of(SourceLanguageType.C, SourceLanguageType.CPP, SourceLanguageType.GO, SourceLanguageType.LUA),
                facts.sourceLanguages());
        assertEquals(4, facts.evidence().size());
        assertTrue(facts.values().isEmpty());
    }

    @Test
    void doesNotInferLanguageFromBuildConfigurationTextOrUnknownFiles() throws Exception {
        var source = new SourceInspectionFacts(2, List.of(Path.of("CMakeLists.txt"), Path.of("README.md")),
                "project(example LANGUAGES C CXX)");
        assertTrue(inspector.inspect(root, source).sourceLanguages().isEmpty());
    }
}
