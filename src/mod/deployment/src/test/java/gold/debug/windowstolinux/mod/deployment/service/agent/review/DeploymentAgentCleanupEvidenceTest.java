package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.*;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.review.*;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual review/channel/graph path with local persisted UNKNOWN evidence and controlled capabilities. */
class DeploymentAgentCleanupEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REVISION = "a".repeat(64);

    private static final String ARCHIVE = "0123456789abcdef" + "b".repeat(48);

    private static final String SOURCE = "c".repeat(64);

    private static final String ARTIFACT = "d".repeat(64);

    private static final String RELEASE = "e".repeat(64);

    private static final String DENIAL = "Current candidate cleanup lacks an authorized deletion boundary.";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void actualHealthAndCleanupRoutesUseNativeAgentWithCurrentEvidenceAndPreserveOutcome(boolean health) throws Exception {
        try (var frame = new Frame()) {
            frame.nativeAgent = true;
            if (health) {
                frame.healthReviewFailure = "denied";
                assertEquals(TaskResult.UNKNOWN, frame.verifyPublished(ignored -> {
                }).outcome());
                assertEquals(0, frame.healthDispatches);
                assertEquals(0, frame.deletions);
                assertEquals(List.of("deployment.publication.original-status.v1", "deployment.publication.health.v1"), frame.approvalOperations());
                assertEquals(1, frame.hostGrants);
            } else {
                assertEquals(TaskResult.SUCCEEDED, frame.cleanup().outcome());
                assertEquals(1, frame.deletions);
                assertEquals(1, frame.absenceQueries);
                assertEquals(4, frame.hostGrants);
                assertEquals(List.of("deployment.publication.original-status.v1", "deployment.publication.original-status.v1",
                    "deployment.publication.candidate-cleanup.v1", "deployment.agent.helper.v1"), frame.approvalOperations());
            }
            assertEquals(frame.reviewFacts.size() * 2, frame.nativeRequests.size(), "Model-selected exact-request reads precede native verdicts");
            assertTrue(frame.nativeRequests.stream().allMatch(request -> request.purpose() == AiPurpose.APPROVAL
                && request.tools().stream().anyMatch(tool -> tool.name().equals("request"))));
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state(), "Review and cleanup cannot replace the original outcome");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "denied"})
    void originalStatusFailureReachesRecoveryDiagnosticsWithoutDispatchOrChangingOutcome(String failure) throws Exception {
        try (var frame = new Frame()) {
            frame.statusReviewFailure = failure;
            var failures = new ArrayList<Exception>();
            var result = frame.verifyPublished(failures::add);
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("DEPLOYMENT_GRAPH_STATUS_UNCONFIRMED", result.problem().orElseThrow().eventCode());
            assertEquals(1, failures.size());
            var actual = assertInstanceOf(ApiException.class, failures.getFirst());
            assertEquals(failure.equals("text") ? "DEPLOYMENT_COMMAND_REVIEW_INVALID" : "DEPLOYMENT_COMMAND_REVIEW_DENIED",
                actual.problem().eventCode());
            if (failure.equals("text"))
                assertEquals("A complete native result submission is required", actual.getCause().getMessage());
            else assertEquals(DENIAL, actual.problem().context().get("reviewReason"));
            assertEquals(List.of("deployment.publication.original-status.v1"), frame.approvalOperations());
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void statusDiagnosticSinkFailureKeepsUnknownAndRetainsTheActualQueryFailure() throws Exception {
        try (var frame = new Frame()) {
            frame.statusReviewFailure = "denied";
            var failures = new ArrayList<Exception>();
            var unavailable = new IllegalStateException("Controlled unavailable original-status diagnostics");
            var result = frame.verifyPublished(actual -> {
                failures.add(actual);
                throw unavailable;
            });
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("DEPLOYMENT_GRAPH_STATUS_UNCONFIRMED", result.problem().orElseThrow().eventCode());
            assertEquals(1, failures.size());
            assertSame(unavailable, failures.getFirst().getSuppressed()[0]);
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void defaultOriginalObservationRetainsItsExistingUnknownWithoutCallerDiagnostics() throws Exception {
        try (var frame = new Frame()) {
            frame.statusReviewFailure = "denied";
            var result = frame.publisher().observeOriginal(frame.proposal, Map.of("component", frame.candidate));
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("DEPLOYMENT_GRAPH_STATUS_UNAVAILABLE", result.problem().orElseThrow().eventCode());
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void recoveryHealthReportsTheActualMissingSubmissionWithoutDispatch() throws Exception {
        try (var frame = new Frame()) {
            frame.healthReviewFailure = "text";
            var failures = new ArrayList<Exception>();
            var result = frame.verifyPublished(failures::add);
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("DEPLOYMENT_GRAPH_HEALTH_UNAVAILABLE", result.problem().orElseThrow().eventCode());
            var actual = assertInstanceOf(ApiException.class, failures.getFirst());
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", actual.problem().eventCode());
            assertEquals("A complete native result submission is required", actual.getCause().getMessage());
            assertEquals(List.of("deployment.publication.original-status.v1", "deployment.publication.health.v1"), frame.approvalOperations());
            assertEquals(1, frame.statusQueries);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void recoveryHealthReportsTheActualDenialWithoutDispatch() throws Exception {
        try (var frame = new Frame()) {
            frame.healthReviewFailure = "denied";
            var failures = new ArrayList<Exception>();
            var result = frame.verifyPublished(failures::add);
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            var actual = assertInstanceOf(ApiException.class, failures.getFirst());
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", actual.problem().eventCode());
            assertEquals(DENIAL, actual.problem().context().get("reviewReason"));
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void recoveryDiagnosticFailureCannotReplaceTheUnknownHealthOutcome() throws Exception {
        try (var frame = new Frame()) {
            frame.healthReviewFailure = "text";
            var failures = new ArrayList<Exception>();
            var diagnostic = new IllegalStateException("Controlled unavailable diagnostic sink");
            var result = frame.verifyPublished(actual -> {
                failures.add(actual);
                throw diagnostic;
            });
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("DEPLOYMENT_GRAPH_HEALTH_UNAVAILABLE", result.problem().orElseThrow().eventCode());
            assertSame(diagnostic, failures.getFirst().getSuppressed()[0]);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void defaultHealthVerificationRetainsItsExistingUnknownResult() throws Exception {
        try (var frame = new Frame()) {
            frame.healthReviewFailure = "text";
            var result = frame.publisher().verifyPublished(frame.proposal, Map.of("component", frame.candidate));
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("DEPLOYMENT_GRAPH_HEALTH_UNAVAILABLE", result.problem().orElseThrow().eventCode());
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void liveReceiptAndOriginalUnknownDeliveryReachOnlyTheNewCleanupReview() throws Exception {
        try (var frame = new Frame()) {
            assertFalse(DeploymentAgentActionLog.effectsSettled(frame.journal.events(frame.task)));
            var before = frame.journal.events(frame.task);
            var result = frame.cleanup();
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals(2, frame.statusQueries);
            assertEquals(1, frame.deletions);
            assertEquals(1, frame.absenceQueries);
            assertEquals(List.of("deployment.publication.original-status.v1", "deployment.publication.original-status.v1",
                "deployment.publication.candidate-cleanup.v1", "deployment.agent.helper.v1"), frame.approvalOperations());
            var cleanup = frame.reviewFacts.stream().filter(value -> value.path("operation").asText().equals("deployment.publication.candidate-cleanup.v1")).findFirst().orElseThrow();
            var evidence = cleanup.path("currentCommandEvidence");
            assertEquals(frame.status(false), evidence.at("/queryResult/stdout").asText());
            assertEquals(frame.lastStatusAction, evidence.at("/queryRequest/exactAction").asText());
            assertEquals(0, evidence.at("/queryResult/exitCode").asInt());
            assertFalse(evidence.at("/queryResult/truncated").asBoolean());
            assertEquals("owned", evidence.at("/parsedStatus/candidate").asText());
            assertFalse(evidence.at("/parsedStatus/buildActive").asBoolean());
            assertEquals(RELEASE, evidence.at("/parsedStatus/current").asText());
            assertEquals(ARTIFACT, evidence.path("sealedArtifactSha256").asText());
            assertEquals(RELEASE, evidence.path("releaseSha256").asText());
            assertNotEquals(ARTIFACT, RELEASE);
            assertEquals(frame.task.id().toString(), evidence.path("currentTask").asText());
            assertEquals(frame.task.id().toString(), evidence.path("originalTask").asText());
            assertEquals(frame.unit.ownershipManifestSha256(), evidence.path("ownershipManifestSha256").asText());
            assertEquals(frame.originalCleanupAction, cleanup.path("exactAction").asText());
            assertTrue(evidence.path("reviewRequestSha256").asText().matches("[0-9a-f]{64}"));
            assertTrue(evidence.path("outerDeliveryOutcome").asText().startsWith("UNCONFIRMED_WRAPPER_RESULT"));
            assertTrue(evidence.path("observationLimits").asText().contains("not fresh remote hashes"));
            assertTrue(evidence.path("originalEventReferences").findValues("type").stream()
                .anyMatch(value -> value.asText().equals("CANDIDATE_CLEANUP_EXECUTING")));
            assertEquals(before, frame.journal.events(frame.task).subList(0, before.size()));
            assertFalse(DeploymentAgentActionLog.effectsSettled(frame.journal.events(frame.task)), "Cleanup must not synthesize an outer delivery result");
            assertEquals(1, frame.events("CLEANUP_REVIEW_REQUEST"));
            assertEquals(1, frame.events("CLEANUP_REVIEW_APPROVED"));
            assertEquals(1, frame.events("CLEANUP_ABSENCE_VERIFIED"));
            assertTrue(frame.reviewFacts.stream().filter(value -> !value.path("operation").asText().equals("deployment.publication.candidate-cleanup.v1"))
                .noneMatch(value -> value.has("currentCommandEvidence")), "Status queries must not recurse into cleanup evidence");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "truncated", "foreign-owner", "wrong-current", "unknown", "malformed", "query-denied"})
    void unconfirmedFreshStatusNeverDispatchesDeletion(String failure) throws Exception {
        try (var frame = new Frame()) {
            frame.failure = failure;
            assertEquals(TaskResult.UNKNOWN, frame.cleanup().outcome());
            assertEquals(0, frame.deletions);
            assertEquals(0, frame.absenceQueries);
            assertEquals(0, frame.events("CLEANUP_REVIEW_APPROVED"));
            assertEquals(0, frame.events("CANDIDATE_CLEANUP_RESULT"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "active", "present", "truncated", "denied"})
    void successfulDeleteWithoutConfirmedInactiveAbsenceRemainsUnknown(String failure) throws Exception {
        try (var frame = new Frame()) {
            frame.absenceFailure = failure;
            assertEquals(TaskResult.UNKNOWN, frame.cleanup().outcome());
            assertEquals(1, frame.deletions);
            assertEquals(0, frame.events("CLEANUP_ABSENCE_VERIFIED"));
            assertEquals(0, frame.events("CANDIDATE_CLEANUP_RESULT"));
        }
    }

    @Test
    void validDenialKeepsItsOriginalProblemAndCannotShopForApproval() throws Exception {
        try (var frame = new Frame()) {
            frame.denyCleanup = true;
            var evidence = frame.evidence();
            var channel = new DeploymentPublishChannel(evidence, evidence.context().interaction(), frame.server, frame.task, ARTIFACT);
            var refused = assertThrows(ApiException.class, () -> channel.cleanupCandidate(frame.unit, frame.candidate.candidateId()));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", refused.problem().eventCode());
            assertEquals(DENIAL, refused.problem().context().get("reviewReason"));
            assertEquals("true", refused.problem().context().get("requiresUserConfirmation"));
            assertEquals(1, frame.events("CLEANUP_REVIEW_DENIED"));
            int reviews = frame.reviewFacts.size();
            int queries = frame.statusQueries;
            assertThrows(ApiException.class, () -> channel.cleanupCandidate(frame.unit, frame.candidate.candidateId()));
            assertEquals(reviews, frame.reviewFacts.size());
            assertEquals(queries, frame.statusQueries);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void profileChangeWhileApprovingCleanupInvalidatesItBeforeDispatch() throws Exception {
        try (var frame = new Frame()) {
            frame.onCleanupReview = ignored -> frame.profile = new ServerInfo(frame.server, "controlled", "other-host", 22, "root", Optional.empty(), Optional.empty());
            assertEquals(TaskResult.UNKNOWN, frame.cleanup().outcome());
            assertEquals(0, frame.deletions);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "CURRENT_SOURCE", "SEALED_ARTIFACT", "SEALED_TOOLS"})
    void selectedBindingChangedDuringCleanupReviewNeverDispatchesDeletion(String type) throws Exception {
        try (var frame = new Frame()) {
            frame.onCleanupReview = ignored -> {
                try {
                    frame.changeBinding(type);
                } catch (Exception unavailable) {
                    throw new IllegalStateException(unavailable);
                }
            };
            assertEquals(TaskResult.UNKNOWN, frame.cleanup().outcome());
            assertEquals(2, frame.statusQueries, "Changed bindings cannot reuse the preceding current observation");
            assertEquals(0, frame.deletions);
            assertEquals(0, frame.events("CLEANUP_ABSENCE_VERIFIED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "CURRENT_SOURCE", "SEALED_ARTIFACT", "SEALED_TOOLS"})
    void selectedBindingChangedAfterHostGrantNeverDispatchesDeletion(String type) throws Exception {
        try (var frame = new Frame(); var request = frame.cleanupRequest("none")) {
            var evidence = frame.evidence();
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(frame.server, ARTIFACT, request)
                .withSemantics(new ActionSemantics("deployment.publication.candidate-cleanup.v1",
                    Set.of(new ActionTarget(frame.server.id(), "deployment-release", frame.unit.releaseRoot()))));
            var grant = ((DeploymentCommandReview) evidence.context().interaction()).approveCommand(intent, request, frame.task);
            assertEquals(TaskResult.SUCCEEDED, grant.outcome());
            frame.changeBinding(type);
            assertThrows(ApiException.class, () -> evidence.execute(request, grant.value().orElseThrow()));
            assertEquals(0, frame.deletions);
            assertEquals(0, frame.events("CLEANUP_ABSENCE_VERIFIED"));
        }
    }

    @Test
    void denialRemainsFinalWhenItsAuditBindingChangesBeforeTheCallback() throws Exception {
        try (var frame = new Frame()) {
            frame.denyCleanup = true;
            var originalProfile = frame.profile;
            frame.onCleanupReview = ignored -> frame.profile = new ServerInfo(frame.server, "controlled", "other-host", 22, "root", Optional.empty(), Optional.empty());
            var evidence = frame.evidence();
            var channel = new DeploymentPublishChannel(evidence, evidence.context().interaction(), frame.server, frame.task, ARTIFACT);
            var refused = assertThrows(ApiException.class, () -> channel.cleanupCandidate(frame.unit, frame.candidate.candidateId()));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", refused.problem().eventCode());
            assertEquals(DENIAL, refused.problem().context().get("reviewReason"));
            assertEquals("true", refused.problem().context().get("requiresUserConfirmation"));
            frame.profile = originalProfile;
            int reviews = frame.reviewFacts.size();
            assertThrows(ApiException.class, () -> channel.cleanupCandidate(frame.unit, frame.candidate.candidateId()));
            assertEquals(reviews, frame.reviewFacts.size());
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void unsettledInnerMutationBlocksTheUnfinishedOuterDeliveryException() throws Exception {
        try (var frame = new Frame()) {
            frame.journal.event(frame.task, "COMMAND_EXECUTING", "f".repeat(64), SOURCE);
            assertThrows(ApiException.class, frame::evidence);
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.deletions);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "server", "target", "revision", "timeout", "stdin"})
    void supplementalEvidenceRejectsChangedIdentityOrRequest(String change) throws Exception {
        try (var frame = new Frame(); var request = frame.cleanupRequest(change)) {
            var evidence = frame.evidence();
            var server = change.equals("server") ? new ServerId("foreign") : frame.server;
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server,
                change.equals("revision") ? SOURCE : ARTIFACT, request).withSemantics(new ActionSemantics("deployment.publication.candidate-cleanup.v1",
                Set.of(new ActionTarget(server.id(), "deployment-release", change.equals("target") ? "/opt/foreign" : frame.unit.releaseRoot()))));
            assertThrows(ApiException.class, () -> evidence.facts(intent, request, frame.task));
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.deletions);
        }
    }

    @Test
    void oldScopeConstructorKeepsDefaultFactsAndAddsNoRemoteObservation() throws Exception {
        try (var frame = new Frame()) {
            var scope = new DeploymentCommandReviewScope(frame.context, frame.context.interaction(), frame.task, "FULL_CONTROL", true, () -> REVISION);
            var channel = new DeploymentPublishChannel(frame.remote, scope.interaction(), frame.server, frame.task, ARTIFACT);
            assertEquals(TaskResult.SUCCEEDED, channel.cleanupCandidate(frame.unit, frame.candidate.candidateId()).outcome());
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.absenceQueries);
            assertEquals(1, frame.deletions);
            assertEquals(1, frame.reviewFacts.size());
            assertFalse(frame.reviewFacts.getFirst().has("currentCommandEvidence"));
        }
    }

    private static final class Frame implements AutoCloseable {
        private final String jdbc = "jdbc:h2:mem:cleanup-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

        private final Connection retained = DriverManager.getConnection(jdbc);

        private final UUID taskId = UUID.randomUUID();

        private final TaskContext task = proxy(TaskContext.class, (name, args) -> switch (name) {
            case "id" -> taskId;
            case "cancelToken" -> (CancelToken) () -> false;
            case "progress" -> null;
            default -> throw new AssertionError(name);
        });

        private final ServerId server = new ServerId("controlled");

        private ServerInfo profile = new ServerInfo(server, "controlled", "controlled-host", 22, "root", Optional.empty(), Optional.empty());

        private final RemoteFacts remoteFacts = new RemoteFacts("Linux", "amd64", "controlled-fingerprint");

        private final DeploymentUnitIdentity unit = DeploymentUnitIdentity.forManaged("controlled", server, "b".repeat(64));

        private final DeploymentGraphPublisher.Candidate candidate = new DeploymentGraphPublisher.Candidate("controlled-0123456789abcdef", ARTIFACT, SOURCE, "0".repeat(64), false, Map.of());

        private final DeploymentPublishResult proposal;

        private final ModContext context;

        private final RemoteSession remote;

        private final DeploymentAgentActionLog journal;

        private final List<JsonNode> reviewFacts = new ArrayList<>();

        private final List<AiRequest> nativeRequests = new ArrayList<>();

        private boolean nativeAgent;

        private int hostGrants;

        private String failure = "none";

        private String absenceFailure = "none";

        private String healthReviewFailure = "none";

        private int healthDispatches;

        private boolean denyCleanup;

        private Consumer<JsonNode> onCleanupReview = ignored -> {
        };

        private int statusQueries;

        private String statusReviewFailure = "";

        private int deletions;

        private int absenceQueries;

        private String lastStatusAction;

        private String originalCleanupAction;

        Frame() throws Exception {
            try (var source = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, source);
            }
            var db = proxy(ModDatabase.class, (name, args) -> name.equals("identity")
                ? new SchemaIdentity(DeploymentSchemaIdentity.OWNER, DeploymentSchemaIdentity.VERSION, taskId) : DriverManager.getConnection(jdbc));
            journal = new DeploymentAgentActionLog(db);
            remote = proxy(RemoteSession.class, (name, args) -> {
                if (name.equals("facts")) return remoteFacts;
                assertEquals("execute", name);
                var request = (CommandRequest) args[0];
                if (request.script().contains("'application-health'")) {
                    healthDispatches++;
                    throw new AssertionError("Rejected or malformed health review must not dispatch");
                }
                if (request.script().contains("'publication-original-status'")) {
                    lastStatusAction = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, ARTIFACT, request).exactAction();
                    statusQueries++;
                    String mode = statusQueries > 1 ? failure : "none";
                    if (mode.equals("unknown")) return unknown();
                    if (mode.equals("foreign-owner")) return new OperationResult<>(TaskResult.FAILED,
                        Optional.of(new CommandResult(Optional.of(64), "", "MANAGED_HELPER_REJECT=publication-candidate-foreign\n", false)), Optional.empty());
                    return success(new CommandResult(Optional.of(0), mode.equals("malformed") ? "PUBLICATION_STATUS=1\n" : status(mode.equals("active"))
                        .replace(RELEASE, mode.equals("wrong-current") ? SOURCE : RELEASE), "", mode.equals("truncated")));
                }
                if (request.script().contains("'candidate-cleanup-task'")) {
                    deletions++;
                    originalCleanupAction = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, ARTIFACT, request).exactAction();
                    return success(new CommandResult(Optional.of(0), "CANDIDATE_CLEANED=1\n", "", false));
                }
                if (request.script().contains("'candidate-query-task'")) {
                    absenceQueries++;
                    if (absenceFailure.equals("unknown")) return unknown();
                    return success(new CommandResult(Optional.of(0), "CANDIDATE_STATE=" + (absenceFailure.equals("present") ? "owned" : "absent")
                        + "\nBUILD_ACTIVE=" + (absenceFailure.equals("active") ? "yes" : "no") + "\n", "", absenceFailure.equals("truncated")));
                }
                throw new AssertionError(request.script());
            });
            var ai = proxy(AiService.class, (name, args) -> {
                if (name.equals("configurationRevision")) return REVISION;
                assertEquals("call", name);
                var request = (AiRequest) args[0];
                assertEquals(AiPurpose.APPROVAL, request.purpose());
                var facts = JSON.readTree(request.messages().getLast().text());
                if (nativeAgent) {
                    nativeRequests.add(request);
                    facts = facts.path("currentRequest");
                    if (request.messages().stream().noneMatch(message -> message.toolResult().isPresent()))
                        return success(new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                            List.of(new AiToolCall("read-" + nativeRequests.size(), "request", "{\"part\":\"script\",\"offset\":0,\"limit\":8000}")), Optional.empty()));
                }
                reviewFacts.add(facts);
                String operation = facts.path("operation").asText();
                if (operation.equals("deployment.publication.health.v1") && healthReviewFailure.equals("text")
                    || operation.equals("deployment.publication.original-status.v1") && statusReviewFailure.equals("text"))
                    return success(new AiResponse("Controlled text without native submission", "controlled", "stop",
                        new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
                boolean cleanup = operation.equals("deployment.publication.candidate-cleanup.v1");
                if (cleanup) onCleanupReview.accept(facts);
                boolean deny = cleanup && denyCleanup || operation.equals("deployment.publication.original-status.v1")
                    && failure.equals("query-denied") && statusQueries == 1 || operation.equals("deployment.agent.helper.v1") && absenceFailure.equals("denied")
                    || operation.equals("deployment.publication.health.v1") && healthReviewFailure.equals("denied");
                deny |= operation.equals("deployment.publication.original-status.v1") && statusReviewFailure.equals("denied");
                var verdict = new LinkedHashMap<String, Object>(Map.of("decision", deny ? "DENY" : "APPROVE",
                    "requiresUserConfirmation", deny, "reason", deny ? DENIAL : "Controlled current boundary"));
                if (nativeAgent) {
                    for (String field : List.of("missingEvidence", "adjustments", "candidateCommands"))
                        verdict.put(field, List.of());
                    if (deny) verdict.put("discussionStatus", "CLOSED");
                }
                return success(new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    List.of(new AiToolCall("review-" + reviewFacts.size(), "submit_result", JSON.writeValueAsString(verdict))), Optional.empty()));
            });
            var host = proxy(InteractionService.class, (name, args) -> {
                assertTrue(Set.of("approve", "approveReviewedAction").contains(name), name);
                hostGrants++;
                return success(new ApprovalGrant(UUID.randomUUID()));
            });
            var tasks = proxy(TaskService.class, (name, args) -> {
                assertEquals("findCheckpoint", name);
                return Optional.empty();
            });
            var servers = proxy(ServerService.class, (name, args) -> {
                assertEquals("get", name);
                return profile;
            });
            var logs = proxy(LogService.class, (name, args) -> name.equals("register") ? new LogTarget(UUID.randomUUID()) : null);
            var ui = proxy(UiService.class, (name, args) -> new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            context = proxy(ModContext.class, (name, args) -> switch (name) {
                case "db" -> db;
                case "ai" -> ai;
                case "interaction" -> host;
                case "tasks" -> tasks;
                case "servers" -> servers;
                case "ui" -> ui;
                case "logs" -> logs;
                default -> throw new AssertionError(name);
            });
            var health = new DeploymentHealthCheck.Process(10, 1);
            var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true,
                new DeploymentCommand("run.sh", List.of()), "", List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
            var runtime = new DeploymentRuntimeDefinition.ManagedProcess(health, DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            var config = new DeploymentUnitRuntimeConfig(health, Optional.empty(), DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            var graph = new DeploymentApplicationGraph("controlled", "component", health, List.of(new DeploymentApplicationGraph.Part("component", unit,
                config, List.of(), runtime, List.of(), new DeploymentResourceBindings(List.of(), Optional.of(List.of())))));
            proposal = new DeploymentPublishResult(graph, List.of(new DeploymentUnitCommit(unit, config, new DeploymentCurrentRelease(unit.id(), RELEASE, Instant.EPOCH),
                DeploymentConfigSnapshot.create(unit.id(), 1, "v1", Instant.EPOCH, List.of()), List.of())));
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), DeploymentAgentInputs.targetDigest(server, remote), REVISION, "FULL_CONTROL", true));
            journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(profile));
            journal.event(task, "SOURCE", ARCHIVE, SOURCE);
            journal.event(task, "CANDIDATE", "component", candidate.candidateId());
            journal.event(task, "CURRENT_SOURCE", "component", SOURCE);
            journal.event(task, "SEALED_ARTIFACT", "component", ARTIFACT);
            journal.event(task, "SEALED_TOOLS", "component", "0".repeat(64) + ":false");
            journal.event(task, "DELIVERY_EXECUTING", "f".repeat(64), "1");
            journal.event(task, "AI_PUBLICATION", "controlled", "MODEL_GENERATED_COMMANDS");
            journal.event(task, "AI_PUBLICATION_VERIFIED", "controlled", "HEALTHY");
            journal.event(task, "AI_PUBLICATION_FINISHED", "controlled", "MODEL_ACCEPTED_VERIFIED_RESULT");
            journal.event(task, "CANDIDATE_CLEANUP_EXECUTING", "component", candidate.candidateId());
            var executions = new DeploymentExecutionStore(db);
            var id = new DeploymentExecutionIdentity(UUID.randomUUID(), taskId, Optional.empty(), "controlled", server, DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, SOURCE);
            var references = gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit.references(server, proposal, Map.of());
            executions.begin(task, id, "deployment-agent", references, Optional.empty(), Instant.EPOCH);
            for (var reference : references)
                executions.recordReference(task, id.executionId(), reference, UUID.randomUUID(), Instant.EPOCH);
            executions.startEffects(task, id.executionId(), Instant.EPOCH.plusSeconds(1));
            executions.recordPublicationTemplate(task, id.executionId(), new DeploymentPublishProposal(proposal.graph(), proposal.units()), Instant.EPOCH.plusSeconds(2));
            executions.recordUncommittedOutcome(task, id.executionId(), TaskResult.UNKNOWN, new Problem(ProblemCode.UNKNOWN, "CLEANUP_UNCONFIRMED", "Retained original cleanup", Map.of(), Optional.empty()), Instant.EPOCH.plusSeconds(3));
            journal.state(task, "UNKNOWN");
        }

        DeploymentAgentCleanupEvidence evidence() throws Exception {
            var scope = scope();
            return new DeploymentAgentCleanupEvidence(scope, remote, server, task, REVISION, proposal, Map.of("component", candidate), journal, DeploymentSendGuard.NONE, true);
        }

        DeploymentGraphPublisher publisher() throws Exception {
            var scope = scope();
            return new DeploymentGraphPublisher(scope, remote, server, task, (type, action, detail) -> journal.event(task, type, action, detail));
        }

        private DeploymentCommandReviewScope scope() throws Exception {
            return nativeAgent ? DeploymentAgentApproval.scope(context, task, "FULL_CONTROL", true, () -> REVISION, DeploymentCurrentCommandEvidence.NONE)
                : new DeploymentCommandReviewScope(context, context.interaction(), task, "FULL_CONTROL", true, () -> REVISION);
        }

        OperationResult<Void> verifyPublished(Consumer<Exception> diagnostics) throws Exception {
            return publisher().verifyPublished(proposal, Map.of("component", candidate), diagnostics);
        }

        OperationResult<Void> cleanup() throws Exception {
            var evidence = evidence();
            return new DeploymentGraphPublisher(evidence.context(), evidence, server, task,
                (type, action, detail) -> journal.event(task, type, action, detail)).cleanup(proposal, Map.of("component", candidate), true);
        }

        String status(boolean active) {
            return "PUBLICATION_STATUS=1\nCURRENT=" + RELEASE + "\nCURRENT_KIND=deployment\nCURRENT_MODE=DAEMON\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE=owned\nBUILD_ACTIVE="
                + (active ? "yes" : "no") + "\nUNIT_PRESENT=yes\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=1\n";
        }

        CommandRequest cleanupRequest(String change) {
            String script = List.of(DeploymentManagedHelperBundle.PATH, "candidate-cleanup-task", unit.id(), candidate.candidateId(),
                    change.equals("task") ? UUID.randomUUID().toString() : taskId.toString()).stream().map(DeploymentBuildCommandChannel::shellQuote)
                .collect(java.util.stream.Collectors.joining(" "));
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script), change.equals("stdin") ? new byte[]{1} : new byte[0],
                Duration.ofSeconds(change.equals("timeout") ? 45 : 90), 4096);
        }

        long events(String type) throws Exception {
            return journal.events(task).stream().filter(value -> value.type().equals(type)).count();
        }

        void changeBinding(String type) throws Exception {
            String changed = switch (type) {
                case "CANDIDATE" -> "controlled-fedcba9876543210";
                case "SEALED_TOOLS" -> "f".repeat(64) + ":true";
                default -> "f".repeat(64);
            };
            journal.event(task, type, "component", changed);
        }

        List<String> approvalOperations() {
            return reviewFacts.stream().map(value -> value.path("operation").asText()).toList();
        }

        @Override
        public void close() throws SQLException {
            retained.close();
        }
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static OperationResult<CommandResult> unknown() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.empty());
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation callback) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> callback.call(method.getName(), args)));
    }
}
