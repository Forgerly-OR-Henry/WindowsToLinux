package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentInputCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PREFIX = "/completeExecutableInput";

    private static CommandRequest request(String script, String stdin) {
        return new CommandRequest(script, stdin.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5), 8192);
    }

    private static ObjectNode facts(String stdin) {
        return JSON.valueToTree(Map.of("inputKind", "EXECUTABLE_SCRIPT", "completeExecutableInput", stdin));
    }

    private static JsonNode input(DeploymentAgentInputCoverage coverage) {
        return JSON.valueToTree(Map.of("executableInput", coverage.inlineInput(), "currentRequest", Map.of()));
    }

    private static Map<String, Object> page(String original, int start, int end) {
        return Map.of("offset", start, "next", end, "total", original.length(), "sha256", DeploymentAgentInputs.hash(original),
            "text", original.substring(start, end));
    }

    private static void deliver(DeploymentAgentInputCoverage coverage, String tool, String route, String original, int start, int end) {
        coverage.returned(tool, JSON.valueToTree(Map.of(tool.equals("request") ? "part" : "pointer", route)), page(original, start, end), "actual-result");
        coverage.delivered(input(coverage), "actual-result"::equals);
    }

    private static ObjectNode structured() {
        var value = JSON.createObjectNode();
        value.put("format", "ORDERED_SHELL_LITERAL_V1");
        value.putObject("sources").put("managedHelper", "#helper\n" + "h".repeat(1100)).put("buildEntry", "#entry\n" + "b".repeat(700));
        var segments = value.putArray("segments");
        segments.addObject().put("kind", "LITERAL").put("text", "printf prefix\n" + "p".repeat(600));
        segments.addObject().put("kind", "SHELL_SINGLE_QUOTE").put("source", "managedHelper");
        segments.addObject().put("kind", "LITERAL").put("text", "\nprintf middle\n");
        segments.addObject().put("kind", "SHELL_SINGLE_QUOTE").put("source", "buildEntry");
        segments.addObject().put("kind", "LITERAL").put("text", "\nprintf tail\n" + "t".repeat(600));
        String executable = DeploymentInstallerEvidence.reconstruct(value);
        value.put("sha256", DeploymentAgentInputs.hash(executable));
        value.put("utf8Bytes", executable.getBytes(StandardCharsets.UTF_8).length);
        return value;
    }

    @ParameterizedTest
    @ValueSource(strings = {"STDIN", "COMPLETE", "LEAVES", "PARENTS"})
    void losslesslyBoundStructuredRoutesAndOriginalStdinAreEquivalent(String route) throws Exception {
        var structure = structured();
        String executable = DeploymentInstallerEvidence.reconstruct(structure);
        var facts = facts(executable).set("completeExecutableInput", structure);
        try (var request = request("/bin/bash", executable)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts);
            coverage.delivered(input(coverage), body -> false);
            assertFalse(coverage.complete());
            if (route.equals("STDIN")) deliver(coverage, "request", "stdin", executable, 0, executable.length());
            else if (route.equals("COMPLETE")) {
                String body = structure.toString();
                deliver(coverage, "evidence", PREFIX, body, 0, body.length());
            } else {
                var pointers = route.equals("PARENTS") ? List.of("/segments", "/sources")
                    : List.of("/segments/4/text", "/sources/buildEntry", "/segments/2/text", "/sources/managedHelper", "/segments/0/text");
                for (String pointer : pointers) {
                    var node = structure.at(pointer);
                    String body = node.isTextual() ? node.textValue() : node.toString();
                    deliver(coverage, "evidence", PREFIX + pointer, body, 0, body.length());
                }
            }
            assertTrue(coverage.complete());
        }
    }

    @Test
    void oneCompleteHelperStillLeavesWrappersEntryAndTailMissing() throws Exception {
        var structure = structured();
        String executable = DeploymentInstallerEvidence.reconstruct(structure);
        try (var request = request("/bin/bash", executable)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts(executable).set("completeExecutableInput", structure));
            String helper = structure.at("/sources/managedHelper").asText();
            deliver(coverage, "evidence", PREFIX + "/sources/managedHelper", helper, 0, helper.length());
            assertFalse(coverage.complete());
            assertEquals(executable.length(), JSON.valueToTree(coverage.missing()).at("/missingRanges/0/ranges/0/endExclusive").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TAIL", "GAP", "HASH", "TEXT", "TOTAL", "SUMMARY", "NOTES", "FOREIGN_ROUTE"})
    void partialWrongOrUnrelatedResultsCannotManufactureDelivery(String kind) throws Exception {
        String original = "中🙂".repeat(400);
        try (var request = request("printf actual", original)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts(original));
            coverage.delivered(input(coverage), result -> false);
            var value = JSON.valueToTree(page(original, 0, original.length()));
            String tool = "request", route = "stdin";
            switch (kind) {
                case "TAIL" -> value = JSON.valueToTree(page(original, 0, original.length() - 1));
                case "GAP" -> {
                    deliver(coverage, tool, route, original, 0, 300);
                    value = JSON.valueToTree(page(original, 301, original.length()));
                }
                case "HASH" -> ((ObjectNode) value).put("sha256", "f".repeat(64));
                case "TEXT" -> ((ObjectNode) value).put("text", "x".repeat(original.length()));
                case "TOTAL" -> ((ObjectNode) value).put("total", original.length() + 1);
                case "SUMMARY" ->
                    value = JSON.valueToTree(Map.of("sha256", DeploymentAgentInputs.hash(original), "summary", "Everything was read"));
                case "NOTES" -> tool = "save_review_notes";
                case "FOREIGN_ROUTE" -> {
                    tool = "evidence";
                    route = "/otherTask/completeExecutableInput";
                }
                default -> throw new AssertionError(kind);
            }
            coverage.returned(tool, JSON.valueToTree(Map.of(tool.equals("request") ? "part" : "pointer", route)), value, "page");
            coverage.delivered(input(coverage), "page"::equals);
            assertFalse(coverage.complete());
        }
    }

    @Test
    void untransmittedOrTrimmedPendingBodyIsNotDeliveryButPreviouslyDeliveredBodyRemainsRecorded() throws Exception {
        String original = "x".repeat(1200);
        try (var request = request("printf actual", original)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts(original));
            coverage.returned("request", JSON.valueToTree(Map.of("part", "stdin")), page(original, 0, original.length()), "not-visible");
            assertFalse(coverage.complete());
            coverage.delivered(input(coverage), result -> false);
            assertFalse(coverage.complete());
            coverage.delivered(input(coverage), "not-visible"::equals);
            assertFalse(coverage.complete(), "A result discarded before transmission cannot later reappear as evidence");
            deliver(coverage, "request", "stdin", original, 0, original.length());
            coverage.delivered(input(coverage), result -> false);
            assertTrue(coverage.complete(), "Delivery can accumulate; it makes no claim of current understanding");
            var fresh = new DeploymentAgentInputCoverage(request, facts(original));
            fresh.delivered(input(fresh), result -> false);
            assertFalse(fresh.complete(), "A new review never inherits an old delivery record");
        }
    }

    @Test
    void originalShortInlineBodiesCountButDescriptorsAndFalseInlineTextDoNot() throws Exception {
        try (var request = request("printf actual", "printf short\n")) {
            var coverage = new DeploymentAgentInputCoverage(request, facts("printf short\n"));
            assertFalse(coverage.complete());
            coverage.delivered(input(coverage), result -> false);
            assertTrue(coverage.complete());
        }
        String original = "x".repeat(600);
        try (var request = request(original, original)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts(original));
            coverage.delivered(input(coverage), result -> false);
            assertFalse(coverage.complete());
            var fake = (ObjectNode) input(coverage);
            ((ObjectNode) fake.at("/executableInput/script")).put("text", "digest is enough");
            ((ObjectNode) fake.at("/executableInput/stdin")).put("text", "already reviewed");
            coverage.delivered(fake, result -> false);
            assertFalse(coverage.complete());
        }
    }

    @Test
    void recordCapacityCannotFillUnrecordedGapsAndWholeOriginalBodyCanStillSatisfyDelivery() throws Exception {
        String original = "x".repeat(1000);
        try (var request = request("printf actual", original)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts(original));
            for (int i = 0; i < 70; i++) deliver(coverage, "request", "stdin", original, i * 2, i * 2 + 1);
            assertFalse(coverage.complete());
            deliver(coverage, "request", "stdin", original, 0, original.length());
            assertTrue(coverage.complete());
        }
    }

    @Test
    void protectedOpaqueBytesStayOpaqueAndMissingClassificationOrChangedReconstructionCloses() throws Exception {
        try (var request = new CommandRequest("printf actual", new byte[]{(byte) 0xff, 0, 1}, Duration.ofSeconds(5), 8192)) {
            var coverage = new DeploymentAgentInputCoverage(request, JSON.valueToTree(Map.of("inputKind", "OPAQUE_BOUND_DATA")));
            assertFalse(JSON.writeValueAsString(coverage.inlineInput()).contains("stdin"));
            coverage.delivered(input(coverage), result -> false);
            assertTrue(coverage.complete());
            assertThrows(IOException.class, () -> new DeploymentAgentInputCoverage(request, JSON.createObjectNode()));
            assertThrows(IOException.class, () -> new DeploymentAgentInputCoverage(request, JSON.valueToTree(Map.of("inputKind", "EMPTY"))));
        }
        var structure = structured();
        String original = DeploymentInstallerEvidence.reconstruct(structure);
        ((ObjectNode) structure.path("sources")).put("managedHelper", "changed actual source");
        try (var request = request("/bin/bash", original)) {
            assertThrows(IOException.class, () -> new DeploymentAgentInputCoverage(request, facts(original).set("completeExecutableInput", structure)));
            assertThrows(IOException.class, () -> new DeploymentAgentInputCoverage(request, facts("different original stdin")));
        }
    }
}
