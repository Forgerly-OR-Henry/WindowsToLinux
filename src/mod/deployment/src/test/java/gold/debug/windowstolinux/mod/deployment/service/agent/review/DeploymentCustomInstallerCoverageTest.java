package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentCustomInstallerCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WORKER = "/completeExecutableInput/sources/dependencyWorker";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oldLeavesDescriptorsReturnedOrUndeliveredWorkerPagesCannotCompleteCustomCoverage(boolean mirrored) throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = fixture.request(fixture.installer("CUSTOM", mirrored))) {
            var facts = JSON.valueToTree(fixture.facts("CUSTOM", request));
            var evidence = facts.path("completeExecutableInput");
            var coverage = new DeploymentAgentInputCoverage(request, facts);
            deliver(coverage, "request", "script", request.script());
            for (String pointer : DeploymentInstallerEvidence.readableBodies(evidence)) {
                if (pointer.equals("/sources/dependencyWorker")) continue;
                deliver(coverage, "evidence", "/completeExecutableInput" + pointer, evidence.at(pointer).textValue());
            }
            assertFalse(coverage.complete(), "Every other complete leaf still omits the newly factored worker");
            assertTrue(JSON.valueToTree(coverage.missing()).path("equivalentEvidencePointers").toString().contains(WORKER));
            String worker = facts.at(WORKER).textValue();
            returned(coverage, "evidence", WORKER, worker, 0, Math.min(4000, worker.length()), "only-returned");
            assertFalse(coverage.complete(), "Local tool return is not model delivery");
            coverage.delivered(JSON.createObjectNode(), ignored -> false);
            assertFalse(coverage.complete(), "Failed or absent delivery does not cover the pending page");
            var descriptor = JSON.createObjectNode();
            descriptor.putObject("currentRequest").putObject("completeExecutableInput").putObject("sources").putObject("dependencyWorker")
                .put("sha256", DeploymentAgentInputs.hash(worker)).put("characters", worker.length()).put("format", "DESCRIPTOR_ONLY");
            coverage.delivered(descriptor, ignored -> false);
            assertFalse(coverage.complete(), "A descriptor never replaces its source body");
            int lastPage = (worker.length() - 1) / 4000 * 4000;
            for (int start = 0; start < lastPage; start += 4000) {
                returned(coverage, "evidence", WORKER, worker, start, start + 4000, "successful-page");
                coverage.delivered(JSON.createObjectNode(), "successful-page"::equals);
            }
            assertFalse(coverage.complete(), "One missing worker page remains incomplete");
            returned(coverage, "evidence", WORKER, worker, lastPage, worker.length(), "last-successful-page");
            coverage.delivered(JSON.createObjectNode(), "last-successful-page"::equals);
            assertTrue(coverage.complete(), "Every complete source and literal has now been successfully delivered");
            assertTrue(fixture.model.requests.isEmpty(), "Coverage accounting performs no review or host grant");
        }
    }

    @Test
    void customWorkerRawUserFrameAndCompositeNavigationCannotBeOmittedReplacedOrRebound() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = fixture.request(fixture.installer("CUSTOM", true))) {
            var conversation = fixture.conversation();
            String current = DeploymentAiJson.facts(conversation.facts("Review complete CUSTOM", fixture.facts("CUSTOM", request),
                fixture.model.configurationRevision, fixture.task), 1048576);
            var messages = conversation.messages(current, fixture.model.configurationRevision, fixture.task);
            assertEquals(JSON.readTree(current), DeploymentApprovalSourceCarrier.restore(current, messages));
            var projection = JSON.readTree(messages.getLast().text());
            var frames = projection.at("/approvalSourceCarrier/frames");
            assertEquals(10, frames.size());
            var workerFrames = new ArrayList<JsonNode>();
            for (var frame : frames) if (frame.path("pointer").asText().equals(WORKER)) workerFrames.add(frame);
            assertEquals(1, workerFrames.size(), "The raw USER manifest includes the complete worker exactly once");
            var worker = workerFrames.getFirst();
            int index = worker.path("messageIndex").intValue();
            assertEquals(AiRole.USER, messages.get(index).role());
            assertEquals(JSON.readTree(current).at(WORKER).textValue(), messages.get(index).text().substring(worker.path("headerCharacters").intValue()));
            for (String mutation : List.of("MISSING", "DESCRIPTOR", "CHANGED")) {
                var altered = new ArrayList<>(messages);
                switch (mutation) {
                    case "MISSING" -> altered.remove(index);
                    case "DESCRIPTOR" -> altered.set(index, new AiMessage(AiRole.USER, projection.at(WORKER).toString(), List.of()));
                    case "CHANGED" -> altered.set(index, new AiMessage(AiRole.USER, messages.get(index).text() + "#changed\n", List.of()));
                }
                assertThrows(IllegalArgumentException.class, () -> DeploymentApprovalSourceCarrier.restore(current, altered), mutation);
            }
            for (String pointer : List.of("/helperInstallationSourceEvidence/installerLocations/0",
                "/helperInstallationSourceEvidence/agentBootstrap/prefixParts/3",
                "/helperInstallationSourceEvidence/agentBootstrap/dependencyProgram/parts/1")) {
                ObjectNode changed = (ObjectNode) JSON.readTree(current);
                ((ObjectNode) changed.at(pointer)).put("characters", 1);
                assertThrows(IllegalArgumentException.class, () -> conversation.messages(changed.toString(), fixture.model.configurationRevision, fixture.task), pointer);
            }
            assertTrue(fixture.model.requests.isEmpty());
        }
    }

    @Test
    void customBoundedFormatCorrectionRetainsEverySameCurrentWorkerAndLiteralFrame() throws Exception {
        try (var fixture = new MirroredInstallerTestFixture(); var request = fixture.request(fixture.installer("CUSTOM", true))) {
            fixture.model.replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("APPROVE", "fixture", "stop",
                new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), List.of(), Optional.empty())), Optional.empty()));
            fixture.model.nativeReply(new AiToolCall("custom-corrected-denial", "submit_result",
                "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Complete worker bytes still require independent denial\"}"));
            var result = new DeploymentAiJson(fixture.context, fixture.conversation()).askCommand("Review complete CUSTOM", fixture.facts("CUSTOM", request),
                fixture.model.configurationRevision, fixture.task, DeploymentReviewDecision::malformed);
            assertEquals("DENY", result.path("decision").textValue());
            assertEquals(2, fixture.model.requests.size());
            var first = fixture.model.requests.getFirst().messages();
            var second = fixture.model.requests.getLast().messages();
            assertEquals(first.getLast(), second.getLast());
            var frames = JSON.readTree(first.getLast().text()).at("/approvalSourceCarrier/frames");
            assertEquals(10, frames.size());
            for (var frame : frames) assertEquals(first.get(frame.path("messageIndex").intValue()), second.get(frame.path("messageIndex").intValue()));
            assertEquals(MirroredInstallerTestFixture.restoreCarrier(first), MirroredInstallerTestFixture.restoreCarrier(second));
        }
    }

    private static void deliver(DeploymentAgentInputCoverage coverage, String tool, String route, String text) {
        for (int start = 0; start < text.length(); start += 4000) {
            returned(coverage, tool, route, text, start, Math.min(text.length(), start + 4000), "successful-page");
            coverage.delivered(JSON.createObjectNode(), "successful-page"::equals);
        }
    }

    private static void returned(DeploymentAgentInputCoverage coverage, String tool, String route, String text, int start, int end, String result) {
        coverage.returned(tool, JSON.valueToTree(Map.of(tool.equals("request") ? "part" : "pointer", route)),
            Map.of("offset", start, "next", end, "total", text.length(), "sha256", DeploymentAgentInputs.hash(text), "text", text.substring(start, end)), result);
    }
}
