package gold.debug.windowstolinux.app.mod.version;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class VersionRangeTest {
    boolean matches(String version, String range) {
        return VersionRange.parse(range).contains(SemanticVersion.parse(version));
    }

    @Test
    void numericSegmentsDoNotOverflowAndPrereleaseRequiresMatchingCoreBoundary() {
        assertTrue(matches("999999999999999999999999999999.0.0", "[1.0.0,)"));
        assertTrue(matches("1.0.0-dev.2", "[1.0.0-dev.1,1.0.0)"));
        assertFalse(matches("1.1.0-dev.1", "[1.0.0-dev.1,2.0.0)"));
        assertFalse(matches("1.0.0-beta.1", "[0.9.0,2.0.0)"));
        assertTrue(matches("1.0.0-beta.1", "1.0.0-beta.1"));
        assertFalse(matches("1.0.0", "[1.0.0-dev.1,1.0.0)"));
        assertTrue(matches("1.0.0", "[1.0.0,1.0.0]"));
        assertTrue(matches("0.0.0", "(,1.0.0]"));
    }

    @Test
    void canonicalSemverOrderingIncludesNumericPrereleasesAndFinalReleases() {
        var ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1");
        for (int i = 1; i < ordered.size(); i++)
            assertTrue(SemanticVersion.parse(ordered.get(i - 1)).compareTo(SemanticVersion.parse(ordered.get(i))) < 0);
        assertEquals(SemanticVersion.parse("1.0.0"), SemanticVersion.parse("1.0.0"));
        assertEquals(SemanticVersion.parse("1.0.0").hashCode(), SemanticVersion.parse("1.0.0").hashCode());
    }

    @Test
    void malformedVersionsAndRangesAreRejected() {
        for (var version : new String[]{"01.0.0", "1.0", "1.0.0+build", "1.0.0-01", "1.0.0 ", "-1.0.0", "1.0.0-",
            "9".repeat(513) + ".0.0"})
            assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse(version));
        for (var range : new String[]{"(,)", "[2.0.0,1.0.0)", "(1.0.0,1.0.0)", "[1.0.0,2.0.0", ">=1.0.0", "*",
            "[1.0.0,2.0.0),[3.0.0,4.0.0)"})
            assertThrows(IllegalArgumentException.class, () -> VersionRange.parse(range));
    }
}
