package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.*;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishArguments;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentContainerArguments;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentRuntimeArguments;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentRecovery;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationObservation;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentHealthEvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REVISION = "a".repeat(64);

    private static final String ARCHIVE = "0123456789abcdef" + "b".repeat(48);

    private static final String SOURCE = "c".repeat(64);

    private static final String ARTIFACT = "d".repeat(64);

    private static final String RELEASE = "e".repeat(64);

    private static final String DENIAL = "Controlled original health target denial";

    @Test
    void publicationIdentityBindsDistinctDigestsWithoutClaimingObservedOwner() throws Exception {
        for (String kind : List.of("HTTP", "CONTAINER")) {
            var layout = kind.equals("CONTAINER") ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
                "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data")) : DeploymentRemoteLayout.original();
            try (var frame = new Frame(kind, null, layout, true);
                 var request = frame.publicationRequest("none")) {
                var scope = frame.publicationScope();
                var evidence = frame.publicationEvidence(scope);
                var intent = frame.publicationIntent(request, "none");
                var current = JSON.valueToTree(evidence.facts(intent, request, frame.task)).path("publicationIdentity");
                var candidate = frame.candidates.get("backend");
                var part = frame.part("backend");
                var commit = frame.proposal.units().stream().filter(value -> value.unit().equals(part.unit())).findFirst().orElseThrow();
                assertEquals(DeploymentAgentInputs.hash("agent-release:" + candidate.artifactSha256() + ":" + candidate.sourceRevision()
                    + ":" + commit.configuration().sha256() + ":" + part.reviewedRuntime() + ":" + part.resources()), frame.release("backend"));
                assertNotEquals(frame.release("backend"), candidate.artifactSha256());
                assertNotEquals(frame.release("backend"), part.unit().ownershipManifestSha256());
                assertEquals(candidate.artifactSha256(), intent.revision());
                assertEquals(SOURCE, current.path("originalSnapshotManifestSha256").asText());
                assertEquals("6".repeat(64), current.path("currentProtectedTreeRevision").asText());
                assertNotEquals(current.path("originalSnapshotManifestSha256"), current.path("currentProtectedTreeRevision"));
                assertEquals(frame.release("backend"), current.path("positionalArguments").get(2).path("value").asText());
                assertEquals("expectedOwnershipManifestSha256", current.path("positionalArguments").get(3).path("role").asText());
                assertTrue(current.path("ownerMeaning").asText().contains("not an observation"));
                assertEquals("NONE", current.path("authority").asText());
                assertEquals("NO_RESULT_FOR_THIS_PENDING_REQUEST", current.path("publicationResult").asText());
                assertEquals("NOT_DECIDED_BY_THIS_PROJECTION", current.path("independentAppReview").asText());
                assertEquals("NOT_GRANTED_BY_THIS_PROJECTION", current.path("hostSingleUseGrant").asText());
                assertEquals(6, current.path("receiptEventIds").size());
                frame.journal.event(frame.task, "DECISION", "review", "APPROVE");
                frame.journal.event(frame.task, "AGENT_ASSESSMENT", "publish", "Controlled append only bookkeeping");
                evidence.verify(intent, request, frame.task);
                evidence.verifyPendingBinding();
                try (var second = frame.publicationRequest("frontend", "none")) {
                    var next = frame.publicationIntent(second, "frontend", "none");
                    assertEquals("frontend", JSON.valueToTree(evidence.facts(next, second, frame.task)).at("/publicationIdentity/component").asText());
                    evidence.verify(next, second, frame.task);
                    evidence.verifyPendingBinding();
                }
                try (var other = frame.healthRequest("none")) {
                    assertTrue(evidence.facts(frame.healthIntent(other, "none"), other, frame.task).isEmpty());
                    assertEquals("", evidence.reviewInstructions());
                }
                assertEquals(0, frame.aiRequests.size());
                assertEquals(0, frame.hostAuthorizations);
                assertEquals(0, frame.remoteExecutions);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-seal", "CURRENT_SOURCE", "SEALED_ARTIFACT", "CANDIDATE", "SEALED_TOOLS", "profile", "route",
        "release", "owner", "candidate", "configuration", "runtime", "resources", "script", "server", "target", "task", "business", "noncanonical-unit", "layout"})
    void publicationIdentityRejectsMissingOrChangedOriginalBindings(String change) throws Exception {
        var layout = change.equals("layout") ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data")) : DeploymentRemoteLayout.original();
        try (var frame = new Frame("HTTP", null, layout, true);
             var request = frame.publicationRequest(change)) {
            if (change.equals("noncanonical-unit")) {
                var old = frame.part("backend");
                var unit = old.unit();
                var wrong = new DeploymentUnitIdentity(unit.id(), unit.server(), frame.part("frontend").unit().systemdUnit(), unit.releaseRoot(), unit.ownershipManifestSha256(), unit.layout());
                var parts = frame.proposal.graph().components().stream().map(part -> part.componentId().equals("backend")
                    ? new DeploymentApplicationGraph.Part(part.componentId(), wrong, part.runtime(), part.dependencies(), part.reviewedRuntime(), part.dataPaths(), part.resources()) : part).toList();
                var commits = frame.proposal.units().stream().map(commit -> commit.unit().equals(unit)
                    ? new DeploymentUnitCommit(wrong, commit.runtime(), commit.release(), commit.configuration(), commit.secrets()) : commit).toList();
                var graph = frame.proposal.graph();
                var changed = new DeploymentPublishResult(new DeploymentApplicationGraph(graph.applicationId(), graph.healthComponentId(), graph.healthCheck(), parts), commits);
                assertThrows(IllegalArgumentException.class, () -> frame.publicationEvidence(frame.publicationScope(), changed));
                assertTrue(frame.aiRequests.isEmpty());
                assertEquals(0, frame.hostAuthorizations);
                assertEquals(0, frame.remoteExecutions);
                return;
            }
            var evidence = frame.publicationEvidence(frame.publicationScope());
            if (change.equals("missing-seal")) {
                try (var delete = frame.retained.prepareStatement("DELETE FROM deployment_agent_event WHERE task_id=? AND event_type='SEAL_RESULT'")) {
                    delete.setString(1, frame.task.id().toString());
                    delete.executeUpdate();
                }
            } else if (Set.of("CURRENT_SOURCE", "SEALED_ARTIFACT", "CANDIDATE", "SEALED_TOOLS", "profile", "route").contains(change))
                frame.changeBinding(change);
            else if (change.equals("layout")) frame.capturedLayout = DeploymentRemoteLayout.original();
            var supplied = change.equals("task") ? proxy(TaskContext.class, (name, args) -> frame.task.id()) : frame.task;
            assertThrows(ApiException.class, () -> evidence.facts(frame.publicationIntent(request, change), request, supplied));
            assertEquals(0, frame.aiRequests.size());
            assertEquals(0, frame.hostAuthorizations);
            assertEquals(0, frame.remoteExecutions);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP", "HOST", "SEND"})
    void publicationBindingsAreRecheckedAfterAppHostAndBeforeSend(String boundary) throws Exception {
        try (var frame = new Frame("HTTP", null, DeploymentRemoteLayout.original(), true);
             var request = frame.publicationRequest("none")) {
            var scope = frame.publicationScope();
            var evidence = frame.publicationEvidence(scope);
            var review = (DeploymentCommandReview) scope.withCurrentEvidence(evidence).interaction();
            var intent = frame.publicationIntent(request, "none");
            if (boundary.equals("APP")) frame.onPublicationReview = ignored -> frame.changeBinding("CURRENT_SOURCE");
            if (boundary.equals("HOST")) frame.onPublicationHost = () -> frame.changeBinding("profile");
            if (boundary.equals("SEND")) {
                var grant = review.approveCommand(intent, request, frame.task);
                assertEquals(TaskResult.SUCCEEDED, grant.outcome());
                frame.changeBinding("SEALED_ARTIFACT");
                assertThrows(ApiException.class, evidence::verifyPendingBinding);
            } else {
                assertThrows(ApiException.class, () -> review.approveCommand(intent, request, frame.task));
                if (boundary.equals("APP")) assertEquals(0, frame.hostAuthorizations);
            }
            assertEquals(1, frame.aiRequests.size());
            assertEquals(0, frame.remoteExecutions);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.events("AI_PUBLICATION_VERIFIED"));
        }
    }

    @Test
    void localCompletionIsProducedByFactsBeforeAnyVerificationOrIndependentReview() throws Exception {
        try (var frame = new Frame(); var request = frame.queryRequest("none")) {
            var evidence = frame.evidence();
            var intent = frame.queryIntent(request);
            var current = JSON.valueToTree(evidence.facts(intent, request, frame.task));
            assertEquals("COMPLETED", current.at("/completedLocalChecks/status").asText());
            assertEquals(DeploymentAgentInputs.hash(intent.exactAction()), current.at("/completedLocalChecks/exactActionSha256").asText());
            assertEquals("backend", current.at("/completedLocalChecks/component").asText());
            assertEquals("NOT_YET_DECIDED", current.at("/completedLocalChecks/independentAppReview").asText());
            assertEquals("NOT_YET_OBTAINED", current.at("/completedLocalChecks/hostSingleUseGrant").asText());
            assertEquals("NO_RESULT_FOR_THIS_PENDING_REQUEST", current.path("receiptState").asText());
            assertEquals("", evidence.reviewInstructions(), "No canonical verify call has exposed SYSTEM instructions yet");
            assertTrue(frame.aiRequests.isEmpty());
            assertEquals(0, frame.hostAuthorizations);
            assertEquals(0, frame.remoteExecutions);
            evidence.verify(intent, request, frame.task);
            assertEquals(DeploymentAgentObservationReviewContract.text(), evidence.reviewInstructions());
            assertTrue(frame.aiRequests.isEmpty());
            assertEquals(0, frame.hostAuthorizations);
            assertEquals(0, frame.remoteExecutions);
        }
    }

    @Test
    void completedLocalChecksBindEachCurrentGraphRequestWithoutClaimingItsPermissionOrRemoteGuards() throws Exception {
        try (var frame = new Frame()) {
            var evidence = frame.evidence();
            var assemblies = new ArrayList<JsonNode>();
            Consumer<JsonNode> inspect = envelope -> {
                var current = envelope.path("currentCommandEvidence");
                var completed = current.path("completedLocalChecks");
                assertEquals("COMPLETED", completed.path("status").asText());
                assertEquals("THIS_FACTS_ASSEMBLY_BEFORE_INDEPENDENT_APP_REVIEW", completed.path("phase").asText());
                assertEquals(DeploymentAgentInputs.hash(envelope.path("exactAction").asText()), completed.path("exactActionSha256").asText());
                assertEquals(frame.task.id().toString(), completed.path("currentTask").asText());
                assertEquals(frame.server.id(), completed.path("server").asText());
                assertEquals(REVISION, completed.path("currentReviewConfigurationRevision").asText());
                String component = completed.path("component").asText();
                assertEquals(component, current.at("/unitBinding/component").asText());
                assertEquals(frame.part(component).unit().id(), current.at("/unitBinding/applicationId").asText());
                assertEquals(frame.part(component).unit().ownershipManifestSha256(), current.at("/unitBinding/ownershipManifestSha256").asText());
                assertEquals(6, completed.path("checks").size());
                assertEquals("NOT_YET_DECIDED", completed.path("independentAppReview").asText());
                assertEquals("NOT_YET_OBTAINED", completed.path("hostSingleUseGrant").asText());
                assertEquals("NOT_OCCURRED", completed.path("pendingRequestSend").asText());
                assertEquals("NOT_EXECUTED_FOR_THIS_REQUEST", completed.path("pendingRemoteGuards").asText());
                assertEquals(3, completed.path("laterRequiredChecks").size());
                assertTrue(completed.path("meaning").asText().contains("No subsequent verify call is counted here"));
                assertEquals(frame.aiRequests.size() - 1, frame.hostAuthorizations, "This request has not obtained its own host grant at APP input assembly");
                if (envelope.path("operation").asText().equals("deployment.publication.original-status.v1")) {
                    assertEquals("NO_RESULT_FOR_THIS_PENDING_REQUEST", current.path("receiptState").asText());
                    assertFalse(current.has("currentGraphQueries"));
                    assertFalse(current.has("result"));
                }
                if (current.has("observationReviewContract"))
                    assertTrue(current.at("/observationReviewContract/meaning").asText().contains("no remote execution-time guard is asserted passed"));
                assemblies.add(current.deepCopy());
            };
            frame.onStatusReview = inspect;
            frame.onHealthReview = inspect;
            frame.onRuntimeReview = inspect;
            assertEquals(TaskResult.SUCCEEDED, frame.verify(evidence).outcome());
            assertEquals(9, assemblies.size());
            var status = assemblies.stream().filter(value -> value.path("schema").asText().equals("agent-current-original-status-evidence.v1")).toList();
            assertEquals(2, status.size());
            assertEquals(Set.of("backend", "frontend"), Set.copyOf(status.stream().map(value -> value.at("/completedLocalChecks/component").asText()).toList()));
            assertNotEquals(status.get(0).at("/completedLocalChecks/exactActionSha256"), status.get(1).at("/completedLocalChecks/exactActionSha256"));
            assertNotEquals(status.get(0).path("completedLocalChecks"), status.get(1).path("completedLocalChecks"));
            assertEquals(2, frame.statusQueries);
            assertEquals(3, frame.healthDispatches);
            assertEquals(2, frame.runtimeInspections);
            assertEquals(2, frame.runtimeObservations);
            assertEquals("", evidence.reviewInstructions());
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "task", "profile", "CURRENT_SOURCE"})
    void failedLocalIdentityOrCanonicalSelectionCannotLeakCompletedFactsToApp(String change) throws Exception {
        try (var frame = new Frame(); var request = frame.queryRequest(change.equals("request") ? "candidate" : "none")) {
            var evidence = frame.evidence();
            if (Set.of("profile", "CURRENT_SOURCE").contains(change)) frame.changeBinding(change);
            var supplied = change.equals("task") ? proxy(TaskContext.class, (name, args) -> {
                throw new AssertionError("An unrelated TaskContext must be rejected by identity before it is used");
            }) : frame.task;
            var before = frame.journal.events(frame.task);
            var review = (DeploymentCommandReview) evidence.context().interaction();
            if (change.equals("task"))
                assertThrows(IllegalArgumentException.class, () -> review.approveCommand(frame.queryIntent(request), request, supplied));
            var rejection = change.equals("task")
                ? assertThrows(ApiException.class, () -> evidence.facts(frame.queryIntent(request), request, supplied))
                : assertThrows(ApiException.class, () -> review.approveCommand(frame.queryIntent(request), request, supplied));
            assertFalse(rejection.problem().context().containsKey("completedLocalChecks"));
            assertTrue(frame.aiRequests.isEmpty());
            assertTrue(frame.reviewFacts.isEmpty());
            assertEquals(0, frame.hostAuthorizations);
            assertEquals(0, frame.remoteExecutions);
            assertEquals(before, frame.journal.events(frame.task));
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @Test
    void formalRecoveryRetainsTheFailedHelperReviewCategoryAndStaysUnknownWithoutSendingOrRetrying() throws Exception {
        try (var frame = new Frame()) {
            var failure = new Problem(ProblemCode.IO_FAILURE, "CONTROLLED_PROVIDER_NATIVE_FAILURE", "Controlled provider failure",
                Map.of("diagnosticId", "controlled-original-diagnostic"), Optional.of(frame.task.id()));
            frame.reviewCallFailure = new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(failure));
            var before = frame.journal.events(frame.task);
            var saved = frame.journal.find(frame.task).orElseThrow();
            var executions = new DeploymentExecutionStore(frame.context.db());
            var formal = executions.findForTask(frame.task).orElseThrow();
            assertTrue(executions.publicationTemplate(formal).isPresent());
            var recovered = new DeploymentAgentRecovery(frame.context, frame.journal).reconcile(frame.task, saved);
            assertEquals(TaskResult.UNKNOWN, recovered.outcome());
            assertEquals("DEPLOYMENT_AGENT_HELPER_CHANGED", recovered.problem().orElseThrow().eventCode());
            assertEquals(1, frame.aiRequests.size());
            var firstEvidence = frame.aiRequests.getFirst().messages().get(1);
            assertEquals(AiRole.USER, firstEvidence.role());
            var reviewEnvelope = JSON.readTree(firstEvidence.text());
            assertEquals("FIRST_USER_MESSAGE_AFTER_BASE_SYSTEM", frame.reviewFacts.getFirst().path("currentEvidence").asText());
            assertEquals(reviewEnvelope.path("reviewBinding"), frame.reviewFacts.getFirst().path("reviewBinding"));
            assertEquals(frame.task.id().toString(), reviewEnvelope.path("task").asText());
            var currentRequest = reviewEnvelope.path("currentRequest");
            assertEquals("deployment.helper.probe.v1", currentRequest.path("operation").asText());
            assertEquals(JSON.valueToTree(List.of(frame.server.id())), currentRequest.path("serverTargets"));
            assertEquals(saved.identity().configurationDigest(), currentRequest.path("businessRevision").asText());
            assertEquals("EMPTY", currentRequest.path("inputKind").asText());
            assertFalse(currentRequest.has("currentCommandEvidence"));
            assertEquals(0, frame.hostAuthorizations);
            assertEquals(0, frame.remoteExecutions);
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.healthDispatches);
            assertEquals(1, frame.workflowOpens);
            assertEquals(1, frame.workflowCloses);
            assertEquals(1, frame.linuxConnections);
            assertEquals(1, frame.remoteCloses);
            assertFalse(frame.progressStages.contains("AI_RECOVERY_ORIGINAL_HOST_VERIFIED"));
            var effects = frame.diagnosticEvents.stream().filter(event -> event.eventCode().equals("DEPLOYMENT_EFFECT_OUTCOME")).toList();
            assertEquals(1, effects.size());
            assertEquals(failure.eventCode(), effects.getFirst().message());
            assertEquals(Optional.of(TaskResult.FAILED), effects.getFirst().outcome());
            assertEquals(Optional.of(frame.task.id()), effects.getFirst().taskId());
            assertTrue(effects.getFirst().details().isEmpty(), "Existing diagnostics retain only the original failure category/outcome, not full provider context");
            assertFalse(effects.getFirst().message().contains("controlled-original-diagnostic"));
            assertEquals(before, frame.journal.events(frame.task));
            assertEquals(saved, frame.journal.find(frame.task).orElseThrow());
            assertEquals(formal, executions.findForTask(frame.task).orElseThrow());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "COMMAND"})
    void trustedHealthContractIsFixedSystemTextOnceAndNotRepeatedAsUserSource(String kind) throws Exception {
        try (var frame = new Frame(kind)) {
            frame.maintenanceMarkers.put("frontend", "deployment-" + frame.release("frontend"));
            var evidence = frame.evidence();
            String contract = DeploymentAgentHealthReviewContract.text();
            assertEquals("", evidence.reviewInstructions());
            assertEquals(TaskResult.SUCCEEDED, frame.verify(evidence).outcome());
            assertEquals("", evidence.reviewInstructions());
            assertEquals(9, frame.aiRequests.size());
            int healthContracts = 0;
            int observationContracts = 0;
            for (var request : frame.aiRequests) {
                var systems = request.messages().stream().filter(message -> message.role() == AiRole.SYSTEM).toList();
                assertEquals(1, systems.size());
                String system = systems.getFirst().text();
                var users = request.messages().stream().filter(message -> message.role() == AiRole.USER).toList();
                assertEquals(1, users.size());
                String user = users.getFirst().text();
                assertFalse(user.contains(contract));
                assertFalse(user.contains(DeploymentAgentObservationReviewContract.text()));
                assertFalse(user.contains("helperSourceEvidence"));
                assertFalse(user.contains("application_health() {"));
                var current = JSON.readTree(user).path("currentCommandEvidence");
                assertTrue(current.path("currentWorkflow").asText().contains("no approval, host grant or health success is asserted obtained"));
                assertFalse(contract.contains(frame.task.id().toString()));
                assertFalse(contract.contains(frame.part("backend").unit().ownershipManifestSha256()));
                if (current.path("schema").asText().equals("agent-current-health-evidence.v1")) {
                    assertTrue(current.has("healthPayloadByteView"));
                    assertFalse(system.contains(DeploymentAgentObservationReviewContract.text()));
                    assertTrue(system.contains(contract));
                    assertEquals(system.indexOf(contract), system.lastIndexOf(contract));
                    healthContracts++;
                    assertEquals(DeploymentAgentHealthReviewContract.REVISION, current.at("/healthReviewContract/revision").asText());
                    assertEquals(DeploymentAgentInputs.hash(contract), current.at("/healthReviewContract/textSha256").asText());
                    if (current.at("/unitBinding/component").asText().equals("backend"))
                        assertEquals(kind, current.at("/typedHealthPolicy/kind").asText());
                    assertEquals(3, current.at("/argumentBinding/argumentCount").asInt());
                } else {
                    assertFalse(current.has("healthPayloadByteView"));
                    assertFalse(system.contains(contract));
                    assertFalse(current.has("healthReviewContract"));
                    String observation = DeploymentAgentObservationReviewContract.text();
                    assertTrue(system.contains(observation));
                    assertEquals(system.indexOf(observation), system.lastIndexOf(observation));
                    observationContracts++;
                    assertEquals(DeploymentAgentObservationReviewContract.REVISION,
                        current.at("/observationReviewContract/revision").asText());
                    assertFalse(current.path("currentWorkflow").asText().contains("trusted health contract"));
                    assertFalse(current.path("currentWorkflow").asText().contains("fixed health admission/orphan controls"));
                }
            }
            assertEquals(3, healthContracts);
            assertEquals(6, observationContracts);
            assertEquals(2, frame.statusQueries);
            assertEquals(3, frame.healthDispatches);
            for (var actual : frame.healthActions) assertEquals(actual.expectedAction(), actual.actualAction());
            frame.changeBinding("CURRENT_SOURCE");
            assertThrows(ApiException.class, evidence::reviewInstructions);
            assertEquals(9, frame.aiRequests.size());
        }
    }

    @Test
    void persistentApprovalConversationCannotDowngradeTrustedStatusInstructionsToUserFacts() throws Exception {
        try (var frame = new Frame(); var request = frame.queryRequest("none")) {
            var conversation = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApprovalConversation(frame.context, frame.task, frame.server,
                "controlled-fingerprint", SOURCE, REVISION);
            var scope = new DeploymentCommandReviewScope(frame.context, frame.context.interaction(), frame.task,
                "FULL_CONTROL", true, () -> REVISION, () -> conversation);
            var evidence = new DeploymentAgentHealthEvidence(scope, frame.remote, frame.server, frame.task,
                REVISION, frame.proposal, frame.candidates, frame.journal);
            assertEquals("", evidence.reviewInstructions());
            var review = (DeploymentCommandReview) evidence.context().interaction();
            var refusal = assertThrows(ApiException.class, () -> review.approveCommand(frame.queryIntent(request), request, frame.task));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", refusal.problem().eventCode());
            assertTrue(refusal.getCause().getMessage().contains("SYSTEM delivery"));
            assertEquals(0, frame.aiRequests.size());
            assertEquals(0, frame.reviewFacts.size());
            assertEquals(0, frame.hostAuthorizations);
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.runtimeInspections);
            assertEquals(0, frame.runtimeObservations);
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @Test
    void healthContractRequiresTheCurrentCanonicalVerificationBeforeInstructionsAreExposed() throws Exception {
        try (var frame = new Frame(); var request = frame.healthRequest("none"); var changed = frame.healthRequest("payload")) {
            var evidence = frame.evidence();
            assertEquals("", evidence.reviewInstructions());
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            assertEquals("", evidence.reviewInstructions());
            var intent = frame.healthIntent(request, "none");
            assertFalse(evidence.facts(intent, request, frame.task).isEmpty());
            assertEquals("", evidence.reviewInstructions());
            evidence.verify(intent, request, frame.task);
            assertEquals(DeploymentAgentHealthReviewContract.text(), evidence.reviewInstructions());
            assertThrows(ApiException.class, () -> evidence.verify(intent, changed, frame.task));
            assertThrows(ApiException.class, evidence::reviewInstructions);
            assertEquals(2, frame.aiRequests.size());
            assertEquals(2, frame.hostAuthorizations);
            assertEquals(0, frame.healthDispatches);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"helper-probe", "container-inline"})
    void uncoveredRequestsCannotInheritTheVerifiedPendingHealthContractOrGrant(String kind) throws Exception {
        try (var frame = new Frame(); var healthRequest = frame.healthRequest("none");
             var otherRequest = new CommandRequest(kind.equals("helper-probe")
                 ? DeploymentManagedHelperBundle.verifiedCommand("'" + DeploymentManagedHelperBundle.PATH + "' 'probe'")
                 : "printf 'OBSERVED=1\\n'", new byte[0], Duration.ofSeconds(20), 4096)) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            var review = (DeploymentCommandReview) evidence.context().interaction();
            var grant = review.approveCommand(frame.healthIntent(healthRequest, "none"), healthRequest, frame.task);
            assertEquals(TaskResult.SUCCEEDED, grant.outcome());
            assertEquals(DeploymentAgentHealthReviewContract.text(), evidence.reviewInstructions());
            var otherIntent = RemoteActions.command(frame.server, ARTIFACT, otherRequest).withSemantics(new ActionSemantics(
                kind.equals("helper-probe") ? "deployment.helper.probe.v1" : "deployment.publication.observe.v1",
                Set.of(new ActionTarget(frame.server.id(), "deployment-release", frame.part("backend").unit().releaseRoot()))));
            evidence.verify(otherIntent, otherRequest, frame.task);
            assertEquals("", evidence.reviewInstructions());
            evidence.verify(frame.healthIntent(healthRequest, "none"), healthRequest, frame.task);
            assertEquals(DeploymentAgentHealthReviewContract.text(), evidence.reviewInstructions());
            assertTrue(evidence.facts(otherIntent, otherRequest, frame.task).isEmpty());
            assertEquals("", evidence.reviewInstructions());
            evidence.verify(otherIntent, otherRequest, frame.task);
            assertEquals("", evidence.reviewInstructions());
            assertThrows(ApiException.class, () -> evidence.execute(otherRequest, grant.value().orElseThrow()));
            assertThrows(ApiException.class, evidence::reviewInstructions);
            assertEquals(3, frame.aiRequests.size());
            assertEquals(3, frame.hostAuthorizations);
            assertEquals(2, frame.statusQueries);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.containerObservations);
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"outer-unsettled", "outer-settled"})
    void statusPreExecutionClassifiesTheOriginalLedgerWithoutInventingItsPendingReceipt(String ledgerState) throws Exception {
        try (var frame = new Frame(); var request = frame.queryRequest("none")) {
            if (ledgerState.equals("outer-settled"))
                frame.journal.event(frame.task, "DELIVERY_RESULT", "f".repeat(64), "controlled original outer result");
            var events = frame.journal.events(frame.task);
            var evidence = frame.evidence();
            frame.onStatusReview = facts -> {
                assertEquals(0, frame.statusQueries);
                assertEquals(0, frame.hostAuthorizations);
                assertEquals("NO_RESULT_FOR_THIS_PENDING_REQUEST", facts.at("/currentCommandEvidence/receiptState").asText());
            };
            frame.onStatusHost = () -> {
                assertEquals(1, frame.aiRequests.size());
                assertEquals(0, frame.statusQueries);
            };
            var intent = frame.queryIntent(request);
            var review = (DeploymentCommandReview) evidence.context().interaction();
            var grant = review.approveCommand(intent, request, frame.task);
            assertEquals(TaskResult.SUCCEEDED, grant.outcome());
            var current = frame.reviewFacts.getLast().path("currentCommandEvidence");
            assertEquals("PRE_EXECUTION_STATUS_QUERY", current.path("reviewPhase").asText());
            assertEquals("UNKNOWN", current.path("originalTaskState").asText());
            assertEquals("UNKNOWN", current.at("/originalExecutionState/outcome").asText());
            assertTrue(current.path("receiptStateMeaning").asText().contains("has not been sent"));
            assertTrue(current.path("receiptStateMeaning").asText().contains("not a claim that an executed request lost its receipt"));
            assertEquals(frame.release("backend"), current.at("/originalExpectedBinding/expectedReleaseSha256").asText());
            assertEquals("PREDICATES_TO_CHECK_WHEN_THE_APPROVED_REQUEST_EXECUTES", current.at("/executionTimeGuards/phase").asText());
            assertEquals("AFTER_ACTUAL_SEND_ONLY", current.at("/currentResultAcceptance/phase").asText());
            assertFalse(current.has("result"));
            assertFalse(current.has("parsedStatus"));
            assertFalse(current.has("currentGraphQueries"));
            var ledger = current.path("originalLedgerClassification");
            boolean settled = ledgerState.equals("outer-settled");
            assertEquals(settled, ledger.path("allOriginalEffectsSettled").asBoolean());
            assertTrue(ledger.path("nonDeliveryEffectResultsSettled").asBoolean());
            assertEquals(!settled, ledger.has("unsettledOuterDelivery"));
            var sequence = events.stream().filter(event -> Set.of("AI_PUBLICATION", "AI_PUBLICATION_VERIFIED", "AI_PUBLICATION_FINISHED")
                .contains(event.type())).map(DeploymentAgentActionLog.Event::id).toList();
            assertEquals(sequence, JSON.convertValue(ledger.path("publicationVerifiedFinishedEventIds"), JSON.getTypeFactory()
                .constructCollectionType(List.class, Long.class)));
            if (!settled) {
                var outer = events.stream().filter(event -> event.type().equals("DELIVERY_EXECUTING")).findFirst().orElseThrow();
                assertEquals(outer.id(), ledger.at("/unsettledOuterDelivery/eventId").asLong());
                assertEquals(outer.action(), ledger.at("/unsettledOuterDelivery/action").asText());
                assertEquals(outer.detail(), ledger.at("/unsettledOuterDelivery/detail").asText());
            }
            assertEquals(TaskResult.SUCCEEDED, evidence.execute(request, grant.value().orElseThrow()).outcome());
            assertEquals(1, frame.statusQueries);
            assertEquals(1, frame.events("HEALTH_STATUS_QUERY_RESULT"));
            assertEquals("", evidence.reviewInstructions());
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
            assertEquals(events, frame.journal.events(frame.task).subList(0, events.size()));
            assertThrows(ApiException.class, () -> review.approveCommand(intent, request, frame.task));
            assertEquals(1, frame.aiRequests.size());
            assertEquals(1, frame.statusQueries);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DENY", "UNKNOWN"})
    void hostRefusalCannotSendOrCreateAStatusObservationAfterIndependentApproval(String outcome) throws Exception {
        try (var frame = new Frame(); var request = frame.queryRequest("none")) {
            frame.hostOutcome = outcome.equals("DENY") ? TaskResult.FAILED : TaskResult.UNKNOWN;
            var evidence = frame.evidence();
            assertEquals(TaskResult.UNKNOWN, frame.observe(evidence).outcome());
            assertEquals(1, frame.aiRequests.size());
            assertEquals(1, frame.hostAuthorizations);
            assertEquals(1, frame.events("HEALTH_STATUS_QUERY_APPROVED"));
            assertEquals(0, frame.events("HEALTH_STATUS_QUERY_RESULT"));
            assertEquals(0, frame.statusQueries);
            assertTrue(frame.statusResults.isEmpty());
            assertEquals(0, frame.healthDispatches);
            var review = (DeploymentCommandReview) evidence.context().interaction();
            assertThrows(ApiException.class, () -> review.approveCommand(frame.queryIntent(request), request, frame.task));
            assertEquals(1, frame.aiRequests.size());
            assertEquals(0, frame.statusQueries);
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-current", "active"})
    void nonMatchingRawStatusResultsAreRetainedWithoutBecomingVerifiedGraphObservations(String failure) throws Exception {
        try (var frame = new Frame()) {
            frame.statusFailure = failure;
            var evidence = frame.evidence();
            assertEquals(TaskResult.UNKNOWN, frame.observe(evidence).outcome());
            assertEquals(1, frame.statusQueries);
            assertEquals(1, frame.statusResults.size());
            var raw = frame.statusResults.getFirst();
            assertEquals(Optional.of(0), raw.exitCode());
            assertFalse(raw.truncated());
            assertTrue(raw.stdout().contains(failure.equals("wrong-current") ? "CURRENT=" + SOURCE + "\n" : "BUILD_ACTIVE=yes\n"));
            var result = frame.journal.events(frame.task).getLast();
            assertEquals("HEALTH_STATUS_QUERY_RESULT", result.type());
            var rawFacts = Map.of("outcome", "SUCCEEDED", "exitCode", 0, "stdout", raw.stdout(), "stderr", raw.stderr(), "truncated", false);
            assertTrue(result.detail().contains("resultSha256=" + DeploymentAgentInputs.hash(DeploymentAiJson.facts(rawFacts, 1048576))));
            assertThrows(ApiException.class, () -> frame.health(evidence, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
            assertEquals(1, frame.aiRequests.size());
            assertEquals(0, frame.healthReviews());
            assertEquals(0, frame.healthDispatches);
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @Test
    void anUnreleasedOriginalModelCheckpointBlocksObservationBeforeAnyReview() throws Exception {
        try (var frame = new Frame()) {
            frame.checkpointActive = true;
            assertThrows(ApiException.class, frame::evidence);
            assertEquals(0, frame.aiRequests.size());
            assertEquals(0, frame.hostAuthorizations);
            assertEquals(0, frame.statusQueries);
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "candidate", "target", "helper-guard"})
    void originalStatusIdentityTargetAndGuardCannotChangeBeforeReviewOrBeforeSend(String change) throws Exception {
        for (String boundary : List.of("APP", "SEND")) {
            try (var frame = new Frame(); var original = frame.queryRequest("none");
                 var changed = frame.queryRequest(change.equals("helper-guard") || change.equals("target") ? "none" : change);
                 var guarded = new CommandRequest(change.equals("helper-guard")
                     ? changed.script().replace(DeploymentManagedHelperBundle.EXPECTED_SHA256, "f".repeat(64)) : changed.script(),
                     new byte[0], changed.timeout(), changed.outputLimitBytes())) {
                var evidence = frame.evidence();
                var changedIntent = frame.queryIntent(guarded);
                if (change.equals("target")) changedIntent = changedIntent.withSemantics(new ActionSemantics(
                    "deployment.publication.original-status.v1", Set.of(new ActionTarget(frame.server.id(), "deployment-release", "/foreign"))));
                var rejectedIntent = changedIntent;
                var review = (DeploymentCommandReview) evidence.context().interaction();
                if (boundary.equals("APP")) {
                    assertThrows(ApiException.class, () -> review.approveCommand(rejectedIntent, guarded, frame.task));
                    assertEquals(0, frame.aiRequests.size());
                    assertEquals(0, frame.hostAuthorizations);
                } else {
                    var grant = review.approveCommand(frame.queryIntent(original), original, frame.task);
                    assertEquals(TaskResult.SUCCEEDED, grant.outcome());
                    assertEquals(DeploymentAgentObservationReviewContract.text(), evidence.reviewInstructions());
                    assertThrows(ApiException.class, () -> evidence.verify(rejectedIntent, guarded, frame.task));
                    assertThrows(ApiException.class, () -> evidence.execute(guarded, grant.value().orElseThrow()));
                    assertEquals(1, frame.aiRequests.size());
                    assertEquals(1, frame.hostAuthorizations);
                }
                assertEquals(0, frame.statusQueries);
                assertEquals(0, frame.events("HEALTH_STATUS_QUERY_RESULT"));
                assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
            }
        }
    }

    @Test
    void originalGraphQueriesSupplyCompleteCurrentFactsWithoutChangingHealthRequestsOrSettlingDelivery() throws Exception {
        try (var frame = new Frame()) {
            var originalEvents = frame.journal.events(frame.task);
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.verify(evidence).outcome());
            assertEquals(2, frame.statusQueries);
            assertEquals(3, frame.healthDispatches);
            assertEquals(2, frame.runtimeInspections);
            assertEquals(2, frame.runtimeObservations);
            assertEquals(9, frame.reviewFacts.size());
            assertEquals("agent-current-original-status-evidence.v1", frame.reviewFacts.getFirst().at("/currentCommandEvidence/schema").asText());
            assertFalse(frame.reviewFacts.getFirst().path("currentCommandEvidence").has("currentGraphQueries"));
            var first = frame.reviewFacts.get(2);
            var current = first.path("currentCommandEvidence");
            assertEquals("agent-current-health-evidence.v1", current.path("schema").asText());
            assertEquals(frame.task.id().toString(), current.path("currentTask").asText());
            assertEquals("backend", current.at("/unitBinding/component").asText());
            assertEquals(frame.part("backend").unit().systemdUnit(), current.at("/unitBinding/systemdUnit").asText());
            assertEquals(frame.part("backend").unit().releaseRoot(), current.at("/unitBinding/releaseRoot").asText());
            assertEquals("HTTP", current.at("/typedHealthPolicy/kind").asText());
            assertEquals("http://127.0.0.1:18101/healthz", current.at("/typedHealthPolicy/endpoint").asText());
            assertEquals(DeploymentWorkloadArguments.healthPayload(frame.part("backend").reviewedRuntime().healthCheck()),
                current.at("/argumentBinding/healthPayloadArgument").asText());
            assertEquals(2, current.path("currentGraphQueries").size());
            for (var query : current.path("currentGraphQueries")) {
                String component = query.path("component").asText();
                var selected = frame.part(component);
                assertEquals("SUCCEEDED", query.at("/result/outcome").asText());
                assertEquals(frame.status(component), query.at("/result/stdout").asText());
                assertFalse(query.at("/result/truncated").asBoolean());
                assertTrue(frame.statusActions.contains(query.at("/request/exactAction").asText()));
                assertTrue(query.at("/request/exactAction").asText().contains(selected.unit().id()));
                assertTrue(query.at("/request/exactAction").asText().contains(selected.unit().ownershipManifestSha256()));
                assertEquals(frame.release(component), query.at("/parsedStatus/current").asText());
            }
            assertNotEquals(frame.release("backend"), frame.release("frontend"));
            assertNotEquals(frame.candidates.get("backend").artifactSha256(), frame.candidates.get("frontend").artifactSha256());
            assertNotEquals(frame.part("backend").unit().ownershipManifestSha256(), frame.part("frontend").unit().ownershipManifestSha256());
            var componentHealth = frame.reviewFacts.stream().filter(value -> value.path("operation").asText()
                .equals("deployment.publication.health.v1")).toList();
            assertEquals("frontend", componentHealth.get(1).at("/currentCommandEvidence/unitBinding/component").asText());
            assertEquals(frame.release("frontend"), componentHealth.get(1).at("/currentCommandEvidence/unitBinding/releaseSha256").asText());
            assertEquals("http://127.0.0.1:18100/healthz", componentHealth.get(1).at("/currentCommandEvidence/typedHealthPolicy/endpoint").asText());
            assertEquals(List.of("WHOLE_APPLICATION"), JSON.convertValue(frame.reviewFacts.getLast()
                .at("/currentCommandEvidence/healthPolicySources"), List.class));
            assertNotEquals(current.at("/unitBinding/sealedArtifactSha256"), current.at("/unitBinding/releaseSha256"));
            assertTrue(current.path("observationLimits").asText().contains("does not prove listener ownership"));
            assertTrue(current.at("/priorDiagnosticLocator/priorExactActionBinding").asText().startsWith("UNKNOWN"));
            assertEquals(originalEvents, frame.journal.events(frame.task).subList(0, originalEvents.size()));
            assertFalse(DeploymentAgentActionLog.effectsSettled(frame.journal.events(frame.task)));
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
            for (var actual : frame.healthActions)
                assertEquals(actual.expectedAction(), actual.actualAction());
            assertEquals(3, frame.events("HEALTH_REVIEW_APPROVED"));
            var runtimeReviews = frame.reviewFacts.stream().filter(value -> Set.of("deployment.publication.inspect.v1",
                "deployment.publication.observe.v1").contains(value.path("operation").asText())).toList();
            assertEquals(4, runtimeReviews.size());
            for (var review : runtimeReviews) {
                var runtimeEvidence = review.path("currentCommandEvidence");
                String component = runtimeEvidence.at("/unitBinding/component").asText();
                var unit = frame.part(component).unit();
                assertEquals("agent-current-runtime-evidence.v1", runtimeEvidence.path("schema").asText());
                assertEquals(unit.id(), runtimeEvidence.at("/argumentBinding/applicationIdArgument").asText());
                assertEquals(unit.ownershipManifestSha256(), runtimeEvidence.at("/argumentBinding/ownershipManifestArgument").asText());
                assertEquals(unit.releaseRoot(), runtimeEvidence.at("/argumentBinding/resourceTargetId").asText());
                assertEquals(unit.systemdUnit(), runtimeEvidence.at("/runtimeBinding/systemdUnit").asText());
                assertEquals(frame.release(component), runtimeEvidence.at("/runtimeBinding/currentReleaseSha256").asText());
                assertEquals(2, runtimeEvidence.at("/argumentBinding/argumentCount").asInt());
                assertTrue(runtimeEvidence.at("/argumentBinding/ownershipManifestMeaning").asText().contains("not a deployment id or resource id"));
                assertTrue(runtimeEvidence.path("enforcedRuntimeBoundary").asText().contains("execution-time guards"));
                assertFalse(runtimeEvidence.has("typedHealthPolicy"));
                assertEquals(2, runtimeEvidence.path("currentGraphQueries").size());
                for (var observed : runtimeEvidence.path("currentGraphQueries"))
                    assertEquals(frame.release(observed.path("component").asText()), observed.at("/parsedStatus/current").asText());
            }
            for (var actual : frame.runtimeActions)
                assertEquals(actual.expectedAction(), actual.actualAction());
            assertEquals(4, frame.events("RUNTIME_REVIEW_APPROVED"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void canonicalHealthArgumentsAreCompleteOrderedAndMatchTheActualCommandBytes() throws Exception {
        try (var frame = new Frame(); var request = frame.healthRequest("none")) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            var intent = frame.healthIntent(request, "none");
            var review = (DeploymentCommandReview) evidence.context().interaction();
            var approved = review.approveCommand(intent, request, frame.task);
            assertEquals(TaskResult.SUCCEEDED, approved.outcome());
            var current = frame.reviewFacts.getLast().path("currentCommandEvidence");
            var binding = current.path("argumentBinding");
            var unit = frame.part("backend").unit();
            String payload = DeploymentWorkloadArguments.healthPayload(frame.part("backend").reviewedRuntime().healthCheck());
            assertEquals("application-health", binding.path("verb").asText());
            assertEquals(3, binding.path("argumentCount").asInt());
            var arguments = binding.path("literalArguments");
            assertEquals(3, arguments.size());
            var roles = List.of("applicationId", "ownershipManifestSha256", "healthPayload");
            var values = List.of(unit.id(), unit.ownershipManifestSha256(), payload);
            for (int index = 0; index < 3; index++) {
                assertEquals(index + 1, arguments.get(index).path("position").asInt());
                assertEquals(roles.get(index), arguments.get(index).path("role").asText());
                assertEquals(values.get(index), arguments.get(index).path("value").asText());
                assertFalse(arguments.get(index).path("meaning").asText().isBlank());
                assertNotEquals(unit.releaseRoot(), arguments.get(index).path("value").asText());
            }
            assertEquals("deployment-release", binding.path("resourceTargetKind").asText());
            assertEquals(unit.releaseRoot(), binding.path("resourceTargetId").asText());
            var invocationValues = new ArrayList<>(List.of(DeploymentManagedHelperBundle.PATH, "application-health"));
            invocationValues.addAll(values);
            String invocation = invocationValues.stream().map(DeploymentBuildCommandChannel::shellQuote)
                .collect(java.util.stream.Collectors.joining(" "));
            assertEquals(invocation, current.at("/commandBinding/unwrappedCanonicalInvocation").asText());
            assertEquals(invocation, DeploymentManagedHelperBundle.verifiedBody(request.script()));
            assertEquals(request.script(), current.at("/commandBinding/exactCommand").asText());
            assertEquals(request.script(), current.at("/commandBinding/expectedCanonicalCommand").asText());
            assertArrayEquals(request.script().getBytes(StandardCharsets.UTF_8),
                current.at("/commandBinding/exactCommand").asText().getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(request.script().getBytes(StandardCharsets.UTF_8),
                DeploymentManagedHelperBundle.verifiedCommand(invocation).getBytes(StandardCharsets.UTF_8));
            assertTrue(current.at("/commandBinding/matchesCanonicalCommand").asBoolean());
            Map<String, Object> hashedFacts = JSON.convertValue(current, Map.class);
            String digest = (String) hashedFacts.remove("evidenceSha256");
            hashedFacts.remove("reviewRequestSha256");
            assertEquals(digest, DeploymentAgentInputs.hash(DeploymentAiJson.facts(hashedFacts, 1048576)));
            var changedBinding = new LinkedHashMap<>((Map<String, Object>) hashedFacts.get("argumentBinding"));
            var literalArguments = (List<?>) changedBinding.get("literalArguments");
            changedBinding.put("literalArguments", literalArguments.subList(0, 2));
            hashedFacts.put("argumentBinding", changedBinding);
            assertNotEquals(digest, DeploymentAgentInputs.hash(DeploymentAiJson.facts(hashedFacts, 1048576)));
            assertEquals(TaskResult.SUCCEEDED, evidence.execute(request, approved.value().orElseThrow()).outcome());
            assertEquals(2, frame.statusQueries);
            assertEquals(1, frame.healthDispatches);
            assertEquals(intent.exactAction(), frame.healthActions.getFirst().actualAction());
            assertEquals("", evidence.reviewInstructions());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HTTP_200", "HTTP_204", "TCP", "PROCESS", "COMMAND", "UDP_REPLY", "UDP_CUSTOM"})
    @SuppressWarnings("unchecked")
    void canonicalHealthPayloadViewsPreserveEveryOrderedFieldAndActualArgumentByte(String variant) throws Exception {
        var argv = List.of("  two words  ", "single'quote", "double\"quote", "汉字🙂", "");
        var command = new DeploymentCommand("检查/verify.sh", argv);
        DeploymentHealthCheck check = switch (variant) {
            case "HTTP_200", "HTTP_204" -> new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:18101/healthz"),
                variant.equals("HTTP_200") ? 200 : 204, 120);
            case "TCP" -> new DeploymentHealthCheck.Tcp(18101, 120, 3);
            case "PROCESS" -> new DeploymentHealthCheck.Process(120, 4);
            case "COMMAND" -> new DeploymentHealthCheck.Command(command, "", 120);
            case "UDP_REPLY" -> new DeploymentHealthCheck.Udp(18101, "0a00", "ff01", Optional.empty(), 120);
            case "UDP_CUSTOM" -> new DeploymentHealthCheck.Udp(18101, "", "",
                Optional.of(new DeploymentCommand("probe.sh", List.of("literal probe", "报文"))), 120);
            default -> throw new AssertionError(variant);
        };
        List<String> expected = switch (variant) {
            case "HTTP_200", "HTTP_204" -> List.of("health-v1", "HTTP", "120", "http://127.0.0.1:18101/healthz",
                variant.equals("HTTP_200") ? "200" : "204");
            case "TCP" -> List.of("health-v1", "TCP", "120", "18101", "3");
            case "PROCESS" -> List.of("health-v1", "PROCESS", "120", "4");
            case "COMMAND" -> {
                var values = new ArrayList<>(List.of("health-v1", "COMMAND", "120", "检查/verify.sh", "5"));
                values.addAll(argv);
                values.add("");
                yield List.copyOf(values);
            }
            case "UDP_REPLY" -> List.of("health-v1", "UDP", "120", "18101", "0a00", "ff01", "0");
            case "UDP_CUSTOM" ->
                List.of("health-v1", "UDP", "120", "18101", "", "", "1", "probe.sh", "2", "literal probe", "报文");
            default -> throw new AssertionError(variant);
        };
        byte[] expectedBytes = (String.join("\0", expected) + "\0").getBytes(StandardCharsets.UTF_8);
        String payload = Base64.getEncoder().encodeToString(expectedBytes);
        assertEquals(payload, DeploymentWorkloadArguments.healthPayload(check));
        try (var frame = new Frame(check); var request = frame.healthRequest("none")) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            for (var earlier : frame.reviewFacts)
                assertFalse(earlier.path("currentCommandEvidence").has("healthPayloadByteView"));
            var review = (DeploymentCommandReview) evidence.context().interaction();
            var grant = review.approveCommand(frame.healthIntent(request, "none"), request, frame.task);
            assertEquals(TaskResult.SUCCEEDED, grant.outcome());
            var current = frame.reviewFacts.getLast().path("currentCommandEvidence");
            var view = current.path("healthPayloadByteView");
            assertEquals(JSON.valueToTree(expected), view.path("fields"));
            assertEquals(expected.size(), view.path("fieldCount").asInt());
            assertEquals(expectedBytes.length, view.path("decodedByteLength").asInt());
            for (String flag : List.of("terminalNulPresent", "strictUtf8", "reconstructedBytesEqual", "canonicalBase64ReencodingEqual"))
                assertTrue(view.path(flag).asBoolean(), flag);
            assertEquals("CURRENT_CANONICAL_THIRD_LITERAL_ARGUMENT", view.path("source").asText());
            assertEquals(DeploymentAgentInputs.hash(payload), view.path("encodedArgumentTextSha256").asText());
            assertEquals(current.at("/selectedHealthExecutionPath/canonicalHealthPayloadSha256").asText(),
                view.path("encodedArgumentTextSha256").asText());
            String byteSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expectedBytes));
            assertEquals(byteSha, view.path("decodedBytesSha256").asText());
            assertNotEquals(byteSha, view.path("encodedArgumentTextSha256").asText());
            assertTrue(view.path("meaning").asText().contains("does not parse health-kind semantics"));
            assertEquals(payload, current.at("/argumentBinding/healthPayloadArgument").asText());
            assertEquals(payload, current.at("/argumentBinding/literalArguments/2/value").asText());
            assertEquals(request.script(), current.at("/commandBinding/exactCommand").asText());
            assertEquals(check, frame.part("backend").reviewedRuntime().healthCheck());
            if (check instanceof DeploymentHealthCheck.Http http)
                assertEquals(http.expectedStatus(), current.at("/typedHealthPolicy/expectedStatus").asInt());
            if (check instanceof DeploymentHealthCheck.Command) {
                assertEquals("", current.at("/typedHealthPolicy/expectedOutput").asText());
                assertEquals("", view.path("fields").get(expected.size() - 1).asText());
                assertEquals(JSON.valueToTree(argv), current.at("/typedHealthPolicy/command/arguments"));
            }
            if (variant.equals("UDP_CUSTOM")) {
                assertEquals("", view.path("fields").get(4).asText());
                assertEquals("", view.path("fields").get(5).asText());
            }
            Map<String, Object> hashed = JSON.convertValue(current, Map.class);
            String digest = (String) hashed.remove("evidenceSha256");
            hashed.remove("reviewRequestSha256");
            assertEquals(digest, DeploymentAgentInputs.hash(DeploymentAiJson.facts(hashed, 1048576)));
            hashed.remove("healthPayloadByteView");
            assertNotEquals(digest, DeploymentAgentInputs.hash(DeploymentAiJson.facts(hashed, 1048576)));
            assertEquals(TaskResult.SUCCEEDED, evidence.execute(request, grant.value().orElseThrow()).outcome());
            assertEquals(1, frame.healthDispatches);
            assertEquals(3, frame.aiRequests.size());
            assertEquals(frame.healthIntent(request, "none").exactAction(), frame.healthActions.getFirst().actualAction());
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "missing-terminal", "invalid-utf8", "invalid-base64", "missing-padding", "noncanonical-pad-bits", "oversize-text", "oversize-bytes"})
    void strictHealthPayloadFramingRejectsMalformedOrNonCanonicalBytes(String failure) {
        String payload = switch (failure) {
            case "empty" -> "";
            case "missing-terminal" -> Base64.getEncoder().encodeToString("health-v1".getBytes(StandardCharsets.UTF_8));
            case "invalid-utf8" -> Base64.getEncoder().encodeToString(new byte[]{(byte) 0xc3, 0x28, 0});
            case "invalid-base64" -> "%%=";
            case "missing-padding" -> "YQA";
            case "noncanonical-pad-bits" -> "YQB=";
            case "oversize-text" -> "A".repeat(87388);
            case "oversize-bytes" -> Base64.getEncoder().encodeToString(new byte[65537]);
            default -> throw new AssertionError(failure);
        };
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHealthEvidence.healthPayloadByteView(payload));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-status", "extra-field", "altered-status", "missing-terminal", "invalid-utf8", "noncanonical-base64"})
    void alteredHealthPayloadCannotReachAppOrReuseAnApprovedGrantWithTheOriginalTypedPolicy(String change) throws Exception {
        for (String boundary : List.of("APP", "SEND")) {
            try (var frame = new Frame(); var original = frame.healthRequest("none")) {
                var fields = new ArrayList<>(List.of("health-v1", "HTTP", "120", "http://127.0.0.1:18101/healthz", "200"));
                if (change.equals("missing-status")) fields.removeLast();
                if (change.equals("extra-field")) fields.add("foreign-extra");
                if (change.equals("altered-status")) fields.set(4, "204");
                byte[] bytes = (String.join("\0", fields) + (change.equals("missing-terminal") ? "" : "\0")).getBytes(StandardCharsets.UTF_8);
                String payload = change.equals("invalid-utf8") ? Base64.getEncoder().encodeToString(new byte[]{(byte) 0xc3, 0x28, 0})
                    : change.equals("noncanonical-base64") ? "YQA" : Base64.getEncoder().encodeToString(bytes);
                if (Set.of("missing-status", "extra-field", "altered-status").contains(change)) {
                    var view = DeploymentAgentHealthEvidence.healthPayloadByteView(payload);
                    assertEquals(fields, view.get("fields")); // Framing reports bytes; canonical selection supplies policy semantics.
                }
                try (var altered = frame.healthRequestWithPayload(payload)) {
                    var check = frame.part("backend").reviewedRuntime().healthCheck();
                    var evidence = frame.evidence();
                    assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
                    var review = (DeploymentCommandReview) evidence.context().interaction();
                    if (boundary.equals("APP")) {
                        assertThrows(ApiException.class, () -> review.approveCommand(frame.healthIntent(altered, "none"), altered, frame.task));
                        assertEquals(2, frame.aiRequests.size());
                        assertEquals(2, frame.hostAuthorizations);
                        assertEquals(0, frame.events("HEALTH_REVIEW_REQUEST"));
                        for (var facts : frame.reviewFacts)
                            assertFalse(facts.path("currentCommandEvidence").has("healthPayloadByteView"));
                    } else {
                        var grant = review.approveCommand(frame.healthIntent(original, "none"), original, frame.task);
                        assertEquals(TaskResult.SUCCEEDED, grant.outcome());
                        assertTrue(frame.reviewFacts.getLast().path("currentCommandEvidence").has("healthPayloadByteView"));
                        assertThrows(ApiException.class, () -> evidence.execute(altered, grant.value().orElseThrow()));
                        assertThrows(ApiException.class, () -> evidence.execute(original, grant.value().orElseThrow()));
                        assertEquals(3, frame.aiRequests.size());
                        assertEquals(3, frame.hostAuthorizations);
                    }
                    assertEquals(check, frame.part("backend").reviewedRuntime().healthCheck());
                    assertEquals(200, ((DeploymentHealthCheck.Http) check).expectedStatus());
                    assertEquals(0, frame.healthDispatches);
                    assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-payload", "unit"})
    void missingOrReplacedHealthArgumentsCannotAcquireEvidenceOrDispatch(String change) throws Exception {
        try (var frame = new Frame(); var request = frame.healthRequest(change)) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            int reviews = frame.reviewFacts.size();
            int authorizations = frame.hostAuthorizations;
            var review = (DeploymentCommandReview) evidence.context().interaction();
            assertThrows(ApiException.class, () -> review.approveCommand(frame.healthIntent(request, "none"), request, frame.task));
            assertEquals(reviews, frame.reviewFacts.size());
            assertEquals(authorizations, frame.hostAuthorizations);
            assertEquals(0, frame.healthDispatches);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void frontendOwnMaintenanceMarkerUsesItsActualReceiptWithoutChangingBackendHealthOrPermissions() throws Exception {
        try (var frame = new Frame()) {
            String frontendMarker = "deployment-" + frame.release("frontend");
            frame.maintenanceMarkers.put("frontend", frontendMarker);
            var originalEvents = frame.journal.events(frame.task);
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.verify(evidence).outcome());
            assertEquals(2, frame.statusQueries);
            assertEquals(3, frame.healthDispatches);
            assertEquals(9, frame.reviewFacts.size());
            var backendReview = frame.reviewFacts.get(2);
            var current = backendReview.path("currentCommandEvidence");
            var backend = frame.part("backend").unit();
            assertEquals(backend.id(), current.at("/argumentBinding/applicationIdArgument").asText());
            assertEquals(backend.ownershipManifestSha256(), current.at("/argumentBinding/ownershipManifestArgument").asText());
            assertEquals(backend.releaseRoot(), current.at("/unitBinding/releaseRoot").asText());
            assertEquals("http://127.0.0.1:18101/healthz", current.at("/typedHealthPolicy/endpoint").asText());
            assertEquals(DeploymentWorkloadArguments.healthPayload(frame.part("backend").reviewedRuntime().healthCheck()),
                current.at("/argumentBinding/healthPayloadArgument").asText());
            assertEquals(backend.id(), current.at("/healthMaintenanceScope/applicationId").asText());
            for (var query : current.path("currentGraphQueries")) {
                String component = query.path("component").asText();
                String actualMarker = component.equals("frontend") ? frontendMarker : "none";
                assertEquals(frame.status(component), query.at("/result/stdout").asText());
                assertTrue(query.at("/result/stdout").asText().contains("\nMAINTENANCE=" + actualMarker + "\n"));
                assertEquals(actualMarker, query.path("maintenanceMarker").asText());
                assertEquals("deployment-" + frame.release(component), query.path("expectedMarker").asText());
                assertEquals(component.equals("frontend"), query.path("matchesOwnExpectedRelease").asBoolean());
                assertEquals(component.equals("frontend"), query.path("markerPresence").asBoolean());
                assertEquals(component.equals("frontend") ? "OWN_EXPECTED_RELEASE" : "ABSENT", query.path("markerState").asText());
                assertTrue(query.path("markerAcceptedByStatusProtocol").asBoolean());
                assertEquals(component.equals("frontend"), query.at("/parsedStatus/maintenance").asBoolean());
                assertEquals("$base_root/application-locks/" + frame.part(component).unit().id() + "/maintenance",
                    query.path("markerPathExpression").asText());
                assertTrue(query.path("maintenanceMeaning").asText().contains("not systemd state"));
                assertTrue(query.path("maintenanceMeaning").asText().contains("does not establish that maintenance ended"));
            }
            assertFalse(current.has("helperSourceEvidence"));
            assertEquals(DeploymentAgentHealthReviewContract.REVISION, current.at("/healthReviewContract/revision").asText());
            assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, current.at("/healthReviewContract/helperSha256").asText());
            assertEquals(DeploymentAgentInputs.hash(DeploymentAgentHealthReviewContract.text()), current.at("/healthReviewContract/textSha256").asText());
            assertTrue(current.path("enforcedHealthBoundary").asText().contains("fixed SYSTEM health contract"));
            assertTrue(current.path("enforcedHealthBoundary").asText().contains("does not bind that particular response socket"));
            var executionPath = current.path("selectedHealthExecutionPath");
            assertEquals("backend", executionPath.path("component").asText());
            assertEquals(backend.id(), executionPath.path("applicationId").asText());
            assertEquals(DeploymentAgentInputs.hash(backendReview.path("exactAction").asText()), executionPath.path("exactActionSha256").asText());
            assertEquals(DeploymentAgentInputs.hash(current.at("/argumentBinding/healthPayloadArgument").asText()),
                executionPath.path("canonicalHealthPayloadSha256").asText());
            assertTrue(executionPath.path("payloadSha256Meaning").asText().contains("third argument text"));
            assertTrue(executionPath.path("payloadSha256Meaning").asText().contains("not decoded payload bytes"));
            assertEquals("HTTP", executionPath.path("kind").asText());
            assertFalse(executionPath.at("/programVerification/reachableAfterParser").asBoolean());
            assertFalse(executionPath.at("/customProbe/reachableAfterParser").asBoolean());
            assertTrue(executionPath.path("parserGuard").asText().contains("every non-COMMAND kind rejects before health_loaded"));
            assertTrue(executionPath.path("limits").asText().contains("not a complete helper dependency closure"));
            var observed = java.util.stream.StreamSupport.stream(current.path("currentGraphQueries").spliterator(), false)
                .filter(value -> value.path("component").asText().equals("backend")).findFirst().orElseThrow();
            assertEquals(observed.path("event").asLong(), executionPath.at("/earlierObservedRuntime/statusEvent").asLong());
            assertEquals(observed.path("resultSha256").asText(), executionPath.at("/earlierObservedRuntime/resultSha256").asText());
            assertEquals("native", executionPath.at("/earlierObservedRuntime/backend").asText());
            assertEquals("DAEMON", executionPath.at("/earlierObservedRuntime/mode").asText());
            assertTrue(executionPath.at("/earlierObservedRuntime/meaning").asText().startsWith("Earlier complete original-status receipt"));
            Map<String, Object> hashedFacts = JSON.convertValue(current, Map.class);
            String evidenceDigest = (String) hashedFacts.remove("evidenceSha256");
            hashedFacts.remove("reviewRequestSha256");
            assertEquals(evidenceDigest, DeploymentAgentInputs.hash(DeploymentAiJson.facts(hashedFacts, 1048576)));
            Object pathEvidence = hashedFacts.remove("selectedHealthExecutionPath");
            assertNotEquals(evidenceDigest, DeploymentAgentInputs.hash(DeploymentAiJson.facts(hashedFacts, 1048576)));
            hashedFacts.put("selectedHealthExecutionPath", pathEvidence);
            hashedFacts.remove("healthReviewContract");
            assertNotEquals(evidenceDigest, DeploymentAgentInputs.hash(DeploymentAiJson.facts(hashedFacts, 1048576)));
            for (var review : frame.reviewFacts) {
                String operation = review.path("operation").asText();
                assertFalse(review.path("currentCommandEvidence").has("helperSourceEvidence"));
                assertEquals(operation.equals("deployment.publication.health.v1"), review.path("currentCommandEvidence").has("healthReviewContract"));
                assertEquals(operation.equals("deployment.publication.health.v1"), review.path("currentCommandEvidence").has("selectedHealthExecutionPath"));
            }
            for (var actual : frame.healthActions) assertEquals(actual.expectedAction(), actual.actualAction());
            for (var actual : frame.runtimeActions) assertEquals(actual.expectedAction(), actual.actualAction());
            assertEquals(originalEvents, frame.journal.events(frame.task).subList(0, originalEvents.size()));
            assertFalse(DeploymentAgentActionLog.effectsSettled(frame.journal.events(frame.task)));
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @Test
    void anotherComponentsMaintenanceTokenCannotBeTreatedAsTheObservedUnitsOwnMarker() throws Exception {
        try (var frame = new Frame()) {
            frame.maintenanceMarkers.put("frontend", "deployment-" + frame.release("backend"));
            var evidence = frame.evidence();
            assertEquals(TaskResult.UNKNOWN, frame.observe(evidence).outcome());
            assertEquals(2, frame.statusQueries);
            assertThrows(ApiException.class,
                () -> frame.health(evidence, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
            assertEquals(0, frame.healthReviews());
            assertEquals(0, frame.healthDispatches);
            assertEquals(1, frame.events("HEALTH_STATUS_QUERY_RESULT"));
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"kind", "payload"})
    void changedKindOrPayloadCannotReuseTheApprovedHealthPath(String change) throws Exception {
        try (var frame = new Frame(); var original = frame.healthRequest("none"); var changed = frame.healthRequest(change)) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            var intent = frame.healthIntent(original, "none");
            var review = (DeploymentCommandReview) evidence.context().interaction();
            var approval = review.approveCommand(intent, original, frame.task);
            assertEquals(TaskResult.SUCCEEDED, approval.outcome());
            int reviews = frame.reviewFacts.size();
            assertThrows(ApiException.class, () -> evidence.execute(changed, approval.value().orElseThrow()));
            assertThrows(ApiException.class, () -> evidence.execute(original, approval.value().orElseThrow()));
            assertEquals(reviews, frame.reviewFacts.size());
            assertEquals(0, frame.healthDispatches);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "foreign", "truncated", "active", "wrong-current", "duplicate", "foreign-snapshot"})
    void unconfirmedCurrentQueryCannotReachHealthApprovalOrExecution(String failure) throws Exception {
        try (var frame = new Frame()) {
            frame.statusFailure = failure;
            var evidence = frame.evidence();
            frame.observe(evidence);
            assertThrows(ApiException.class, () -> frame.health(evidence, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.healthReviews());
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @Test
    void queryDenialAndHealthDenialRemainFinalAndRetainTheirOriginalReason() throws Exception {
        for (String operation : List.of("deployment.publication.original-status.v1", "deployment.publication.health.v1")) {
            try (var frame = new Frame()) {
                frame.deniedOperation = operation;
                var evidence = frame.evidence();
                if (operation.endsWith("original-status.v1")) {
                    assertEquals(TaskResult.UNKNOWN, frame.observe(evidence).outcome());
                    assertEquals(1, frame.events("HEALTH_STATUS_QUERY_DENIED"));
                } else {
                    assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
                    var refusal = assertThrows(ApiException.class, () -> frame.health(evidence, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
                    assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", refusal.problem().eventCode());
                    assertEquals(DENIAL, refusal.problem().context().get("reviewReason"));
                    assertEquals(1, frame.events("HEALTH_REVIEW_DENIED"));
                    assertTrue(frame.journal.events(frame.task).getLast().detail().contains("reasonSha256=" + DeploymentAgentInputs.hash(DENIAL)));
                }
                int reviews = frame.reviewFacts.size();
                frame.deniedOperation = "none";
                assertThrows(ApiException.class, () -> frame.health(evidence, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
                assertEquals(reviews, frame.reviewFacts.size());
                assertEquals(0, frame.healthDispatches);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "CURRENT_SOURCE", "SEALED_ARTIFACT", "SEALED_TOOLS", "profile", "route"})
    void originalBindingChangeDuringAppReviewCannotReachHostAuthorizationOrHealth(String change) throws Exception {
        try (var frame = new Frame()) {
            var evidence = frame.evidence();
            frame.observe(evidence);
            int grants = frame.hostAuthorizations;
            frame.onHealthReview = ignored -> frame.changeBinding(change);
            assertThrows(ApiException.class, () -> frame.health(evidence, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
            assertEquals(grants, frame.hostAuthorizations);
            assertEquals(0, frame.healthDispatches);
        }
    }

    @Test
    void profileChangeDuringHostAuthorizationStopsBeforeActualSend() throws Exception {
        try (var frame = new Frame()) {
            var evidence = frame.evidence();
            frame.observe(evidence);
            frame.onHealthHost = () -> frame.changeBinding("profile");
            assertThrows(ApiException.class, () -> frame.health(evidence, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
            assertEquals(0, frame.healthDispatches);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANDIDATE", "CURRENT_SOURCE", "SEALED_ARTIFACT", "SEALED_TOOLS"})
    void originalSelectedBindingIsCheckedAgainImmediatelyBeforeActualSend(String change) throws Exception {
        try (var frame = new Frame(); var request = frame.healthRequest("none")) {
            var evidence = frame.evidence();
            frame.observe(evidence);
            var intent = frame.healthIntent(request, "none");
            var review = (DeploymentCommandReview) evidence.context().interaction();
            var grant = review.approveCommand(intent, request, frame.task);
            assertEquals(TaskResult.SUCCEEDED, grant.outcome());
            frame.changeBinding(change);
            assertThrows(ApiException.class, () -> evidence.execute(request, grant.value().orElseThrow()));
            assertEquals(0, frame.healthDispatches);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"manifest", "payload", "server", "target", "revision", "timeout", "stdin", "output"})
    void foreignOrAlteredHealthRequestGetsNoSupplementOrAppReview(String change) throws Exception {
        try (var frame = new Frame(); var request = frame.healthRequest(change)) {
            var evidence = frame.evidence();
            frame.observe(evidence);
            int reviews = frame.reviewFacts.size();
            assertThrows(ApiException.class, () -> evidence.facts(frame.healthIntent(request, change), request, frame.task));
            assertEquals(reviews, frame.reviewFacts.size());
            assertEquals(0, frame.healthDispatches);
        }
    }

    @Test
    void everyOriginalStatusResourceRejectsMissingExtraAndDriftedBindingsBeforeReviewOrDispatch() throws Exception {
        for (String kind : List.of("deployment-release", "deployment-candidate", "deployment-snapshot", "deployment-application-locks")) {
            for (String alteration : List.of("missing", "extra", "drift")) {
                try (var frame = new Frame(); var request = frame.queryRequest("")) {
                    var original = frame.queryIntent(request);
                    var targets = new HashSet<>(original.semantics().orElseThrow().targets());
                    var selected = targets.stream().filter(target -> target.kind().equals(kind)).findFirst().orElseThrow();
                    if (!alteration.equals("extra")) targets.remove(selected);
                    if (!alteration.equals("missing")) targets.add(new ActionTarget(selected.serverId(), selected.kind(), selected.id() + "-foreign"));
                    var changed = original.withSemantics(new ActionSemantics("deployment.publication.original-status.v1", Set.copyOf(targets)));
                    var evidence = frame.evidence();
                    assertThrows(ApiException.class, () -> evidence.facts(changed, request, frame.task), kind + ":" + alteration);
                    assertEquals(0, frame.reviewFacts.size()); assertEquals(0, frame.statusQueries); assertEquals(0, frame.hostAuthorizations);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "candidate", "release"})
    void foreignOriginalStatusIdentityCannotBecomeCurrentEvidence(String change) throws Exception {
        try (var frame = new Frame(); var request = frame.queryRequest(change)) {
            var evidence = frame.evidence();
            var unit = frame.part("backend").unit();
            var intent = RemoteActions.command(frame.server, ARTIFACT, request).withSemantics(new ActionSemantics(
                "deployment.publication.original-status.v1", Set.of(new ActionTarget(frame.server.id(), "deployment-release", unit.releaseRoot()))));
            assertThrows(ApiException.class, () -> evidence.facts(intent, request, frame.task));
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.reviewFacts.size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TCP", "PROCESS", "COMMAND", "UDP"})
    void originalTypedPoliciesIncludeTheirCompleteProgramAndProtocolInputs(String kind) throws Exception {
        try (var frame = new Frame(kind)) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            var part = frame.part("backend");
            assertEquals(TaskResult.SUCCEEDED, frame.health(evidence, part, part.reviewedRuntime().healthCheck()).outcome());
            var current = frame.reviewFacts.getLast().path("currentCommandEvidence");
            assertEquals(kind, current.at("/typedHealthPolicy/kind").asText());
            var path = current.path("selectedHealthExecutionPath");
            assertEquals(kind, path.path("kind").asText());
            assertEquals(kind.equals("COMMAND"), path.at("/programVerification/reachableAfterParser").asBoolean());
            assertEquals(kind.equals("UDP"), path.at("/customProbe/reachableAfterParser").asBoolean());
            assertEquals("DAEMON", path.at("/earlierObservedRuntime/mode").asText());
            if (kind.equals("COMMAND"))
                assertTrue(path.at("/programVerification/meaning").asText().contains("complete original program still require review"));
            if (kind.equals("UDP"))
                assertTrue(path.at("/customProbe/meaning").asText().contains("complete original probe still require review"));
            assertEquals(DeploymentWorkloadArguments.healthPayload(part.reviewedRuntime().healthCheck()),
                current.at("/argumentBinding/healthPayloadArgument").asText());
            if (kind.equals("COMMAND")) {
                assertEquals("verify.sh", current.at("/typedHealthPolicy/command/entrypoint").asText());
                assertEquals("controlled-output", current.at("/typedHealthPolicy/expectedOutput").asText());
            }
            if (kind.equals("UDP"))
                assertEquals("probe.sh", current.at("/typedHealthPolicy/probeCommand/entrypoint").asText());
            assertEquals(2, frame.statusQueries);
            assertEquals(1, frame.healthDispatches);
        }
    }

    @Test
    void anotherInvocationCannotUseEarlierStatusReceiptsOrHistoricApproval() throws Exception {
        try (var frame = new Frame()) {
            var first = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(first).outcome());
            first.close();
            assertThrows(ApiException.class, () -> frame.health(first, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
            var fresh = frame.evidence();
            assertThrows(ApiException.class, () -> frame.health(fresh, frame.part("backend"), frame.part("backend").reviewedRuntime().healthCheck()));
            assertEquals(2, frame.statusQueries);
            assertEquals(0, frame.healthReviews());
            assertEquals(0, frame.healthDispatches);
        }
    }

    @Test
    void arbitraryValidSystemdNameOrForeignFormalProposalCannotSupplyAUnitMapping() throws Exception {
        try (var frame = new Frame()) {
            var original = frame.part("backend");
            var foreign = new DeploymentUnitIdentity(original.unit().id(), frame.server, "windowstolinux-foreign.service",
                original.unit().releaseRoot(), original.unit().ownershipManifestSha256());
            var part = new DeploymentApplicationGraph.Part(original.componentId(), foreign, original.runtime(), original.dependencies(),
                original.reviewedRuntime(), original.dataPaths(), original.resources());
            var units = new ArrayList<>(frame.proposal.units());
            var commit = units.getFirst();
            units.set(0, new DeploymentUnitCommit(foreign, commit.runtime(), commit.release(), commit.configuration(), commit.secrets()));
            var graph = new DeploymentApplicationGraph(frame.proposal.graph().applicationId(), "frontend", frame.proposal.graph().healthCheck(),
                List.of(part, frame.part("frontend")));
            assertThrows(Exception.class, () -> frame.evidence(new DeploymentPublishResult(graph, units)));
            var changed = new DeploymentApplicationGraph(frame.proposal.graph().applicationId(), "backend", frame.proposal.graph().healthCheck(),
                frame.proposal.graph().components());
            assertThrows(ApiException.class, () -> frame.evidence(new DeploymentPublishResult(changed, frame.proposal.units())));
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.healthReviews());
        }
    }

    @Test
    void unresolvedInnerMutationCannotBorrowTheFinishedOuterDeliveryException() throws Exception {
        try (var frame = new Frame()) {
            frame.journal.event(frame.task, "COMMAND_EXECUTING", "f".repeat(64), SOURCE);
            assertThrows(ApiException.class, frame::evidence);
            assertEquals(0, frame.statusQueries);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unit", "manifest", "operation", "timeout", "stdin", "output"})
    void alteredCanonicalRuntimeRequestsCannotReceiveEvidenceOrExecute(String change) throws Exception {
        for (String verb : List.of("inspect-runtime", "observe-deployment")) {
            try (var frame = new Frame(); var request = frame.runtimeRequest(verb, change)) {
                var evidence = frame.evidence();
                assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
                int reviews = frame.reviewFacts.size();
                var review = (DeploymentCommandReview) evidence.context().interaction();
                assertThrows(ApiException.class, () -> review.approveCommand(frame.runtimeIntent(request, verb, change), request, frame.task));
                assertThrows(ApiException.class, () -> evidence.execute(request, new ApprovalGrant(UUID.randomUUID())));
                assertEquals(reviews, frame.reviewFacts.size());
                assertEquals(0, frame.runtimeInspections + frame.runtimeObservations);
                assertEquals(2, frame.statusQueries);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP", "HOST", "SEND"})
    void runtimeBindingChangesStopAtEveryApprovalAndSendBoundary(String boundary) throws Exception {
        for (String verb : List.of("inspect-runtime", "observe-deployment")) {
            try (var frame = new Frame(); var request = frame.runtimeRequest(verb, "none")) {
                var evidence = frame.evidence();
                assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
                int grants = frame.hostAuthorizations;
                var review = (DeploymentCommandReview) evidence.context().interaction();
                var intent = frame.runtimeIntent(request, verb, "none");
                if (boundary.equals("APP")) frame.onRuntimeReview = ignored -> frame.changeBinding("CURRENT_SOURCE");
                if (boundary.equals("HOST")) frame.onRuntimeHost = () -> frame.changeBinding("profile");
                if (boundary.equals("SEND")) {
                    var grant = review.approveCommand(intent, request, frame.task);
                    assertEquals(TaskResult.SUCCEEDED, grant.outcome());
                    frame.changeBinding("SEALED_ARTIFACT");
                    assertThrows(ApiException.class, () -> evidence.execute(request, grant.value().orElseThrow()));
                } else {
                    assertThrows(ApiException.class, () -> review.approveCommand(intent, request, frame.task));
                    if (boundary.equals("APP")) assertEquals(grants, frame.hostAuthorizations);
                }
                assertEquals(0, frame.runtimeInspections + frame.runtimeObservations);
                assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
            }
        }
    }

    @Test
    void approvedRuntimeCannotDispatchDifferentHelperOrInlineCommandBytes() throws Exception {
        for (String verb : List.of("inspect-runtime", "observe-deployment")) {
            for (boolean inline : List.of(false, true)) {
                try (var frame = new Frame(); var request = frame.runtimeRequest(verb, "none")) {
                    var evidence = frame.evidence();
                    assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
                    var review = (DeploymentCommandReview) evidence.context().interaction();
                    var grant = review.approveCommand(frame.runtimeIntent(request, verb, "none"), request, frame.task);
                    assertEquals(TaskResult.SUCCEEDED, grant.outcome());
                    try (var changed = inline ? new CommandRequest("/bin/bash -lc 'printf changed'", new byte[0], Duration.ofSeconds(20), 4096)
                        : frame.runtimeRequest(verb.equals("inspect-runtime") ? "observe-deployment" : "inspect-runtime", "none")) {
                        assertThrows(ApiException.class, () -> evidence.execute(changed, grant.value().orElseThrow()));
                    }
                    assertEquals(0, frame.runtimeInspections + frame.runtimeObservations + frame.containerObservations);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inspect-runtime", "observe-deployment"})
    void runtimeDenialRetainsTheExactReasonAndStopsThisVerification(String verb) throws Exception {
        try (var frame = new Frame(); var request = frame.runtimeRequest(verb, "none")) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            var intent = frame.runtimeIntent(request, verb, "none");
            frame.deniedOperation = intent.semantics().orElseThrow().operation();
            int grants = frame.hostAuthorizations;
            var review = (DeploymentCommandReview) evidence.context().interaction();
            var refusal = assertThrows(ApiException.class, () -> review.approveCommand(intent, request, frame.task));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", refusal.problem().eventCode());
            assertEquals(DENIAL, refusal.problem().context().get("reviewReason"));
            assertEquals(1, frame.events("RUNTIME_REVIEW_DENIED"));
            var event = frame.journal.events(frame.task).getLast();
            assertTrue(event.detail().contains("reasonSha256=" + DeploymentAgentInputs.hash(DENIAL)));
            assertTrue(event.detail().contains("exactAction=" + DeploymentAgentInputs.hash(intent.exactAction())));
            assertTrue(event.detail().contains("evidence=" + frame.reviewFacts.getLast().at("/currentCommandEvidence/evidenceSha256").asText()));
            int reviews = frame.reviewFacts.size();
            frame.deniedOperation = "none";
            assertThrows(ApiException.class, () -> review.approveCommand(intent, request, frame.task));
            assertThrows(ApiException.class, () -> evidence.execute(request, new ApprovalGrant(UUID.randomUUID())));
            assertEquals(reviews, frame.reviewFacts.size());
            assertEquals(grants, frame.hostAuthorizations);
            assertEquals(0, frame.runtimeInspections + frame.runtimeObservations);
        }
    }

    @Test
    void containerInlineObservationKeepsItsExistingDefaultApprovalFactsAndBytes() throws Exception {
        try (var frame = new Frame("CONTAINER")) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            var part = frame.part("backend");
            var channel = new DeploymentPublishChannel(evidence, evidence.context().interaction(), frame.server, frame.task,
                frame.candidates.get("backend").artifactSha256());
            assertEquals(TaskResult.SUCCEEDED, channel.observe(part.unit(), part.reviewedRuntime()).outcome());
            assertEquals("container", frame.reviewFacts.get(2).at("/currentCommandEvidence/runtimeBinding/kind").asText());
            assertEquals("inspect-runtime", frame.reviewFacts.get(2).at("/currentCommandEvidence/argumentBinding/verb").asText());
            assertEquals("deployment.publication.observe.v1", frame.reviewFacts.getLast().path("operation").asText());
            assertFalse(frame.reviewFacts.getLast().has("currentCommandEvidence"));
            for (var facts : frame.reviewFacts)
                assertFalse(facts.path("currentCommandEvidence").has("healthPayloadByteView"));
            for (var request : frame.aiRequests)
                for (var message : request.messages())
                    assertFalse(message.text().contains(DeploymentAgentHealthReviewContract.text()));
            for (int index = 0; index < frame.aiRequests.size(); index++) {
                boolean helperObservation = frame.reviewFacts.get(index).has("currentCommandEvidence");
                String system = frame.aiRequests.get(index).messages().getFirst().text();
                assertEquals(helperObservation, system.contains(DeploymentAgentObservationReviewContract.text()));
            }
            assertEquals("", evidence.reviewInstructions());
            assertEquals(1, frame.containerObservations);
            assertEquals(0, frame.runtimeObservations);
            assertEquals(2, frame.statusQueries);
            for (var actual : frame.runtimeActions) assertEquals(actual.expectedAction(), actual.actualAction());
            try (var nativeRequest = frame.runtimeRequest("observe-deployment", "none")) {
                assertThrows(ApiException.class, () -> evidence.facts(frame.runtimeIntent(nativeRequest, "observe-deployment", "none"), nativeRequest, frame.task));
            }
        }
    }

    @Test
    void pendingStatusProvidesCanonicalSevenArgumentsAndFormalBindingsWithoutInventingAReceipt() throws Exception {
        for (String runtime : List.of("HTTP", "CONTAINER")) {
            try (var frame = new Frame(runtime); var request = frame.queryRequest("none")) {
                var evidence = frame.evidence();
                var intent = frame.queryIntent(request);
                var review = (DeploymentCommandReview) evidence.context().interaction();
                var grant = review.approveCommand(intent, request, frame.task);
                assertEquals(TaskResult.SUCCEEDED, grant.outcome());
                assertEquals(DeploymentAgentObservationReviewContract.text(), evidence.reviewInstructions());
                var current = frame.reviewFacts.getLast().path("currentCommandEvidence");
                var unit = frame.part("backend").unit();
                var arguments = current.at("/argumentBinding/positionalArguments");
                assertEquals(7, arguments.size());
                assertEquals(7, current.at("/argumentBinding/argumentCount").asInt());
                assertEquals(List.of(unit.id(), unit.ownershipManifestSha256(), frame.task.id().toString(),
                        frame.candidates.get("backend").candidateId(), frame.release("backend"),
                        runtime.equals("CONTAINER") ? "container" : "native", runtime.equals("CONTAINER") ? "docker" : "none"),
                    JSON.convertValue(arguments.findValues("value"), List.class));
                for (int position = 0; position < 7; position++)
                    assertEquals(position + 1, arguments.get(position).path("position").asInt());
                assertTrue(arguments.get(1).path("meaning").asText().contains("not a deployment id or resource id"));
                assertTrue(arguments.get(4).path("meaning").asText().contains("not an observation or assertion of actual CURRENT"));
                assertEquals(unit.releaseRoot(), current.at("/resourceBinding/id").asText());
                assertEquals(intent.exactAction(), current.at("/pendingQuery/exactAction").asText());
                assertEquals(ARTIFACT, current.at("/unitBinding/sealedArtifactSha256").asText());
                assertEquals(SOURCE, current.at("/unitBinding/sealedSourceRevision").asText());
                assertEquals("0".repeat(64), current.at("/unitBinding/sealedToolBindingSha256").asText());
                assertEquals(frame.proposal.units().getFirst().configuration().sha256(), current.at("/unitBinding/runtimeConfigurationSha256").asText());
                assertEquals(2, current.path("originalGraphBindings").size());
                assertFalse(current.path("formalReferences").isEmpty());
                var formal = new DeploymentExecutionStore(frame.context.db());
                var original = formal.findForTask(frame.task).orElseThrow();
                var actualReferences = formal.references(original);
                assertEquals(actualReferences.size(), current.path("formalReferences").size());
                for (var reference : current.path("formalReferences"))
                    assertTrue(actualReferences.values().stream().anyMatch(value -> value.orElseThrow().toString().equals(reference.path("referenceId").asText())));
                assertEquals("UNKNOWN", current.at("/originalExecutionState/outcome").asText());
                assertEquals("NO_RESULT_FOR_THIS_PENDING_REQUEST", current.path("receiptState").asText());
                assertFalse(current.has("currentGraphQueries"));
                assertFalse(current.has("parsedStatus"));
                assertFalse(current.has("result"));
                assertTrue(current.path("observationLimits").asText().contains("No fresh remote receipt"));
                assertEquals(0, frame.statusQueries);
                assertEquals(0, frame.events("HEALTH_STATUS_QUERY_RESULT"));
                assertFalse(DeploymentAgentActionLog.effectsSettled(frame.journal.events(frame.task)));
                assertEquals(1, frame.events("HEALTH_STATUS_QUERY_REQUEST"));
                assertEquals(TaskResult.SUCCEEDED, evidence.execute(request, grant.value().orElseThrow()).outcome());
                assertEquals(1, frame.statusQueries);
                var result = frame.journal.events(frame.task).getLast();
                assertEquals("HEALTH_STATUS_QUERY_RESULT", result.type());
                assertTrue(result.detail().contains("review=" + current.path("reviewRequestSha256").asText()));
                assertTrue(result.detail().contains("evidence=" + current.path("evidenceSha256").asText()));
                int reviews = frame.reviewFacts.size();
                assertThrows(ApiException.class, () -> review.approveCommand(intent, request, frame.task));
                assertEquals(reviews, frame.reviewFacts.size());
                assertEquals(1, frame.statusQueries);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APP", "HOST", "SEND"})
    void statusBindingsAndRequestBytesAreRecheckedAfterAppHostAndBeforeSend(String boundary) throws Exception {
        for (boolean changedBody : boundary.equals("SEND") ? List.of(false, true) : List.of(false)) {
            try (var frame = new Frame(); var request = frame.queryRequest("none")) {
                var evidence = frame.evidence();
                var review = (DeploymentCommandReview) evidence.context().interaction();
                var intent = frame.queryIntent(request);
                if (boundary.equals("APP")) frame.onStatusReview = ignored -> frame.changeBinding("CURRENT_SOURCE");
                if (boundary.equals("HOST")) frame.onStatusHost = () -> frame.changeBinding("profile");
                if (boundary.equals("SEND")) {
                    var grant = review.approveCommand(intent, request, frame.task);
                    assertEquals(TaskResult.SUCCEEDED, grant.outcome());
                    if (changedBody) {
                        try (var changed = frame.queryRequest("task")) {
                            assertThrows(ApiException.class, () -> evidence.execute(changed, grant.value().orElseThrow()));
                        }
                    } else {
                        frame.changeBinding("SEALED_TOOLS");
                        assertThrows(ApiException.class, () -> evidence.execute(request, grant.value().orElseThrow()));
                    }
                } else {
                    assertThrows(ApiException.class, () -> review.approveCommand(intent, request, frame.task));
                    if (boundary.equals("APP")) assertEquals(0, frame.hostAuthorizations);
                }
                assertEquals(0, frame.statusQueries);
                assertEquals(0, frame.healthDispatches);
                assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"manifest", "backend", "engine"})
    void foreignStatusOwnershipAndRuntimeArgumentsNeverReachAppOrDispatch(String change) throws Exception {
        try (var frame = new Frame(); var request = frame.queryRequest(change)) {
            var evidence = frame.evidence();
            var review = (DeploymentCommandReview) evidence.context().interaction();
            assertThrows(ApiException.class, () -> review.approveCommand(frame.queryIntent(request), request, frame.task));
            assertEquals(0, frame.reviewFacts.size());
            assertEquals(0, frame.statusQueries);
            assertEquals(0, frame.events("HEALTH_STATUS_QUERY_REQUEST"));
        }
    }

    @Test
    void secondStatusDenialPreservesEarlierDenialAndStopsBeforeHealthOrAnyReplay() throws Exception {
        try (var frame = new Frame(); var request = frame.queryRequest("none")) {
            var oldIntent = frame.queryIntent(request);
            String originalDetail = "exactAction=" + DeploymentAgentInputs.hash(oldIntent.exactAction())
                + ";evidence=none;reasonSha256=" + DeploymentAgentInputs.hash("Controlled retained query refusal");
            frame.journal.event(frame.task, "HEALTH_STATUS_QUERY_DENIED", "8".repeat(64), originalDetail);
            var oldDenial = frame.journal.events(frame.task).getLast();
            frame.onStatusReview = facts -> {
                if (facts.at("/currentCommandEvidence/unitBinding/component").asText().equals("frontend"))
                    frame.deniedOperation = "deployment.publication.original-status.v1";
            };
            var evidence = frame.evidence();
            assertEquals(TaskResult.UNKNOWN, frame.verify(evidence).outcome());
            assertEquals(2, frame.reviewFacts.size());
            assertEquals(1, frame.statusQueries);
            assertEquals(0, frame.healthDispatches);
            assertEquals(0, frame.runtimeInspections + frame.runtimeObservations);
            assertEquals(2, frame.events("HEALTH_STATUS_QUERY_DENIED"));
            assertTrue(frame.journal.events(frame.task).contains(oldDenial));
            var current = frame.reviewFacts.getLast().path("currentCommandEvidence");
            assertTrue(current.path("originalEventReferences").findValues("detail").stream().anyMatch(value -> value.asText().equals(originalDetail)));
            var denial = frame.journal.events(frame.task).getLast();
            assertTrue(denial.detail().contains("reasonSha256=" + DeploymentAgentInputs.hash(DENIAL)));
            assertTrue(denial.detail().contains("evidence=" + current.path("evidenceSha256").asText()));
            assertEquals(current.path("reviewRequestSha256").asText(), denial.action());
            int reviews = frame.reviewFacts.size();
            frame.deniedOperation = "none";
            var review = (DeploymentCommandReview) evidence.context().interaction();
            assertThrows(ApiException.class, () -> review.approveCommand(frame.queryIntent(request), request, frame.task));
            assertEquals(reviews, frame.reviewFacts.size());
            assertEquals(1, frame.statusQueries);
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @Test
    void emptyEvidenceHookKeepsOriginalSystemPolicyBytesAndHostGrantOrder() throws Exception {
        try (var frame = new Frame(); var request = frame.healthRequest("none")) {
            var scope = new DeploymentCommandReviewScope(frame.context, frame.context.interaction(), frame.task,
                "FULL_CONTROL", true, () -> REVISION);
            frame.onHealthHost = () -> assertEquals(1, frame.aiRequests.size(), "The host still follows the independent verdict");
            var result = ((DeploymentCommandReview) scope.interaction()).approveCommand(frame.healthIntent(request, "none"), request, frame.task);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertTrue(result.value().isPresent());
            assertEquals(1, frame.hostAuthorizations);
            assertEquals(0, frame.healthDispatches);
            assertFalse(frame.reviewFacts.getFirst().has("currentCommandEvidence"));
            assertEquals(frame.healthIntent(request, "none").exactAction(), frame.reviewFacts.getFirst().path("exactAction").asText());
            String actualSystem = frame.aiRequests.getFirst().messages().getFirst().text();
            new DeploymentAiJson(frame.context).askCommand(
                DeploymentReviewDecision.INSTRUCTION + DeploymentSkillResources.text(DeploymentFixedCommandApproval.class, "command-review.md"),
                Map.of("operation", "deployment.publication.health.v1"), REVISION, frame.task, ignored -> false);
            assertEquals(actualSystem, frame.aiRequests.getLast().messages().getFirst().text(),
                "The empty hook must keep the original shared command-review policy byte for byte");
            assertFalse(actualSystem.contains(DeploymentAgentHealthReviewContract.text()));
            assertEquals(1, frame.hostAuthorizations);
        }
    }

    private record ActionCheck(String expectedAction, String actualAction) {
    }

    @Test
    void originalCustomGraphUsesCapturedLayoutForEveryStatusHealthAndRuntimeRequest() throws Exception {
        var layout = new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));
        try (var frame = new Frame("HTTP", null, layout)) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.verify(evidence).outcome());
            assertEquals(2, frame.statusQueries);
            assertEquals(3, frame.healthDispatches);
            assertEquals(2, frame.runtimeInspections);
            assertEquals(2, frame.runtimeObservations);
            assertEquals(9, frame.reviewFacts.size());
            for (var review : frame.reviewFacts) {
                var current = review.path("currentCommandEvidence");
                assertEquals(layout.canonical(), current.at("/originalRemoteLayout/canonical").asText());
                assertEquals(layout.digest(), current.at("/originalRemoteLayout/sha256").asText());
                assertEquals(DeploymentManagedHelperBundle.helperSha256(layout), current.path("helperSha256").asText());
                assertTrue(review.path("exactAction").asText().contains(layout.helperPath()));
                assertFalse(current.has("permission"));
            }
            assertEquals("UNKNOWN", frame.journal.find(frame.task).orElseThrow().state());
        }
    }

    @Test
    void customGraphCannotBorrowDefaultHealthCommandOrChangedCapturedLayout() throws Exception {
        var layout = new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));
        try (var frame = new Frame("HTTP", null, layout); var actual = frame.healthRequest("none")) {
            var evidence = frame.evidence();
            assertEquals(TaskResult.SUCCEEDED, frame.observe(evidence).outcome());
            String body = DeploymentManagedHelperBundle.verifiedBody(actual.script(), layout);
            String foreign = body.replace(layout.helperPath(), DeploymentRemoteLayout.original().helperPath());
            try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(foreign), new byte[0], actual.timeout(), actual.outputLimitBytes())) {
                assertThrows(ApiException.class, () -> evidence.facts(frame.healthIntent(request, "none"), request, frame.task));
            }
            assertEquals(2, frame.reviewFacts.size());
            assertEquals(0, frame.healthDispatches);
        }
        try (var frame = new Frame("HTTP", null, layout)) {
            frame.capturedLayout = DeploymentRemoteLayout.original();
            assertThrows(ApiException.class, frame::evidence);
            assertEquals(0, frame.reviewFacts.size());
            assertEquals(0, frame.remoteExecutions);
        }
    }

    @Test
    void unfinishedPublicationObservationKeepsCompleteFactsUnknownAndDefaultVerificationClosed() throws Exception {
        try (var frame = new Frame("HTTP", null, DeploymentRemoteLayout.original(), true)) {
            assertThrows(ApiException.class, frame::evidence);
            var result = frame.observePartialPublication();
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            var recorded = DeploymentAgentPublicationObservation.readLatest(frame.journal.events(frame.task), frame.taskId);
            assertTrue(recorded.observationComplete());
            assertEquals(2, recorded.observedComponents());
            assertEquals(2, frame.statusQueries);
            assertEquals(2, frame.hostAuthorizations);
            assertEquals(0, frame.healthDispatches + frame.runtimeInspections + frame.runtimeObservations);
            assertTrue(frame.journal.events(frame.task).stream().noneMatch(event -> Set.of("AI_PUBLICATION_FINISHED",
                "AI_RECOVERY_SETTLED", "GRAPH_ROLLBACK_RESULT", "AI_PUBLICATION_CLEANED").contains(event.type())));
            assertFalse(new DeploymentExecutionStore(frame.context.db()).findForTask(frame.task).orElseThrow().businessCommitted());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"denied", "truncated", "foreign", "unknown"})
    void partialPublicationObservationRetainsFirstReceiptAndStopsWithoutRetry(String failure) throws Exception {
        try (var frame = new Frame("HTTP", null, DeploymentRemoteLayout.original(), true)) {
            frame.onStatusReview = facts -> {
                if (facts.at("/currentCommandEvidence/unitBinding/component").asText().equals("frontend")) {
                    if (failure.equals("denied")) frame.deniedOperation = "deployment.publication.original-status.v1";
                    else frame.statusFailure = failure;
                }
            };
            assertEquals(TaskResult.UNKNOWN, frame.observePartialPublication().outcome());
            var recorded = DeploymentAgentPublicationObservation.readLatest(frame.journal.events(frame.task), frame.taskId);
            assertFalse(recorded.observationComplete());
            assertEquals(1, recorded.observedComponents());
            assertFalse(JSON.readTree(recorded.componentFrames().getFirst()).path("receipt").isNull());
            var second = JSON.readTree(recorded.componentFrames().getLast());
            assertTrue(second.path("receipt").isNull());
            assertEquals(failure.equals("denied") ? "APP_DENIED"
                : failure.equals("unknown") ? "REQUESTED_WITHOUT_VERIFIED_RESULT" : "INVALID_OR_INCOMPLETE_RESULT",
                second.path("missingReason").asText());
            assertEquals(failure.equals("denied") ? 1 : 2, frame.statusQueries);
            assertEquals(2, frame.reviewFacts.size());
            assertEquals(0, frame.healthDispatches + frame.runtimeInspections + frame.runtimeObservations);
        }
    }

    @Test
    void partialPublicationObservationPreservesDifferentOwnedCurrentAndRejectsEveryMutation() throws Exception {
        try (var frame = new Frame("HTTP", null, DeploymentRemoteLayout.original(), true)) {
            frame.statusFailure = "wrong-current";
            assertEquals(TaskResult.UNKNOWN, frame.observePartialPublication().outcome());
            var recorded = DeploymentAgentPublicationObservation.readLatest(frame.journal.events(frame.task), frame.taskId);
            assertTrue(recorded.observationComplete());
            for (var row : recorded.componentFrames())
                assertEquals(SOURCE, JSON.readTree(row).at("/receipt/status/current").asText());
            var evidence = frame.partialEvidence();
            try (var request = frame.healthRequest("none")) {
                assertThrows(ApiException.class, () -> evidence.facts(frame.healthIntent(request, "none"), request, frame.task));
                assertThrows(ApiException.class, () -> evidence.execute(request, new ApprovalGrant(UUID.randomUUID())));
            }
            try (var request = frame.publicationRequest("none")) {
                assertThrows(ApiException.class, () -> evidence.facts(frame.publicationIntent(request, "none"), request, frame.task));
            }
            assertEquals(2, frame.remoteExecutions);
            assertEquals(0, frame.healthDispatches);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"prefix", "route", "candidate", "task", "profile"})
    void partialPublicationObservationFailsClosedOnOriginalBindingDrift(String change) throws Exception {
        try (var frame = new Frame("HTTP", null, DeploymentRemoteLayout.original(), true)) {
            var evidence = frame.partialEvidence();
            switch (change) {
                case "prefix" -> {
                    try (var update = frame.retained.prepareStatement("UPDATE deployment_agent_event SET event_detail=? WHERE event_type='SOURCE'")) {
                        update.setString(1, "f".repeat(64)); update.executeUpdate();
                    }
                }
                case "route" -> frame.route = "f".repeat(64);
                case "candidate" -> frame.journal.event(frame.task, "CANDIDATE", "backend", "foreign-0123456789abcdef");
                case "profile" -> frame.profile = new ServerInfo(frame.server, "changed", "foreign-host", 22, "root", Optional.empty(), Optional.empty());
                default -> { }
            }
            try (var request = frame.queryRequest("none")) {
                var supplied = change.equals("task") ? proxy(TaskContext.class, (name, args) -> UUID.randomUUID()) : frame.task;
                assertThrows(ApiException.class, () -> evidence.facts(frame.queryIntent(request), request, supplied));
            }
            assertEquals(0, frame.remoteExecutions);
            assertEquals(0, frame.hostAuthorizations);
        }
    }

    private static final class Frame implements AutoCloseable {
        private final DeploymentRemoteLayout layout;

        private DeploymentRemoteLayout capturedLayout;

        private final String jdbc = "jdbc:h2:mem:health-evidence-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

        private final Connection retained = DriverManager.getConnection(jdbc);

        private final UUID taskId = UUID.randomUUID();

        private final List<String> progressStages = new ArrayList<>();

        private final TaskContext task = proxy(TaskContext.class, (name, args) -> switch (name) {
            case "id" -> taskId;
            case "cancelToken" -> (CancelToken) () -> false;
            case "progress" -> {
                progressStages.add((String) args[0]);
                yield null;
            }
            default -> throw new AssertionError(name);
        });

        private final ServerId server = new ServerId("controlled");

        private ServerInfo profile = new ServerInfo(server, "controlled", "controlled-host", 22, "root", Optional.empty(), Optional.empty());

        private final RemoteFacts remoteFacts = new RemoteFacts("Linux", "amd64", "controlled-fingerprint");

        private final Map<String, DeploymentGraphPublisher.Candidate> candidates = new LinkedHashMap<>();

        private final DeploymentPublishResult proposal;

        private final ModContext context;

        private final RemoteSession remote;

        private final DeploymentAgentActionLog journal;

        private final List<JsonNode> reviewFacts = new ArrayList<>();

        private final List<AiRequest> aiRequests = new ArrayList<>();

        private OperationResult<AiResponse> reviewCallFailure;

        private final List<LogEvent> diagnosticEvents = new ArrayList<>();

        private final List<String> statusActions = new ArrayList<>();

        private final List<ActionCheck> healthActions = new ArrayList<>();

        private final List<ActionCheck> runtimeActions = new ArrayList<>();

        private final Map<String, String> maintenanceMarkers = new LinkedHashMap<>();

        private String statusFailure = "none";

        private String deniedOperation = "none";

        private TaskResult hostOutcome = TaskResult.SUCCEEDED;

        private boolean checkpointActive;

        private final List<CommandResult> statusResults = new ArrayList<>();

        private String route = REVISION;

        private Consumer<JsonNode> onHealthReview = ignored -> {
        };

        private Runnable onHealthHost = () -> {
        };

        private Consumer<JsonNode> onRuntimeReview = ignored -> {
        };

        private Runnable onRuntimeHost = () -> {
        };

        private Consumer<JsonNode> onStatusReview = ignored -> {
        };

        private Runnable onStatusHost = () -> {
        };

        private Consumer<JsonNode> onPublicationReview = ignored -> {
        };

        private Runnable onPublicationHost = () -> {
        };

        private int statusQueries;

        private int healthDispatches;

        private int runtimeInspections;

        private int runtimeObservations;

        private int containerObservations;

        private int hostAuthorizations;

        private int workflowOpens;

        private int workflowCloses;

        private int linuxConnections;

        private int remoteCloses;

        private int remoteExecutions;

        Frame() throws Exception {
            this("HTTP");
        }

        Frame(String healthKind) throws Exception {
            this(healthKind, null);
        }

        Frame(DeploymentHealthCheck health) throws Exception {
            this("HTTP", health);
        }

        Frame(String healthKind, DeploymentHealthCheck selectedHealth) throws Exception {
            this(healthKind, selectedHealth, DeploymentRemoteLayout.original());
        }

        Frame(String healthKind, DeploymentHealthCheck selectedHealth, DeploymentRemoteLayout layout) throws Exception {
            this(healthKind, selectedHealth, layout, false);
        }

        Frame(String healthKind, DeploymentHealthCheck selectedHealth, DeploymentRemoteLayout layout, boolean publication) throws Exception {
            this.layout = layout;
            this.capturedLayout = layout;
            try (var source = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, source);
            }
            var db = proxy(ModDatabase.class, (name, args) -> name.equals("identity")
                ? new SchemaIdentity(DeploymentSchemaIdentity.OWNER, DeploymentSchemaIdentity.VERSION, taskId) : DriverManager.getConnection(jdbc));
            journal = new DeploymentAgentActionLog(db);
            remote = proxy(RemoteSession.class, (name, args) -> {
                if (name.equals("facts")) return remoteFacts;
                if (name.equals("close")) {
                    remoteCloses++;
                    return null;
                }
                assertEquals("execute", name);
                remoteExecutions++;
                var request = (CommandRequest) args[0];
                var part = requestPart(request);
                String component = part.componentId();
                if (request.script().contains("'publication-original-status'")) {
                    statusQueries++;
                    statusActions.add(RemoteActions.command(server, candidates.get(component).artifactSha256(), request).exactAction());
                    if (statusFailure.equals("unknown"))
                        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.empty());
                    if (statusFailure.equals("foreign"))
                        return success(new CommandResult(Optional.of(64), "", "MANAGED_HELPER_REJECT=publication-current-owner\n", false));
                    String output = status(component);
                    if (statusFailure.equals("active")) output = output.replace("BUILD_ACTIVE=no", "BUILD_ACTIVE=yes");
                    if (statusFailure.equals("wrong-current")) output = output.replace(release(component), SOURCE);
                    if (statusFailure.equals("duplicate")) output += "CURRENT=" + release(component) + "\n";
                    if (statusFailure.equals("foreign-snapshot"))
                        output = output.replace("SNAPSHOT=none", "SNAPSHOT=" + UUID.randomUUID());
                    var result = new CommandResult(Optional.of(0), output, "", statusFailure.equals("truncated"));
                    statusResults.add(result);
                    return success(result);
                }
                if (request.script().contains("'application-health'")) {
                    healthDispatches++;
                    var intent = RemoteActions.command(server, candidates.get(component).artifactSha256(), request);
                    String expected = reviewFacts.getLast().path("exactAction").asText();
                    healthActions.add(new ActionCheck(expected, intent.exactAction()));
                    return success(new CommandResult(Optional.of(0), "HEALTHY=1\n", "", false));
                }
                if (request.script().contains("'inspect-runtime'")) {
                    runtimeInspections++;
                    runtimeActions.add(new ActionCheck(reviewFacts.getLast().path("exactAction").asText(),
                        RemoteActions.command(server, candidates.get(component).artifactSha256(), request).exactAction()));
                    String identity = part.reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container
                        ? "KIND=container\nENGINE=docker\nMODE=DAEMON\n" : "KIND=deployment\nMODE=DAEMON\n";
                    return success(new CommandResult(Optional.of(0), identity, "", false));
                }
                if (request.script().contains("'observe-deployment'")) {
                    runtimeObservations++;
                    runtimeActions.add(new ActionCheck(reviewFacts.getLast().path("exactAction").asText(),
                        RemoteActions.command(server, candidates.get(component).artifactSha256(), request).exactAction()));
                    return success(new CommandResult(Optional.of(0), "OWNER=1\nRUNNING=1\nENABLED=enabled\nQUERY_OK=1\nActiveState=active\nSubState=running\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=34205\n", "", false));
                }
                if (request.script().contains("docker inspect --format")) {
                    containerObservations++;
                    runtimeActions.add(new ActionCheck(reviewFacts.getLast().path("exactAction").asText(),
                        RemoteActions.command(server, candidates.get(component).artifactSha256(), request).exactAction()));
                    return success(new CommandResult(Optional.of(0), "OWNER=1\nRUNNING=true\nENABLED=always\n", "", false));
                }
                throw new AssertionError(request.script());
            });
            var ai = proxy(AiService.class, (name, args) -> {
                if (name.equals("configurationRevision")) return route;
                assertEquals("call", name);
                var request = (AiRequest) args[0];
                aiRequests.add(request);
                assertTrue(request.requiredToolName().isEmpty());
                assertTrue(request.tools().stream().anyMatch(tool -> tool.name().equals("submit_result")));
                var facts = JSON.readTree(request.messages().getLast().text());
                reviewFacts.add(facts);
                String operation = facts.path("operation").asText();
                if (operation.equals("deployment.publication.original-status.v1")) onStatusReview.accept(facts);
                if (operation.equals("deployment.publication.health.v1")) onHealthReview.accept(facts);
                if (operation.equals("deployment.publication.publish.v1")) onPublicationReview.accept(facts);
                if (Set.of("deployment.publication.inspect.v1", "deployment.publication.observe.v1").contains(operation))
                    onRuntimeReview.accept(facts);
                if (reviewCallFailure != null) return reviewCallFailure;
                boolean deny = operation.equals(deniedOperation);
                return success(new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    List.of(new AiToolCall("review-" + reviewFacts.size(), "submit_result", JSON.writeValueAsString(Map.of("decision", deny ? "DENY" : "APPROVE",
                        "requiresUserConfirmation", deny, "reason", deny ? DENIAL : "Controlled original health binding")))), Optional.empty()));
            });
            var host = proxy(InteractionService.class, (name, args) -> {
                if (name.equals("openReviewedWorkflow")) {
                    assertEquals("deployment.workflow.authority", ((ActionIntent) args[0]).type());
                    assertEquals(true, args[3]);
                    assertSame(task, args[4]);
                    workflowOpens++;
                    return success((WorkflowGrant) () -> {
                        workflowCloses++;
                    });
                }
                assertTrue(Set.of("approve", "approveReviewedAction").contains(name), name);
                hostAuthorizations++;
                if (reviewFacts.getLast().path("operation").asText().equals("deployment.publication.original-status.v1"))
                    onStatusHost.run();
                if (reviewFacts.getLast().path("operation").asText().equals("deployment.publication.health.v1"))
                    onHealthHost.run();
                if (reviewFacts.getLast().path("operation").asText().equals("deployment.publication.publish.v1"))
                    onPublicationHost.run();
                if (Set.of("deployment.publication.inspect.v1", "deployment.publication.observe.v1").contains(
                    reviewFacts.getLast().path("operation").asText())) onRuntimeHost.run();
                if (hostOutcome != TaskResult.SUCCEEDED)
                    return new OperationResult<>(hostOutcome, Optional.empty(), Optional.of(new Problem(
                        hostOutcome == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : ProblemCode.ACCESS_DENIED,
                        "CONTROLLED_HOST_REFUSAL", "Controlled host refused or could not confirm this request", Map.of(), Optional.empty())));
                return success(new ApprovalGrant(UUID.randomUUID()));
            });
            var tasks = proxy(TaskService.class, (name, args) -> {
                assertEquals("findCheckpoint", name);
                if (args[1].equals("deployment.config.remote-layout.v1") && !layout.equals(DeploymentRemoteLayout.original())) {
                    var node = JSON.createObjectNode().put("formatVersion", 1).put("task", taskId.toString());
                    node.set("layout", JSON.readTree(capturedLayout.canonical()));
                    node.put("layoutSha256", capturedLayout.digest());
                    return Optional.of(proxy(TaskCheckpoint.class, (method, arguments) -> {
                        assertEquals("snapshot", method);
                        return new TaskCheckpoint.Snapshot(0, node.toString(), true);
                    }));
                }
                return checkpointActive ? Optional.of(proxy(TaskCheckpoint.class, (method, arguments) -> {
                    assertEquals("snapshot", method);
                    return new TaskCheckpoint.Snapshot(0, "{}", false);
                })) : Optional.empty();
            });
            var servers = proxy(ServerService.class, (name, args) -> {
                assertEquals("get", name);
                return profile;
            });
            var logs = proxy(LogService.class, (name, args) -> {
                if (name.equals("register")) return new LogTarget(UUID.randomUUID());
                if (name.equals("write")) diagnosticEvents.add((LogEvent) args[1]);
                return null;
            });
            var linux = proxy(LinuxService.class, (name, args) -> {
                assertEquals("connect", name);
                assertEquals(server, args[0]);
                assertSame(task, args[1]);
                linuxConnections++;
                return remote;
            });
            var ui = proxy(UiService.class, (name, args) -> new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            context = proxy(ModContext.class, (name, args) -> switch (name) {
                case "db" -> db;
                case "ai" -> ai;
                case "interaction" -> host;
                case "tasks" -> tasks;
                case "servers" -> servers;
                case "ui" -> ui;
                case "logs" -> logs;
                case "linux" -> linux;
                default -> throw new AssertionError(name);
            });
            var parts = new ArrayList<DeploymentApplicationGraph.Part>();
            var units = new ArrayList<DeploymentUnitCommit>();
            for (String component : List.of("backend", "frontend")) {
                boolean backend = component.equals("backend");
                var unit = DeploymentUnitIdentity.forManaged("controlled-" + component, server, (backend ? "b" : "9").repeat(64), layout);
                DeploymentHealthCheck health = component.equals("backend") ? switch (healthKind) {
                    case "TCP" -> new DeploymentHealthCheck.Tcp(18101, 120, 1);
                    case "PROCESS" -> new DeploymentHealthCheck.Process(120, 1);
                    case "COMMAND" ->
                        new DeploymentHealthCheck.Command(new DeploymentCommand("verify.sh", List.of("complete-input")), "controlled-output", 120);
                    case "UDP" ->
                        new DeploymentHealthCheck.Udp(18101, "", "", Optional.of(new DeploymentCommand("probe.sh", List.of("complete-probe-input"))), 120);
                    default -> new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:18101/healthz"), 200, 120);
                } : new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:18100/healthz"), 200, 120);
                if (backend && selectedHealth != null) health = selectedHealth;
                var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true, new DeploymentCommand("run.sh", List.of()),
                    "", List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
                var policy = healthKind.equals("CONTAINER") && backend ? DeploymentIdentityMode.CONTAINER_NON_ROOT : DeploymentIdentityMode.SYSTEMD_STATIC;
                DeploymentRuntimeDefinition runtime = policy == DeploymentIdentityMode.CONTAINER_NON_ROOT
                    ? new DeploymentRuntimeDefinition.Container(DeploymentRuntimeDefinition.ContainerEngineType.DOCKER, Map.of(18101, 18101), List.of(), health, policy, workload)
                    : new DeploymentRuntimeDefinition.ManagedProcess(health, policy, workload);
                var config = new DeploymentUnitRuntimeConfig(health, Optional.empty(), policy, workload);
                var resources = new DeploymentResourceBindings(List.of(), Optional.of(List.of()));
                var configuration = DeploymentConfigSnapshot.create(unit.id(), 1, "v1", Instant.EPOCH, List.of());
                String artifact = backend ? ARTIFACT : "8".repeat(64), currentSource = publication ? "6".repeat(64) : SOURCE;
                String release = publication ? DeploymentAgentInputs.publicationReleaseIdentity(artifact, currentSource, configuration.sha256(), runtime, resources)
                    : backend ? RELEASE : "7".repeat(64);
                parts.add(new DeploymentApplicationGraph.Part(component, unit, config, List.of(), runtime, List.of(), resources));
                units.add(new DeploymentUnitCommit(unit, config, new DeploymentCurrentRelease(unit.id(), release, Instant.EPOCH), configuration, List.of()));
                candidates.put(component, new DeploymentGraphPublisher.Candidate(unit.id() + "-0123456789abcdef", artifact, currentSource, "0".repeat(64), false, Map.of()));
            }
            var whole = new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:18100/"), 200, 120);
            proposal = new DeploymentPublishResult(new DeploymentApplicationGraph("controlled", "frontend", whole, parts), units);
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), DeploymentAgentInputs.targetDigest(server, remote), REVISION,
                publication ? "AUTOMATIC" : "FULL_CONTROL", !publication));
            if (!layout.equals(DeploymentRemoteLayout.original()))
                gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.record(journal, task, server, layout);
            journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(profile));
            journal.event(task, "SOURCE", ARCHIVE, SOURCE);
            for (String component : candidates.keySet()) {
                journal.event(task, "CANDIDATE", component, candidates.get(component).candidateId());
                journal.event(task, "CURRENT_SOURCE", component, candidates.get(component).sourceRevision());
                if (publication) {
                    String seal = DeploymentAgentInputs.hash(component + ":" + candidates.get(component).sourceRevision() + ":seal");
                    journal.event(task, "SEAL_EXECUTING", seal, candidates.get(component).sourceRevision());
                    journal.event(task, "SEAL_RESULT", seal, candidates.get(component).artifactSha256());
                }
                journal.event(task, "SEALED_ARTIFACT", component, candidates.get(component).artifactSha256());
                journal.event(task, "SEALED_TOOLS", component, "0".repeat(64) + ":false");
            }
            journal.event(task, "DELIVERY_EXECUTING", "f".repeat(64), "1");
            journal.event(task, "AI_PUBLICATION", "controlled", "MODEL_GENERATED_COMMANDS");
            if (!publication) {
                journal.event(task, "AI_PUBLICATION_VERIFIED", "controlled", "HEALTHY");
                journal.event(task, "AI_PUBLICATION_FINISHED", "controlled", "MODEL_ACCEPTED_VERIFIED_RESULT");
                journal.event(task, "CANDIDATE_CLEANUP_EXECUTING", "backend", candidates.get("backend").candidateId());
            }
            var executions = new DeploymentExecutionStore(db);
            var id = new DeploymentExecutionIdentity(UUID.randomUUID(), taskId, Optional.empty(), "controlled", server, DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, SOURCE);
            var references = DeploymentOriginalPublishCommit.references(server, proposal, Map.of());
            executions.begin(task, id, "deployment-agent", references, Optional.empty(), Instant.EPOCH);
            for (var reference : references)
                executions.recordReference(task, id.executionId(), reference, UUID.randomUUID(), Instant.EPOCH);
            executions.startEffects(task, id.executionId(), Instant.EPOCH.plusSeconds(1));
            executions.recordPublicationTemplate(task, id.executionId(), new DeploymentPublishProposal(proposal.graph(), proposal.units()), Instant.EPOCH.plusSeconds(2));
            if (!publication) {
                executions.recordUncommittedOutcome(task, id.executionId(), TaskResult.UNKNOWN,
                    new Problem(ProblemCode.UNKNOWN, "CLEANUP_UNCONFIRMED", "Retained original cleanup", Map.of(), Optional.empty()), Instant.EPOCH.plusSeconds(3));
                journal.state(task, "UNKNOWN");
            }
        }

        DeploymentCommandReviewScope publicationScope() throws Exception {
            return new DeploymentCommandReviewScope(context, context.interaction(), task, "AUTOMATIC", false, () -> REVISION);
        }

        DeploymentAgentPublicationEvidence publicationEvidence(DeploymentCommandReviewScope scope) throws Exception {
            return publicationEvidence(scope, proposal);
        }

        DeploymentAgentPublicationEvidence publicationEvidence(DeploymentCommandReviewScope scope, DeploymentPublishResult selected) throws Exception {
            var workspaces = new LinkedHashMap<String, DeploymentRemoteWorkspace>();
            for (var part : selected.graph().components())
                workspaces.put(part.componentId(), new DeploymentRemoteWorkspace(part.unit().id(), ARCHIVE, layout));
            return new DeploymentAgentPublicationEvidence(scope, remote, server, task, REVISION, selected, candidates, workspaces, journal);
        }

        CommandRequest publicationRequest(String change) {
            return publicationRequest("backend", change);
        }

        CommandRequest publicationRequest(String component, String change) {
            var part = part(component);
            var candidate = candidates.get(component);
            var commit = proposal.units().stream().filter(value -> value.unit().equals(part.unit())).findFirst().orElseThrow();
            var runtime = part.reviewedRuntime();
            var values = new ArrayList<>(List.of(layout.helperPath(), runtime instanceof DeploymentRuntimeDefinition.Container ? "publish-container" : "publish-deployment",
                part.unit().id(), candidate.candidateId(), commit.release().sha256(), part.unit().ownershipManifestSha256(),
                "identity-v2", runtime.identityPolicy().name(), DeploymentWorkloadArguments.payload(runtime)));
            values.addAll(DeploymentPublishArguments.inputs(commit.configuration().sha256(), Map.of()));
            values.addAll(DeploymentPublishArguments.content(part.unit().id(), part.resources(), layout));
            if (runtime instanceof DeploymentRuntimeDefinition.Container container) values.addAll(DeploymentContainerArguments.from(container));
            else values.addAll(DeploymentRuntimeArguments.from(null, runtime));
            switch (change) {
                case "release" -> values.set(4, "f".repeat(64));
                case "owner" -> values.set(5, "f".repeat(64));
                case "candidate" -> values.set(3, part.unit().id() + "-fedcba9876543210");
                case "configuration" -> values.set(9, "f".repeat(64));
                case "runtime" -> values.set(7, "CONTAINER_NON_ROOT");
                case "resources" -> values.set(11, "foreign-application");
                default -> { }
            }
            String body = values.stream().map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            if (change.equals("script")) body += "; printf unexpected";
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(body, layout), new byte[0], Duration.ofSeconds(120), 4096);
        }

        ActionIntent publicationIntent(CommandRequest request, String change) {
            return publicationIntent(request, "backend", change);
        }

        ActionIntent publicationIntent(CommandRequest request, String component, String change) {
            var actualServer = change.equals("server") ? new ServerId("foreign") : server;
            return RemoteActions.command(actualServer, change.equals("business") ? "f".repeat(64) : candidates.get(component).artifactSha256(), request).withSemantics(new ActionSemantics(
                "deployment.publication.publish.v1", Set.of(new ActionTarget(actualServer.id(), "deployment-release",
                    change.equals("target") ? "/opt/foreign" : part(component).unit().releaseRoot()),
                    new ActionTarget(actualServer.id(), "deployment-candidate", layout.candidateRoot(candidates.get(component).candidateId())))));
        }

        DeploymentAgentHealthEvidence evidence() throws Exception {
            return evidence(proposal);
        }

        DeploymentAgentHealthEvidence partialEvidence() throws Exception {
            var scope = new DeploymentCommandReviewScope(context, context.interaction(), task, "FULL_CONTROL", true, () -> REVISION);
            return DeploymentAgentHealthEvidence.forOriginalPublicationObservation(scope, remote, server, task, REVISION, proposal, candidates, journal);
        }

        OperationResult<Void> observePartialPublication() throws Exception {
            var scope = new DeploymentCommandReviewScope(context, context.interaction(), task, "FULL_CONTROL", true, () -> REVISION);
            var execution = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
            return DeploymentAgentPublicationObservation.observe(scope, remote, server, task, REVISION, execution, proposal, candidates, journal);
        }

        DeploymentAgentHealthEvidence evidence(DeploymentPublishResult original) throws Exception {
            var scope = new DeploymentCommandReviewScope(context, context.interaction(), task, "FULL_CONTROL", true, () -> REVISION);
            return new DeploymentAgentHealthEvidence(scope, remote, server, task, REVISION, original, candidates, journal);
        }

        OperationResult<?> observe(DeploymentAgentHealthEvidence evidence) {
            return new DeploymentGraphPublisher(evidence.context(), evidence, server, task,
                (type, action, detail) -> journal.event(task, type, action, detail)).observeOriginal(proposal, candidates);
        }

        OperationResult<Void> verify(DeploymentAgentHealthEvidence evidence) {
            return new DeploymentGraphPublisher(evidence.context(), evidence, server, task,
                (type, action, detail) -> journal.event(task, type, action, detail)).verifyPublished(proposal, candidates);
        }

        OperationResult<Void> health(DeploymentAgentHealthEvidence evidence, DeploymentApplicationGraph.Part part, DeploymentHealthCheck check) throws ApiException {
            return new DeploymentPublishChannel(evidence, evidence.context().interaction(), server, task,
                candidates.get(part.componentId()).artifactSha256()).healthCheck(part.unit(), check);
        }

        DeploymentApplicationGraph.Part part(String component) {
            return proposal.graph().components().stream().filter(value -> value.componentId().equals(component)).findFirst().orElseThrow();
        }

        DeploymentApplicationGraph.Part requestPart(CommandRequest request) {
            return proposal.graph().components().stream().filter(value -> request.script()
                    .contains(DeploymentBuildCommandChannel.shellQuote(value.unit().id())) || request.script().contains(value.unit().releaseRoot()))
                .findFirst().orElseThrow();
        }

        String release(String component) {
            return proposal.units().stream().filter(value -> value.unit().equals(part(component).unit()))
                .findFirst().orElseThrow().release().sha256();
        }

        String status(String component) {
            boolean container = part(component).reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container;
            return "PUBLICATION_STATUS=1\nCURRENT=" + release(component) + "\nCURRENT_KIND=" + (container ? "container" : "deployment")
                + "\nCURRENT_MODE=DAEMON\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE="
                + maintenanceMarkers.getOrDefault(component, "none") + "\nCANDIDATE=owned\nBUILD_ACTIVE=no\nUNIT_PRESENT=yes\nCONTAINER_PRESENT="
                + (container ? "yes" : "not-applicable") + "\nRETAINED_COUNT=1\n";
        }

        CommandRequest runtimeRequest(String verb, String change) {
            var unit = part("backend").unit();
            String script = List.of(layout.helperPath(), verb,
                    change.equals("unit") ? "controlled-foreign" : unit.id(),
                    change.equals("manifest") ? "f".repeat(64) : unit.ownershipManifestSha256()).stream()
                .map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), change.equals("stdin") ? new byte[]{1} : new byte[0],
                Duration.ofSeconds(change.equals("timeout") ? 21 : 20), change.equals("output") ? 8192 : 4096);
        }

        ActionIntent runtimeIntent(CommandRequest request, String verb, String change) {
            String operation = change.equals("operation") ? "deployment.publication.health.v1"
                : verb.equals("inspect-runtime") ? "deployment.publication.inspect.v1" : "deployment.publication.observe.v1";
            return RemoteActions.command(server, ARTIFACT, request).withSemantics(new ActionSemantics(operation,
                Set.of(new ActionTarget(server.id(), "deployment-release", part("backend").unit().releaseRoot()))));
        }

        CommandRequest healthRequest(String change) {
            var unit = part("backend").unit();
            String payload = DeploymentWorkloadArguments.healthPayload(change.equals("payload")
                ? new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:9999/foreign"), 200, 120)
                : change.equals("kind") ? new DeploymentHealthCheck.Command(new DeploymentCommand("verify.sh", List.of()), "foreign", 120)
                : part("backend").reviewedRuntime().healthCheck());
            var arguments = new ArrayList<>(List.of(layout.helperPath(), "application-health",
                change.equals("unit") ? "controlled-foreign" : unit.id(),
                change.equals("manifest") ? "f".repeat(64) : unit.ownershipManifestSha256()));
            if (!change.equals("missing-payload")) arguments.add(payload);
            String script = arguments.stream()
                .map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), change.equals("stdin") ? new byte[]{1} : new byte[0],
                Duration.ofSeconds(change.equals("timeout") ? 90 : 165), change.equals("output") ? 8192 : 4096);
        }

        CommandRequest healthRequestWithPayload(String payload) {
            var unit = part("backend").unit();
            String script = List.of(layout.helperPath(), "application-health", unit.id(), unit.ownershipManifestSha256(), payload)
                .stream().map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), new byte[0],
                Duration.ofSeconds(part("backend").reviewedRuntime().healthCheck().timeoutSeconds() + 45L), 4096);
        }

        CommandRequest queryRequest(String change) {
            var unit = part("backend").unit();
            boolean container = part("backend").reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container;
            String script = List.of(layout.helperPath(), "publication-original-status", unit.id(),
                    change.equals("manifest") ? "f".repeat(64) : unit.ownershipManifestSha256(),
                    change.equals("task") ? UUID.randomUUID().toString() : task.id().toString(),
                    change.equals("candidate") ? unit.id() + "-fedcba9876543210" : candidates.get("backend").candidateId(),
                    change.equals("release") ? SOURCE : RELEASE, change.equals("backend") || container ? "container" : "native",
                    change.equals("engine") || container ? "docker" : "none").stream().map(DeploymentBuildCommandChannel::shellQuote)
                .collect(java.util.stream.Collectors.joining(" "));
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script, layout), new byte[0], Duration.ofSeconds(30), 4096);
        }

        ActionIntent queryIntent(CommandRequest request) {
            return RemoteActions.command(server, ARTIFACT, request).withSemantics(new ActionSemantics("deployment.publication.original-status.v1",
                Set.of(new ActionTarget(server.id(), "deployment-release", part("backend").unit().releaseRoot()),
                    new ActionTarget(server.id(), "deployment-candidate", layout.candidateRoot(candidates.get("backend").candidateId())),
                    new ActionTarget(server.id(), "deployment-snapshot", layout.snapshotRoot(part("backend").unit().id(), task.id())),
                    new ActionTarget(server.id(), "deployment-application-locks", layout.applicationLockRoot(part("backend").unit().id())))));
        }

        ActionIntent healthIntent(CommandRequest request, String change) {
            var actualServer = change.equals("server") ? new ServerId("foreign") : server;
            return RemoteActions.command(actualServer, change.equals("revision") ? SOURCE : ARTIFACT, request).withSemantics(
                new ActionSemantics("deployment.publication.health.v1", Set.of(new ActionTarget(actualServer.id(), "deployment-release",
                    change.equals("target") ? "/opt/foreign" : part("backend").unit().releaseRoot()))));
        }

        void changeBinding(String type) {
            try {
                if (type.equals("profile"))
                    profile = new ServerInfo(server, "controlled", "foreign-host", 22, "root", Optional.empty(), Optional.empty());
                else if (type.equals("route")) route = "f".repeat(64);
                else
                    journal.event(task, type, "backend", type.equals("CANDIDATE") ? "controlled-backend-fedcba9876543210"
                        : type.equals("SEALED_TOOLS") ? "f".repeat(64) + ":true" : "f".repeat(64));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        long events(String type) throws Exception {
            return journal.events(task).stream().filter(value -> value.type().equals(type)).count();
        }

        long healthReviews() {
            return reviewFacts.stream().filter(value -> value.path("operation").asText().equals("deployment.publication.health.v1")).count();
        }

        @Override
        public void close() throws SQLException {
            retained.close();
        }
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(String method, Object[] args) throws Throwable;
    }

    private static <T> T proxy(Class<T> type, Invocation callback) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> callback.call(method.getName(), args)));
    }
}
