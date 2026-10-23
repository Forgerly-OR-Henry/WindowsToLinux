package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProjectLanguageCheckerTest {
    @TempDir
    Path root;

    private final ProjectLanguageChecker inspector = new ProjectLanguageChecker();

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
        var source = new SourceCheckFacts(1, List.of(Path.of(file)), "must not execute", "reviewed-source");
        var facts = inspector.inspect(root, source);
        assertEquals(Set.of(ecosystem), facts.ecosystems());
        assertEquals(Set.of(language), facts.sourceLanguages());
        assertTrue(facts.values().isEmpty());
        assertEquals(1, facts.evidence().size());
        assertEquals(Path.of(file).toString(), facts.evidence().getFirst().source());
    }

    @Test
    void mergesMixedLanguagesAndKeepsOneMarkerPerLanguage() throws Exception {
        var source = new SourceCheckFacts(7, List.of(Path.of("main.c"), Path.of("other.c"), Path.of("main.cpp"),
            Path.of("go.mod"), Path.of("main.go"), Path.of("script.lua"), Path.of("README.md")), "", "reviewed-source");
        var facts = inspector.inspect(root, source);
        assertEquals(
            Set.of(SourceLanguageType.C, SourceLanguageType.CPP, SourceLanguageType.GO, SourceLanguageType.LUA),
            facts.sourceLanguages());
        assertEquals(4, facts.evidence().size());
        assertTrue(facts.values().isEmpty());
    }

    @Test
    void doesNotInferLanguageFromBuildConfigurationTextOrUnknownFiles() throws Exception {
        var source = new SourceCheckFacts(2, List.of(Path.of("CMakeLists.txt"), Path.of("README.md")),
            "project(example LANGUAGES C CXX)", "reviewed-source");
        assertTrue(inspector.inspect(root, source).sourceLanguages().isEmpty());
    }
}
