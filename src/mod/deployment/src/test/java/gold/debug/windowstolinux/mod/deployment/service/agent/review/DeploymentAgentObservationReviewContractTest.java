package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import org.junit.jupiter.api.Test;

class DeploymentAgentObservationReviewContractTest {
    @Test
    void independentlyPinnedObservationContractRejectsProtocolAndHelperDrift() {
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentAgentObservationReviewContract.HELPER_SHA256);
        assertEquals(DeploymentAgentObservationReviewContract.HELPER_SHA256, DeploymentAgentInputs.hash(DeploymentManagedHelperBundle.renderScript()));
        assertDoesNotThrow(() -> DeploymentAgentObservationReviewContract.requireIdentity(
            DeploymentManagedHelperBundle.PROTOCOL_VERSION, DeploymentManagedHelperBundle.EXPECTED_SHA256));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentObservationReviewContract.requireIdentity(
            DeploymentManagedHelperBundle.PROTOCOL_VERSION + 1, DeploymentManagedHelperBundle.EXPECTED_SHA256));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentObservationReviewContract.requireIdentity(
            DeploymentManagedHelperBundle.PROTOCOL_VERSION, "f".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentObservationReviewContract.requireIdentity(
            DeploymentManagedHelperBundle.PROTOCOL_VERSION, null));
        String contract = DeploymentAgentObservationReviewContract.text();
        assertEquals(contract, DeploymentAgentObservationReviewContract.text());
        assertTrue(contract.contains("PRE_EXECUTION"));
        assertTrue(contract.contains("Independent APP may still DENY"));
        assertTrue(contract.contains("Retained UNKNOWN/EXECUTING"));
    }

    @Test
    void pinnedAssemblyConfirmsObservationArgumentsAndDistinctNativeUnitGuards() {
        String script = DeploymentManagedHelperBundle.renderScript();
        String statusEntry = function(script, "publication_original_status");
        assertTrue(statusEntry.contains("[ \"$#\" -eq 7 ] || reject publication-status-arguments"));
        assertTrue(statusEntry.contains("publication_original_status_owned \"$1\" \"$2\" \"$3\" \"$3\" \"$4\" \"$5\" \"$6\" \"$7\" 0"));
        String status = function(script, "publication_original_status_owned");
        for (String guard : List.of("require_candidate \"$app\" \"$candidate_id\"", "publication-current-owner",
            "publication-snapshot-foreign", "publication-candidate-foreign", "publication-maintenance-foreign",
            "publication-build-query", "publication-release-foreign", "publication-release-count"))
            assertTrue(status.contains(guard), guard);
        assertFalse(status.contains("FragmentPath"));
        assertFalse(status.contains("DropInPaths"));
        for (String symbol : List.of("publication_original_status_owned", "inspect_managed_runtime", "observe_deployment")) {
            String body = function(script, symbol);
            for (String healthEffect : List.of("application_admit_run ", "application_maintenance ", "application_assert_no_orphan "))
                assertFalse(body.contains(healthEffect), symbol + ": " + healthEffect);
        }
        for (String symbol : List.of("inspect_managed_runtime", "observe_deployment")) {
            String body = function(script, symbol);
            assertTrue(body.contains("[ \"$#\" -eq 2 ]"));
            assertTrue(body.contains("assert_deployment_current_or_empty \"$app\" \"$manifest\""));
        }
        String nativeGuard = function(script, "assert_deployment_current_or_empty");
        for (String predicate : List.of("current-owner", "FragmentPath", "DropInPaths", "unit-digest", "load_deployment_parameters"))
            assertTrue(nativeGuard.contains(predicate), predicate);
        assertTrue(DeploymentAgentObservationReviewContract.text().contains("STATUS unit presence/state queries do not themselves"));
    }

    @Test
    void pinnedVerifyBranchUsesAnExistingSharedReadLockAndReturnsBeforePreparation() throws Exception {
        String fragment;
        try (var source = Objects.requireNonNull(DeploymentManagedHelperBundle.class.getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain/40-binding-protocol.py"))) {
            fragment = new String(source.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertTrue(DeploymentManagedHelperBundle.renderScript().contains(fragment));
        int verifying = fragment.indexOf("    if verifying:\n");
        int preparing = fragment.indexOf("    else:\n        prepare_shared_directories()", verifying);
        assertTrue(verifying >= 0 && preparing > verifying);
        String admission = fragment.substring(verifying, preparing);
        assertTrue(admission.contains("lock_path.is_file()"));
        assertTrue(admission.contains("lock_path.stat().st_uid != 0"));
        assertFalse(admission.contains("mkdir("));
        assertTrue(fragment.contains("with lock_path.open('r' if verifying else 'a') as lock:"));
        assertTrue(fragment.contains("fcntl.LOCK_SH if verifying else fcntl.LOCK_EX"));
        assertTrue(fragment.contains("| fcntl.LOCK_NB"));
        assertTrue(DeploymentAgentObservationReviewContract.text().contains("shared nonblocking flock (LOCK_SH | LOCK_NB)"));
        int verifyBody = fragment.indexOf("        if verifying:\n", preparing);
        int prepareDispatch = fragment.indexOf("        if len(args) == 2 and args[0] == 'bind':", verifyBody);
        String body = fragment.substring(verifyBody, prepareDispatch);
        assertTrue(body.contains("hashlib.sha256(payload).hexdigest() != args[1]"));
        assertTrue(body.contains("verify_system(record)\n            return\n"));
        assertFalse(body.contains("prepare_shared_directories()"));
        assertFalse(body.contains("download("));
        assertTrue(DeploymentAgentObservationReviewContract.text().contains("engine's internal implementation has absolutely no writes"));
    }

    private static String function(String script, String symbol) {
        String anchor = symbol + "() {\n";
        int start = script.indexOf(anchor);
        assertTrue(start >= 0, symbol);
        assertEquals(start, script.lastIndexOf(anchor), symbol);
        int end = script.indexOf("\n}\n", start);
        assertTrue(end > start, symbol);
        return script.substring(start, end + 3);
    }
}
