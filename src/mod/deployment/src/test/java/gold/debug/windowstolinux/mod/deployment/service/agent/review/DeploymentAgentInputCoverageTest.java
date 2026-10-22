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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trackedRecipeAndActualControllerEachRequireCompleteDelivery(boolean inline) throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        String operation = "a".repeat(32);
        var argv = List.of("prepare", "GO", "1.24", "1.24.13", "90", "-", operation,
            gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainReleaseTest
                .encoded("GO", "1.24", "1.24.13", operation));
        var recipe = gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainTracked.create(
            java.util.UUID.randomUUID().toString(), "server", "SHA256:" + "A".repeat(43), layout, "PREPARE", operation, argv, inline);
        String source = inline
            ? gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.existingController(layout)
            : gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.controller(layout);
        String raw = new String(recipe.bytes(), StandardCharsets.UTF_8);
        String sourcePointer = inline ? PREFIX : "/agentToolchainInstalledInputBinding/controllerSource";
        var facts = JSON.createObjectNode().put("inputKind", "TRACKED_TOOLCHAIN_REQUEST");
        if (inline) facts.put("completeExecutableInput", source);
        else facts.putObject("agentToolchainInstalledInputBinding").put("controllerSource", source);
        facts.putObject("agentTrackedToolchain").put("recipeUtf8", raw).put("invocationKind", inline ? "EXISTING_INLINE" : "INSTALLED_LOADER");
        String stdin = inline ? source + raw : raw;
        try (var request = request("fixed reviewed launcher", stdin)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts);
            coverage.delivered(input(coverage), ignored -> false);
            if (!inline) {
                deliverAll(coverage, "request", "stdin", raw);
                assertFalse(coverage.complete(), "Recipe delivery cannot stand in for the actual executed controller");
            }
            deliverAll(coverage, "evidence", sourcePointer, source);
            if (inline) {
                assertFalse(coverage.complete(), "Controller source alone cannot stand in for the appended original recipe");
                deliverAll(coverage, "evidence", "/agentTrackedToolchain/recipeUtf8", raw);
            }
            assertTrue(coverage.complete());
            var changed = facts.deepCopy();
            if (inline) changed.put("completeExecutableInput", source + "# changed\n");
            else ((ObjectNode) changed.path("agentToolchainInstalledInputBinding")).put("controllerSource", source + "# changed\n");
            assertThrows(IOException.class, () -> new DeploymentAgentInputCoverage(request, changed));
        }
        try (var changed = request("fixed reviewed launcher", stdin + " ")) {
            assertThrows(IOException.class, () -> new DeploymentAgentInputCoverage(changed, facts));
        }
        if (inline) try (var request = request("fixed reviewed launcher", stdin)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts);
            deliverAll(coverage, "request", "stdin", stdin);
            assertTrue(coverage.complete(), "The exact full stdin really contains both the source and recipe");
        }
    }

    private static void deliverAll(DeploymentAgentInputCoverage coverage, String tool, String route, String body) {
        for (int start = 0; start < body.length(); start += 8000)
            deliver(coverage, tool, route, body, start, Math.min(start + 8000, body.length()));
    }

    private static CommandRequest request(String script, String stdin) {
        return new CommandRequest(script, stdin.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5), 8192);
    }

    private static ObjectNode facts(String stdin) {
        return JSON.valueToTree(Map.of("inputKind", "EXECUTABLE_SCRIPT", "completeExecutableInput", stdin));
    }

    private static JsonNode input(DeploymentAgentInputCoverage coverage) {
        return JSON.valueToTree(Map.of("executableInput", coverage.inlineInput(512, DeploymentAgentInputCoverage.INLINE_PAYLOAD_BYTES), "currentRequest", Map.of()));
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

    @Test
    void onePageInlineBodiesShareRawAndEncodedBudgets() throws Exception {
        for (String body : List.of("x".repeat(7000), "中".repeat(5000), "\u0001".repeat(3000), "🙂".repeat(4000))) {
            try (var request = request("printf actual", body)) {
                var coverage = new DeploymentAgentInputCoverage(request, facts(body));
                var inline = JSON.valueToTree(coverage.inlineInput(8000, 14000));
                assertTrue(JSON.writeValueAsBytes(inline).length <= 14000);
                int chars = inline.path("script").path("text").asText().length() + inline.path("stdin").path("text").asText().length();
                assertTrue(chars <= 8000);
                if (body.startsWith("x")) assertEquals(body, inline.at("/stdin/text").asText());
                else
                    assertFalse(inline.path("stdin").has("text"), "No partial text may stand in for the original body");
                assertEquals(body.length(), inline.at("/stdin/total").asInt());
                assertFalse(coverage.complete(), "Presentation alone is not delivery");
                System.out.println("APP_INLINE_MEASUREMENT originalUtf16=" + body.length() + " encodedUtf8Bytes=" + JSON.writeValueAsBytes(inline).length + " bodyPreloaded=" + inline.path("stdin").has("text"));
            }
        }
        try (var request = request("s".repeat(4100), "i".repeat(4100))) {
            var coverage = new DeploymentAgentInputCoverage(request, facts("i".repeat(4100)));
            var inline = JSON.valueToTree(coverage.inlineInput(8000, 14000));
            assertTrue(inline.path("script").has("text"));
            assertFalse(inline.path("stdin").has("text"));
        }
        try (var request = request("printf actual", "x".repeat(8001))) {
            var coverage = new DeploymentAgentInputCoverage(request, facts("x".repeat(8001)));
            assertFalse(JSON.valueToTree(coverage.inlineInput(8000, 14000)).path("stdin").has("text"));
            assertThrows(IllegalArgumentException.class, () -> coverage.inlineInput(8001, 14000));
            assertThrows(IllegalArgumentException.class, () -> coverage.inlineInput(8000, 1));
        }
    }

    @Test
    void inlinePresentationDoesNotManufactureDeliveryAcrossFailures() throws Exception {
        String original = "#complete public input\n" + "x".repeat(1200);
        try (var request = request("printf actual", original)) {
            var coverage = new DeploymentAgentInputCoverage(request, facts(original));
            var shown = JSON.valueToTree(Map.of("executableInput", coverage.inlineInput(8000, 14000), "currentRequest", Map.of()));
            assertFalse(coverage.complete());
            coverage.delivered(JSON.createObjectNode(), result -> false);
            assertFalse(coverage.complete(), "Failed, cancelled or absent fixed evidence cannot confirm inline input");
            coverage.delivered(shown, result -> false);
            assertTrue(coverage.complete());
            var fresh = new DeploymentAgentInputCoverage(request, facts(original));
            assertFalse(fresh.complete());
            assertFalse(JSON.valueToTree(fresh.inlineInput(512, 14000)).path("stdin").has("text"));
        }
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
            assertFalse(JSON.writeValueAsString(coverage.inlineInput(512, DeploymentAgentInputCoverage.INLINE_PAYLOAD_BYTES)).contains("stdin"));
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
    @Test
    void recoveryInstallerRequiresEveryLeafAfterActualDelivery() throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram(layout);
        var unit = gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity
            .forManaged("backend", new gold.debug.windowstolinux.appapi.server.model.ServerId("original-host"), "a".repeat(64), layout);
        JsonNode structure = JSON.valueToTree(program.installationEvidence());
        var leaves = List.of("/sources/bash", "/sources/loader", "/derived/controller/segments/0/text",
            "/derived/controller/segments/2/text", "/segments/0/text", "/segments/2/text", "/segments/4/text");
        try (var original = program.install(unit, java.util.UUID.randomUUID(), java.util.UUID.randomUUID())) {
            var current = facts(program.installer()).put("operation",
                gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.INSTALL);
            current.set("completeExecutableInput", structure);
            for (String missing : leaves) {
                var coverage = new DeploymentAgentInputCoverage(original.command(), current);
                for (String pointer : leaves) if (!pointer.equals(missing)) deliverRecoveryLeaf(coverage, structure, pointer);
                assertFalse(coverage.complete(), missing);
                coverage.returned("save_review_notes", JSON.createObjectNode(), Map.of("summary", "All sources reviewed"), "notes");
                coverage.delivered(input(coverage), "notes"::equals);
                assertFalse(coverage.complete(), "Notes and descriptors cannot fill " + missing);
                deliverRecoveryLeaf(coverage, structure, missing);
                assertTrue(coverage.complete());
                assertEquals("NONE", JSON.valueToTree(coverage.missing()).path("authority").asText());
                var fresh = new DeploymentAgentInputCoverage(original.command(), current);
                assertFalse(fresh.complete(), "New reviews inherit neither delivery nor authority");
            }
            assertThrows(IOException.class, () -> new DeploymentAgentInputCoverage(original.command(), current.deepCopy().put("operation", "other")));
        }
        try (var rollback = program.guardedFirstRollback(unit, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
            "backend-0123456789abcdef", "b".repeat(64), "c".repeat(64), java.util.UUID.randomUUID())) {
            var current = facts(""); current.put("operation",
                gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.ROLLBACK);
            current.putObject("publicationRecoveryProgram").set("sourceView", JSON.valueToTree(program.rollbackSourceEvidence()));
            var coverage = new DeploymentAgentInputCoverage(rollback.command(), current);
            var view = current.at("/publicationRecoveryProgram/sourceView");
            for (String pointer : List.of("/sources/bash", "/sources/loader", "/derived/controller/segments/0/text", "/derived/controller/segments/2/text")) {
                String body = view.at(pointer).textValue();
                deliver(coverage, "evidence", "/publicationRecoveryProgram/sourceView" + pointer, body, 0, body.length());
            }
            assertFalse(coverage.complete(), "Non-stdin source view cannot fill the actual loader script gap");
            String script = rollback.command().script();
            deliver(coverage, "request", "script", script, 0, script.length());
            assertTrue(coverage.complete(), "Only the actual current script satisfies its executable delivery gate");
        }
    }

    private static void deliverRecoveryLeaf(DeploymentAgentInputCoverage coverage, JsonNode structure, String pointer) throws Exception {
        String body = structure.at(pointer).textValue(); assertTrue(body != null && !body.isEmpty());
        for (int start = 0; start < body.length();) {
            int end = Math.min(body.length(), start + 8000);
            while (JSON.writeValueAsString(Map.of("trust", "UNTRUSTED_DATA", "reviewBinding", "a".repeat(64),
                "value", page(body, start, end))).length() > 14000) {
                end = start + (end - start) / 2; assertTrue(end > start);
            }
            deliver(coverage, "evidence", PREFIX + pointer, body, start, end);
            start = end;
        }
    }

}
