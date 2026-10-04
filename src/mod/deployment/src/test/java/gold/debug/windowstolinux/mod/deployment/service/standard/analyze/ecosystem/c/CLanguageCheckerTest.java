package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.SourceCheckFacts;
import org.junit.jupiter.api.Test;

class CLanguageCheckerTest {
    @Test
    void headerMarkersDoNotBecomeCompilationUnits() {
        var source = new SourceCheckFacts(2, List.of(Path.of("api.h"), Path.of("api.hpp")), "", "reviewed-source");
        var facts = new CLanguageChecker().inspect(source);
        assertEquals(Set.of(SourceLanguageType.C, SourceLanguageType.CPP), facts.sourceLanguages());
        assertTrue(CLanguageChecker.compilationLanguage("api.h").isEmpty());
        assertTrue(CLanguageChecker.compilationLanguage("api.hpp").isEmpty());
        assertEquals(SourceLanguageType.CPP, CLanguageChecker.compilationLanguage("src/MAIN.CXX").orElseThrow());
    }
}
