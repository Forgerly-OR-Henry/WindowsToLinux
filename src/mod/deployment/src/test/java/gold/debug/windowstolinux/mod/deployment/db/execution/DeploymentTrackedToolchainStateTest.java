package gold.debug.windowstolinux.mod.deployment.db.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefKey;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.*;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import org.junit.jupiter.api.Test;

class DeploymentTrackedToolchainStateTest extends DeploymentExecutionFixture {
    @Test
    void refusalBeforeDispatchRetainsNotSentWithoutManufacturingUnknownEffects() throws Exception {
        var current = recipe(initial(UUID.randomUUID(), UUID.randomUUID()));
        var request = bind(current);
        current = append(current, request, "bind-tracked", "INTENT", null);
        current = append(current, request, "bind-tracked", "OBSERVED", null);
        var operation = state(current).operations().getFirst();
        assertEquals("NOT_SENT", operation.events().getLast().event());
        assertTrue(operation.settled());
        assertFalse(operation.dispatched("bind-tracked"));
        var refused = current;
        assertThrows(SQLException.class, () -> append(refused, request, "bind-tracked", "DISPATCHED", null));
    }

    @Test
    void legacyEnvelopeHasNoRecoveryEvidenceAndOptionalExtensionRemainsStrict() throws Exception {
        var original = initial(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(11, DeploymentExecutionCodec.version(original));
        assertTrue(DeploymentTrackedToolchainState.read(original, sha(recoveryIntent().encode())).isEmpty());
        var request = DeploymentJsonDocument.parse(original.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        request.putNull("trackedToolchains");
        assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(copy(original, request.toString())));
        request.putObject("trackedToolchains").put("formatVersion", 2).putArray("attempts");
        assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(copy(original, request.toString())));
    }

    @Test
    void originalBytesAndUnknownOutcomeSurviveObservationAndRejectReplayAndTampering() throws Exception {
        var current = recipe(initial(UUID.randomUUID(), UUID.randomUUID()));
        var identity = tracked(current);
        var generated = DeploymentTrackedToolchains.prepare(UUID.randomUUID(), current.identity().taskId().toString(), identity,
            "server", DeploymentRemoteLayout.original(), DeploymentToolchainType.NODE, "22", "22.1.0", 600, "-");
        byte[] exact = ("  " + new String(generated.bytes(), StandardCharsets.UTF_8) + " \n").getBytes(StandardCharsets.UTF_8);
        var request = DeploymentTrackedToolchains.decode(exact);
        current = append(current, request, "prepare-tracked", "INTENT", null);
        var unsent = state(current);
        assertTrue(unsent.allSettled());
        assertArrayEquals(exact, unsent.operations().getFirst().request().bytes());
        current = append(current, request, "prepare-tracked", "DISPATCHED", null);
        current = append(current, request, "prepare-tracked", "OBSERVED", null);
        assertFalse(state(current).allSettled());
        current = unknown(current);
        final var unresolved = current;
        assertThrows(SQLException.class, () -> append(unresolved, request, "prepare-tracked", "INTENT", null));
        assertThrows(SQLException.class, () -> append(unresolved, request, "prepare-tracked", "DISPATCHED", null));
        current = append(current, request, "observe-tracked", "INTENT", null);
        current = append(current, request, "observe-tracked", "DISPATCHED", null);
        current = append(current, request, "observe-tracked", "OBSERVED", prepared(request));
        assertEquals(Optional.of(TaskResult.UNKNOWN), current.outcome());
        assertEquals(unresolved.resultJson(), current.resultJson());
        assertTrue(state(current).allSettled());
        assertEquals(ExecutionStatus.FAILED, state(current).operations().getFirst().latestObservation().orElseThrow().executionStatus());
        assertArrayEquals(exact, state(current).operations().getFirst().request().bytes());
        var corrupted = DeploymentJsonDocument.parse(current.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        var operation = (ObjectNode) corrupted.path("trackedToolchains").path("attempts").get(0).path("operations").get(0);
        operation.put("requestSha256", "f".repeat(64));
        final var settled = current;
        assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(copy(settled, corrupted.toString())));
        operation.put("requestSha256", request.sha256());
        ((ObjectNode) operation.path("events").get(5).path("observation")).put("operationId", UUID.randomUUID().toString());
        assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(copy(settled, corrupted.toString())));
    }

    @Test
    void boundsDuplicateOperationsAndUnconfirmedCleanupAreRejected() throws Exception {
        var current = recipe(initial(UUID.randomUUID(), UUID.randomUUID()));
        var request = bind(current);
        current = append(current, request, "bind-tracked", "INTENT", null);
        current = append(current, request, "bind-tracked", "DISPATCHED", null);
        current = append(current, request, "bind-tracked", "OBSERVED", null);
        current = unknown(current);
        current = append(current, request, "cleanup-tracked", "INTENT", null);
        current = append(current, request, "cleanup-tracked", "DISPATCHED", null);
        final var cleanupUnknown = current;
        assertThrows(SQLException.class, () -> append(cleanupUnknown, request, "cleanup-tracked", "INTENT", null));
        assertFalse(state(current).allSettled());
        var payload = DeploymentJsonDocument.parse(current.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        var operations = (com.fasterxml.jackson.databind.node.ArrayNode) payload.path("trackedToolchains").path("attempts").get(0).path("operations");
        operations.add(operations.get(0).deepCopy());
        assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(copy(cleanupUnknown, payload.toString())));
        operations.remove(1);
        var events = (com.fasterxml.jackson.databind.node.ArrayNode) operations.get(0).path("events");
        while (events.size() <= 16) events.add(events.get(0).deepCopy());
        assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(copy(cleanupUnknown, payload.toString())));
        assertThrows(IllegalArgumentException.class, () -> new ScriptRecipe(2, 1, "", ""));
        assertThrows(IllegalArgumentException.class, () -> new ScriptRecipe(1, 1, "中".repeat(400000), ""));
    }

    @Test
    void validRequestWithAnotherTaskServerExecutionAttemptOrLayoutIsRejectedEvenWithRecomputedHash() throws Exception {
        var current = recipe(initial(UUID.randomUUID(), UUID.randomUUID()));
        var request = bind(current);
        current = append(current, request, "bind-tracked", "INTENT", null);
        final var original = current;
        List<java.util.function.Consumer<ObjectNode>> changes = List.of(
            node -> node.put("taskId", UUID.randomUUID().toString()),
            node -> node.put("executionId", UUID.randomUUID().toString()),
            node -> node.put("serverId", "another-server"),
            node -> node.put("unitId", "another-unit"),
            node -> node.put("recoveryAttempt", 2),
            node -> node.put("remoteLayout", new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
                "/srv/other-helper", "/srv/other-apps", "/srv/other-state", "/srv/other-config", "/srv/other-data")).canonical()));
        for (var change : changes) {
            var source = DeploymentJsonDocument.parse(new String(request.bytes(), StandardCharsets.UTF_8));
            change.accept(source);
            var forged = DeploymentTrackedToolchains.decode(source.toString().getBytes(StandardCharsets.UTF_8));
            var payload = DeploymentJsonDocument.parse(current.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
            ((ObjectNode) payload.path("trackedToolchains").path("attempts").get(0).path("operations").get(0))
                .put("requestBase64", Base64.getEncoder().encodeToString(forged.bytes())).put("requestSha256", forged.sha256());
            assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(copy(original, payload.toString())));
        }
        var oversized = DeploymentJsonDocument.parse(current.requestJson(), DeploymentExecutionCodec.READ_LIMIT);
        ((ObjectNode) oversized.path("trackedToolchains").path("attempts").get(0).path("operations").get(0))
            .put("requestBase64", Base64.getEncoder().encodeToString(new byte[DeploymentTrackedToolchains.MAX_REQUEST_BYTES + 1]));
        assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(copy(original, oversized.toString())));
    }

    @Test
    void reconciliationConsumesOnlyVerifiedOriginalBindingAndKeepsRawUnknownResult() throws Exception {
        var current = recipe(initial(UUID.randomUUID(), UUID.randomUUID()));
        var request = bind(current);
        current = append(current, request, "bind-tracked", "INTENT", null);
        current = append(current, request, "bind-tracked", "DISPATCHED", null);
        current = append(current, request, "bind-tracked", "OBSERVED", null);
        current = unknown(current);
        var review = review(recipeValue().render(tools("22.1.0")), encodedTools("22.1.0"));
        final var unresolved = current;
        assertThrows(SQLException.class, () -> DeploymentTrackedToolchainState.reconcile(unresolved, review));
        current = append(current, request, "observe-tracked", "INTENT", null);
        current = append(current, request, "observe-tracked", "DISPATCHED", null);
        current = append(current, request, "observe-tracked", "OBSERVED", bound(request));
        final var verified = current;
        assertThrows(SQLException.class, () -> DeploymentTrackedToolchainState.reconcile(verified,
            review("unreviewed free script", encodedTools("22.1.0"))));
        assertThrows(SQLException.class, () -> DeploymentTrackedToolchainState.reconcile(verified,
            review(recipeValue().render(tools("22.2.0")), encodedTools("22.2.0"))));
        String payload = DeploymentTrackedToolchainState.reconcile(current, review);
        var old = current.identity();
        var nextIdentity = new DeploymentExecutionIdentity(old.executionId(), old.taskId(), old.projectId(), old.applicationId(),
            old.server(), old.mode(), review.nextRoot());
        var reconciled = new DeploymentExecutionRecord(nextIdentity, current.phase(), current.outcome(), payload,
            DeploymentExecutionCodec.recovery(nextIdentity, current.phase(), DeploymentExecutionCodec.known(current).ids()),
            current.resultJson(), false, false, current.createdAt(), current.updatedAt());
        DeploymentExecutionCodec.known(reconciled);
        assertEquals(verified.resultJson(), reconciled.resultJson());
        assertEquals(Optional.of(TaskResult.UNKNOWN), reconciled.outcome());
        assertTrue(DeploymentExecutionCodec.buildIntent(reconciled).isEmpty());
        assertEquals(List.of(review), DeploymentExecutionCodec.buildReviews(reconciled));
        var forgedResult = DeploymentJsonDocument.parse(reconciled.resultJson().orElseThrow());
        forgedResult.put("eventCode", "FORGED_UNKNOWN");
        assertThrows(SQLException.class, () -> DeploymentExecutionCodec.known(new DeploymentExecutionRecord(nextIdentity,
            reconciled.phase(), reconciled.outcome(), reconciled.requestJson(), reconciled.recoveryJson(), Optional.of(forgedResult.toString()),
            false, false, reconciled.createdAt(), reconciled.updatedAt())));
    }

    @Test
    void journalPersistsCasUnderOriginalTaskAndDoesNotClearUnknownOutcome() throws Exception {
        enable();
        var retained = new java.util.concurrent.atomic.AtomicReference<DeploymentExecutionRecord>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                UUID id = UUID.randomUUID();
                var initial = initial(task.id(), id);
                var journal = journal(runtime);
                var ref = DeploymentRefKey.unit(new ServerId("server"), "service");
                journal.begin(task, initial.identity(), "deployment-publication", List.of(ref), Optional.empty(), OBSERVED);
                var reserved = runtime.modContexts().get(OWNER).context().servers().reserve(new ServerId("server"), ref.businessKey(), task);
                journal.recordReference(task, id, ref, reserved.id(), OBSERVED.plusSeconds(1));
                journal.startEffects(task, id, OBSERVED.plusSeconds(2));
                journal.recordBuildIntent(task, id, recoveryIntent(), OBSERVED.plusSeconds(3));
                var tracked = tracked(initial);
                journal.recordTracked(task, id, tracked, null, null, "RECIPE", null, recipeValue(), OBSERVED.plusSeconds(4));
                var bind = bind(initial);
                journal.recordTracked(task, id, tracked, bind, "bind-tracked", "INTENT", null, null, OBSERVED.plusSeconds(5));
                journal.recordTracked(task, id, tracked, bind, "bind-tracked", "DISPATCHED", null, null, OBSERVED.plusSeconds(6));
                journal.recordTracked(task, id, tracked, bind, "bind-tracked", "OBSERVED", null, null, OBSERVED.plusSeconds(7));
                var review = review(recipeValue().render(tools("22.1.0")), encodedTools("22.1.0"));
                assertThrows(SQLException.class, () -> journal.reconcileBuildReview(task, id, review, OBSERVED.plusSeconds(8)));
                journal.recordUncommittedOutcome(task, id, TaskResult.UNKNOWN, problem(TaskResult.UNKNOWN, "TRACKED_UNKNOWN"), OBSERVED.plusSeconds(8));
                var unknown = journal.find(task, id).orElseThrow();
                journal.recordTracked(task, id, tracked, bind, "observe-tracked", "INTENT", null, null, OBSERVED.plusSeconds(9));
                journal.recordTracked(task, id, tracked, bind, "observe-tracked", "DISPATCHED", null, null, OBSERVED.plusSeconds(10));
                journal.recordTracked(task, id, tracked, bind, "observe-tracked", "OBSERVED", bound(bind), null, OBSERVED.plusSeconds(11));
                journal.reconcileBuildReview(task, id, review, OBSERVED.plusSeconds(12));
                var reconciled = journal.find(task, id).orElseThrow();
                assertEquals(unknown.resultJson(), reconciled.resultJson());
                assertEquals(unknown.outcome(), reconciled.outcome());
                assertEquals(unknown.phase(), reconciled.phase());
                assertEquals(journal.references(unknown), journal.references(reconciled));
                assertThrows(SQLException.class, () -> journal.reconcileBuildReview(task, id, review, OBSERVED.plusSeconds(13)));
                assertEquals(reconciled, journal.find(task, id).orElseThrow());
                retained.set(reconciled);
                return success();
            });
            valid(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var journal = journal(runtime);
                var original = retained.get();
                assertEquals(original, journal.find(task, original.identity().executionId()).orElseThrow());
                assertTrue(journal.trackedToolchains(original, sha(recoveryIntent().encode())).orElseThrow().allSettled());
                assertThrows(SQLException.class, () -> journal.recordTracked(task, original.identity().executionId(), tracked(original),
                    null, null, "RECIPE", null, recipeValue(), OBSERVED.plusSeconds(14)));
                assertEquals(original, journal.find(task, original.identity().executionId()).orElseThrow());
                return success();
            });
            valid(runtime);
        }
    }

    private DeploymentExecutionRecord initial(UUID task, UUID execution) throws Exception {
        var identity = new DeploymentExecutionIdentity(execution, task, Optional.empty(), "service", new ServerId("server"),
            DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED, description(OBSERVED).reviewedRevision());
        var ref = DeploymentRefKey.unit(new ServerId("server"), "service");
        String request = DeploymentExecutionCodec.request(identity, "deployment-publication", List.of(ref), Optional.empty());
        var record = new DeploymentExecutionRecord(identity, "EXECUTING", Optional.empty(), request,
            DeploymentExecutionCodec.recovery(identity, "EXECUTING", Map.of(0, UUID.randomUUID())), Optional.empty(), false, false, OBSERVED, OBSERVED);
        return copy(record, DeploymentExecutionCodec.withBuildIntent(record, recoveryIntent()));
    }

    private DeploymentAssistedBuildReviewCodec.Description description(Instant at) {
        var component = component("service", "server", List.of());
        var configuration = DeploymentConfigSnapshot.create("service", 1, "1", OBSERVED, List.of());
        var source = Map.of("snapshot", "12345678-1234-1234-1234-123456789012", "manifest", "b".repeat(64),
            "archive", "a".repeat(64), "relativeRoot", ".", "bytes", "1", "uncompressedBytes", "1");
        var facts = Map.of("projectType", "NODE_SERVICE", "buildTool", "NPM", "buildDirectory", component.reviewedRuntime().workload().buildDirectory(),
            "language", "JavaScript", "support", "DIRECT", "architecture", "x86_64", "framework", "node");
        var hashFacts = new LinkedHashMap<>(facts); hashFacts.put("applicationId", "service");
        var limits = new DeploymentConfig.Build(1800, 512, 2048, 8_388_608, 8_589_934_592L).limits();
        String digest = DeploymentReviewedPlanDigest.from(new DeploymentReviewedPlanDigest.Values("server", UUID.fromString(source.get("snapshot")),
            source.get("manifest"), source.get("archive"), 1, 1, hashFacts, List.of(), configuration, List.of(), component.resources(),
            component.reviewedRuntime(), Optional.empty(), limits, at, false, false));
        return new DeploymentAssistedBuildReviewCodec.Description("service", "server", source, facts, List.of(), Optional.empty(), configuration,
            List.of(), component.resources(), component.reviewedRuntime(), limits, List.of("build"),
            Map.of("primary", "", "secondary", "", "version", "22", "jvmTarget", ""), at, false, false, digest);
    }

    private DeploymentAssistedBuildIntent recoveryIntent() throws Exception {
        var before = description(OBSERVED); var after = description(OBSERVED.plusSeconds(1));
        return new DeploymentAssistedBuildIntent(1, before.reviewedRevision(), after.reviewedRevision(), "", "service",
            DeploymentAssistedBuildReviewCodec.encodeDescription(before), DeploymentAssistedBuildReviewCodec.encodeDescription(after), "old script",
            encodedTools("22.0.0"), failure(), 1, "c".repeat(64), "d".repeat(64), OBSERVED);
    }

    private DeploymentAssistedBuildReview review(String script, String tools) throws Exception {
        var intent = recoveryIntent();
        return new DeploymentAssistedBuildReview(1, intent.previousRoot(), intent.nextRoot(), "", "service", intent.previousPlan(), intent.nextPlan(),
            intent.previousScript(), script, intent.previousTools(), tools,
            DeploymentAssistedBuildReview.failureBinding(intent.previousPlan(), intent.previousScript(), intent.previousTools(), intent.failureEvidence()),
            intent.failureEvidence(), "e".repeat(64), intent.approvalAction(), intent.modelRevision(), OBSERVED);
    }

    private String failure() {
        return DeploymentJsonDocument.object().put("formatVersion", 1).put("exit", 1).put("bytes", 12).put("outputSHA", "f".repeat(64))
            .put("uid", "1001").put("engine", "ordinary").put("tool", "NPM").put("diagnosticSHA", "e".repeat(64)).put("event", "DEPLOYMENT_BUILD_FAILED").toString();
    }

    private DeploymentResolvedToolchains tools(String version) {
        var requirement = ToolchainRequirement.declared(DeploymentToolchainType.NODE, "22", "package.json", ToolchainRequirement.PurposeType.BUILD);
        return new DeploymentResolvedToolchains("tracked-test", List.of(new DeploymentResolvedToolchains.Selection(requirement,
            DeploymentToolchainVersion.parse(DeploymentToolchainType.NODE, version).orElseThrow(), "/opt/node-" + version,
            DeploymentResolvedToolchains.OriginType.MANAGED, "https://nodejs.org/", "a".repeat(64))));
    }
    private String encodedTools(String version) { return DeploymentToolchainBindingCodec.encode(tools(version)); }
    private ScriptRecipe recipeValue() { return new ScriptRecipe(1, 1, "set -e\n", "\nnode --version\n"); }
    private Identity tracked(DeploymentExecutionRecord record) throws Exception {
        return new Identity(record.identity().executionId().toString(), "service", 1, sha(recoveryIntent().encode()), "b".repeat(64));
    }
    private Request bind(DeploymentExecutionRecord record) throws Exception {
        return DeploymentTrackedToolchains.bind(UUID.randomUUID(), record.identity().taskId().toString(), tracked(record), "server", DeploymentRemoteLayout.original(),
            Base64.getEncoder().encodeToString(encodedTools("22.1.0").getBytes(StandardCharsets.UTF_8)));
    }
    private DeploymentTrackedToolchainState state(DeploymentExecutionRecord record) throws Exception {
        return DeploymentTrackedToolchainState.read(record, sha(recoveryIntent().encode())).orElseThrow();
    }
    private DeploymentExecutionRecord recipe(DeploymentExecutionRecord record) throws Exception {
        return copy(record, DeploymentTrackedToolchainState.append(record, tracked(record), null, null, "RECIPE", null, recipeValue(), record.updatedAt()));
    }
    private DeploymentExecutionRecord append(DeploymentExecutionRecord record, Request request, String verb, String event, JsonNode observation) throws Exception {
        String payload = DeploymentTrackedToolchainState.append(record, tracked(record), request, verb, event, observation, null, record.updatedAt());
        var next = copy(record, payload); DeploymentExecutionCodec.known(next); return next;
    }
    private DeploymentExecutionRecord unknown(DeploymentExecutionRecord record) throws Exception {
        return new DeploymentExecutionRecord(record.identity(), record.phase(), Optional.of(TaskResult.UNKNOWN), record.requestJson(), record.recoveryJson(),
            Optional.of(DeploymentExecutionCodec.result(record.identity(), TaskResult.UNKNOWN, Optional.of(ProblemCode.UNKNOWN), "TRACKED_UNKNOWN", List.of(), Optional.empty())),
            false, false, record.createdAt(), record.updatedAt());
    }
    private DeploymentExecutionRecord copy(DeploymentExecutionRecord record, String request) {
        return new DeploymentExecutionRecord(record.identity(), record.phase(), record.outcome(), request, record.recoveryJson(), record.resultJson(),
            record.businessCommitted(), record.cleanupComplete(), record.createdAt(), record.updatedAt());
    }
    private ObjectNode prepared(Request request) {
        var result = observation(request, "FAILED", "PREPARED_VERIFIED", "FAILED", "VERIFIED");
        result.put("failureCategory", "interrupted");
        result.putObject("selection").put("ecosystem", "NODE").put("version", "22.1.0").put("directory", "/opt/node-22.1.0")
            .put("origin", "MANAGED").put("source", "https://nodejs.org/").put("sha256", "a".repeat(64));
        return result;
    }
    private ObjectNode bound(Request request) {
        return observation(request, "UNKNOWN", "BINDING_VERIFIED", "COMPLETED", "NOT_NEEDED")
            .put("bindingSha256", DeploymentToolchainBindingCodec.identity(request.binding()));
    }
    private ObjectNode observation(Request request, String execution, String effect, String phase, String cleanup) {
        var result = DeploymentJsonDocument.object().put("formatVersion", 1).put("operationId", request.operationId().toString()).put("taskId", request.taskId())
            .put("requestSha256", request.sha256()).put("originalIntentSha256", request.identity().originalIntentSha256()).put("kind", request.kind().name())
            .put("executionStatus", execution).put("effectState", effect).put("phase", phase).putNull("selection").putNull("bindingSha256")
            .putNull("failureCategory").put("cleanupState", cleanup);
        result.putArray("retainedEffects"); return result;
    }
    private static String sha(String value) { return DeploymentTrackedToolchainState.sha(value); }
}
