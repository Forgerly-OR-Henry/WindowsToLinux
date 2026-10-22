package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.agent.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentPublicationEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Actual publication runner and native loop with local JDBC and controlled capabilities, without a provider or SSH. */
class DeploymentAgentPublishRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void partialCommandReceiptReachesAgentWithoutGrantingVerifiedFinish() throws Exception {
        try (var frame = new Frame()) {
            frame.partialModelCommand = true;
            frame.script = (request, turn) -> {
                if (turn == 0) return calls(turn, "stage", assessed(Map.of(), "o1"));
                if (turn == 1)
                    return calls(turn, "command", assessed(Map.of("script", "printf controlled", "seconds", 1,
                        "reason", "Controlled partial diagnostic"), "o2"));
                assertFalse(state(request).path("verified").asBoolean());
                if (turn == 2) {
                    assertTrue(lastResult(request).text().contains("EXIT=0"));
                    assertTrue(lastResult(request).text().contains("OUTPUT_TRUNCATED=1"));
                    assertTrue(lastResult(request).text().contains("not proof of publication, health, sealed artifacts or acceptance"));
                    assertTrue(lastResult(request).text().contains("HEALTHY=1"));
                    return calls(turn, "finish", assessed(Map.of(), "o3"));
                }
                assertTrue(lastResult(request).error());
                assertTrue(lastResult(request).text().contains("Current independent publication verification required"));
                return calls(turn, "unable", Map.of("reason", "Partial diagnostic output does not verify publication"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(1, frame.modelDispatches);
            assertEquals(1, frame.modelReceiptQueries);
            assertEquals("ENDED", JSON.readTree(frame.modelCheckpoint.snapshot().payload()).path("commands").get(0).path("phase").asText());
            assertTrue(frame.modelCheckpoint.snapshot().released());
            assertEquals(0, frame.releases);
            assertEquals(0, frame.count("AI_PUBLICATION_VERIFIED"));
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            assertEquals(0, frame.count("AI_PUBLICATION_CLEANED"));
        }
    }

    @Test
    void nativeReviewDenialReachesNextMainContextWithoutSendOrVerifiedFinish() throws Exception {
        try (var frame = new Frame()) {
            frame.nativeApproval = true;
            frame.nativeDecision = "DENY";
            frame.nativeReason = "Missing current runtime request /exactAction; observation has not run\napi_key=synthetic-review-private";
            frame.script = (request, turn) -> {
                if (turn == 0) return calls(turn, "verify", assessed(Map.of(), "o1"));
                var diagnostic = state(request).path("lastVerificationFailure");
                assertTrue(diagnostic.toString().length() <= 1800);
                assertEquals("DATA", diagnostic.path("trust").asText());
                assertEquals("NONE", diagnostic.path("authority").asText());
                assertEquals("APPROVAL_DENIED", diagnostic.path("kind").asText());
                assertTrue(diagnostic.path("reason").asText().contains("/exactAction"));
                assertFalse(request.messages().getLast().text().contains("synthetic-review-private"));
                assertEquals("OPEN", diagnostic.path("discussionStatus").asText());
                assertEquals("NOT_SENT_AT_ORIGINAL_REVIEW_BOUNDARY", diagnostic.path("pendingInvocation").asText());
                assertFalse(state(request).path("verified").asBoolean());
                assertEquals(0, frame.executed);
                assertEquals(0, frame.approvals);
                if (turn == 1) {
                    assertTrue(lastResult(request).text().contains("PUBLICATION_NOT_VERIFIED"));
                    assertTrue(lastResult(request).text().contains("UNKNOWN"));
                    return calls(turn, "finish", assessed(Map.of(), "o2"));
                }
                assertTrue(lastResult(request).error());
                assertTrue(lastResult(request).text().contains("Current independent publication verification required"));
                return calls(turn, "unable", Map.of("reason", "Keep unverified original publication"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(1, frame.nativeReviews);
            assertEquals(0, frame.releases);
            assertEquals(0, frame.count("AI_PUBLICATION_VERIFIED"));
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            assertEquals(0, frame.count("AI_PUBLICATION_CLEANED"));
        }
    }

    @Test
    void originalOpenRefusalIsBoundedAndSuccessfulVerifyClearsItWithoutFinishing() throws Exception {
        try (var frame = new Frame()) {
            frame.approvalFailure = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException.fromReview(frame.task,
                new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Controlled original review",
                    Map.of("task", frame.taskId.toString(), "reviewReason", "Missing current bound runtime observation",
                        "discussionStatus", "OPEN", "reviewBinding", "f".repeat(64), "missingEvidence", "Bound current request"), Optional.empty())));
            frame.script = (request, turn) -> {
                if (turn == 0) return calls(turn, "verify", assessed(Map.of(), "o1"));
                if (turn == 1) {
                    var diagnostic = state(request).path("lastVerificationFailure");
                    assertEquals("OPEN", diagnostic.path("discussionStatus").asText());
                    assertEquals("f".repeat(64), diagnostic.path("reviewBinding").asText());
                    assertTrue(diagnostic.path("reason").asText().contains("current bound runtime"));
                    assertEquals("NOT_SENT_AT_ORIGINAL_REVIEW_BOUNDARY", diagnostic.path("pendingInvocation").asText());
                    assertFalse(state(request).path("verified").asBoolean());
                    assertEquals(0, frame.executed);
                    frame.approvalFailure = null;
                    frame.healthyVerification = true;
                    return calls(turn, "verify", assessed(Map.of(), "o2"));
                }
                assertTrue(state(request).path("lastVerificationFailure").isEmpty(), "Successful verify cannot retain an old refusal");
                assertTrue(state(request).path("verified").asBoolean());
                assertTrue(lastResult(request).text().contains("VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH"));
                assertEquals(5, frame.executed);
                return calls(turn, "unable", Map.of("reason", "Controlled health proof only; do not finish"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(1, frame.count("AI_PUBLICATION_VERIFIED"));
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            assertEquals(0, frame.releases);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ACCESS_DENIED", "FOREIGN_REVIEW", "EXCEPTION", "TRANSPORT_UNKNOWN"})
    void nonReviewUnknownDoesNotInventUnsentApprovalOrExposeExceptionData(String scenario) throws Exception {
        try (var frame = new Frame()) {
            if (scenario.equals("ACCESS_DENIED"))
                frame.approvalFailure = new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                    "CONTROLLED_HOST_ACCESS_DENIED", "password=synthetic-exception-private", Map.of(), Optional.empty()));
            if (scenario.equals("FOREIGN_REVIEW"))
                frame.approvalFailure = new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                    "DEPLOYMENT_COMMAND_REVIEW_DENIED", "password=synthetic-exception-private", Map.of("task", UUID.randomUUID().toString()), Optional.empty()));
            if (scenario.equals("EXCEPTION"))
                frame.transportFailure = new IllegalStateException("password=synthetic-exception-private");
            frame.transportUnknown = scenario.equals("TRANSPORT_UNKNOWN");
            frame.script = (request, turn) -> {
                if (turn == 0) return calls(turn, "verify", assessed(Map.of(), "o1"));
                assertFalse(state(request).path("verified").asBoolean());
                var diagnostic = state(request).path("lastVerificationFailure");
                if (scenario.equals("TRANSPORT_UNKNOWN"))
                    assertTrue(diagnostic.isEmpty(), "A nonexception UNKNOWN remains the original result");
                else {
                    assertEquals("VERIFICATION_UNAVAILABLE", diagnostic.path("kind").asText());
                    assertEquals("UNCONFIRMED", diagnostic.path("pendingInvocation").asText());
                    assertFalse(diagnostic.has("reason"));
                    assertFalse(diagnostic.has("discussionStatus"));
                }
                assertFalse(request.messages().getLast().text().contains("synthetic-exception-private"));
                assertTrue(lastResult(request).text().contains("PUBLICATION_NOT_VERIFIED"));
                assertTrue(lastResult(request).text().contains("UNKNOWN"));
                return calls(turn, "unable", Map.of("reason", "Unconfirmed verification cannot finish"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(0, frame.count("AI_PUBLICATION_VERIFIED"));
            assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            assertEquals(0, frame.releases);
        }
    }

    private static final String REVISION = "a".repeat(64);

    private static final String HELPER = "/gold/debug/windowstolinux/mod/deployment/linux/helper/";

    @Test
    void publicationModelLoadsVerificationSkillIntoNextContextWithoutGrantOrHealthClaim() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> {
                assertEquals(2, state(request).path("skills").size());
                if (turn == 0) {
                    assertFalse(request.messages().getFirst().text().contains("Trusted version-owned product skill"));
                    return calls(turn, "skill", Map.of("id", "publication-verification"));
                }
                assertTrue(request.messages().getFirst().text().contains("Trusted version-owned product skill publication-verification"));
                assertTrue(request.messages().getFirst().text().contains("that pending invocation has no result receipt yet"));
                assertTrue(request.messages().getFirst().text().contains("a closed DENY must not be reopened for the same request"));
                assertEquals(1, state(request).path("judgement").path("evidenceIndex").path("total").asInt());
                assertFalse(state(request).path("verified").asBoolean());
                assertTrue(lastResult(request).text().contains("CURRENT_CONTEXT"));
                return calls(turn, "unable", Map.of("reason", "Controlled skill activation verified"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(0, frame.approvals);
            assertEquals(0, frame.executed);
            assertEquals(0, frame.releases);
        }
    }

    @Test
    void actualBuiltInPublicationAndFailedHealthUseInjectedNativeApproverBeforeEverySend() throws Exception {
        try (var frame = new Frame()) {
            frame.nativeApproval = true;
            for (String output : List.of("INPUT_STAGED=1\n", "INPUT_STAGED=1\n", "PREVIOUS=0\n", "PUBLISHED=1\n"))
                frame.remoteReplies.add(new CommandResult(Optional.of(0), output, "", false));
            frame.remoteReplies.add(new CommandResult(Optional.of(64), "", "launcher: run.sh not found", false));
            frame.remoteReplies.add(new CommandResult(Optional.of(0), "ROLLED_BACK=1\n", "", false));
            frame.script = (request, turn) -> turn == 0 ? calls(turn, "publish", assessed(Map.of(), "o1"))
                : calls(turn, "unable", Map.of("reason", "Controlled native publication route verified"));
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(6, frame.nativeReviews);
            assertEquals(6, frame.approvals);
            assertEquals(6, frame.executed);
            assertEquals(1, frame.count("GRAPH_ROLLBACK_RESULT"));
            assertEquals(0, frame.releases);
        }
    }

    @Test
    void actualBuiltInPublishReceivesIdentityEvidenceAndStillNeedsIndependentDecision() throws Exception {
        for (boolean approve : List.of(false, true)) {
            try (var frame = new Frame(true)) {
                frame.nativeApproval = true;
                frame.publicationDecision = approve ? "APPROVE" : "DENY";
                for (String output : List.of("INPUT_STAGED=1\n", "INPUT_STAGED=1\n", "PREVIOUS=0\n"))
                    frame.remoteReplies.add(new CommandResult(Optional.of(0), output, "", false));
                if (approve) {
                    frame.remoteReplies.add(new CommandResult(Optional.of(0), "PUBLISHED=1\n", "", false));
                    for (int index = 0; index < 3; index++)
                        frame.remoteReplies.add(new CommandResult(Optional.of(0), "HEALTHY=1\n", "", false));
                }
                frame.script = (request, turn) -> turn == 0 ? calls(turn, "publish", assessed(Map.of(), "o1"))
                    : calls(turn, "unable", Map.of("reason", "Controlled publication review only; do not finish"));
                assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
                assertEquals(1, frame.publicationReviewFacts.size());
                var identity = frame.publicationReviewFacts.getFirst();
                assertEquals("component", identity.path("component").asText());
                assertEquals("NONE", identity.path("authority").asText());
                assertEquals("d".repeat(64), identity.path("sealedArtifactSha256").asText());
                assertNotEquals(identity.path("releaseSha256").asText(), identity.path("sealedArtifactSha256").asText());
                assertEquals("expectedOwnershipManifestSha256", identity.path("positionalArguments").get(3).path("role").asText());
                assertEquals(approve ? 1 : 0, frame.publicationSends);
                assertEquals(frame.executed, frame.approvals);
                assertEquals(frame.approvals, new HashSet<>(frame.hostGrants).size(), "Every sent action uses a distinct host grant");
                assertEquals(approve ? frame.executed : frame.executed + 1, frame.nativeReviews);
                assertEquals(0, frame.releases);
                assertEquals(0, frame.count("AI_PUBLICATION_FINISHED"));
            }
        }
    }

    @Test
    void invalidContractCorrectsAndAllOfferedPagesReconstructTheirCompleteOriginalFiles() throws Exception {
        try (var frame = new Frame()) {
            var selected = new ArrayList<String>();
            var actualLines = new ArrayList<String>();
            var file = new AtomicInteger();
            var offset = new AtomicInteger();
            var pages = new AtomicInteger();
            var recovered = new AtomicInteger();
            var firstPage = new java.util.concurrent.atomic.AtomicReference<String>();
            frame.script = (request, turn) -> {
                var state = state(request);
                if (recovered.get() == 1) {
                    assertTrue(lastResult(request).text().startsWith("o2\no2 "));
                    assertTrue(lastResult(request).text().contains(firstPage.get()));
                    assertEquals(pages.get() + 2, state.path("judgement").path("evidenceIndex").path("total").asInt());
                    return calls(turn, "unable", Map.of("reason", "Controlled pagination verification complete"));
                }
                if (turn == 0) {
                    state.path("contractFiles").forEach(value -> selected.add(value.asText()));
                    assertEquals(13, selected.size());
                    var schema = JSON.readTree(tool(request, "contract").inputSchema());
                    assertEquals(500, schema.at("/properties/path/maxLength").asInt());
                    assertEquals(13, schema.at("/properties/path/enum").size());
                    assertEquals(0, schema.at("/properties/offset/minimum").asInt());
                    assertEquals(1000000, schema.at("/properties/offset/maximum").asInt());
                    assertEquals(1, schema.at("/properties/limit/minimum").asInt());
                    assertEquals(100, schema.at("/properties/limit/maximum").asInt());
                    var indexSchema = JSON.readTree(tool(request, "evidence_index").inputSchema());
                    assertEquals(400, indexSchema.at("/properties/offset/maximum").asInt());
                    assertEquals(50, indexSchema.at("/properties/limit/maximum").asInt());
                    return calls(turn, "contract", Map.of("path", "runtime/not-offered.sh", "offset", 0, "limit", 100));
                }
                if (turn == 1) {
                    assertTrue(lastResult(request).error());
                    assertTrue(lastResult(request).text().contains("Unsupported publication contract"));
                } else {
                    var result = lastResult(request);
                    assertFalse(result.error());
                    assertTrue(result.text().length() <= 6000);
                    var page = JSON.readTree(result.text().substring(result.text().indexOf('\n') + 1));
                    if (firstPage.get() == null)
                        firstPage.set(result.text().substring(result.text().indexOf('\n') + 1));
                    assertTrue(page.path("complete").asBoolean());
                    if (file.get() == selected.size()) {
                        assertEquals(page.path("totalLines").asInt(), page.path("offset").asInt());
                        assertEquals(0, page.path("returnedLines").asInt());
                        assertFalse(page.path("hasMore").asBoolean());
                        assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream())
                            .noneMatch(value -> value.text().startsWith("o2\n")), "The first contract body was evicted from the rolling transcript");
                        recovered.set(1);
                        return calls(turn, "observation", Map.of("id", "o2", "offset", 0, "limit", 6000));
                    }
                    assertEquals(selected.get(file.get()), page.path("path").asText());
                    assertEquals(offset.get(), page.path("offset").asInt());
                    page.path("lines").forEach(line -> actualLines.add(line.asText()));
                    assertEquals(page.path("lines").size(), page.path("returnedLines").asInt());
                    assertEquals(offset.get() + page.path("returnedLines").asInt(), page.path("nextLine").asInt());
                    pages.incrementAndGet();
                    if (page.path("hasMore").asBoolean()) offset.set(page.path("nextLine").asInt());
                    else {
                        try (var input = DeploymentAgentPublishRunner.class.getResourceAsStream(HELPER + selected.get(file.get()))) {
                            assertNotNull(input);
                            var expected = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate.render(
                                new String(input.readAllBytes(), StandardCharsets.UTF_8),
                                gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()).lines().toList();
                            assertEquals(expected, actualLines, selected.get(file.get()));
                            assertEquals(expected.size(), page.path("totalLines").asInt());
                        }
                        actualLines.clear();
                        offset.set(0);
                        file.incrementAndGet();
                    }
                }
                return calls(turn, "contract", Map.of("path", selected.get(Math.min(file.get(), selected.size() - 1)),
                    "offset", file.get() == selected.size() ? 1000000 : offset.get(), "limit", 100));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(13, file.get());
            assertTrue(pages.get() > 13, "Long contracts must expose and read their actual tails");
            assertEquals(0, frame.executed);
            assertEquals(0, frame.count("AI_PUBLICATION_ERROR"));
        }
    }

    @Test
    void failedHealthAndVerifiedWholeGraphRollbackReachTheActualModelObservation() throws Exception {
        try (var frame = new Frame()) {
            for (String output : List.of("INPUT_STAGED=1\n", "INPUT_STAGED=1\n", "PREVIOUS=0\n", "PUBLISHED=1\n"))
                frame.remoteReplies.add(new CommandResult(Optional.of(0), output, "", false));
            frame.remoteReplies.add(new CommandResult(Optional.of(64), "stdout-private-value", "launcher: run.sh not found\nsecret=stderr-private-value\n", false));
            frame.remoteReplies.add(new CommandResult(Optional.of(0), "ROLLED_BACK=1\n", "", false));
            frame.script = (request, turn) -> {
                if (turn == 0) return calls(turn, "publish", assessed(Map.of(), "o1"));
                String observation = lastResult(request).text();
                assertTrue(observation.contains("DEPLOYMENT_HEALTH_FAILED"), observation);
                var facts = JSON.readTree(observation.substring(observation.indexOf('{')));
                assertEquals("FAILED", facts.path("outcome").asText());
                assertTrue(facts.path("rollbackConfirmed").asBoolean());
                assertEquals("component", facts.path("healthReceipts").get(0).path("component").asText());
                assertEquals(64, facts.path("healthReceipts").get(0).path("exit").asInt());
                assertTrue(facts.path("healthReceipts").get(0).path("knownHealthFailure").asBoolean());
                assertTrue(observation.contains("run.sh not found"));
                assertFalse(observation.contains("stdout-private-value"));
                assertFalse(observation.contains("stderr-private-value"));
                return calls(turn, "unable", Map.of("reason", "Controlled failed health receipt observed"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(6, frame.executed);
            assertEquals(6, frame.approvals);
            assertEquals(1, frame.count("GRAPH_ROLLBACK_RESULT"));
            assertTrue(frame.remoteReplies.isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"token-worker, launcher: run.sh not found", "component, unknown option --token",
        "component, APPLICATION_LISTENER_ADDRESS endpoint=web transport=tcp port=46110 expected=0.0.0.0 observed=127.0.0.1"})
    void typedPublicationFeedbackKeepsKeywordComponentAndDiagnosticMetadata(String component, String diagnostic) throws Exception {
        try (var frame = new Frame()) {
            frame.componentId = component;
            for (String output : List.of("INPUT_STAGED=1\n", "INPUT_STAGED=1\n", "PREVIOUS=0\n", "PUBLISHED=1\n"))
                frame.remoteReplies.add(new CommandResult(Optional.of(0), output, "", false));
            frame.remoteReplies.add(new CommandResult(Optional.of(64), "stdout-private-value", diagnostic + "\nsecret=stderr-private-value\n", false));
            frame.remoteReplies.add(new CommandResult(Optional.of(0), "ROLLED_BACK=1\n", "", false));
            frame.script = (request, turn) -> {
                if (turn == 0) return calls(turn, "publish", assessed(Map.of(), "o1"));
                String observation = lastResult(request).text();
                assertFalse(lastResult(request).error());
                var facts = JSON.readTree(observation.substring(observation.indexOf('{')));
                assertEquals("FAILED", facts.path("outcome").asText());
                assertEquals("DEPLOYMENT_HEALTH_FAILED", facts.path("event").asText());
                assertTrue(facts.path("rollbackConfirmed").asBoolean());
                var health = facts.path("healthReceipts").get(0);
                assertEquals(component, health.path("component").asText());
                assertTrue(health.path("requestSha256").asText().matches("[0-9a-f]{64}"));
                assertEquals(64, health.path("exit").asInt());
                assertTrue(health.path("stderr").asText().contains(diagnostic));
                assertFalse(observation.contains("stdout-private-value"));
                assertFalse(observation.contains("stderr-private-value"));
                return calls(turn, "unable", Map.of("reason", "Controlled typed receipt verification complete"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(6, frame.executed);
            assertEquals(6, frame.approvals);
            assertTrue(frame.remoteReplies.isEmpty());
        }
    }

    @Test
    void originalObservationAndDuplicateContractKeepIdsAndNeverRecoverExcludedCredentials() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> {
                var entries = state(request).path("judgement").path("evidenceIndex").path("entries");
                return switch (turn) {
                    case 0 -> calls(turn, "observation", Map.of("id", "o1", "offset", 0, "limit", 6000));
                    case 1 -> {
                        assertEquals(1, entries.size());
                        assertTrue(lastResult(request).text().startsWith("o1\no1 "));
                        assertTrue(lastResult(request).text().contains("exact sealed graph"));
                        yield calls(turn, "contract", Map.of("path", "input/17-managed-content.sh", "offset", 0, "limit", 100));
                    }
                    case 2 -> calls(turn, List.of(
                        new AiToolCall("recover-" + turn, "observation", JSON.writeValueAsString(Map.of("id", "o2", "offset", 0, "limit", 6000))),
                        new AiToolCall("repeat-" + turn, "contract", JSON.writeValueAsString(Map.of("path", "input/17-managed-content.sh", "offset", 0, "limit", 100)))));
                    case 3 -> {
                        assertEquals(2, entries.size());
                        assertTrue(lastResult(request).text().startsWith("o2\n"));
                        yield calls(turn, "input", Map.of("question", "Controlled nonsecret intent"));
                    }
                    case 4 -> {
                        assertEquals(3, entries.size());
                        assertFalse(lastResult(request).text().contains("synthetic-private-value"));
                        assertTrue(lastResult(request).text().contains("credential content excluded"));
                        yield calls(turn, "observation", Map.of("id", "o3", "offset", 0, "limit", 6000));
                    }
                    default -> {
                        assertEquals(3, entries.size());
                        assertTrue(lastResult(request).text().startsWith("o3\no3 "));
                        assertFalse(lastResult(request).text().contains("synthetic-private-value"));
                        yield calls(turn, "unable", Map.of("reason", "Controlled identity verification complete"));
                    }
                };
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(0, frame.executed);
        }
    }

    @Test
    void unstagedCommandIsRejectedBeforeTransportAndActualStagingAdvancesStateAndBudgets() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> {
                var state = state(request);
                var budget = state.path("budget");
                assertEquals(turn + 1, budget.path("decisionsStarted").asInt());
                assertEquals(turn, budget.path("observedAttempts").asInt());
                assertEquals(78 - turn, budget.path("sharedRemainingAttempts").asInt());
                if (turn == 0) {
                    assertFalse(state.path("staged").asBoolean());
                    assertFalse(state.path("standardSent").asBoolean());
                    assertFalse(state.path("verified").asBoolean());
                    assertEquals(2, state.path("nextStep").path("routes").size());
                    return calls(turn, "command", assessed(Map.of("script", "printf controlled", "seconds", 1, "reason", "Controlled precondition"), "o1"));
                }
                if (turn == 1) {
                    assertEquals(0, frame.executed);
                    assertTrue(lastResult(request).error());
                    assertTrue(lastResult(request).text().contains("Stage exact publication inputs"));
                    return calls(turn, "stage", assessed(Map.of(), "o1"));
                }
                assertEquals("STAGED", state.path("stage").asText());
                assertTrue(state.path("staged").asBoolean());
                assertFalse(state.path("verified").asBoolean());
                assertEquals(2, frame.executed);
                assertEquals(2, frame.approvals);
                return calls(turn, "unable", Map.of("reason", "Controlled staging verification complete"));
            };
            assertEquals(TaskResult.UNKNOWN, frame.run(78).outcome());
            assertEquals(0, frame.releases);
        }
    }

    @Test
    void changedModelRouteStillHardStopsAndRecordsOnlyControlledCause() throws Exception {
        try (var frame = new Frame()) {
            frame.script = (request, turn) -> {
                frame.modelRevision = "b".repeat(64);
                return calls(turn, "contract", Map.of("path", "00-protocol-foundation.sh", "offset", 0, "limit", 100));
            };
            var failure = assertThrows(SecurityException.class, () -> frame.run(78));
            assertEquals("Native model route changed", failure.getMessage());
            assertEquals("MODEL_ROUTE_CHANGED", frame.detail("AI_PUBLICATION_ERROR_CAUSE"));
            assertEquals(0, frame.executed);
        }
    }

    @ParameterizedTest
    @CsvSource({"TASK_CHECKPOINT_RELEASE,false", "FIXTURE_PRIVATE_EVENT,false", "TASK_CHECKPOINT_RELEASE,true"})
    void resourceReleaseFailureRetainsSafeFinishPhaseAndOriginalApiException(String event, boolean nullMessage) throws Exception {
        try (var frame = new Frame()) {
            String privateMessage = "password=fixture-private-message; original-command=fixture-private-script";
            var problem = new Problem(ProblemCode.UNKNOWN, event, privateMessage,
                Map.of("credential", "fixture-private-context"), Optional.empty());
            var original = nullMessage ? new ApiException(problem) {
                @Override
                public String getMessage() {
                    return null;
                }
            } : new ApiException(problem);
            frame.resourceReleaseFailure = original;
            frame.healthyVerification = true;
            frame.script = (request, turn) -> {
                if (turn == 0) return calls(turn, "verify", assessed(Map.of(), "o1"));
                assertEquals(1, turn, "Only actual verification and assessed finish are requested");
                assertTrue(state(request).path("verified").asBoolean());
                assertTrue(lastResult(request).text().contains("VERIFIED_CURRENT_GRAPH_AND_APPLICATION_HEALTH"));
                return calls(turn, "finish", assessed(Map.of(), "o2"));
            };
            assertSame(original, assertThrows(ApiException.class, () -> frame.run(78)));
            assertEquals(2, frame.decisions.get());
            assertEquals(1, frame.releases);
            assertEquals(1, frame.count("AI_PUBLICATION_VERIFIED"));
            assertEquals(1, frame.count("AI_PUBLICATION_FINISHED"));
            assertEquals(0, frame.count("AI_PUBLICATION_CLEANED"));
            assertEquals(0, frame.count("CANDIDATE_CLEANUP_EXECUTING"));
            assertEquals("ApiException", frame.detail("AI_PUBLICATION_ERROR"));
            assertEquals("OTHER", frame.detail("AI_PUBLICATION_ERROR_CAUSE"));
            var metadata = JSON.readTree(frame.detail("AI_PUBLICATION_ERROR_METADATA"));
            assertEquals("RESOURCE_RELEASE", metadata.path("phase").asText());
            assertEquals("ApiException", metadata.path("exceptionClass").asText());
            assertEquals("UNKNOWN", metadata.path("problemCode").asText());
            assertEquals(nullMessage ? "ABSENT" : diagnosticHash(privateMessage), metadata.path("messageSha256").asText());
            if (event.equals("TASK_CHECKPOINT_RELEASE")) {
                assertEquals(event, metadata.path("problemEventCode").asText());
                assertFalse(metadata.has("problemEventCodeSha256"));
            } else {
                assertFalse(metadata.has("problemEventCode"));
                assertEquals(diagnosticHash(event), metadata.path("problemEventCodeSha256").asText());
            }
            var events = frame.journal.events(frame.task);
            var stages = events.stream().filter(value -> value.type().equals("AI_PUBLICATION_FINISH_STAGE"))
                .map(value -> value.action() + ":" + value.detail()).toList();
            assertEquals(List.of("RESOURCE_RELEASE:CALLING", "FINALLY_MODEL_COMMAND_RELEASE:CALLING", "FINALLY_MODEL_COMMAND_RELEASE:RETURNED"), stages);
            String stored = events.toString();
            assertFalse(stored.contains(privateMessage));
            assertFalse(stored.contains("fixture-private-script"));
            assertFalse(stored.contains("fixture-private-context"));
            if (!event.equals("TASK_CHECKPOINT_RELEASE")) assertFalse(stored.contains(event));
        }
    }

    private static String diagnosticHash(String value) {
        return gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(value);
    }

    @Test
    void owningAgentFeedbackKeepsCommandRedactionAndRejectedCallsConsumeActualAttempts() throws Exception {
        try (var frame = new Frame()) {
            var review = new DeploymentModelToolLoop.SelectionReview() {
                public boolean required(String name) {
                    return false;
                }

                public void assess(String name, JsonNode summary) {
                }

                public String observed(String name, String output) {
                    return "o1";
                }

                public Map<String, Object> state() {
                    return Map.of();
                }

                public String observation(String id, int offset, int limit) {
                    return "";
                }

                public Map<String, Object> evidenceIndex(int offset, int limit) {
                    return Map.of();
                }
            };
            var loop = new DeploymentModelToolLoop<String>(frame.context, frame.task, REVISION, "Controlled Agent feedback", 5,
                review, () -> "", () -> gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoalBudget.beforeModel(frame.task, false),
                gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback::safe);
            frame.script = (request, turn) -> {
                if (turn == 0)
                    return calls(turn, List.of(new AiToolCall("unknown", "unavailable", "{}"), new AiToolCall("command", "command", "{}")));
                assertEquals(2, loop.budgetState().get("observedAttempts"));
                assertEquals(3, loop.budgetState().get("remainingAttempts"));
                assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).anyMatch(AiToolResult::error));
                assertFalse(lastResult(request).text().contains("synthetic-private-value"));
                assertTrue(lastResult(request).text().contains("credential content excluded"));
                return calls(turn, "end", Map.of());
            };
            var result = loop.run(() -> Map.of("command", new DeploymentModelToolLoop.Tool<>("Controlled command feedback", args ->
                    DeploymentModelToolLoop.Feedback.observed("secret=synthetic-private-value\npublic result")), "end",
                new DeploymentModelToolLoop.Tool<>("Controlled end", args -> DeploymentModelToolLoop.Feedback.ended(success("done")))), Map::of);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
        }
    }

    private static JsonNode state(AiRequest request) throws Exception {
        return JSON.readTree(request.messages().getLast().text()).path("state");
    }

    private static AiToolDefinition tool(AiRequest request, String name) {
        return request.tools().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow();
    }

    private static AiToolResult lastResult(AiRequest request) {
        return request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((first, last) -> last).orElseThrow();
    }

    private static Map<String, Object> assessed(Map<String, Object> args, String id) {
        var value = new HashMap<String, Object>(args);
        value.put("assessment", Map.of("basis", List.of(id), "suitability", "Controlled applicability",
            "differences", "No unresolved differences", "resolution", "CONSISTENT", "decision", "Select controlled action"));
        return value;
    }

    private static AiResponse calls(int turn, String name, Map<String, ?> args) throws Exception {
        return calls(turn, List.of(new AiToolCall("call-" + turn, name, JSON.writeValueAsString(args))));
    }

    private static AiResponse calls(int turn, List<AiToolCall> calls) {
        return new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), calls, Optional.empty());
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    @FunctionalInterface
    private interface Script {
        AiResponse reply(AiRequest request, int turn) throws Exception;
    }

    private static final class Frame implements AutoCloseable {
        private static final String ARCHIVE = "0123456789abcdef" + "0".repeat(48);

        private final boolean publicationIdentity;
        private final UUID taskId = UUID.randomUUID();

        private final String jdbc = "jdbc:h2:mem:" + UUID.randomUUID();

        private final Connection retained = DriverManager.getConnection(jdbc);

        private final ServerId server = new ServerId("controlled-publish");

        private final ServerInfo profile = new ServerInfo(server, "controlled", "controlled-host", 22, "root", Optional.empty(), Optional.empty());

        private final AtomicInteger decisions = new AtomicInteger();

        private final TaskContext task;

        private final ModContext context;

        private final DeploymentAgentActionLog journal;

        private final RemoteSession session;

        private Script script;

        private String modelRevision = REVISION;

        private int executed;

        private int approvals;

        private int releases;

        private ApiException resourceReleaseFailure;

        private boolean nativeApproval;

        private String nativeDecision = "APPROVE";

        private String nativeReason = "Controlled original exact publication request";

        private Exception approvalFailure;

        private Exception transportFailure;

        private boolean transportUnknown;

        private boolean healthyVerification;

        private int nativeReviews;

        private String publicationDecision = "APPROVE";

        private int publicationSends;

        private final List<JsonNode> publicationReviewFacts = new ArrayList<>();

        private final List<ApprovalGrant> hostGrants = new ArrayList<>();

        private final Set<ApprovalGrant> consumedGrants = new HashSet<>();

        private boolean partialModelCommand;

        private int modelDispatches, modelReceiptQueries;

        private gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint modelCheckpoint;

        private String modelReceipt;

        private final Deque<CommandResult> remoteReplies = new ArrayDeque<>();

        private String componentId = "component";

        Frame() throws Exception {
            this(false);
        }

        Frame(boolean publicationIdentity) throws Exception {
            this.publicationIdentity = publicationIdentity;
            if (publicationIdentity) {
                try (var source = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                    org.h2.tools.RunScript.execute(retained, source);
                }
            } else {
                try (var sql = retained.createStatement()) {
                    sql.execute("CREATE TABLE deployment_agent_task(task_id VARCHAR(36) PRIMARY KEY)");
                    sql.execute("CREATE TABLE deployment_agent_event(event_id BIGINT GENERATED BY DEFAULT AS IDENTITY,task_id VARCHAR(36),action_id VARCHAR(128),event_type VARCHAR(48),event_detail VARCHAR(4096),created_at BIGINT)");
                    sql.execute("INSERT INTO deployment_agent_task VALUES('" + taskId + "')");
                }
            }
            task = proxy(TaskContext.class, (method, args) -> switch (method) {
                case "id" -> taskId;
                case "cancelToken" -> (CancelToken) () -> false;
                case "progress" -> null;
                default -> throw new AssertionError(method);
            });
            var db = proxy(ModDatabase.class, (method, args) -> method.equals("identity")
                ? new SchemaIdentity(DeploymentSchemaIdentity.OWNER, DeploymentSchemaIdentity.VERSION, taskId) : DriverManager.getConnection(jdbc));
            journal = new DeploymentAgentActionLog(db);
            var ai = proxy(AiService.class, (method, args) -> {
                if (method.equals("configurationRevision")) return modelRevision;
                var request = (AiRequest) args[0];
                if (nativeApproval && request.purpose() == AiPurpose.APPROVAL) {
                    nativeReviews++;
                    assertTrue(request.tools().stream().anyMatch(tool -> tool.name().equals("request")));
                    String decision = nativeDecision;
                    if (publicationIdentity) {
                        var dynamic = JSON.readTree(request.messages().getLast().text());
                        var current = dynamic.path("currentEvidence").asText().equals("FIRST_USER_MESSAGE_AFTER_BASE_SYSTEM")
                            ? JSON.readTree(request.messages().get(1).text()) : dynamic;
                        if (current.at("/currentRequest/operation").asText().equals("deployment.publication.publish.v1")) {
                            var identity = current.at("/currentRequest/currentCommandEvidence/publicationIdentity");
                            assertFalse(identity.isMissingNode(), "The actual native APP receives the publication projection");
                            publicationReviewFacts.add(identity);
                            decision = publicationDecision;
                        }
                    }
                    return success(calls(nativeReviews, "submit_result", Map.of("decision", decision, "requiresUserConfirmation", false,
                        "reason", nativeReason, "missingEvidence", List.of(), "adjustments", List.of(), "candidateCommands", List.of())));
                }
                return success(script.reply(request, decisions.getAndIncrement()));
            });
            var interaction = proxy(InteractionService.class, (method, args) -> {
                if (method.equals("approve") || nativeApproval && method.equals("approveReviewedAction")) {
                    if (approvalFailure != null) throw approvalFailure;
                    approvals++;
                    var grant = new ApprovalGrant(UUID.randomUUID());
                    hostGrants.add(grant);
                    return success(grant);
                }
                if (method.equals("requestText")) return success("secret=synthetic-private-value\npublic intent");
                throw new AssertionError(method);
            });
            var tasks = proxy(TaskService.class, (method, args) -> {
                if (method.equals("findCheckpoint"))
                    return "deployment-model-commands".equals(args[1]) ? Optional.ofNullable(modelCheckpoint) : Optional.empty();
                assertTrue(partialModelCommand);
                assertEquals("beginCheckpoint", method);
                var saved = new java.util.concurrent.atomic.AtomicReference<>(new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(0, (String) args[2], false));
                modelCheckpoint = proxy(gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.class, (name, values) -> {
                    if (name.equals("snapshot")) return saved.get();
                    if (name.equals("advance")) {
                        assertEquals(saved.get(), values[0]);
                        saved.set(new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(saved.get().revision() + 1, (String) values[1], false));
                        return saved.get();
                    }
                    assertEquals("close", name);
                    saved.set(new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(saved.get().revision() + 1, saved.get().payload(), true));
                    return null;
                });
                return modelCheckpoint;
            });
            var logs = proxy(LogService.class, (method, args) -> method.equals("register") ? new LogTarget(UUID.randomUUID()) : null);
            var ui = proxy(UiService.class, (method, args) -> {
                assertEquals("environment", method);
                return new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false);
            });
            var servers = proxy(ServerService.class, (method, args) -> {
                assertEquals("get", method);
                return profile;
            });
            context = proxy(ModContext.class, (method, args) -> switch (method) {
                case "ai" -> ai;
                case "db" -> db;
                case "interaction" -> interaction;
                case "tasks" -> tasks;
                case "logs" -> logs;
                case "ui" -> ui;
                case "servers" -> servers;
                default -> throw new AssertionError(method);
            });
            session = proxy(RemoteSession.class, (method, args) -> {
                if ((partialModelCommand || publicationIdentity) && method.equals("facts"))
                    return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("linux", "x86_64", "SHA256:controlled");
                assertEquals("execute", method);
                executed++;
                if (publicationIdentity) {
                    assertEquals(hostGrants.getLast(), args[1]);
                    assertTrue(consumedGrants.add((ApprovalGrant) args[1]), "An earlier grant cannot authorize another action");
                    if (((gold.debug.windowstolinux.appapi.linux.command.CommandRequest) args[0]).script().contains("'publish-deployment'")) publicationSends++;
                }
                if (partialModelCommand) {
                    var request = (gold.debug.windowstolinux.appapi.linux.command.CommandRequest) args[0];
                    if (request.script().equals(gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.LAUNCHER)) {
                        var bound = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.executableFacts(new String(request.stdin(), StandardCharsets.UTF_8));
                        assertEquals(taskId.toString(), bound.get("task"));
                        var entry = JSON.readTree(modelCheckpoint.snapshot().payload()).path("commands").get(0);
                        assertEquals("DISPATCHED", entry.path("phase").asText());
                        assertEquals(entry.path("body").asText(), bound.get("bodySha256"));
                        modelReceipt = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.header(taskId,
                            UUID.fromString((String) bound.get("operation")), (String) bound.get("bodySha256")) + "EXIT=0\n";
                        modelDispatches++;
                        return success(new CommandResult(Optional.of(0), "HEALTHY=1\nAI_PUBLICATION_VERIFIED=1\n", "partial stderr", true));
                    }
                    if (modelReceipt != null) {
                        var entry = JSON.readTree(modelCheckpoint.snapshot().payload()).path("commands").get(0);
                        assertTrue(request.script().contains(entry.path("operation").asText()));
                        assertEquals(0, request.stdin().length);
                        modelReceiptQueries++;
                        return success(new CommandResult(Optional.of(0), modelReceipt, "", false));
                    }
                }
                if (transportFailure != null) throw transportFailure;
                if (transportUnknown)
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                        "CONTROLLED_TRANSPORT_UNKNOWN", "password=synthetic-exception-private", Map.of(), Optional.empty())));
                if (healthyVerification) {
                    String script = ((gold.debug.windowstolinux.appapi.linux.command.CommandRequest) args[0]).script();
                    String output;
                    if (script.contains("'publication-original-status'"))
                        output = "PUBLICATION_STATUS=1\nCURRENT=" + "c".repeat(64)
                            + "\nCURRENT_KIND=deployment\nCURRENT_MODE=DAEMON\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\n"
                            + "CANDIDATE=owned\nBUILD_ACTIVE=no\nUNIT_PRESENT=yes\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=1\n";
                    else if (script.contains("'application-health'")) output = "HEALTHY=1\n";
                    else if (script.contains("'inspect-runtime'")) output = "KIND=deployment\nMODE=DAEMON\n";
                    else if (script.contains("'observe-deployment'"))
                        output = "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\nSubState=running\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=123\n";
                    else throw new AssertionError("No mutating command in controlled verification");
                    return success(new CommandResult(Optional.of(0), output, "", false));
                }
                return success(remoteReplies.isEmpty() ? new CommandResult(Optional.of(0), "INPUT_STAGED=1\n", "", false)
                    : remoteReplies.removeFirst());
            });
        }

        OperationResult<DeploymentPublishResult> run(int budget) throws Exception {
            var unit = DeploymentUnitIdentity.forManaged("controlled", server, "b".repeat(64));
            var health = new DeploymentHealthCheck.Process(10, 1);
            var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true,
                new DeploymentCommand("run.sh", List.of()), "", List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
            var runtime = new DeploymentRuntimeDefinition.ManagedProcess(health, DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            var config = new DeploymentUnitRuntimeConfig(health, Optional.empty(), DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            var graph = new DeploymentApplicationGraph("controlled", componentId, health, List.of(new DeploymentApplicationGraph.Part(
                componentId, unit, config, List.of(), runtime, List.of(), new DeploymentResourceBindings(List.of(), Optional.of(List.of())))));
            var snapshot = DeploymentConfigSnapshot.create(unit.id(), 1, "v1", Instant.EPOCH, List.of());
            String release = publicationIdentity ? DeploymentAgentInputs.publicationReleaseIdentity("d".repeat(64), "e".repeat(64),
                snapshot.sha256(), runtime, graph.components().getFirst().resources()) : "c".repeat(64);
            var proposal = new DeploymentPublishResult(graph, List.of(new DeploymentUnitCommit(unit, config,
                new DeploymentCurrentRelease(unit.id(), release, Instant.EPOCH), snapshot, List.of())));
            var candidate = new DeploymentGraphPublisher.Candidate("controlled-0123456789abcdef", "d".repeat(64), "e".repeat(64), "0".repeat(64), false, Map.of());
            if (publicationIdentity) {
                journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), DeploymentAgentInputs.targetDigest(server, session), REVISION, "AUTOMATIC", false));
                journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(profile));
                journal.event(task, "SOURCE", ARCHIVE, "9".repeat(64));
                journal.event(task, "CANDIDATE", componentId, candidate.candidateId());
                journal.event(task, "CURRENT_SOURCE", componentId, candidate.sourceRevision());
                String seal = DeploymentAgentInputs.hash(componentId + ":" + candidate.sourceRevision() + ":seal");
                journal.event(task, "SEAL_EXECUTING", seal, candidate.sourceRevision());
                journal.event(task, "SEAL_RESULT", seal, candidate.artifactSha256());
                journal.event(task, "SEALED_ARTIFACT", componentId, candidate.artifactSha256());
                journal.event(task, "SEALED_TOOLS", componentId, candidate.toolchainBindingSha256() + ":false");
            }
            try (var shared = DeploymentToolBudget.open(80)) {
                assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("outer-1", "read", "{}"), new AiToolCall("outer-2", "deliver", "{}"))));
                ModContext reviewed = context;
                if (nativeApproval) {
                    gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval agent;
                    if (publicationIdentity)
                        agent = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.forTask(context, task);
                    else agent = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(context, task,
                        new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence() {
                            public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding() {
                                return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(server.id(), "b".repeat(64), "d".repeat(64));
                            }

                            public Map<String, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources() {
                                return Map.of();
                            }

                            public Object receipts() throws Exception {
                                return Map.of("events", journal.events(task));
                            }
                        });
                    reviewed = new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope(context, context.interaction(), task,
                        "AUTOMATIC", false, () -> REVISION, null, gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE, () -> agent);
                }
                DeploymentSendGuard guard = DeploymentSendGuard.NONE;
                if (publicationIdentity) {
                    var scope = (DeploymentCommandReviewScope) reviewed;
                    var evidence = new DeploymentAgentPublicationEvidence(scope, session, server, task, REVISION, proposal,
                        Map.of(componentId, candidate), Map.of(componentId, new DeploymentRemoteWorkspace(unit.id(), ARCHIVE)), journal);
                    reviewed = scope.withCurrentEvidence(evidence);
                    guard = evidence::verifyPendingBinding;
                }
                return DeploymentAgentPublishRunner.run(reviewed, session, server, task, REVISION, proposal, Map.of(componentId, candidate),
                    journal, guard, budget, () -> {
                        releases++;
                        if (resourceReleaseFailure != null) throw resourceReleaseFailure;
                    });
            }
        }

        int count(String type) throws Exception {
            return journal.events(task).stream().filter(event -> event.type().equals(type)).toList().size();
        }

        String detail(String type) throws Exception {
            return journal.events(task).stream().filter(event -> event.type().equals(type)).findFirst().orElseThrow().detail();
        }

        public void close() throws SQLException {
            retained.close();
        }
    }

    @FunctionalInterface
    private interface Invocation {
        Object run(String method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation invoke) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> invoke.run(method.getName(), args)));
    }
}
