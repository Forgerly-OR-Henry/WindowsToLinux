package gold.debug.windowstolinux.shared.analyze.ecosystem.c;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
