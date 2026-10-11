package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentHelperSourceEvidenceTest {
    @Test
    @SuppressWarnings("unchecked")
    void selectedRangesMatchActualAssembledLinesAndPinnedIdentityWithExplicitLimits() {
        String script = DeploymentManagedHelperBundle.renderScript();
        var facts = DeploymentAgentHelperSourceEvidence.facts();
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentAgentInputs.hash(script));
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, facts.get("helperSha256"));
        assertEquals(DeploymentManagedHelperBundle.PROTOCOL_VERSION, facts.get("helperProtocol"));
        var lines = script.split("\n", -1);
        var spans = (List<DeploymentAgentHelperSourceEvidence.Span>) facts.get("spans");
        var symbols = new HashMap<String, DeploymentAgentHelperSourceEvidence.Span>();
        int selectedBytes = 0;
        for (var span : spans) {
            assertNull(symbols.put(span.symbol(), span));
            assertTrue(span.assembledStartLine() > 0 && span.assembledEndLine() >= span.assembledStartLine());
            assertEquals(String.join("\n", Arrays.copyOfRange(lines, span.assembledStartLine() - 1, span.assembledEndLine())) + "\n",
                span.text(), span.symbol());
            assertEquals(DeploymentAgentInputs.hash(span.text()), span.textSha256(), span.symbol());
            selectedBytes += span.text().getBytes(StandardCharsets.UTF_8).length;
            if (span.scope().equals("COMPLETE_FUNCTION")) {
                assertTrue(span.text().startsWith(span.symbol() + "() {"));
                assertTrue(span.text().stripTrailing().endsWith("}"));
            }
        }
        assertTrue(selectedBytes < script.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(symbols.keySet().containsAll(Set.of("application_health", "application_admit_run", "application_lock_directory",
            "application_assert_no_orphan", "application_load_current", "application_assert_inputs", "parse_application_health_payload",
            "parse_application_payload", "parse_application_health", "load_deployment_parameters", "assert_deployment_current_or_empty",
            "expected_deployment_unit_digest", "render_deployment_unit", "publication_original_status_owned",
            "application_process_identity", "application_udp_owned", "application_tcp_response", "application_udp_response", "application_health_loaded")));
        assertTrue(symbols.get("render_deployment_unit").text().contains("/sys/fs/selinux/enforce"));
        assertFalse(symbols.get("render_deployment_unit").text().contains("# @compat:selinux-entry@"));
        assertTrue(symbols.get("application_process_identity").text().contains("${application_health_unit:-$(unit_name"));
        assertTrue(symbols.get("application_process_identity").text().contains("${application_health_container:-$(container_name"));
        assertTrue(symbols.get("application_admit_run").text().contains("${application_validation:-0}"));
        assertTrue(symbols.get("publication_original_status_owned").text().contains("$base_root/application-locks/$app/maintenance"));
        assertTrue(((List<String>) facts.get("unexpandedRelevantDefinitions")).contains("restore_application_health"));
        assertTrue(facts.get("dependencyBoundary").toString().contains("not a complete execution dependency closure"));
        assertTrue(facts.get("sideEffects").toString().contains("not strictly read-only"));
        assertTrue(facts.get("listenerLimits").toString().contains("does not bind the particular HTTP connection/response socket"));
        assertTrue(facts.get("optionalOverrides").toString().contains("remain verbatim"));
    }

    @Test
    void dispatchUsesExactStandaloneCallAndRejectsMissingOrDuplicateCalls() {
        String script = DeploymentManagedHelperBundle.renderScript();
        assertTrue(script.contains("require_root() {\n"));
        var dispatch = DeploymentAgentHelperSourceEvidence.extractRanges(script).stream()
            .filter(span -> span.symbol().equals("health_dispatch_prefix")).findFirst().orElseThrow();
        assertTrue(dispatch.text().startsWith("require_root\n[ \"$#\" -ge 1 ]"));
        assertTrue(dispatch.text().contains("application-health) application_health \"$@\" ;;"));
        assertFalse(dispatch.text().contains("require_root() {"));
        assertFalse(dispatch.text().contains("    app-run)"));
        assertThrows(IllegalStateException.class, () -> DeploymentAgentHelperSourceEvidence.extractRanges(script + "require_root\n"));
        assertThrows(IllegalStateException.class,
            () -> DeploymentAgentHelperSourceEvidence.extractRanges(script.replace("\nrequire_root\n", "\nmissing_root_call\n")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "reversed", "range-bounds"})
    void changedFixedAnchorsOrSourceBoundsAreRejected(String fault) {
        String script = DeploymentManagedHelperBundle.renderScript();
        String changed = switch (fault) {
            case "missing" -> script.replace("reject() {", "missing_reject() {");
            case "duplicate" -> script + "reject() {\n}\n";
            case "reversed" -> script.replace("reject() {", "TEMP_ROOT_ANCHOR")
                .replace("require_root() {", "reject() {").replace("TEMP_ROOT_ANCHOR", "require_root() {");
            case "range-bounds" -> script.replace("reject() {\n", "reject() {\n#" + "x".repeat(32768) + "\n");
            default -> throw new AssertionError(fault);
        };
        assertThrows(IllegalStateException.class, () -> DeploymentAgentHelperSourceEvidence.extractRanges(changed));
    }

    @Test
    void completeSourceDriftCannotAcquirePinnedEvidence() {
        String script = DeploymentManagedHelperBundle.renderScript();
        String changed = script.replace("helper_protocol=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\n", "helper_protocol=999\n");
        assertNotEquals(script, changed);
        assertThrows(IllegalStateException.class, () -> DeploymentAgentHelperSourceEvidence.fromScript(changed));
    }
}
