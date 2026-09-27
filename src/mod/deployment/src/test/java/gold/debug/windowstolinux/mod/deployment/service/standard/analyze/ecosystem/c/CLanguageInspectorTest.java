package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.SourceInspectionFacts;
import org.junit.jupiter.api.Test;

class CLanguageInspectorTest {
    @Test
    void headerMarkersDoNotBecomeCompilationUnits() {
        var source = new SourceInspectionFacts(2, List.of(Path.of("api.h"), Path.of("api.hpp")), "");
        var facts = new CLanguageInspector().inspect(source);
        assertEquals(Set.of(SourceLanguageType.C, SourceLanguageType.CPP), facts.sourceLanguages());
        assertTrue(CLanguageInspector.compilationLanguage("api.h").isEmpty());
        assertTrue(CLanguageInspector.compilationLanguage("api.hpp").isEmpty());
        assertEquals(SourceLanguageType.CPP, CLanguageInspector.compilationLanguage("src/MAIN.CXX").orElseThrow());
    }
}
