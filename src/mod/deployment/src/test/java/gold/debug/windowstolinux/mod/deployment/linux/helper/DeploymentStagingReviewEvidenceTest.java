package gold.debug.windowstolinux.mod.deployment.linux.helper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the security implementation actually available to the independent staging reviewer. */
class DeploymentStagingReviewEvidenceTest {
    @Test
    void includesTheCompleteStorageParentGuardFromThePinnedHelper() throws Exception {
        var layout = DeploymentRemoteLayout.original();
        String helper = DeploymentManagedHelperBundle.renderScript(layout);
        var facts = DeploymentManagedHelperBundle.configurationStagingImplementationFacts(layout);
        var sources = (List<?>) facts.get("sources");
        var reviewed = new StringBuilder();
        for (var value : sources) {
            var source = (Map<?, ?>) value;
            String body = (String) source.get("source");
            assertTrue(helper.contains(body), "Review code must be exact pinned helper source");
            assertEquals(source.get("sha256"), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8))));
            reviewed.append(body);
        }
        int begin = helper.indexOf("assert_storage_parent() {\n");
        int end = helper.indexOf("parse_managed_data_bindings() {\n", begin);
        String guard = helper.substring(begin, end);
        assertTrue(guard.contains("reject managed-storage-parent-owner"));
        assertTrue(guard.contains("reject managed-storage-parent-writable"));
        assertTrue(guard.contains("reject managed-storage-link-boundary"));
        assertTrue(reviewed.toString().contains(guard), "The reviewer must receive the full called parent guard");
        assertEquals(DeploymentManagedHelperBundle.helperSha256(layout), facts.get("helperSha256"));
    }
}
