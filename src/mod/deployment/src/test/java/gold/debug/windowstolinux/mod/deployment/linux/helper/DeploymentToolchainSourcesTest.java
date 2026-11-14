package gold.debug.windowstolinux.mod.deployment.linux.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentToolchainSourcesTest {
    @Test
    void pureResourceExtractionKeepsDefaultHelperBytesUnchanged() {
        assertEquals("465dfa7284068239e65f34ad8d927a2b67209fa9ab7a0535038304f75dc37466", DeploymentManagedHelperBundle.EXPECTED_SHA256);
        String helper = DeploymentManagedHelperBundle.renderScript();
        String pure = DeploymentToolchainSources.archives();
        assertTrue(helper.contains(pure));
        assertTrue(pure.contains("def owned_directory(path):"));
        assertTrue(pure.contains("def extract(archive, destination):"));
        assertFalse(pure.contains("def run("));
        assertFalse(pure.contains("subprocess.Popen"));
        String metadata = DeploymentToolchainSources.metadata();
        assertTrue(helper.contains(metadata));
        assertTrue(metadata.contains("SHIPPED_CATALOG = {"));
        assertTrue(metadata.contains("def official_url(url, historical=False):"));
        assertTrue(metadata.contains("def task_mirrors():"));
        assertTrue(metadata.contains("MIRROR_MODULE['MirrorPolicy'].from_environment(globals().get('DEADLINE'))"));
        assertTrue(metadata.contains("module['mirror_transfer'](policy, point, official_url(url), target,"));
        assertFalse(metadata.contains("__WTL_MIRROR_RUNTIME_SHA256__"));
        assertFalse(metadata.contains("def install("));
        assertFalse(metadata.contains("def probe("));
    }
}
