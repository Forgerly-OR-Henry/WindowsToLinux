package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.net.URI;
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
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.schema.DeploymentSchemaIdentity;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.linux.runtime.DeploymentWorkloadArguments;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
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
            assertEquals("agent-assembled-helper-source-evidence.v1", current.at("/helperSourceEvidence/schema").asText());
            assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, current.at("/helperSourceEvidence/helperSha256").asText());
            assertTrue(current.at("/helperSourceEvidence/sideEffects").asText().contains("not strictly read-only"));
            assertTrue(current.at("/helperSourceEvidence/listenerLimits").asText().contains("does not bind the particular HTTP"));
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
            hashedFacts.remove("helperSourceEvidence");
            assertNotEquals(evidenceDigest, DeploymentAgentInputs.hash(DeploymentAiJson.facts(hashedFacts, 1048576)));
            for (var review : frame.reviewFacts) {
                String operation = review.path("operation").asText();
                assertEquals(operation.equals("deployment.publication.health.v1"), review.path("currentCommandEvidence").has("helperSourceEvidence"));
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

    private record ActionCheck(String expectedAction, String actualAction) {
    }

    private static final class Frame implements AutoCloseable {
        private final String jdbc = "jdbc:h2:mem:health-evidence-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

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

        private final Map<String, DeploymentGraphPublisher.Candidate> candidates = new LinkedHashMap<>();

        private final DeploymentPublishResult proposal;

        private final ModContext context;

        private final RemoteSession remote;

        private final DeploymentAgentActionLog journal;

        private final List<JsonNode> reviewFacts = new ArrayList<>();

        private final List<String> statusActions = new ArrayList<>();

        private final List<ActionCheck> healthActions = new ArrayList<>();

        private final List<ActionCheck> runtimeActions = new ArrayList<>();

        private final Map<String, String> maintenanceMarkers = new LinkedHashMap<>();

        private String statusFailure = "none";

        private String deniedOperation = "none";

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

        private int statusQueries;

        private int healthDispatches;

        private int runtimeInspections;

        private int runtimeObservations;

        private int containerObservations;

        private int hostAuthorizations;

        Frame() throws Exception {
            this("HTTP");
        }

        Frame(String healthKind) throws Exception {
            try (var source = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/db/schema-v1.sql")), StandardCharsets.UTF_8)) {
                org.h2.tools.RunScript.execute(retained, source);
            }
            var db = proxy(ModDatabase.class, (name, args) -> name.equals("identity")
                ? new SchemaIdentity(DeploymentSchemaIdentity.OWNER, "1", taskId) : DriverManager.getConnection(jdbc));
            journal = new DeploymentAgentActionLog(db);
            remote = proxy(RemoteSession.class, (name, args) -> {
                if (name.equals("facts")) return remoteFacts;
                assertEquals("execute", name);
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
                    return success(new CommandResult(Optional.of(0), output, "", statusFailure.equals("truncated")));
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
                assertEquals(Optional.of("submit_result"), request.requiredToolName());
                var facts = JSON.readTree(request.messages().getLast().text());
                reviewFacts.add(facts);
                String operation = facts.path("operation").asText();
                if (operation.equals("deployment.publication.original-status.v1")) onStatusReview.accept(facts);
                if (operation.equals("deployment.publication.health.v1")) onHealthReview.accept(facts);
                if (Set.of("deployment.publication.inspect.v1", "deployment.publication.observe.v1").contains(operation))
                    onRuntimeReview.accept(facts);
                boolean deny = operation.equals(deniedOperation);
                return success(new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    List.of(new AiToolCall("review-" + reviewFacts.size(), "submit_result", JSON.writeValueAsString(Map.of("decision", deny ? "DENY" : "APPROVE",
                        "requiresUserConfirmation", deny, "reason", deny ? DENIAL : "Controlled original health binding")))), Optional.empty()));
            });
            var host = proxy(InteractionService.class, (name, args) -> {
                assertTrue(Set.of("approve", "approveReviewedAction").contains(name), name);
                hostAuthorizations++;
                if (reviewFacts.getLast().path("operation").asText().equals("deployment.publication.original-status.v1"))
                    onStatusHost.run();
                if (reviewFacts.getLast().path("operation").asText().equals("deployment.publication.health.v1"))
                    onHealthHost.run();
                if (Set.of("deployment.publication.inspect.v1", "deployment.publication.observe.v1").contains(
                    reviewFacts.getLast().path("operation").asText())) onRuntimeHost.run();
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
            var parts = new ArrayList<DeploymentApplicationGraph.Part>();
            var units = new ArrayList<DeploymentUnitCommit>();
            for (String component : List.of("backend", "frontend")) {
                boolean backend = component.equals("backend");
                var unit = DeploymentUnitIdentity.forManaged("controlled-" + component, server, (backend ? "b" : "9").repeat(64));
                DeploymentHealthCheck health = component.equals("backend") ? switch (healthKind) {
                    case "TCP" -> new DeploymentHealthCheck.Tcp(18101, 120, 1);
                    case "PROCESS" -> new DeploymentHealthCheck.Process(120, 1);
                    case "COMMAND" ->
                        new DeploymentHealthCheck.Command(new DeploymentCommand("verify.sh", List.of("complete-input")), "controlled-output", 120);
                    case "UDP" ->
                        new DeploymentHealthCheck.Udp(18101, "", "", Optional.of(new DeploymentCommand("probe.sh", List.of("complete-probe-input"))), 120);
                    default -> new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:18101/healthz"), 200, 120);
                } : new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:18100/healthz"), 200, 120);
                var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true, new DeploymentCommand("run.sh", List.of()),
                    "", List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
                var policy = healthKind.equals("CONTAINER") && backend ? DeploymentIdentityMode.CONTAINER_NON_ROOT : DeploymentIdentityMode.SYSTEMD_STATIC;
                DeploymentRuntimeDefinition runtime = policy == DeploymentIdentityMode.CONTAINER_NON_ROOT
                    ? new DeploymentRuntimeDefinition.Container(DeploymentRuntimeDefinition.ContainerEngineType.DOCKER, Map.of(18101, 18101), List.of(), health, policy, workload)
                    : new DeploymentRuntimeDefinition.ManagedProcess(health, policy, workload);
                var config = new DeploymentUnitRuntimeConfig(health, Optional.empty(), policy, workload);
                parts.add(new DeploymentApplicationGraph.Part(component, unit, config, List.of(), runtime, List.of(),
                    new DeploymentResourceBindings(List.of(), Optional.of(List.of()))));
                units.add(new DeploymentUnitCommit(unit, config, new DeploymentCurrentRelease(unit.id(), backend ? RELEASE : "7".repeat(64), Instant.EPOCH),
                    DeploymentConfigSnapshot.create(unit.id(), 1, "v1", Instant.EPOCH, List.of()), List.of()));
                candidates.put(component, new DeploymentGraphPublisher.Candidate(unit.id() + "-0123456789abcdef", backend ? ARTIFACT : "8".repeat(64), SOURCE, "0".repeat(64), false, Map.of()));
            }
            var whole = new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:18100/"), 200, 120);
            proposal = new DeploymentPublishResult(new DeploymentApplicationGraph("controlled", "frontend", whole, parts), units);
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), DeploymentAgentInputs.targetDigest(server, remote), REVISION, "FULL_CONTROL", true));
            journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(profile));
            journal.event(task, "SOURCE", ARCHIVE, SOURCE);
            for (String component : candidates.keySet()) {
                journal.event(task, "CANDIDATE", component, candidates.get(component).candidateId());
                journal.event(task, "CURRENT_SOURCE", component, SOURCE);
                journal.event(task, "SEALED_ARTIFACT", component, candidates.get(component).artifactSha256());
                journal.event(task, "SEALED_TOOLS", component, "0".repeat(64) + ":false");
            }
            journal.event(task, "DELIVERY_EXECUTING", "f".repeat(64), "1");
            journal.event(task, "AI_PUBLICATION", "controlled", "MODEL_GENERATED_COMMANDS");
            journal.event(task, "AI_PUBLICATION_VERIFIED", "controlled", "HEALTHY");
            journal.event(task, "AI_PUBLICATION_FINISHED", "controlled", "MODEL_ACCEPTED_VERIFIED_RESULT");
            journal.event(task, "CANDIDATE_CLEANUP_EXECUTING", "backend", candidates.get("backend").candidateId());
            var executions = new DeploymentExecutionStore(db);
            var id = new DeploymentExecutionIdentity(UUID.randomUUID(), taskId, Optional.empty(), "controlled", server, DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, SOURCE);
            var references = DeploymentOriginalPublishCommit.references(server, proposal, Map.of());
            executions.begin(task, id, "deployment-agent", references, Optional.empty(), Instant.EPOCH);
            for (var reference : references)
                executions.recordReference(task, id.executionId(), reference, UUID.randomUUID(), Instant.EPOCH);
            executions.startEffects(task, id.executionId(), Instant.EPOCH.plusSeconds(1));
            executions.recordPublicationTemplate(task, id.executionId(), new DeploymentPublishProposal(proposal.graph(), proposal.units()), Instant.EPOCH.plusSeconds(2));
            executions.recordUncommittedOutcome(task, id.executionId(), TaskResult.UNKNOWN,
                new Problem(ProblemCode.UNKNOWN, "CLEANUP_UNCONFIRMED", "Retained original cleanup", Map.of(), Optional.empty()), Instant.EPOCH.plusSeconds(3));
            journal.state(task, "UNKNOWN");
        }

        DeploymentAgentHealthEvidence evidence() throws Exception {
            return evidence(proposal);
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
            String script = List.of(DeploymentManagedHelperBundle.PATH, verb,
                    change.equals("unit") ? "controlled-foreign" : unit.id(),
                    change.equals("manifest") ? "f".repeat(64) : unit.ownershipManifestSha256()).stream()
                .map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script), change.equals("stdin") ? new byte[]{1} : new byte[0],
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
            String script = List.of(DeploymentManagedHelperBundle.PATH, "application-health", unit.id(),
                    change.equals("manifest") ? "f".repeat(64) : unit.ownershipManifestSha256(), payload).stream()
                .map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script), change.equals("stdin") ? new byte[]{1} : new byte[0],
                Duration.ofSeconds(change.equals("timeout") ? 90 : 165), change.equals("output") ? 8192 : 4096);
        }

        CommandRequest queryRequest(String change) {
            var unit = part("backend").unit();
            boolean container = part("backend").reviewedRuntime() instanceof DeploymentRuntimeDefinition.Container;
            String script = List.of(DeploymentManagedHelperBundle.PATH, "publication-original-status", unit.id(),
                    change.equals("manifest") ? "f".repeat(64) : unit.ownershipManifestSha256(),
                    change.equals("task") ? UUID.randomUUID().toString() : task.id().toString(),
                    change.equals("candidate") ? unit.id() + "-fedcba9876543210" : candidates.get("backend").candidateId(),
                    change.equals("release") ? SOURCE : RELEASE, change.equals("backend") || container ? "container" : "native",
                    change.equals("engine") || container ? "docker" : "none").stream().map(DeploymentBuildCommandChannel::shellQuote)
                .collect(java.util.stream.Collectors.joining(" "));
            return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script), new byte[0], Duration.ofSeconds(30), 4096);
        }

        ActionIntent queryIntent(CommandRequest request) {
            return RemoteActions.command(server, ARTIFACT, request).withSemantics(new ActionSemantics("deployment.publication.original-status.v1",
                Set.of(new ActionTarget(server.id(), "deployment-release", part("backend").unit().releaseRoot()))));
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
