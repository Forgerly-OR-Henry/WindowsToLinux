package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.config.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentBuildIsolationEvidenceRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exposesActualRenderedControllerAndWholeHashBoundEntryWithinExistingRequestBudget(boolean custom) throws Exception {
        var layout = custom ? new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data")) : DeploymentRemoteLayout.original();
        var facts = DeploymentManagedHelperBundle.buildIsolationImplementationFacts(layout);
        String helper = DeploymentManagedHelperBundle.renderScript(layout);
        assertEquals(DeploymentManagedHelperBundle.helperSha256(layout), facts.get("helperSha256"));
        var sources = (List<Map<String, Object>>) facts.get("sources");
        for (var source : sources) {
            String text = source.get("source").toString();
            assertTrue(helper.contains(text));
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))), source.get("sha256"));
        }
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(layout), facts.get("buildEntrySource"));
        assertEquals(DeploymentManagedHelperBundle.buildEntrySha256(layout), facts.get("buildEntrySha256"));
        assertTrue(helper.contains(facts.get("buildEntrySha256").toString()));
        String controller = sources.get(1).get("source").toString();
        assertTrue(controller.contains("--property=DynamicUser=yes"));
        assertTrue(controller.contains("--property=NoNewPrivileges=yes"));
        assertTrue(controller.contains("--property=NoNewPrivileges=no"), "Container branch exceptions must remain visible");
        assertTrue(controller.contains("ReadWritePaths=$mutable"));
        assertTrue(facts.get("buildEntrySource").toString().contains("[ \"$(id -u)\" -gt 0 ]"));
        assertFalse(facts.containsKey("liveIsolation"));
        assertFalse(facts.containsKey("commandGrant"));
        assertTrue(DeploymentAiJson.facts(facts, 1048576).length() < 1048576);
    }
}
