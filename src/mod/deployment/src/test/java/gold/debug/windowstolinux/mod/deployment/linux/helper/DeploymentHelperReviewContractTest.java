package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeploymentHelperReviewContractTest {
    @Test
    void contractRequiresItsIndependentlyPinnedSourceAndCompleteGuard() {
        assertTrue(DeploymentHelperReviewContract.text().contains(DeploymentManagedHelperBundle.EXPECTED_SHA256));
        assertDoesNotThrow(() -> DeploymentHelperReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
            DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentManagedHelperBundle.buildEntrySha256()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION + 1,
            DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentManagedHelperBundle.buildEntrySha256()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
            "a".repeat(64), DeploymentManagedHelperBundle.buildEntrySha256()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
            DeploymentManagedHelperBundle.EXPECTED_SHA256, "a".repeat(64)));
        String invocation = "'" + DeploymentManagedHelperBundle.PATH + "' probe";
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(invocation);
        assertEquals(invocation, DeploymentHelperReviewContract.verifiedInvocation(guarded));
        String bounded = DeploymentManagedHelperBundle.verifiedCommand("WTL_SYSTEMD_QUERY_SECONDS=45 " + invocation);
        assertEquals(invocation, DeploymentHelperReviewContract.verifiedInvocation(bounded));
        assertTrue(bounded.contains("WTL_SYSTEMD_QUERY_SECONDS=45 "), "The complete approved request retains its budget");
        for (String budget : java.util.List.of("0", "045", "7201", "45;", "$untrusted", "45  "))
            assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.verifiedInvocation(
                DeploymentManagedHelperBundle.verifiedCommand("WTL_SYSTEMD_QUERY_SECONDS=" + budget + " " + invocation)));
        for (String changed : java.util.List.of(invocation, guarded.replace("-perm /022", "-perm /000"),
            guarded.replace(DeploymentManagedHelperBundle.EXPECTED_SHA256, "b".repeat(64)),
            guarded.replace("' probe", "' unknown-command"), guarded.replace("' probe", "' $untrusted")))
            assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.verifiedInvocation(changed));
    }
}
