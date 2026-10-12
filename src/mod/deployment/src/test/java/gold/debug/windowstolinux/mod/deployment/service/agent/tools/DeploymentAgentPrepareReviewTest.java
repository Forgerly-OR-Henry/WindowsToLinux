package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentSession;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Actual H2 task ledger and native Agent roles, with local controlled model and transport only. */
class DeploymentAgentPrepareReviewTest extends DeploymentRuntimeFixture {
    private static final String REVISION = "a".repeat(64);

    private static final ObjectMapper JSON = new ObjectMapper();

    enum VerificationMode {
        APPROVE, DENY, INCOMPLETE_BODY, CONFIGURATION_CHANGED, CAPACITY_LOW, CAPACITY_TRUNCATED, CAPACITY_CANCELLED, CAPACITY_MISSING, CAPACITY_UNSETTLED, BUILD_SMALL, BUILD_LARGE, CAPACITY_AFTER_INSTALL, CAPACITY_INSTALL_UNKNOWN;

        boolean confirmedRefusal() {
            return this == CAPACITY_LOW || this == CAPACITY_AFTER_INSTALL;
        }
    }

    @ParameterizedTest
    @EnumSource(value = VerificationMode.class, names = {"APPROVE", "DENY", "INCOMPLETE_BODY", "CONFIGURATION_CHANGED"})
    void uploadedArchiveConsumerEvidenceReachesNativeReviewBeforeVerifyAndOpen(VerificationMode mode) throws Exception {
        runVerification(mode, false);
    }

    @ParameterizedTest
    @EnumSource(value = VerificationMode.class, names = {"CAPACITY_LOW", "CAPACITY_TRUNCATED", "CAPACITY_CANCELLED", "CAPACITY_MISSING", "CAPACITY_UNSETTLED"})
    void confirmedCapacityRefusalReturnsToMainButUnknownObservationDoesNot(VerificationMode mode) throws Exception {
        runVerification(mode, false);
    }

    @ParameterizedTest
    @EnumSource(value = VerificationMode.class, names = {"BUILD_SMALL", "BUILD_LARGE"})
    void actualAgentRecipeUsesItsAcceptedQuotaInCreateBuildAndBusinessReplan(VerificationMode mode) throws Exception {
        runVerification(mode, false);
    }

    @ParameterizedTest
    @EnumSource(value = VerificationMode.class, names = {"CAPACITY_AFTER_INSTALL", "CAPACITY_INSTALL_UNKNOWN"})
    void capacityAfterConfirmedOrUnknownInstallMaintainsOriginalEffectBoundary(VerificationMode mode) throws Exception {
        runVerification(mode, false);
    }

    @Test
    void uploadedArchiveConsumerEvidenceRejectsUnboundRequests() throws Exception {
        runVerification(VerificationMode.APPROVE, true);
    }

    private void runVerification(VerificationMode mode, boolean unbound) throws Exception {
        var source = Files.createDirectories(root.resolve("verification-source"));
        Files.writeString(source.resolve("index.html"), "<html>actual controlled source</html>\n");
        if (mode.name().startsWith("BUILD_"))
            Files.writeString(Files.createDirectories(source.resolve("public")).resolve("index.html"), "<html>actual output</html>\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(gold.debug.windowstolinux.app.service.server.ServerProfile.fromStored(
                new gold.debug.windowstolinux.app.db.server.StoredServerProfile("native-host", "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new java.util.concurrent.CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("native-candidate-verification", "Controlled original candidate verification", List.of("native-host"), true), task -> {
                try {
                    var selection = actual.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task);
                         var archive = new DeploymentSourceArchiver(actual.file()).prepare(material, 1048576, task)) {
                        var frame = new VerificationFrame(actual, task, material, archive, mode);
                        if (unbound) frame.unbound();
                        else frame.run();
                    }
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    throw error;
                } finally {
                    try {
                        actual.file().closeTaskResources(task);
                    } finally {
                        finished.complete(null);
                    }
                }
            });
            try {
                finished.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                handle.requestCancel();
                throw timeout;
            }
            if (failure.get() != null)
                throw new AssertionError("Controlled candidate verification failed", failure.get());
        }
    }

    private final class VerificationFrame {
        final TaskContext task;

        final DeploymentSourceMaterial material;

        final DeploymentSourceArchive archive;

        final VerificationMode mode;

        final ServerId server = new ServerId("native-host");

        final DeploymentRemoteWorkspace workspace;

        final DeploymentAgentActionLog journal;

        final RemoteSession peer;

        final ModContext context;

        final AtomicReference<String> revision = new AtomicReference<>(REVISION);

        final IllegalStateException stop = new IllegalStateException("Controlled read after completed prepare");

        final Map<String, Integer> offsets = new HashMap<>();

        final Map<String, String> parts = new HashMap<>();

        String pageBinding = "";

        int decisions, approvalCalls, pages, creates, uploads, verifyGrants, verifySends, opens, verifyVerdicts, capacityReads, readinessReads, installs;

        long selectedQuota;

        String buildScript = "";

        boolean incompleteRejected;

        String verifiedRequest = "";

        VerificationFrame(ModContext actual, TaskContext task, DeploymentSourceMaterial material, DeploymentSourceArchive archive, VerificationMode mode) {
            this.task = task;
            this.material = material;
            this.archive = archive;
            this.mode = mode;
            workspace = new DeploymentRemoteWorkspace("demo-api", archive.sha256());
            journal = new DeploymentAgentActionLog(actual.db());
            peer = proxy(RemoteSession.class, (p, m, a) -> switch (m.getName()) {
                case "facts" -> new RemoteFacts("Linux", "x86_64", "SHA256:native-controlled");
                case "close" -> null;
                case "execute" -> transport((CommandRequest) a[0]);
                case "upload" -> {
                    var transfer = (TransferRequest) a[0];
                    uploads++;
                    assertEquals(1, creates);
                    assertEquals(archive.path(), transfer.localPath());
                    assertEquals(workspace.candidateRoot() + "/mutable/source.tar.gz", transfer.remoteAbsolutePath());
                    yield succeeded(new TransferResult(archive.byteCount(), archive.sha256(), transfer.localPath(), transfer.remoteAbsolutePath()));
                }
                default -> throw new AssertionError("No real transport: " + m.getName());
            });
            var ai = proxy(AiService.class, (p, m, a) -> {
                if (m.getName().equals("configurationRevision")) return revision.get();
                assertEquals("call", m.getName());
                assertSame(task, a[a.length - 1]);
                var request = (AiRequest) a[0];
                return succeeded(request.purpose() == AiPurpose.APPROVAL ? approve(request) : decide(request));
            });
            var host = proxy(InteractionService.class, (p, m, a) -> {
                if (m.getName().equals("approveReviewedAction")) {
                    var intent = (ActionIntent) a[0];
                    if (intent.semantics().orElseThrow().operation().equals("deployment.candidate.verify.v1")) {
                        assertTrue(mode == VerificationMode.APPROVE || mode.name().startsWith("BUILD_"));
                        assertEquals(1, verifyVerdicts);
                        assertEquals(1, creates);
                        assertEquals(1, uploads);
                        assertEquals(0, verifySends);
                        verifyGrants++;
                        verifiedRequest = intent.exactAction();
                    }
                }
                try {
                    return m.invoke(actual.interaction(), a);
                } catch (InvocationTargetException error) {
                    throw error.getCause();
                }
            });
            context = proxy(ModContext.class, (p, m, a) -> {
                if (m.getName().equals("ai")) return ai;
                if (m.getName().equals("interaction")) return host;
                try {
                    return m.invoke(actual, a);
                } catch (InvocationTargetException error) {
                    throw error.getCause();
                }
            });
        }

        DeploymentAgentRemoteTools remote() throws Exception {
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "FULL_CONTROL", true));
            journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
            return new DeploymentAgentRemoteTools(context, peer, context.interaction(), server, task, archive, material,
                REVISION, () -> revision.get(), journal, Optional.empty(), "FULL_CONTROL", true);
        }

        void run() throws Exception {
            try (var tools = remote(); var authorization = DeploymentWorkflowAuthorization.open(context, task, server, REVISION, "FULL_CONTROL", true,
                "source=" + material.snapshot().sha256() + "\narchive=" + archive.sha256())) {
                var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Observe actual source"),
                    new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()), tools, journal,
                    "FULL_CONTROL", true, REVISION, () -> revision.get());
                if (mode == VerificationMode.CAPACITY_UNSETTLED)
                    journal.event(task, "CAPABILITY_EXECUTING", "c".repeat(64), "earlier-unconfirmed");
                var error = assertThrows(Exception.class, session::run);
                if (mode.name().startsWith("CAPACITY_")) {
                    assertEquals(0, creates + uploads + opens);
                    assertEquals(mode == VerificationMode.CAPACITY_AFTER_INSTALL || mode == VerificationMode.CAPACITY_INSTALL_UNKNOWN ? 1 : 0, installs);
                    var events = journal.events(task);
                    assertFalse(events.stream().anyMatch(event -> Set.of("PREPARE_RESULT", "CANDIDATE", "PREPARE_NOT_SENT").contains(event.type())));
                    if (mode.confirmedRefusal()) {
                        assertSame(stop, error);
                        assertEquals(5, decisions, "The main model independently received refusal and selected its next tool");
                        var rejection = events.stream().filter(event -> event.type().equals(DeploymentAgentCandidateCapacityException.EVENT)).findFirst().orElseThrow();
                        selectedQuota = JSON.readTree(rejection.detail()).path("workspaceBytes").longValue();
                        assertTrue(DeploymentAgentCandidateCapacityException.matches(rejection, events));
                        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
                        var changed = new DeploymentAgentActionLog.Event(rejection.id(), rejection.type(), rejection.action(), rejection.detail().replace("\"workspaceBytes\":" + selectedQuota, "\"workspaceBytes\":" + (selectedQuota + 1)));
                        assertFalse(DeploymentAgentCandidateCapacityException.matches(changed, events));
                        for (String key : List.of("task", "component", "source", "configuration", "plan", "prepareAction", "capacityRequest")) {
                            var altered = (ObjectNode) JSON.readTree(rejection.detail());
                            altered.put(key, "foreign");
                            assertFalse(DeploymentAgentCandidateCapacityException.matches(new DeploymentAgentActionLog.Event(rejection.id(), rejection.type(), rejection.action(), altered.toString()), events));
                        }
                        var oldAttempt = new ArrayList<>(events);
                        oldAttempt.add(new DeploymentAgentActionLog.Event(rejection.id() + 100, "PREPARE_EXECUTING", rejection.action(), "api"));
                        var stale = new DeploymentAgentActionLog.Event(rejection.id() + 101, rejection.type(), rejection.action(), rejection.detail());
                        oldAttempt.add(stale);
                        assertFalse(DeploymentAgentCandidateCapacityException.matches(stale, oldAttempt));
                        var duplicate = new ArrayList<>(events);
                        duplicate.add(new DeploymentAgentActionLog.Event(rejection.id() + 1000, rejection.type(), rejection.action(), rejection.detail()));
                        assertFalse(DeploymentAgentActionLog.effectsSettled(duplicate));
                    } else {
                        assertEquals(4, decisions);
                        assertEquals(mode == VerificationMode.CAPACITY_UNSETTLED, events.stream().anyMatch(event -> event.type().equals(DeploymentAgentCandidateCapacityException.EVENT)));
                        assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                    }
                    return;
                }
                if (creates != 1) throw new AssertionError("Original prepare did not reach candidate creation", error);
                assertEquals(1, creates);
                assertEquals(1, uploads);
                assertEquals(mode == VerificationMode.APPROVE || mode.name().startsWith("BUILD_") ? 1 : 0, verifyGrants);
                assertEquals(verifyGrants, verifySends);
                assertEquals(verifySends, opens);
                assertTrue(pages > 0, "Actual native review receives complete executable pages");
                var events = journal.events(task);
                assertEquals(1, events.stream().filter(e -> e.type().equals("PREPARE_EXECUTING")).count());
                assertFalse(events.stream().anyMatch(e -> Set.of("PREPARE_NOT_SENT", "PREPARE_DISPATCH_NOT_SENT").contains(e.type())));
                if (mode == VerificationMode.APPROVE || mode.name().startsWith("BUILD_")) {
                    assertSame(stop, error);
                    assertTrue(events.stream().anyMatch(e -> e.type().equals("PREPARE_RESULT")));
                    assertTrue(events.stream().anyMatch(e -> e.type().equals("CURRENT_SOURCE")));
                    if (mode.name().startsWith("BUILD_")) {
                        assertTrue(buildScript.contains(Long.toString(selectedQuota)), "Actual recipe build uses the accepted candidate quota");
                        var field = DeploymentAgentSession.class.getDeclaredField("candidates");
                        field.setAccessible(true);
                        @SuppressWarnings("unchecked") var owned = (Map<String, DeploymentAgentDeliveryPort.Prepared>) field.get(session);
                        var candidate = owned.get("api");
                        assertEquals(selectedQuota, candidate.quota().bytes());
                        var query = DeploymentAgentSession.class.getDeclaredMethod("planPage", gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProposal.PlanState.class);
                        query.setAccessible(true);
                        Object page = query.invoke(session, new gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProposal.PlanState("api", 0, 4000));
                        var output = page.getClass().getDeclaredMethod("output");
                        output.setAccessible(true);
                        assertEquals(selectedQuota, JSON.readTree((String) output.invoke(page)).at("/workspace/bytes").longValue());
                        var planField = DeploymentAgentSession.class.getDeclaredField("plan");
                        planField.setAccessible(true);
                        var accepted = (DeploymentAgentPlan) planField.get(session);
                        assertEquals(selectedQuota, tools.selectWorkspaces(accepted, owned).getFirst().workspaceSelection().orElseThrow().bytes(), "Same demand business replan preserves quota at lower available capacity");
                        var component = accepted.components().getFirst();
                        var sameBytes = new DeploymentAgentPlan.Part(component.id(), component.sourceRoot(), component.dependencies(), component.evidence(), component.runtime(), component.resources(),
                            component.configuration(), component.databaseIds(), component.secretReferences(), Optional.of(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.automatic(0, 1073741824)), Optional.empty());
                        assertEquals(selectedQuota, tools.selectWorkspaces(new DeploymentAgentPlan(accepted.applicationId(), accepted.healthComponentId(), accepted.health(), List.of(sameBytes)), owned)
                            .getFirst().workspaceSelection().orElseThrow().bytes(), "Revised preferred headroom cannot resize an existing candidate or consume its quota twice");
                        var different = new DeploymentAgentPlan.Part(component.id(), component.sourceRoot(), component.dependencies(), component.evidence(), component.runtime(), component.resources(),
                            component.configuration(), component.databaseIds(), component.secretReferences(), Optional.of(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.explicit(selectedQuota + 67108864)), Optional.empty());
                        assertThrows(IllegalArgumentException.class, () -> tools.selectWorkspaces(new DeploymentAgentPlan(accepted.applicationId(), accepted.healthComponentId(), accepted.health(), List.of(different)), owned));
                        var recover = gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentRecovery.class.getDeclaredMethod("originalWorkspaces", UUID.class, DeploymentAgentActionLog.Identity.class,
                            List.class, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.class);
                        recover.setAccessible(true);
                        var identity = journal.find(task).orElseThrow().identity();
                        assertTrue(((Optional<?>) recover.invoke(null, task.id(), identity, events, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original())).isPresent());
                        var missing = events.stream().filter(event -> !event.type().equals("CANDIDATE_QUOTA")).toList();
                        assertTrue(((Optional<?>) recover.invoke(null, task.id(), identity, missing, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original())).isEmpty());
                        var changed = events.stream().map(event -> event.type().equals("CANDIDATE_QUOTA") ? new DeploymentAgentActionLog.Event(event.id(), event.type(), event.action(), event.detail().replace("\"workspaceBytes\":" + selectedQuota, "\"workspaceBytes\":" + (selectedQuota + 1))) : event).toList();
                        assertTrue(((Optional<?>) recover.invoke(null, task.id(), identity, changed, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original())).isEmpty());
                        for (String key : List.of("source", "plan", "reserveBytes")) {
                            var damaged = new ArrayList<DeploymentAgentActionLog.Event>();
                            for (var event : events) {
                                if (Set.of("CANDIDATE_QUOTA", "WORKSPACE_QUOTA").contains(event.type())) {
                                    var data = (ObjectNode) JSON.readTree(event.detail());
                                    if (key.equals("reserveBytes")) data.remove(key);
                                    else data.put(key, "f".repeat(64));
                                    damaged.add(new DeploymentAgentActionLog.Event(event.id(), event.type(), event.action(), data.toString()));
                                } else damaged.add(event);
                            }
                            assertTrue(((Optional<?>) recover.invoke(null, task.id(), identity, damaged, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original())).isEmpty(), key);
                        }
                    }
                } else {
                    var api = assertInstanceOf(ApiException.class, error);
                    assertEquals(mode == VerificationMode.CONFIGURATION_CHANGED ? "DEPLOYMENT_COMMAND_CONFIGURATION_CHANGED" : "DEPLOYMENT_COMMAND_REVIEW_DENIED", api.problem().eventCode());
                    assertTrue(events.stream().anyMatch(e -> e.type().equals("STATE") && e.detail().equals("UNKNOWN")));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                    assertFalse(events.stream().anyMatch(e -> Set.of("PREPARE_RESULT", "CANDIDATE", "CURRENT_SOURCE").contains(e.type())));
                    if (mode == VerificationMode.INCOMPLETE_BODY) assertTrue(incompleteRejected);
                }
            }
        }

        void unbound() throws Exception {
            try (var tools = remote()) {
                var channel = new DeploymentCandidateChannel(peer, context.interaction(), server, task, REVISION, new DeploymentBuildBudget(7200));
                var spec = channel.verification(workspace, archive);
                try (var correct = new CommandRequest(spec.guardedScript(), new byte[0], Duration.ofSeconds(30), 4096)) {
                    var original = verificationIntent(correct);
                    var evidence = tools.verificationEvidence(spec, workspace);
                    assertThrows(IllegalArgumentException.class, () -> new CommandRequest(spec.guardedScript(), new byte[0], Duration.ZERO, 4096));
                    for (String changed : List.of("BODY", "GUARD", "STDIN", "OUTPUT", "TIMEOUT_HIGH", "TIMEOUT_NANOS", "TARGET", "OPERATION", "REVISION", "TASK")) {
                        String script = changed.equals("BODY") ? spec.guardedScript() + "\ntrue\n" : changed.equals("GUARD") ? spec.script() : spec.guardedScript();
                        Duration timeout = changed.equals("TIMEOUT_HIGH") ? Duration.ofSeconds(31) : changed.equals("TIMEOUT_ZERO") ? Duration.ZERO : changed.equals("TIMEOUT_NANOS") ? Duration.ofSeconds(29).plusNanos(1) : Duration.ofSeconds(30);
                        try (var bad = new CommandRequest(script, changed.equals("STDIN") ? new byte[]{1} : new byte[0], timeout, changed.equals("OUTPUT") ? 4097 : 4096)) {
                            var intent = verificationIntent(bad);
                            if (changed.equals("TARGET"))
                                intent = intent.withSemantics(new ActionSemantics("deployment.candidate.verify.v1", Set.of(new ActionTarget(server.id(), "deployment-candidate", workspace.candidateRoot() + "-foreign"))));
                            if (changed.equals("OPERATION"))
                                intent = intent.withSemantics(new ActionSemantics("deployment.candidate.query.v1", intent.semantics().orElseThrow().targets()));
                            if (changed.equals("REVISION"))
                                intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, "d".repeat(64), bad).withSemantics(original.semantics().orElseThrow());
                            var actualIntent = intent;
                            TaskContext supplied = changed.equals("TASK") ? proxy(TaskContext.class, (p, m, a) -> task.id()) : task;
                            assertThrows(ApiException.class, () -> evidence.facts(actualIntent, bad, supplied), changed);
                        }
                    }
                    assertThrows(ApiException.class, () -> evidence.verify(original, correct, task), "Invalid first requests cannot become the original binding");
                    var facts = JSON.valueToTree(evidence.facts(original, correct, task));
                    assertEquals(spec.expectedOutput(), facts.at("/consumer/expectedStdout").asText());
                    evidence.verify(original, correct, task);
                    try (var changed = new CommandRequest(spec.guardedScript(), new byte[0], Duration.ofSeconds(29), 4096)) {
                        assertThrows(ApiException.class, () -> evidence.facts(verificationIntent(changed), changed, task));
                        var clipped = tools.verificationEvidence(spec, workspace);
                        assertEquals(29, JSON.valueToTree(clipped.facts(verificationIntent(changed), changed, task)).at("/request/timeoutSeconds").asInt());
                        clipped.verify(verificationIntent(changed), changed, task);
                    }
                    var copy = new DeploymentSourceArchive(archive.snapshot(), archive.sha256(), archive.byteCount() + 1,
                        archive.uncompressedByteCount(), archive.originSnapshotId(), archive.sourceManifestSha256(), archive.relativeRoot());
                    assertThrows(ApiException.class, () -> tools.verificationEvidence(channel.verification(workspace, copy), workspace));
                    var otherSha = archive.sha256().substring(0, 16) + (archive.sha256().substring(16).equals("d".repeat(48)) ? "e" : "d").repeat(48);
                    var other = new DeploymentSourceArchive(archive.snapshot(), otherSha, archive.byteCount(), archive.uncompressedByteCount(), archive.originSnapshotId(), archive.sourceManifestSha256(), archive.relativeRoot());
                    assertThrows(ApiException.class, () -> tools.verificationEvidence(channel.verification(workspace, other), workspace));
                    assertThrows(ApiException.class, () -> tools.verificationEvidence(spec, new DeploymentRemoteWorkspace("demo-api", archive.sha256())));
                    revision.set("e".repeat(64));
                    assertThrows(ApiException.class, () -> evidence.verify(original, correct, task));
                }
                assertEquals(0, creates + uploads + verifyGrants + verifySends + opens + approvalCalls);
            }
        }

        ActionIntent verificationIntent(CommandRequest request) {
            return gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, REVISION, request)
                .withSemantics(new ActionSemantics("deployment.candidate.verify.v1", Set.of(new ActionTarget(server.id(), "deployment-candidate", workspace.candidateRoot()))));
        }

        AiResponse approve(AiRequest request) throws Exception {
            approvalCalls++;
            var input = JSON.readTree(request.messages().getLast().text());
            boolean verification = input.at("/currentRequest/operation").asText().equals("deployment.candidate.verify.v1");
            if (verification) {
                var facts = input.at("/currentRequest/currentCommandEvidence");
                assertEquals("NONE", facts.path("authority").asText());
                assertEquals("UPLOADED_ARCHIVE_BEFORE_AGENT_OPEN", facts.path("phase").asText());
                assertEquals(archive.byteCount(), facts.at("/capturedArchive/bytes").asLong());
                assertEquals(archive.sha256(), facts.at("/capturedArchive/sha256").asText());
                assertEquals(material.snapshot().id().toString(), facts.at("/capturedArchive/originSnapshotId").asText());
                assertEquals(material.snapshot().sha256(), facts.at("/capturedArchive/sourceManifestSha256").asText());
                assertEquals(expectedOutput(), facts.at("/consumer/expectedStdout").asText());
                assertEquals("NO_CURRENT_SOURCE_RECEIPT", input.at("/identityRoles/protectedTreeAvailability").asText());
                assertEquals(0, verifyGrants + verifySends + opens);
                if (mode == VerificationMode.INCOMPLETE_BODY) {
                    if (verifyVerdicts++ == 0) return verdict("APPROVE");
                    assertTrue(request.messages().stream().flatMap(m -> m.toolResult().stream()).anyMatch(r -> r.text().contains("EXECUTABLE_INPUT_NOT_DELIVERED")));
                    incompleteRejected = true;
                    return verdict("DENY");
                }
            } else
                assertTrue(input.at("/currentRequest/currentCommandEvidence").isMissingNode(), "Only this actual verify carries its consumer evidence");
            var page = bodyPage(request, input);
            if (page != null) return page;
            if (verification) {
                verifyVerdicts++;
                if (mode == VerificationMode.DENY) return verdict("DENY");
                if (mode == VerificationMode.CONFIGURATION_CHANGED) revision.set("e".repeat(64));
            }
            return verdict("APPROVE");
        }

        AiResponse verdict(String decision) {
            var result = JSON.createObjectNode().put("decision", decision).put("requiresUserConfirmation", false)
                .put("reason", "Independent controlled current request assessment");
            if (decision.equals("DENY")) result.put("discussionStatus", "CLOSED");
            return nativeCall("v" + approvalCalls, "submit_result", result);
        }

        AiResponse bodyPage(AiRequest request, JsonNode input) throws Exception {
            String binding = input.path("reviewBinding").asText();
            if (!binding.equals(pageBinding)) {
                pageBinding = binding;
                offsets.clear();
                parts.clear();
            }
            var prior = request.messages().stream().flatMap(m -> m.toolResult().stream()).reduce((a, b) -> b);
            if (prior.isPresent() && parts.containsKey(prior.orElseThrow().callId())) {
                var result = prior.orElseThrow();
                assertFalse(result.error());
                var envelope = JSON.readTree(result.text());
                assertEquals(binding, envelope.path("reviewBinding").asText());
                String part = parts.get(result.callId());
                assertEquals(input.at("/executableInput/" + part + "/sha256"), envelope.at("/value/sha256"));
                offsets.put(part, envelope.at("/value/next").asInt());
            }
            for (String part : List.of("script", "stdin")) {
                var original = input.at("/executableInput/" + part);
                int offset = offsets.getOrDefault(part, 0);
                if (original.has("text") || offset >= original.path("total").asInt()) continue;
                String id = "verification_page_" + ++pages;
                parts.put(id, part);
                return nativeCall(id, "request", JSON.createObjectNode().put("part", part).put("offset", offset).put("limit", 8000));
            }
            return null;
        }

        AiResponse decide(AiRequest request) throws Exception {
            request.messages().stream().flatMap(m -> m.toolResult().stream()).reduce((a, b) -> b)
                .ifPresent(result -> {
                    if (mode.confirmedRefusal() && decisions == 4) {
                        assertTrue(result.error());
                        assertTrue(result.text().contains("WORKSPACE_HOST_INSUFFICIENT"));
                        assertTrue(result.text().contains("availableBytes"));
                        assertTrue(result.text().contains("requiredBytes"));
                    } else assertFalse(result.error(), result.text());
                });
            int turn = decisions++;
            if (mode.confirmedRefusal() && turn == 4) throw stop;
            String tool;
            ObjectNode args;
            if (turn == 0) {
                tool = "read";
                args = JSON.createObjectNode().put("path", "index.html").put("offset", 0).put("limit", 100);
            } else if (turn == 1) {
                tool = "identify";
                args = (ObjectNode) JSON.readTree("{\"components\":[{\"component\":\"api\",\"root\":\".\",\"technology\":\"Actual static HTML\",\"runtime\":\"Source observation\",\"resources\":\"No database required\",\"uncertainties\":\"Unverified candidate\",\"evidence\":[\"o1\"]}]}");
            } else if (turn == 2) {
                tool = "plan";
                args = (ObjectNode) JSON.readTree("{\"applicationId\":\"demo\",\"explanation\":\"Plan the read source\",\"components\":[{\"id\":\"api\",\"sourceRoot\":\".\",\"dependencies\":[],\"evidence\":[\"o1\"],\"backend\":\"PROCESS\",\"mode\":\"DAEMON\",\"entrypoint\":\"current.sh\",\"arguments\":[],\"workingDirectory\":\"\",\"health\":{\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:8080/\",\"status\":200,\"timeout\":10},\"ports\":[],\"resources\":[],\"configuration\":[],\"databases\":[],\"secretReferences\":[]}],\"applicationHealth\":{\"component\":\"api\",\"health\":{\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:8080/\",\"status\":200,\"timeout\":10}}}");
            } else if (mode.name().startsWith("BUILD_") && turn == 3) {
                tool = "analyze";
                args = JSON.createObjectNode().put("component", "api").put("type", "STATIC_SITE");
            } else if (mode.name().startsWith("BUILD_") && turn == 4) {
                tool = "prepare_tools";
                args = JSON.createObjectNode().put("component", "api");
                args.putObject("recipe").put("version", "").put("primary", "public").put("secondary", "").put("jvmTarget", "").put("servicePort", 8080);
            } else if (mode.name().startsWith("BUILD_") && turn == 5) {
                tool = "build";
                args = JSON.createObjectNode().put("component", "api").put("seconds", 60);
            } else {
                assertEquals(mode.name().startsWith("BUILD_") ? 6 : 3, turn);
                tool = "remote_read";
                args = JSON.createObjectNode().put("component", "api").put("path", "index.html").put("offset", 0).put("limit", 1);
            }
            if (tool.equals("plan")) {
                ((ObjectNode) args.path("components").get(0)).putObject("workspace").put("mode", "AUTO")
                    .put("minimumBuildBytes", 0).put("preferredBuildBytes", mode == VerificationMode.BUILD_LARGE ? 536870912 : 134217728).put("explicitBytes", 0);
            }
            if (Set.of("plan", "prepare_tools", "build").contains(tool)) {
                var assessment = args.putObject("assessment");
                var basis = assessment.putArray("basis");
                var pending = JSON.readTree(request.messages().getLast().text()).at("/state/judgement/pending");
                if (pending.isEmpty()) basis.add("o1");
                else pending.forEach(id -> basis.add(id.asText()));
                assessment.put("suitability", "Use original observations").put("differences", "Candidate not yet observed").put("resolution", "RESOLVED").put("decision", "Read the source through this original proposal");
            }
            return nativeCall("verification_model_" + decisions, tool, args);
        }

        String expectedOutput() {
            return "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + archive.byteCount() + "\nSHA256=" + archive.sha256() + "\nOWNER=0\n";
        }

        OperationResult<CommandResult> transport(CommandRequest request) throws Exception {
            if (new String(request.stdin(), StandardCharsets.UTF_8).equals(DeploymentAgentHelperReadiness.script())) {
                readinessReads++;
                if (readinessReads == 1 && (mode == VerificationMode.CAPACITY_AFTER_INSTALL || mode == VerificationMode.CAPACITY_INSTALL_UNKNOWN))
                    return reply("FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=MISSING\nREASON=HELPER_MISSING\nPACKAGED_PROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nHELPER=ABSENT\nBUILD_ENTRY=ABSENT\nDEPENDENCIES=ABSENT\nROOTS=VALID_OR_ABSENT\nRECEIPTS=ABSENT\n");
                return reply("FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=READY\nREASON=CURRENT_TASK_READY\nPACKAGED_PROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nHELPER=MATCH\nBUILD_ENTRY=MATCH\nDEPENDENCIES=MATCH\nROOTS=VALID_OR_ABSENT\nRECEIPTS=SETTLED\n");
            }
            if (request.script().equals(DeploymentAgentHelperInstallerScript.LAUNCHER)) {
                installs++;
                if (mode == VerificationMode.CAPACITY_INSTALL_UNKNOWN)
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_INSTALL_UNKNOWN", "Controlled installation receipt is unknown", Map.of(), Optional.of(task.id()))));
                return reply("HELPER_INSTALL=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            }
            if (request.script().contains("managed-helper' probe"))
                return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            if (request.script().equals(DeploymentAgentHostProbe.baseline()))
                return reply("FORMAT=WTL_AGENT_BASELINE_1\nDISTRO_ID=ubuntu\nDISTRO_VARIANT=\nVERSION=24.04\nARCH=x86_64\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\n");
            if (request.script().contains("stat -f -c '%a %S'")) {
                capacityReads++;
                if (capacityReads == 2 && mode.name().startsWith("CAPACITY_")) {
                    if (mode == VerificationMode.CAPACITY_TRUNCATED)
                        return succeeded(new CommandResult(Optional.of(0), "1 4096\n", "", true));
                    if (mode == VerificationMode.CAPACITY_CANCELLED)
                        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
                    if (mode == VerificationMode.CAPACITY_MISSING)
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    return reply("1 4096\n");
                }
                return reply(mode.name().startsWith("BUILD_") && capacityReads > 2 ? "1 4096\n" : "1000000000 4096\n");
            }
            if (request.script().contains("printf 'BYTES='")) {
                verifySends++;
                assertEquals(1, verifyGrants);
                assertEquals(verifiedRequest, verificationIntent(request).exactAction());
                return reply(expectedOutput());
            }
            if (request.script().contains(" candidate-create-task ")) {
                creates++;
                var quota = java.util.regex.Pattern.compile("candidate-create-task '[^']+' '[^']+' ([0-9]+)").matcher(request.script());
                assertTrue(quota.find());
                selectedQuota = Long.parseLong(quota.group(1));
                assertEquals(mode == VerificationMode.BUILD_LARGE ? 671088640L : 268435456L, selectedQuota);
                return reply("CANDIDATE=" + workspace.candidateRoot() + "\n");
            }
            if (request.script().contains(" candidate-query-task "))
                return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n");
            if (request.script().contains("'agent-open'")) {
                opens++;
                assertEquals(1, verifySends);
                return reply("SOURCE_REVISION=" + material.snapshot().sha256() + "\n");
            }
            if (request.script().contains("'agent-run'")) {
                buildScript = new String(request.stdin(), StandardCharsets.UTF_8);
                return reply("BUILD=completed\n");
            }
            String script = request.script();
            String program = new String(request.stdin(), StandardCharsets.UTF_8);
            if (program.equals(gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.dependencies())) {
                var match = java.util.regex.Pattern.compile("'PYTHON' '([0-9a-f]{32})'$").matcher(script);
                assertTrue(match.find());
                assertEquals(gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.command(List.of("PYTHON", match.group(1))), script);
                var packages = new TreeMap<String, String>();
                gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.packages("APT", "PYTHON").forEach(name -> packages.put(name, "controlled-version"));
                return reply("TOOLCHAIN_DEPENDENCIES=" + JSON.writeValueAsString(Map.of("operation", match.group(1),
                    "ecosystem", "PYTHON", "manager", "APT", "packages", packages)) + "\n");
            }
            if (program.equals(gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.resolver())) {
                var match = java.util.regex.Pattern.compile("'resolve' 'PYTHON' '([^']+)' '([^']+)' '([0-9]+)' '([0-9a-f]{32})'$").matcher(script);
                assertTrue(match.find());
                assertEquals(gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.command(List.of("resolve", "PYTHON", match.group(1),
                    match.group(2), match.group(3), match.group(4))), script);
                return reply("TOOLCHAIN_RELEASE=" + gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainReleaseTest.encoded("PYTHON", match.group(1),
                    match.group(2), match.group(4)) + "\n");
            }
            if (program.equals(gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.controller()) && script.contains("'prepare' 'PYTHON'")) {
                var match = java.util.regex.Pattern.compile("'prepare' 'PYTHON' '([^']+)' '([^']+)' '([0-9]+)' '-' '([0-9a-f]{32})' '([A-Za-z0-9+/=]+)'$").matcher(script);
                assertTrue(match.find());
                assertEquals(gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms.command(List.of("prepare", "PYTHON", match.group(1),
                    match.group(2), match.group(3), "-", match.group(4), match.group(5))), script);
                var release = JSON.readTree(Base64.getDecoder().decode(match.group(5)));
                assertEquals(match.group(4), release.path("operation").asText());
                return reply("TOOLCHAIN=PYTHON|" + release.path("version").asText()
                    + "|/usr/local/lib/windowstolinux/toolchains/versions/python-" + release.path("digest").asText()
                    + "|MANAGED|" + release.path("url").asText() + "|" + release.path("digest").asText() + "\n");
            }
            if (request.script().contains("'bind'")) {
                var binding = java.util.regex.Pattern.compile("'bind' '([^']+)'").matcher(request.script());
                assertTrue(binding.find());
                var tools = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.decode(
                    new String(Base64.getDecoder().decode(binding.group(1)), StandardCharsets.UTF_8));
                return reply("TOOLCHAIN_BINDING=" + gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.identity(tools) + "\n");
            }
            if (request.script().contains("'agent-read'")) {
                assertEquals(1, opens);
                throw stop;
            }
            throw new AssertionError("Unrequested controlled operation");
        }
    }

    @Override
    protected gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts interactionPrompts() {
        return proxy(gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts.class, (p, m, a) -> {
            if (m.getName().equals("approve")) return true;
            throw new AssertionError("No text, transfer or unrequested interaction");
        });
    }

    @Test
    void openInstallRefusalDeliversBoundReplyThenReviewsCurrentSelectionBeforeFirstInstallSend() throws Exception {
        runDiscussion(false);
    }

    @Test
    void closedInstallRefusalCannotReopenFromReplyReplanNonceOrIdenticalReadiness() throws Exception {
        runDiscussion(true);
    }

    @Test
    void onlyCapturedOriginalNativeDeniedVerdictsQualifyForAlternativeFeedback() {
        UUID id = UUID.randomUUID();
        TaskContext task = proxy(TaskContext.class, (p, m, a) -> id);
        for (String status : List.of("OPEN", "CLOSED")) {
            var data = new HashMap<>(Map.of("task", id.toString(), "reviewBinding", "b".repeat(64), "discussionStatus", status));
            var denied = new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Actual review data", data, Optional.of(id)));
            var stopped = new DeploymentAgentPrepareNotSentException(denied, task, "api", REVISION, new ServerId("native-host"), REVISION, REVISION, REVISION);
            assertSame(denied, stopped.originalFailure());
            assertSame(denied, stopped.getCause());
            assertSame(denied, stopped.nativeReviewDenial(task).orElseThrow());
            assertTrue(stopped.nativeReviewDenial(proxy(TaskContext.class, (p, m, a) -> id)).isEmpty());
            for (String field : List.of("task", "reviewBinding", "discussionStatus")) {
                var invalid = new HashMap<>(data);
                invalid.put(field, "unbound");
                var fake = new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Unbound review data", invalid, Optional.of(id)));
                assertTrue(new DeploymentAgentPrepareNotSentException(fake, task, "api", REVISION, new ServerId("native-host"), REVISION, REVISION, REVISION)
                    .nativeReviewDenial(task).isEmpty());
            }
            for (var problem : List.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Unknown", data, Optional.of(id)),
                new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_INVALID", "Invalid", data, Optional.of(id))))
                assertTrue(new DeploymentAgentPrepareNotSentException(new ApiException(problem), task, "api", REVISION, new ServerId("native-host"), REVISION, REVISION, REVISION)
                    .nativeReviewDenial(task).isEmpty());
        }
    }

    private void runDiscussion(boolean closed) throws Exception {
        var source = Files.createDirectories(root.resolve("native-source"));
        Files.writeString(source.resolve("index.html"), "<html>actual local source</html>\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(gold.debug.windowstolinux.app.service.server.ServerProfile.fromStored(
                new gold.debug.windowstolinux.app.db.server.StoredServerProfile("native-host", "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new java.util.concurrent.CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("native-prepare-review", "Bound local native refusal discussion", List.of("native-host"), true), task -> {
                try {
                    var selection = actual.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task);
                         var archive = new DeploymentSourceArchiver(actual.file()).prepare(material, 1048576, task)) {
                        new Frame(actual, task, material, archive, closed).run();
                    }
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    throw error;
                } finally {
                    try {
                        actual.file().closeTaskResources(task);
                    } finally {
                        finished.complete(null);
                    }
                }
            });
            try {
                finished.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                handle.requestCancel();
                throw timeout;
            }
            if (failure.get() != null)
                throw new AssertionError("Controlled native prepare discussion failed", failure.get());
        }
    }

    private final class Frame {
        final TaskContext task;

        final DeploymentSourceMaterial material;

        final DeploymentSourceArchive archive;

        final boolean closed;

        final ServerId server = new ServerId("native-host");

        final DeploymentAgentActionLog journal;

        final RemoteSession peer;

        final ModContext context;

        final List<String> installerRequests = new ArrayList<>();

        final IllegalStateException stop = new IllegalStateException("Fixture stopped after the independently approved installer");

        int decisions, approvalCalls, installReviews, installGrants, installSends, readiness;

        String deniedBinding = "";

        String pageBinding = "";

        final Map<String, Integer> pageOffsets = new java.util.HashMap<>();

        final Map<String, String> pageParts = new java.util.HashMap<>();

        int bodyPageCalls;

        Frame(ModContext actual, TaskContext task, DeploymentSourceMaterial material, DeploymentSourceArchive archive, boolean closed) {
            this.task = task;
            this.material = material;
            this.archive = archive;
            this.closed = closed;
            journal = new DeploymentAgentActionLog(actual.db());
            peer = proxy(RemoteSession.class, (p, m, a) -> switch (m.getName()) {
                case "facts" -> new RemoteFacts("Linux", "x86_64", "SHA256:native-controlled");
                case "close" -> null;
                case "execute" -> transport((CommandRequest) a[0]);
                default -> throw new AssertionError("No SSH or transfer operation: " + m.getName());
            });
            var ai = proxy(AiService.class, (p, m, a) -> {
                if (m.getName().equals("configurationRevision")) return REVISION;
                assertEquals("call", m.getName());
                assertSame(task, a[a.length - 1]);
                var request = (AiRequest) a[0];
                return succeeded(request.purpose() == AiPurpose.APPROVAL ? approve(request) : decide(request));
            });
            var host = proxy(InteractionService.class, (p, m, a) -> {
                if (m.getName().equals("approveReviewedAction")) {
                    var intent = (gold.debug.windowstolinux.appapi.task.action.ActionIntent) a[0];
                    if (intent.semantics().orElseThrow().operation().equals("deployment.helper.install.files.custom.v1")) {
                        assertFalse(closed);
                        assertEquals(2, installReviews);
                        assertEquals(0, installSends);
                        installGrants++;
                    }
                }
                try {
                    return m.invoke(actual.interaction(), a);
                } catch (InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
            context = proxy(ModContext.class, (p, m, a) -> {
                if (m.getName().equals("ai")) return ai;
                if (m.getName().equals("interaction")) return host;
                try {
                    return m.invoke(actual, a);
                } catch (InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
        }

        void run() throws Exception {
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "FULL_CONTROL", true));
            journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
            try (var remote = new DeploymentAgentRemoteTools(context, peer, context.interaction(), server, task, archive, material,
                REVISION, () -> REVISION, journal, Optional.empty(), "FULL_CONTROL", true);
                 var authorization = DeploymentWorkflowAuthorization.open(context, task, server, REVISION, "FULL_CONTROL", true,
                     "source=" + material.snapshot().sha256() + "\narchive=" + archive.sha256())) {
                var selected = proxy(DeploymentAgentDeliveryPort.class, (p, m, a) -> {
                    if (m.getName().equals("prepare")) {
                        var component = (DeploymentAgentPlan.Part) a[1];
                        assertEquals(decisions == 4 || closed ? "rejected.sh" : "current.sh", component.runtime().workload().command().entrypoint(),
                            "Preparation receives the current independently chosen plan, never a cached rejected plan");
                    }
                    try {
                        return m.invoke(remote, a);
                    } catch (InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
                var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Build the actual source"),
                    new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()), selected, journal,
                    "FULL_CONTROL", true, REVISION, () -> REVISION);
                var error = assertThrows(Exception.class, session::run);
                assertEquals(closed ? 8 : 7, decisions);
                assertEquals(closed ? 1 : 2, installReviews);
                assertEquals(closed ? 0 : 1, installGrants);
                assertEquals(closed ? 0 : 1, installSends);
                if (!closed) {
                    assertSame(stop, error);
                    assertEquals(installerRequests.get(0), installerRequests.get(1), "A fresh attempt retains the exact immutable installation identity");
                }
                var events = journal.events(task);
                var first = events.stream().filter(e -> e.type().equals("PREPARE_EXECUTING")).findFirst().orElseThrow();
                assertTrue(events.stream().anyMatch(e -> e.type().equals("PREPARE_DISPATCH_NOT_SENT") && e.action().equals(first.action())));
                assertTrue(events.stream().anyMatch(e -> e.type().equals("PREPARE_NOT_SENT") && e.action().equals(first.action())
                    && e.detail().equals(DeploymentAgentPrepareNotSentException.DETAIL)));
                assertTrue(events.stream().anyMatch(e -> e.type().equals("AGENT_OBSERVATION") && e.action().equals("ALTERNATIVE_REQUIRED")));
                assertEquals(closed ? 0 : 1, events.stream().filter(e -> e.type().equals("AGENT_OBSERVATION") && e.action().equals("REVIEW_RESPONSE")).count());
                assertTrue(events.stream().noneMatch(e -> Set.of("CANDIDATE", "PREPARE_RESULT", "SEALED_ARTIFACT", "AI_PUBLICATION_VERIFIED").contains(e.type())));
                if (closed) assertTrue(DeploymentAgentActionLog.effectsSettled(events));
                else
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events), "Actually sent installation is never falsely settled by prior denial proof");
                assertTrue(readiness >= 2);
            }
        }

        AiResponse approve(AiRequest request) throws Exception {
            approvalCalls++;
            var input = JSON.readTree(request.messages().getLast().text());
            var facts = input.path("currentRequest");
            assertFalse(facts.isMissingNode(), "The actual native approval Agent supplies a fresh complete currentRequest");
            boolean install = facts.path("operation").asText().equals("deployment.helper.install.files.custom.v1");
            if (!install || installReviews > 0) {
                var page = executablePage(request, input);
                if (page != null) return page;
            }
            if (install) {
                installReviews++;
                assertEquals(0, installGrants);
                assertEquals(0, installSends);
                installerRequests.add(facts.path("exactAction").asText());
                if (installReviews == 1) {
                    deniedBinding = input.path("reviewBinding").asText();
                    return nativeCall("a" + approvalCalls, "submit_result", JSON.createObjectNode().put("decision", "DENY")
                        .put("requiresUserConfirmation", false).put("reason", "A remaining reachable evidence page needs independent review")
                        .put("discussionStatus", closed ? "CLOSED" : "OPEN").set("missingEvidence", JSON.createArrayNode().add("Approval Agent can read the remaining helper evidence page")));
                }
                assertFalse(closed);
                assertEquals(deniedBinding, input.path("discussion").path("priorReviewBinding").asText());
                assertFalse(input.path("discussion").path("sameMeaningfulFacts").asBoolean(), "The current changed plan is independently supplied alongside the historical reply");
                assertEquals("MODEL_AUTHORED_DATA", input.at("/discussion/response/trust").asText());
                assertEquals("NONE", input.at("/discussion/response/authority").asText());
                assertEquals("Review your reachable helper evidence pages against the current request", input.at("/discussion/response/message").asText());
                assertTrue(request.messages().stream().filter(m -> m.role() == AiRole.SYSTEM)
                    .noneMatch(m -> m.text().contains("Review your reachable helper evidence pages against the current request")));
            }
            return nativeCall("a" + approvalCalls, "submit_result", JSON.createObjectNode().put("decision", "APPROVE")
                .put("requiresUserConfirmation", false).put("reason", "Exact original locally controlled request"));
        }

        private AiResponse executablePage(AiRequest request, com.fasterxml.jackson.databind.JsonNode input) throws Exception {
            String binding = input.path("reviewBinding").asText();
            if (!binding.equals(pageBinding)) {
                pageBinding = binding;
                pageOffsets.clear();
                pageParts.clear();
            }
            var prior = request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((a, b) -> b);
            if (prior.isPresent() && pageParts.containsKey(prior.orElseThrow().callId())) {
                var result = prior.orElseThrow();
                assertFalse(result.error());
                var envelope = JSON.readTree(result.text());
                assertEquals(binding, envelope.path("reviewBinding").asText());
                String part = pageParts.get(result.callId());
                var original = input.path("executableInput").path(part);
                assertEquals(original.path("sha256"), envelope.at("/value/sha256"));
                assertEquals(original.path("total"), envelope.at("/value/total"));
                pageOffsets.put(part, envelope.at("/value/next").asInt());
            }
            for (String part : List.of("script", "stdin")) {
                var original = input.path("executableInput").path(part);
                int offset = pageOffsets.getOrDefault(part, 0);
                if (original.has("text") || offset >= original.path("total").asInt()) continue;
                if (input.at("/currentRequest/operation").asText().equals("deployment.helper.install.files.custom.v1")) {
                    assertEquals(0, installGrants);
                    assertEquals(0, installSends);
                }
                String id = "body_" + ++bodyPageCalls;
                pageParts.put(id, part);
                return nativeCall(id, "request", JSON.createObjectNode().put("part", part).put("offset", offset).put("limit", 8000));
            }
            return null;
        }

        AiResponse decide(AiRequest request) throws Exception {
            var state = JSON.readTree(request.messages().getLast().text()).path("state");
            int turn = decisions++;
            String tool;
            ObjectNode args;
            if (turn == 0) {
                tool = "read";
                args = JSON.createObjectNode().put("path", "index.html").put("offset", 0).put("limit", 100);
            } else if (turn == 1) {
                tool = "identify";
                args = (ObjectNode) JSON.readTree("{\"components\":[{\"component\":\"api\",\"root\":\".\",\"technology\":\"Actual static HTML\",\"runtime\":\"Review generated launcher\",\"resources\":\"No database required\",\"uncertainties\":\"Build unverified\",\"evidence\":[\"o1\"]}]}");
            } else if (turn == 2 || turn == 5) {
                tool = "plan";
                args = (ObjectNode) JSON.readTree("""
                    {"applicationId":"demo","explanation":"Plan the actually read source","components":[{"id":"api","sourceRoot":".","dependencies":[],"evidence":["o1"],
                    "backend":"PROCESS","mode":"DAEMON","entrypoint":"current.sh","arguments":[],"workingDirectory":"","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10},
                    "ports":[],"resources":[],"configuration":[],"databases":[],"secretReferences":[]}],"applicationHealth":{"component":"api","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10}}}
                    """);
                if (turn == 2 || closed) ((ObjectNode) args.path("components").get(0)).put("entrypoint", "rejected.sh");
            } else if (turn == 4) {
                assertEquals(0, installSends);
                assertEquals(0, installGrants);
                assertTrue(request.messages().stream().flatMap(m -> m.toolResult().stream()).anyMatch(r -> r.text().contains(deniedBinding)));
                tool = "review_response";
                args = JSON.createObjectNode().put("reviewBinding", deniedBinding)
                    .put("message", "Review your reachable helper evidence pages against the current request");
            } else if (turn == 3 || turn == 6) {
                tool = "command";
                args = JSON.createObjectNode().put("component", "api").put("seconds", 10)
                    .put("script", turn == 3 ? "printf rejected > $WTL_OUTPUT/rejected.sh" : "printf current > $WTL_OUTPUT/current.sh");
            } else {
                assertTrue(closed);
                assertEquals(7, turn);
                tool = "unable";
                args = JSON.createObjectNode().put("reason", "Original CLOSED denial remains unresolved");
            }
            if (Set.of("plan", "command").contains(tool)) {
                var assessment = args.putObject("assessment");
                var basis = assessment.putArray("basis");
                var pending = state.path("judgement").path("pending");
                if (pending.isEmpty()) basis.add("o1");
                else pending.forEach(id -> basis.add(id.asText()));
                assessment.put("suitability", "Use the current source and original result IDs").put("differences", "Earlier denial grants no authority")
                    .put("resolution", "RESOLVED").put("decision", "Choose this current complete proposal");
            }
            return nativeCall("d" + decisions, tool, args);
        }

        OperationResult<CommandResult> transport(CommandRequest request) {
            String body = new String(request.stdin(), StandardCharsets.UTF_8);
            if (body.equals(DeploymentAgentHelperReadiness.script())) {
                readiness++;
                return reply(installSends == 0 ? "FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=MISSING\nREASON=HELPER_MISSING\nPACKAGED_PROTOCOL="
                    + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nHELPER=ABSENT\nBUILD_ENTRY=ABSENT\nDEPENDENCIES=ABSENT\nROOTS=VALID_OR_ABSENT\nRECEIPTS=ABSENT\n"
                    : "FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=READY\nREASON=CURRENT_TASK_READY\nPACKAGED_PROTOCOL="
                    + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nHELPER=MATCH\nBUILD_ENTRY=MATCH\nDEPENDENCIES=MATCH\nROOTS=VALID_OR_ABSENT\nRECEIPTS=SETTLED\n");
            }
            if (request.script().equals(DeploymentAgentHelperInstallerScript.LAUNCHER)) {
                DeploymentAgentHelperInstallerScript.parseOperation(body);
                assertEquals(1, installGrants);
                assertEquals(2, installReviews);
                installSends++;
                return reply("HELPER_INSTALL=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            }
            if (request.script().contains(" probe\n"))
                return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION
                    + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            assertEquals(1, installSends);
            throw stop;
        }
    }

    private static AiResponse nativeCall(String id, String tool, ObjectNode args) {
        if (tool.equals("submit_result")) {
            for (String field : List.of("missingEvidence", "adjustments", "candidateCommands"))
                if (!args.has(field)) args.putArray(field);
        }
        return new AiResponse("", "local-controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall(id, tool, args.toString())), Optional.empty());
    }

    private static <T> OperationResult<T> succeeded(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static OperationResult<CommandResult> reply(String output) {
        return succeeded(new CommandResult(Optional.of(0), output, "", false));
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
