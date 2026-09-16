package gold.debug.windowstolinux.shared.model.toolchain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolchainReleaseIdentityTest {
    @Test void buildNumbersAreIdentityButNotCompatibilityOrder() {
        var first = ToolchainVersion.parse(ToolchainEcosystemType.JAVA, "21.0.2+13").orElseThrow();
        var second = ToolchainVersion.parse(ToolchainEcosystemType.JAVA, "21.0.2+14").orElseThrow();
        assertEquals(0, first.compareTo(second));
        assertFalse(first.sameRelease(second));
        assertTrue(first.sameRelease(ToolchainVersion.parse(ToolchainEcosystemType.JAVA, "jdk-21.0.2+13").orElseThrow()));
    }
}
