package probe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionProbeTest {
    @Test void numericSegmentsDoNotOverflowAndPrereleaseRequiresMatchingCoreBoundary() {
        assertTrue(VersionRule.matches("999999999999999999999999999999.0.0","[1.0.0,)"));
        assertTrue(VersionRule.matches("1.0.0-dev.2","[1.0.0-dev.1,1.0.0)"));
        assertFalse(VersionRule.matches("1.1.0-dev.1","[1.0.0-dev.1,2.0.0)"));
        assertFalse(VersionRule.matches("1.0.0-beta.1","[0.9.0,2.0.0)"));
        assertTrue(VersionRule.matches("1.0.0-beta.1","1.0.0-beta.1"));
        assertFalse(VersionRule.matches("1.0.0","[1.0.0-dev.1,1.0.0)"));
        assertTrue(VersionRule.parse("1.0.0-11").compareTo(VersionRule.parse("1.0.0-2"))>0);
    }
    @Test void malformedVersionsAndRangesAreRejected() {
        for(var version:new String[]{"01.0.0","1.0","1.0.0+build","1.0.0-01","1.0.0 ","-1.0.0"})assertThrows(IllegalArgumentException.class,()->VersionRule.parse(version));
        for(var range:new String[]{"(,)","[2.0.0,1.0.0)","(1.0.0,1.0.0)","[1.0.0,2.0.0",">=1.0.0","*"})assertThrows(IllegalArgumentException.class,()->VersionRule.matches("1.0.0",range));
    }
}
