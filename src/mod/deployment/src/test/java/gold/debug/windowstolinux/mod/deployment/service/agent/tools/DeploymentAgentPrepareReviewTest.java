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
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentSession;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Actual H2 task ledger and native Agent roles, with local controlled model and transport only. */
class DeploymentAgentPrepareReviewTest extends DeploymentRuntimeFixture {
    private static final String REVISION = "a".repeat(64);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TOOLCHAIN_DENIAL_REASON = "Choose a different independently reviewed candidate action. "
        + "The original existing installation remains unconfirmed; ".repeat(20) + "COMPLETE_DENIAL_END_288";

    private boolean diagnosticCandidateSessionIdentityJourney;
    private boolean isolationMissingPlanJourney, isolationMissingHelperJourney, isolationRefusalJourney, isolationUnknownJourney, isolationReadyJourney;
    private boolean isolationCancelAfterCreateJourney;
    private boolean isolationReviewDeniedJourney, isolationReviewPersistenceFailure;
    private boolean patchReviewDeniedJourney;
    private boolean patchCommandReviewDeniedJourney;
    private boolean isolationExistingToolchainJourney;
    private boolean isolationQuotaReplanJourney;
    private boolean isolationToolchainDeniedJourney, isolationToolchainPersistenceFailure;
    private UUID isolatedToolchainTask;

    @Test
    void isolatedHostCommandRequiresAcceptedPlanBeforeAnyReviewOrSend() throws Exception {
        isolationMissingPlanJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void isolatedPreparationRejectsMissingSharedHelperWithoutInstallingOrCreatingCandidate() throws Exception {
        assertEquals(2415919104L, DeploymentAgentBuildTools.isolationMemoryFloor());
        isolationMissingHelperJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void confirmedIsolationRefusalReturnsBudgetsAndAllowsFreshPlanWithoutEffects() throws Exception {
        isolationRefusalJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void independentIsolationDenialIsDurablyUnsentBeforeDifferentScopeIsReviewed() throws Exception {
        isolationRefusalJourney = true;
        isolationReviewDeniedJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void isolationDenialWithFailedNotSentRecordNeverReturnsToMainModel() throws Exception {
        isolationRefusalJourney = true;
        isolationReviewDeniedJourney = true;
        isolationReviewPersistenceFailure = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void patchDenialReturnsToSameSessionBeforeDifferentPatchGetsFreshReviewAndGrant() throws Exception {
        patchReviewDeniedJourney = true;
        verifyPatchUnknownTail();
    }

    @Test
    void patchCommandDenialIsRecordedBeforeDifferentPatchReceivesFreshFullCommandReview() throws Exception {
        patchReviewDeniedJourney = true;
        patchCommandReviewDeniedJourney = true;
        verifyPatchUnknownTail();
    }

    private void verifyPatchUnknownTail() throws Exception {
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void unconfirmedIsolationProbeNeverBecomesKnownBusinessRefusal() throws Exception {
        isolationUnknownJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void readyIsolationSurvivesCandidateCreateQueryUploadVerifyAndOpen() throws Exception {
        isolationReadyJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void cancellationAfterConfirmedCandidateCreateStopsQueryAndRetainsUnknown() throws Exception {
        isolationReadyJourney = true;
        isolationCancelAfterCreateJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void isolatedRemoteToolsUsesExistingOnlyPreparationAfterControllerReady() throws Exception {
        isolationReadyJourney = true;
        isolationExistingToolchainJourney = true;
        var blocked = assertThrows(ApiException.class, () -> runVerification(VerificationMode.APPROVE, false));
        assertNotNull(isolatedToolchainTask, "Accept shutdown blocking only after this exact task passed every action-boundary assertion");
        assertEquals(ProblemCode.BUSY, blocked.problem().code());
        assertEquals("MOD_STOP_BLOCKED", blocked.problem().eventCode());
        assertEquals(Map.of("ownerId", OWNER), blocked.problem().context());
        assertEquals("Mod cleanup is blocked: Task " + isolatedToolchainTask + " still owns Mod resources", blocked.problem().message());
    }

    @Test
    void isolatedReplanReservesPreparedReleaseAndProbeWithoutChangingNativeCapacity() throws Exception {
        isolationReadyJourney = true;
        isolationQuotaReplanJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void deniedExistingToolchainPreparationReturnsToSessionBeforeFreshAlternativeReview() throws Exception {
        isolationReadyJourney = true;
        isolationExistingToolchainJourney = true;
        isolationToolchainDeniedJourney = true;
        runVerification(VerificationMode.APPROVE, false);
    }

    @Test
    void deniedExistingToolchainPreparationCannotContinueWhenNotSentPersistenceFails() throws Exception {
        isolationReadyJourney = true;
        isolationExistingToolchainJourney = true;
        isolationToolchainDeniedJourney = true;
        isolationToolchainPersistenceFailure = true;
        var blocked = assertThrows(ApiException.class, () -> runVerification(VerificationMode.APPROVE, false));
        assertNotNull(isolatedToolchainTask, "Accept shutdown blocking only after the original uncertain checkpoint assertions pass");
        assertEquals(ProblemCode.BUSY, blocked.problem().code());
        assertEquals("MOD_STOP_BLOCKED", blocked.problem().eventCode());
        assertEquals(Map.of("ownerId", OWNER), blocked.problem().context());
        assertEquals("Mod cleanup is blocked: Task " + isolatedToolchainTask + " still owns Mod resources", blocked.problem().message());
    }

    enum VerificationMode {
        APPROVE, DENY, INLINE_INPUT, CONFIGURATION_CHANGED, CAPACITY_LOW, CAPACITY_TRUNCATED, CAPACITY_CANCELLED, CAPACITY_MISSING, CAPACITY_UNSETTLED, BUILD_SMALL, BUILD_LARGE, CAPACITY_AFTER_INSTALL, CAPACITY_INSTALL_UNKNOWN,
        OPEN_APPROVE, OPEN_DENY, OPEN_CONFIGURATION_CHANGED;

        boolean confirmedRefusal() {
            return this == CAPACITY_LOW || this == CAPACITY_AFTER_INSTALL;
        }

        boolean verified() {
            return this == APPROVE || this == INLINE_INPUT || name().startsWith("BUILD_") || name().startsWith("OPEN_");
        }

        boolean opened() {
            return verified() && this != OPEN_DENY && this != OPEN_CONFIGURATION_CHANGED;
        }
    }

    @ParameterizedTest
    @EnumSource(value = VerificationMode.class, names = {"APPROVE", "DENY", "INLINE_INPUT", "CONFIGURATION_CHANGED"})
    void uploadedArchiveConsumerEvidenceReachesNativeReviewBeforeVerifyAndOpen(VerificationMode mode) throws Exception {
        runVerification(mode, false);
    }

    @ParameterizedTest
    @EnumSource(value = VerificationMode.class, names = {"OPEN_APPROVE", "OPEN_DENY", "OPEN_CONFIGURATION_CHANGED"})
    void agentOpenPhaseEvidenceReachesNativeReviewWithoutInventingCurrentSource(VerificationMode mode) throws Exception {
        runVerification(mode, false);
    }

    @Test
    void diagnosticCandidateSessionIdentityIsSharedByProducerEvidenceAndConsumer() throws Exception {
        diagnosticCandidateSessionIdentityJourney = true;
        runVerification(VerificationMode.APPROVE, false);
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
        if (isolationReadyJourney) {
            Files.writeString(Files.createDirectories(source.resolve("backend")).resolve("main.go"),
                "package main\nimport \"net/http\"\nfunc main() { http.ListenAndServe(\"127.0.0.1:49283\", nil) }\n");
            Files.writeString(Files.createDirectories(source.resolve("web")).resolve("server.mjs"),
                "import http from 'node:http';\nhttp.createServer((req, res) => res.end('web')).listen(49282);\n");
            if (isolationExistingToolchainJourney) {
                Files.writeString(source.resolve("backend/go.mod"), "module controlled/file-transfer\n\ngo 1.24.0\n");
                Files.writeString(source.resolve("backend/go.sum"), "");
            }
        }
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
            var cancellation = new java.util.concurrent.CompletableFuture<Runnable>();
            var handle = actual.tasks().submit(new TaskRequest("native-candidate-verification", "Controlled original candidate verification", List.of("native-host"), true), task -> {
                try {
                    var selection = actual.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task);
                         var archive = new DeploymentSourceArchiver(actual.file()).prepare(material, 1048576, task)) {
                        var frame = new VerificationFrame(actual, task, material, archive, mode);
                        frame.requestCancel = () -> cancellation.join().run();
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
            cancellation.complete(() -> {
                try {
                    handle.requestCancel();
                } catch (ApiException unavailable) {
                    throw new AssertionError("Controlled cancellation request failed", unavailable);
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
            if (isolationExistingToolchainJourney) isolatedToolchainTask = handle.id();
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

        int decisions, approvalCalls, pages, creates, uploads, verifyGrants, verifySends, opens, verifyVerdicts, capacityReads, readinessReads, installs, openReviews;
        int patchVerdicts, patchGrants, patchSends, patchCommandVerdicts;

        String openEvidence = "", openPolicy = "";

        int isolationSends, isolationVerdicts;
        int candidateQuerySends, candidateQueryVerdicts;
        int controllerReadinessSends, existingPreparationSends, existingPreparationVerdicts;
        final Set<UUID> toolchainGrants = new HashSet<>();
        String existingPreparationExact = "";
        UUID existingPreparationGrant;
        String deniedPreparationExact = "";
        String deniedExactPage = "";
        String alternativeExact = "";
        UUID alternativeGrant;
        int alternativeSends;
        boolean denialFeedbackObserved;
        final ApiException persistenceFailure = new ApiException(new Problem(ProblemCode.UNKNOWN, "CONTROLLED_NOT_SENT_PERSISTENCE_FAILURE",
            "Controlled checkpoint advance failure", Map.of(), Optional.empty()));
        Runnable requestCancel;
        final Set<UUID> isolationActions = new HashSet<>(), isolationGrants = new HashSet<>();
        final List<Long> isolationRequirements = new ArrayList<>();
        final Map<String, String> isolationReceipts = new LinkedHashMap<>();

        long selectedQuota;

        long isolationQuotaCapacityBytes = 2233180160L;
        boolean isolationQuotaAdmitted;

        String buildScript = "";

        boolean inlineInputObserved;

        String verifiedRequest = "";

        VerificationFrame(ModContext actual, TaskContext task, DeploymentSourceMaterial material, DeploymentSourceArchive archive, VerificationMode mode) {
            this.task = task;
            this.material = material;
            this.archive = archive;
            this.mode = mode;
            workspace = new DeploymentRemoteWorkspace(isolationReadyJourney ? "demo-backend" : "demo-api", archive.sha256());
            journal = new DeploymentAgentActionLog(actual.db());
            peer = proxy(RemoteSession.class, (p, m, a) -> switch (m.getName()) {
                case "facts" -> new RemoteFacts("Linux", "x86_64", "SHA256:" + "A".repeat(43));
                case "close" -> null;
                case "execute" -> {
                    var request = (CommandRequest) a[0];
                    if (isolationExistingToolchainJourney && Arrays.equals(request.stdin(), DeploymentAgentToolchainPrograms
                        .existingController(gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()).getBytes(StandardCharsets.UTF_8))) {
                        assertEquals(1, existingPreparationVerdicts, "The exact existing-only input needs its independent APP verdict");
                        assertEquals(2, toolchainGrants.size(), "Readiness and preparation receive separate fresh grants");
                        assertEquals(existingPreparationGrant, ((gold.debug.windowstolinux.appapi.task.action.ApprovalGrant) a[1]).id());
                        assertEquals(existingPreparationExact, gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, REVISION, request).exactAction());
                    }
                    if (isolationToolchainDeniedJourney && request.script().contains("'agent-read'"))
                        assertEquals(alternativeGrant, ((gold.debug.windowstolinux.appapi.task.action.ApprovalGrant) a[1]).id());
                    yield transport(request);
                }
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
                    if (patchReviewDeniedJourney && intent.exactAction().contains("'agent-patch'")) {
                        assertEquals(2, patchVerdicts);
                        patchGrants++;
                    }
                    if (intent.semantics().orElseThrow().operation().equals("deployment.candidate.verify.v1")) {
                        assertTrue(mode.verified());
                        assertEquals(1, verifyVerdicts);
                        assertEquals(1, creates);
                        assertEquals(1, uploads);
                        assertEquals(0, verifySends);
                        verifyGrants++;
                        verifiedRequest = intent.exactAction();
                    }
                }
                try {
                    Object result = m.invoke(actual.interaction(), a);
                    if (m.getName().equals("approveReviewedAction") && ((ActionIntent) a[0]).semantics().orElseThrow().operation()
                        .equals(gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.OPERATION)) {
                        @SuppressWarnings("unchecked") var approved = (OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>) result;
                        assertTrue(isolationGrants.add(approved.value().orElseThrow().id()), "Every preflight needs a fresh host grant");
                    }
                    if (isolationExistingToolchainJourney && m.getName().equals("approveReviewedAction")) {
                        var intent = (ActionIntent) a[0];
                        String operation = intent.semantics().orElseThrow().operation();
                        if (Set.of("deployment.agent.toolchain.controller.readiness.v1", "deployment.agent.toolchain.prepare.v1").contains(operation)) {
                            @SuppressWarnings("unchecked") var approved = (OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>) result;
                            UUID grant = approved.value().orElseThrow().id();
                            assertFalse(isolationGrants.contains(grant));
                            assertTrue(toolchainGrants.add(grant), "The current toolchain command needs a fresh exact host grant");
                            if (operation.equals("deployment.agent.toolchain.prepare.v1")) {
                                existingPreparationExact = intent.exactAction();
                                existingPreparationGrant = grant;
                            }
                        }
                    }
                    if (isolationToolchainDeniedJourney && m.getName().equals("approveReviewedAction")
                        && ((ActionIntent) a[0]).exactAction().contains("'agent-read'")) {
                        @SuppressWarnings("unchecked") var approved = (OperationResult<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>) result;
                        alternativeExact = ((ActionIntent) a[0]).exactAction();
                        alternativeGrant = approved.value().orElseThrow().id();
                        assertTrue(denialFeedbackObserved);
                        assertNotEquals(deniedPreparationExact, alternativeExact);
                        assertFalse(toolchainGrants.contains(alternativeGrant));
                        assertFalse(isolationGrants.contains(alternativeGrant));
                    }
                    return result;
                } catch (InvocationTargetException error) {
                    throw error.getCause();
                }
            });
            var tasks = proxy(TaskService.class, (p, m, a) -> {
                Object result;
                try { result = m.invoke(actual.tasks(), a); }
                catch (InvocationTargetException error) { throw error.getCause(); }
                if (isolationToolchainPersistenceFailure && m.getName().equals("beginCheckpoint")
                    && ((String) a[1]).startsWith("agent-controller-program.")
                    && JSON.readTree((String) a[2]).at("/index/phase").asText().equals("PREPARE")) {
                    var retained = (TaskCheckpoint) result;
                    return proxy(TaskCheckpoint.class, (cp, cm, ca) -> {
                        if (cm.getName().equals("advance")) throw persistenceFailure;
                        try { return cm.invoke(retained, ca); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
                }
                return result;
            });
            context = proxy(ModContext.class, (p, m, a) -> {
                if (m.getName().equals("ai")) return ai;
                if (m.getName().equals("interaction")) return host;
                if (m.getName().equals("tasks") && isolationToolchainPersistenceFailure) return tasks;
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
            if (isolationMissingPlanJourney || isolationMissingHelperJourney || isolationRefusalJourney || isolationUnknownJourney || isolationReadyJourney) {
                var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                var policy = gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.trusted(
                    "controlled-isolation", "demo", layout, List.of(new gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.ProtectedApplication(
                        UUID.randomUUID(), "retained", layout.digest(), Set.of("retained-api"), Set.of("retained-api-1234567890abcdef"), Map.of(), Set.of()))).minimumResources((2048L + 256) * 1024 * 1024, 1);
                return new DeploymentAgentRemoteTools(context, peer, context.interaction(), server, task, archive, material,
                    REVISION, () -> revision.get(), journal, Optional.empty(), "FULL_CONTROL", true, layout, null, policy);
            }
            return new DeploymentAgentRemoteTools(context, peer, context.interaction(), server, task, archive, material,
                REVISION, () -> revision.get(), journal, Optional.empty(), "FULL_CONTROL", true);
        }

        void run() throws Exception {
            try (var tools = remote(); var authorization = DeploymentWorkflowAuthorization.open(context, task, server, REVISION, "FULL_CONTROL", true,
                "source=" + material.snapshot().sha256() + "\narchive=" + archive.sha256())) {
                if (isolationQuotaReplanJourney) {
                    verifyIsolationQuotaReplan(tools);
                    return;
                }
                if (isolationMissingPlanJourney || isolationMissingHelperJourney) {
                    assertThrows(SecurityException.class, () -> tools.hostCommand(task, "printf controlled-no-effect", 30, "controlled"));
                    assertEquals(0, approvalCalls + creates + uploads + installs + readinessReads);
                    if (isolationMissingHelperJourney) {
                        var health = new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Process(30, 1);
                        var runtime = new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.NodeService(24, health);
                        var request = gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.explicit(268435456L);
                        var part = new DeploymentAgentPlan.Part("api", ".", List.of(), List.of("controlled-source"), runtime,
                            new gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings(List.of(), Optional.of(List.of())),
                            List.of(), Optional.of(List.of()), List.of(), Optional.of(request), Optional.empty());
                        var declared = new DeploymentAgentPlan("demo", "api", health, List.of(part));
                        var accepted = new DeploymentAgentPlan("demo", "api", health, tools.selectWorkspaces(declared, Map.of()));
                        tools.acceptWorkspaces(accepted);
                        assertThrows(SecurityException.class, () -> tools.prepare("demo", accepted.components().getFirst()));
                        assertEquals(1, readinessReads);
                        assertEquals(0, creates + uploads + installs + opens + verifyGrants + verifySends);
                        assertFalse(journal.events(task).stream().anyMatch(event -> event.type().equals("ISOLATION_PREFLIGHT")));
                    }
                    return;
                }
                var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Observe actual source"),
                    new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()), tools, journal,
                    "FULL_CONTROL", true, REVISION, () -> revision.get());
                if (isolationReviewPersistenceFailure)
                    try (var connection = context.db().openConnection(task); var statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE deployment_agent_event ADD CONSTRAINT reject_isolation_not_sent CHECK (event_type <> 'ACTION_REVIEW_NOT_SENT')");
                    }
                if (mode == VerificationMode.CAPACITY_UNSETTLED)
                    journal.event(task, "CAPABILITY_EXECUTING", "c".repeat(64), "earlier-unconfirmed");
                var error = assertThrows(Exception.class, session::run);
                if (patchReviewDeniedJourney) {
                    var events = journal.events(task);
                    assertSame(stop, error);
                    assertEquals(5, decisions);
                    assertEquals(2, patchVerdicts);
                    assertEquals(1, patchGrants);
                    assertEquals(1, patchSends);
                    assertEquals(1, events.stream().filter(event -> event.type().equals("ACTION_REVIEW_NOT_SENT")).count());
                    assertEquals(patchCommandReviewDeniedJourney ? 2 : 1, events.stream().filter(event -> event.type().equals("PATCH_EXECUTING")).count());
                    assertEquals(patchCommandReviewDeniedJourney ? 1 : 0, events.stream().filter(event -> event.type().equals("PATCH_NOT_SENT")).count());
                    assertFalse(events.stream().anyMatch(event -> event.type().equals("PATCH_RESULT")));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events), "Unconfirmed sent replacement stops further proposals");
                    assertEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                    return;
                }
                if (isolationReviewDeniedJourney) {
                    var events = journal.events(task);
                    assertEquals(0, creates + uploads + installs + opens + verifyGrants + verifySends);
                    assertFalse(events.stream().anyMatch(event -> Set.of("PREPARE_EXECUTING", "CANDIDATE", "ISOLATION_PREFLIGHT").contains(event.type())));
                    if (isolationReviewPersistenceFailure) {
                        assertEquals(4, decisions, "An unrecorded DENY cannot be returned as a known alternative");
                        assertEquals(0, isolationSends + isolationGrants.size());
                        assertEquals(1, isolationVerdicts);
                        assertEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                        assertFalse(events.stream().anyMatch(event -> event.type().equals("ACTION_REVIEW_NOT_SENT")));
                    } else {
                        assertSame(stop, error);
                        assertEquals(7, decisions);
                        assertEquals(2, isolationVerdicts, "Changed scope obtains a new complete independent review");
                        assertEquals(1, isolationSends);
                        assertEquals(1, isolationGrants.size(), "Denied scope never gets a host permit");
                        assertEquals(1, events.stream().filter(event -> event.type().equals("ACTION_REVIEW_NOT_SENT")).count());
                        assertTrue(DeploymentAgentActionLog.effectsSettled(events));
                    }
                    return;
                }
                if (isolationRefusalJourney || isolationUnknownJourney) {
                    var events = journal.events(task);
                    assertEquals(0, creates + uploads + installs + opens + verifyGrants + verifySends);
                    assertFalse(events.stream().anyMatch(event -> Set.of("CANDIDATE", "CURRENT_SOURCE", "ISOLATION_PREFLIGHT").contains(event.type())), "REFUSED never admits or creates business state");
                    int expected = isolationRefusalJourney ? 2 : 1;
                    assertEquals(expected, isolationSends);
                    assertEquals(expected, isolationVerdicts, "Every current preflight receives its own complete independent APP verdict");
                    assertEquals(expected, isolationActions.size());
                    assertEquals(expected, isolationGrants.size());
                    if (isolationRefusalJourney) {
                        assertSame(stop, error);
                        assertEquals(7, decisions, "The same real Task replans after error feedback and obtains another APP/grant");
                        assertNotEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                        assertFalse(events.stream().anyMatch(event -> Set.of("PREPARE_EXECUTING", "PREPARE_RESULT").contains(event.type())), "Admission refusal precedes every business-effect attempt");
                        assertTrue(DeploymentAgentActionLog.effectsSettled(events), "The public consumer reads the retained refusal without a synthetic effect result");
                        assertTrue(DeploymentAgentActionLog.preparationsSettled(events));
                        assertTrue(isolationRequirements.get(1) < isolationRequirements.getFirst(), "The next accepted quota actually shrinks");
                        for (var entry : isolationReceipts.entrySet()) {
                            var pieces = events.stream().filter(event -> event.type().equals("ISOLATION_REFUSAL_RECEIPT") && event.action().equals(entry.getKey())).toList();
                            var reconstructed = new StringBuilder();
                            for (int i = 0; i < pieces.size(); i++) {
                                assertTrue(pieces.get(i).detail().startsWith(i + ":"));
                                reconstructed.append(pieces.get(i).detail().substring(pieces.get(i).detail().indexOf(':') + 1));
                            }
                            assertEquals(entry.getValue(), reconstructed.toString(), "Keep the actual canonical receipt without editing free bytes");
                        }
                    } else {
                        assertEquals(4, decisions, "No known-refusal reply is returned to the main model");
                        assertEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                        assertFalse(events.stream().anyMatch(event -> event.type().startsWith("ISOLATION_REFUSAL") || event.type().equals("PREPARE_RESULT")));
                        assertTrue(DeploymentAgentActionLog.effectsSettled(events), "No business effect was attempted; UNKNOWN receipt is not an admission proof");
                    }
                    return;
                }
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
                if (isolationToolchainDeniedJourney) {
                    verifyToolchainDenial(error);
                    return;
                }
                if (isolationExistingToolchainJourney) {
                    if (existingPreparationSends != 1)
                        throw new AssertionError("RemoteTools did not reach the approved existing-only preparation after controller READY", error);
                    assertSame(stop, error, "Stop at the actual prepare boundary without inventing a toolchain receipt");
                    assertFalse(task.cancelToken().requested());
                    assertEquals(1, controllerReadinessSends);
                    assertEquals(1, existingPreparationVerdicts);
                    assertEquals(2, toolchainGrants.size());
                    assertEquals(0, installs);
                    assertEquals(1, isolationSends);
                    assertEquals(1, creates);assertEquals(1, candidateQuerySends);assertEquals(1, uploads);
                    assertEquals(1, verifyGrants);assertEquals(1, verifySends);assertEquals(1, opens);
                    var events = journal.events(task);
                    assertEquals("RUNNING", journal.find(task).orElseThrow().state(),
                        "This direct Session callback stops before Workflow.failed classifies its outer result");
                    assertTrue(events.stream().anyMatch(event -> event.type().equals("PREPARE_RESULT")));
                    assertTrue(events.stream().anyMatch(event -> event.type().equals("CANDIDATE")));
                    assertTrue(events.stream().anyMatch(event -> event.type().equals("CAPABILITY_EXECUTING") && event.detail().equals("prepare_tools")));
                    assertFalse(events.stream().anyMatch(event -> event.type().equals("CAPABILITY_RESULT")));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                    return;
                }
                if (isolationCancelAfterCreateJourney) {
                    assertTrue(task.cancelToken().requested());
                    assertEquals(1, creates, "The candidate-create command already returned SUCCEEDED/0");
                    assertEquals(939524096L, selectedQuota);
                    assertEquals(0, candidateQuerySends + candidateQueryVerdicts + uploads + verifyGrants + verifySends + opens);
                    var api = assertInstanceOf(ApiException.class, error);
                    assertEquals("DEPLOYMENT_AGENT_ISOLATION_UNCONFIRMED", api.problem().eventCode());
                    var unavailable = assertInstanceOf(IllegalStateException.class, api.getCause());
                    assertEquals("Snapshot content or task identity is no longer available", unavailable.getMessage());
                    var admitted = DeploymentAgentRemoteTools.class.getDeclaredField("isolationAdmitted");
                    admitted.setAccessible(true);
                    assertTrue(admitted.getBoolean(tools), "The READY flag did not regress; the cancelled snapshot cannot rebuild its scope");
                    var events = journal.events(task);
                    assertEquals("UNKNOWN", journal.find(task).orElseThrow().state());
                    assertEquals(1, events.stream().filter(event -> event.type().equals("ISOLATION_PREFLIGHT")).count());
                    assertEquals(1, events.stream().filter(event -> event.type().equals("PREPARE_EXECUTING")).count());
                    assertFalse(events.stream().anyMatch(event -> Set.of("PREPARE_RESULT", "CANDIDATE", "PREPARE_NOT_SENT", "PREPARE_DISPATCH_NOT_SENT").contains(event.type())));
                    assertFalse(events.stream().anyMatch(event -> event.type().startsWith("ISOLATION_REFUSAL")));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events), "A stopped owner query cannot settle the already-created candidate");
                    return;
                }
                if (creates != 1) throw new AssertionError("Original prepare did not reach candidate creation", error);
                assertEquals(1, creates);
                if (isolationReadyJourney && uploads != 1)
                    throw new AssertionError("READY isolation stopped between candidate creation and upload", error);
                assertEquals(1, uploads);
                if (diagnosticCandidateSessionIdentityJourney && error instanceof ApiException failure) {
                    String event = failure.problem().eventCode();
                    System.out.println("CANDIDATE_SESSION_IDENTITY_FAILURE problemCode=" + failure.problem().code().name()
                        + " evidenceChanged=" + "DEPLOYMENT_AGENT_VERIFICATION_EVIDENCE_CHANGED".equals(event)
                        + " verifyGrants=" + verifyGrants + " verifySends=" + verifySends + " opens=" + opens);
                }
                assertEquals(mode.verified() ? 1 : 0, verifyGrants);
                assertEquals(verifyGrants, verifySends);
                assertEquals(mode.opened() ? 1 : 0, opens);
                if (mode.name().startsWith("OPEN_")) assertEquals(2, openReviews);
                if (mode == VerificationMode.INLINE_INPUT) assertTrue(inlineInputObserved);
                else assertTrue(pages > 0, "This controlled model chooses an explicit request reread");
                var events = journal.events(task);
                if (isolationReadyJourney) {
                    assertEquals(1, isolationSends);
                    assertEquals(1, isolationVerdicts);
                    assertEquals(1, candidateQuerySends);
                    assertEquals(1, candidateQueryVerdicts);
                    assertEquals(1, isolationActions.size());
                    assertEquals(1, isolationGrants.size());
                    assertEquals(1, events.stream().filter(event -> event.type().equals("ISOLATION_PREFLIGHT")).count());
                    assertFalse(events.stream().anyMatch(event -> event.type().startsWith("ISOLATION_REFUSAL")));
                }
                assertEquals(1, events.stream().filter(e -> e.type().equals("PREPARE_EXECUTING")).count());
                assertFalse(events.stream().anyMatch(e -> Set.of("PREPARE_NOT_SENT", "PREPARE_DISPATCH_NOT_SENT").contains(e.type())));
                if (mode.opened()) {
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
                    assertEquals(mode == VerificationMode.CONFIGURATION_CHANGED || mode == VerificationMode.OPEN_CONFIGURATION_CHANGED ? "DEPLOYMENT_COMMAND_CONFIGURATION_CHANGED" : "DEPLOYMENT_COMMAND_REVIEW_DENIED", api.problem().eventCode());
                    assertTrue(events.stream().anyMatch(e -> e.type().equals("STATE") && e.detail().equals("UNKNOWN")));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                    assertFalse(events.stream().anyMatch(e -> Set.of("PREPARE_RESULT", "CANDIDATE", "CURRENT_SOURCE").contains(e.type())));
                }
            }
        }

        void unbound() throws Exception {
            try (var tools = remote()) {
                var field = DeploymentAgentRemoteTools.class.getDeclaredField("candidates");
                field.setAccessible(true);
                var channel = (DeploymentCandidateChannel) field.get(tools);
                var spec = channel.verification(workspace, archive);
                var foreign = new DeploymentCandidateChannel(peer, context.interaction(), server, task, REVISION, new DeploymentBuildBudget(7200));
                assertThrows(ApiException.class, () -> tools.verificationEvidence(foreign.verification(workspace, archive), workspace));
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

        void verifyToolchainDenial(Exception error) throws Exception {
            assertEquals(1, existingPreparationVerdicts);
            assertEquals(0, existingPreparationSends);
            assertNull(existingPreparationGrant, "An independent DENY cannot reach host grant creation");
            assertEquals(1, controllerReadinessSends);
            assertEquals(1, toolchainGrants.size());
            assertEquals(1, creates);
            assertEquals(1, uploads);
            assertEquals(1, opens);
            var events = journal.events(task);
            assertEquals(1, events.stream().filter(event -> event.type().equals("PREPARE_RESULT")).count());
            assertEquals(1, events.stream().filter(event -> event.type().equals("CANDIDATE")).count());
            var rows = DeploymentAgentToolchainProgramState.snapshot(context.tasks(), task, server.id(),
                gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original(), events);
            assertEquals(2, rows.size());
            var pending = (JsonNode) rows.getLast().get("payload");
            var diagnostics = new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context).read(task.id());
            assertTrue(diagnostics.problem().isEmpty());
            var denials = diagnostics.records().stream().filter(record -> record.event().eventCode().equals("DEPLOYMENT_AGENT_COMMAND_REVIEW_DENIED")).toList();
            assertEquals(1, denials.size());
            assertFalse(denials.getFirst().truncated());
            assertEquals(TOOLCHAIN_DENIAL_REASON, denials.getFirst().event().message(), "Keep the complete original bounded denial after the soft-feedback route bypasses Workflow.failed");
            assertTrue(denials.getFirst().event().message().length() > 512);
            if (isolationToolchainPersistenceFailure) {
                var uncertain = assertInstanceOf(ApiException.class, error);
                assertEquals(ProblemCode.UNKNOWN, uncertain.problem().code());
                assertEquals("DEPLOYMENT_AGENT_CONTROLLER_STATE_UNCONFIRMED", uncertain.problem().eventCode());
                assertSame(persistenceFailure, uncertain.getCause());
                assertEquals(1, uncertain.getSuppressed().length);
                assertInstanceOf(gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException.class, uncertain.getSuppressed()[0]);
                assertEquals("INTENT", pending.path("state").asText());
                assertFalse((Boolean) rows.getLast().get("released"));
                assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                assertFalse(events.stream().anyMatch(event -> event.type().equals("CAPABILITY_NOT_SENT") || event.type().equals("CAPABILITY_RESULT")));
                assertFalse(events.stream().anyMatch(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("ALTERNATIVE_REQUIRED")));
                assertEquals(6, decisions, "No next model call can observe a fabricated known NOT_SENT");
                assertFalse(denialFeedbackObserved);
                assertNull(alternativeGrant);
                assertEquals(0, alternativeSends);
                return;
            }
            assertSame(stop, error, "The independently reviewed replacement read stops at its actual transport boundary");
            assertEquals("NOT_SENT", pending.path("state").asText());
            assertTrue((Boolean) rows.getLast().get("released"));
            assertEquals("DEPLOYMENT_ALTERNATIVE_REQUIRED", pending.path("problemEvent").asText());
            assertEquals(1, events.stream().filter(event -> event.type().equals("CAPABILITY_NOT_SENT") && event.detail().equals("ALTERNATIVE_REQUIRED")).count());
            assertTrue(DeploymentAgentActionLog.effectsSettled(events));
            assertTrue(events.stream().anyMatch(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("ALTERNATIVE_REQUIRED")));
            assertEquals(7, decisions);
            assertTrue(denialFeedbackObserved);
            assertNotNull(alternativeGrant);
            assertNotEquals(deniedPreparationExact, alternativeExact);
            assertEquals(1, alternativeSends);
        }

        void verifyIsolationQuotaReplan(DeploymentAgentRemoteTools tools) throws Exception {
            long mib = 1024L * 1024, reserve = 1024 * mib;
            var health = new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Process(30, 1);
            var runtime = new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.NodeService(24, health);
            var resources = new gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings(List.of(), Optional.of(List.of()));
            var backend = new DeploymentAgentPlan.Part("backend", "backend", List.of(), List.of("actual-backend-source"), runtime, resources,
                List.of(), Optional.of(List.of()), List.of(), Optional.of(DeploymentWorkspaceQuota.Request.explicit(256 * mib)), Optional.empty());
            var web = new DeploymentAgentPlan.Part("web", "web", List.of(), List.of("actual-web-source"), runtime, resources,
                List.of(), Optional.of(List.of()), List.of(), Optional.of(DeploymentWorkspaceQuota.Request.automatic(128 * mib, 256 * mib)), Optional.empty());
            var declared = new DeploymentAgentPlan("demo", "web", health, List.of(backend, web));
            var first = new DeploymentAgentPlan("demo", "web", health, tools.selectWorkspaces(declared, Map.of()));
            assertEquals(List.of(256 * mib, 256 * mib), first.components().stream().map(part -> part.workspaceSelection().orElseThrow().bytes()).toList());
            assertEquals(384 * mib, first.components().get(1).workspaceSelection().orElseThrow().preferredBytes());
            assertEquals(2233180160L, first.components().get(1).workspaceSelection().orElseThrow().availableBytes());
            tools.acceptWorkspaces(first);
            var prepared = tools.prepare("demo", first.components().getFirst());
            assertEquals(1, creates);
            assertEquals(1, uploads);
            assertEquals(1, verifySends);
            assertEquals(1, opens);
            assertEquals(256 * mib, prepared.quota().bytes());
            isolationQuotaCapacityBytes = 1964691456L;
            var owned = Map.of("backend", prepared);
            var next = new DeploymentAgentPlan("demo", "web", health, tools.selectWorkspaces(declared, owned));
            assertEquals(prepared.quota(), next.components().getFirst().workspaceSelection().orElseThrow(), "An existing physical quota and its original native observation remain intact");
            var nextWeb = next.components().get(1).workspaceSelection().orElseThrow();
            assertEquals(256 * mib, nextWeb.bytes(), "Prepared release256 + additional64 must remain before selecting the web's two copies");
            assertEquals(384 * mib, nextWeb.preferredBytes());
            assertEquals(1964691456L, nextWeb.availableBytes(), "Never expose the internally reduced planning allowance as native availableBytes");
            tools.acceptWorkspaces(next);
            isolationQuotaAdmitted = false;
            tools.validateIsolationAdmission();
            assertEquals(List.of(1088 * mib, 832 * mib), isolationRequirements);
            assertEquals(2, isolationSends);
            assertEquals(2, isolationVerdicts);
            assertEquals(2, isolationActions.size());
            assertEquals(2, isolationGrants.size(), "The replanned scope needs an independently reviewed fresh native grant and READY receipt");
            assertEquals(1, creates + installs, "Planning and admission never create the web candidate or install a toolchain");
            var explicitWeb = new DeploymentAgentPlan.Part(web.id(), web.sourceRoot(), web.dependencies(), web.evidence(), web.runtime(), web.resources(),
                web.configuration(), web.databaseIds(), web.secretReferences(), Optional.of(DeploymentWorkspaceQuota.Request.explicit(384 * mib)), Optional.empty());
            var explicitPlan = new DeploymentAgentPlan("demo", "web", health, List.of(backend, explicitWeb));
            var refused = assertThrows(DeploymentAgentRemoteTools.PlanCapacityRefusal.class, () -> tools.selectWorkspaces(explicitPlan, owned));
            refused.requireBinding(task, material.snapshot().sha256(), REVISION, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original().digest());
            var refusal = JSON.readTree(refused.feedback().substring("TOOL_REJECTED: WORKSPACE_HOST_INSUFFICIENT DATA=".length()));
            assertEquals(1964691456L, refusal.path("availableBytes").longValue());
            assertEquals(2112 * mib, refusal.path("requiredBytes").longValue());
            assertEquals(reserve, refusal.path("hostReserveBytes").longValue());
            assertEquals(256 * mib, refusal.path("preparedReleaseBytes").longValue());
            assertEquals(64 * mib, refusal.path("additionalDiskDemandBytes").longValue());
            assertEquals(320 * mib, refusal.path("fixedCostBytes").longValue());
            assertEquals(768 * mib, refusal.path("candidateHostCostBytes").longValue());
            assertEquals(1964691456L - 320 * mib, refusal.path("planningAvailableBytes").longValue());
            isolationQuotaCapacityBytes = 2112 * mib - 1;
            assertThrows(DeploymentAgentRemoteTools.PlanCapacityRefusal.class, () -> tools.selectWorkspaces(explicitPlan, owned));
            isolationQuotaCapacityBytes++;
            assertEquals(384 * mib, tools.selectWorkspaces(explicitPlan, owned).get(1).workspaceSelection().orElseThrow().bytes());
            var onlyPrepared = new DeploymentAgentPlan("demo", "backend", health, List.of(backend));
            isolationQuotaCapacityBytes = reserve + 320 * mib - 1;
            var fixedRefusal = assertThrows(DeploymentAgentRemoteTools.PlanCapacityRefusal.class, () -> tools.selectWorkspaces(onlyPrepared, owned));
            var fixed = JSON.readTree(fixedRefusal.feedback().substring("TOOL_REJECTED: WORKSPACE_HOST_INSUFFICIENT DATA=".length()));
            assertEquals(reserve + 320 * mib - 1, fixed.path("availableBytes").longValue());
            assertEquals(reserve + 320 * mib, fixed.path("requiredBytes").longValue());
            assertEquals(0, fixed.path("componentCount").intValue());
            isolationQuotaCapacityBytes++;
            assertEquals(prepared.quota(), tools.selectWorkspaces(onlyPrepared, owned).getFirst().workspaceSelection().orElseThrow());
            isolationQuotaCapacityBytes = 1;
            var exhausted = assertThrows(DeploymentAgentRemoteTools.PlanCapacityRefusal.class, () -> tools.selectWorkspaces(explicitPlan, owned));
            var empty = JSON.readTree(exhausted.feedback().substring("TOOL_REJECTED: WORKSPACE_HOST_INSUFFICIENT DATA=".length()));
            assertEquals(1, empty.path("availableBytes").longValue());
            assertEquals(0, empty.path("planningAvailableBytes").longValue());
            assertEquals(2112 * mib, empty.path("requiredBytes").longValue());
            assertEquals(1, creates);
            assertEquals(0, installs + controllerReadinessSends + existingPreparationSends);
        }

        ActionIntent verificationIntent(CommandRequest request) {
            return gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, REVISION, request)
                .withSemantics(new ActionSemantics("deployment.candidate.verify.v1", Set.of(new ActionTarget(server.id(), "deployment-candidate", workspace.candidateRoot()))));
        }

        AiResponse approve(AiRequest request) throws Exception {
            approvalCalls++;
            var dynamic = (ObjectNode) JSON.readTree(request.messages().getLast().text());
            if (patchReviewDeniedJourney && dynamic.path("type").asText().equals("PATCH")) {
                patchVerdicts++;
                assertEquals(workspace.candidateRoot(), dynamic.at("/facts/candidateRoot").asText());
                assertEquals(DeploymentAgentSourceRevision.digest(material.snapshot().directory()), dynamic.at("/facts/sourceRevision").asText());
                assertEquals(0, patchSends);
                var answer = JSON.createObjectNode()
                    .put("decision", patchVerdicts == 1 && !patchCommandReviewDeniedJourney ? "DENY" : "APPROVE").put("requiresUserConfirmation", false)
                    .put("reason", "Exact original structured patch review");
                return new AiResponse("", "controlled-patch", "tool_calls",
                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    List.of(new AiToolCall("patch_review_" + patchVerdicts, "submit_result", answer.toString())), Optional.empty());
            }
            assertEquals("FIRST_USER_MESSAGE_AFTER_BASE_SYSTEM", dynamic.path("currentEvidence").asText());
            var input = (ObjectNode) JSON.readTree(request.messages().get(1).text());
            input.setAll(dynamic);
            if (isolationToolchainDeniedJourney && deniedPreparationExact.isEmpty() && !deniedExactPage.isEmpty()) {
                var prior = request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((first, last) -> last).orElseThrow();
                assertEquals(deniedExactPage, prior.callId());
                assertFalse(prior.error());
                var envelope = JSON.readTree(prior.text());
                assertEquals(input.path("reviewBinding"), envelope.path("reviewBinding"));
                var descriptor = input.at("/currentRequest/exactAction");
                var value = envelope.path("value");
                assertEquals(descriptor.path("sha256"), value.path("sha256"));
                assertEquals(0, value.path("offset").intValue());
                assertFalse(value.path("hasMore").booleanValue());
                assertEquals(descriptor.path("characters").intValue(), value.path("total").intValue());
                assertEquals(value.path("total"), value.path("next"));
                deniedPreparationExact = value.path("text").asText();
                assertFalse(deniedPreparationExact.isBlank());
                assertEquals(descriptor.path("sha256").asText(), fixtureDigest(deniedPreparationExact));
            }
            if (mode.name().startsWith("OPEN_") && input.at("/currentRequest/agentOpenPhaseEvidence").isObject()) {
                var opening = input.at("/currentRequest/agentOpenPhaseEvidence");
                assertEquals("BEFORE_PROTECTED_SOURCE_CREATION", opening.path("phase").asText());
                assertFalse(opening.path("remoteObserved").asBoolean());
                assertFalse(opening.at("/postconditions/projectBodyExecuted").asBoolean());
                assertTrue(opening.at("/postconditions/buildEntry").asText().startsWith("Not called by agent-open"));
                assertEquals(task.id().toString(), opening.at("/arguments/task").asText());
                assertEquals(workspace.applicationId(), opening.at("/arguments/application").asText());
                assertEquals(workspace.candidateId(), opening.at("/arguments/candidate").asText());
                assertEquals(archive.sha256(), opening.at("/arguments/compressedArchiveSha256").asText());
                assertEquals(workspace.candidateRoot(), opening.at("/paths/candidateRoot").asText());
                assertEquals("NO_CURRENT_SOURCE_RECEIPT", input.at("/identityRoles/protectedTreeAvailability").asText());
                assertEquals("EMPTY", input.at("/currentRequest/inputKind").asText());
                assertEquals(opening.at("/request/scriptSha256").asText(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.at("/executableInput/script/text").asText().getBytes(StandardCharsets.UTF_8))));
                assertTrue(input.at("/executableInput/script/text").asText().contains("'agent-open'"));
                assertEquals(1, verifySends);
                assertEquals(0, opens);
                assertFalse(journal.events(task).stream().anyMatch(event -> Set.of("PREPARE_RESULT", "CANDIDATE", "CURRENT_SOURCE").contains(event.type())));
                assertFalse(dynamic.has("agentOpenPhaseEvidence"));
                for (var value : opening.path("remoteState")) assertEquals("UNKNOWN", value.asText());
                if (openReviews++ == 0) {
                    openEvidence = request.messages().get(1).text();
                    openPolicy = request.messages().getFirst().text();
                    assertTrue(openPolicy.contains("not a remote observation"));
                    return nativeCall("open_contract_" + approvalCalls, "contract", JSON.createObjectNode().put("offset", 0).put("limit", 8000));
                }
                assertEquals(openEvidence, request.messages().get(1).text());
                assertEquals(openPolicy, request.messages().getFirst().text());
                if (mode == VerificationMode.OPEN_CONFIGURATION_CHANGED) revision.set("e".repeat(64));
                return verdict(mode == VerificationMode.OPEN_DENY ? "DENY" : "APPROVE");
            }
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
                if (mode == VerificationMode.INLINE_INPUT) {
                    assertTrue(input.at("/executableInput/script/text").isTextual());
                    assertTrue(input.at("/executableInput/stdin").isMissingNode());
                    assertEquals("EMPTY", input.at("/currentRequest/inputKind").asText());
                    assertEquals(facts.at("/request/scriptSha256").asText(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(input.at("/executableInput/script/text").asText().getBytes(StandardCharsets.UTF_8))));
                    inlineInputObserved = true;
                    verifyVerdicts++;
                    return verdict("APPROVE");
                }
            } else if (isolationReadyJourney) {
                String operation = input.at("/currentRequest/operation").asText();
                var isolation = input.at("/currentRequest/currentCommandEvidence/agentIsolation");
                assertTrue(isolation.isObject(), "Every native command retains the actual isolation guard");
                boolean admitted = !operation.equals(gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.OPERATION)
                    && isolationSends > 0;
                if (isolationQuotaReplanJourney) admitted = isolationQuotaAdmitted;
                assertEquals(admitted, isolation.path("admitted").asBoolean());
            } else if (isolationMissingHelperJourney || isolationRefusalJourney || isolationUnknownJourney) {
                String operation = input.at("/currentRequest/operation").asText();
                assertTrue(Set.of("deployment.candidate.capacity.v1", DeploymentAgentHelperReadiness.OPERATION, gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.OPERATION).contains(operation));
                var isolation = input.at("/currentRequest/currentCommandEvidence/agentIsolation");
                assertTrue(isolation.isObject(), "Read-only approvals retain the actual protected policy before admission");
                assertFalse(isolation.path("admitted").asBoolean(true));
                assertEquals(0, creates + uploads + installs + opens + verifyGrants + verifySends);
                if (isolationMissingHelperJourney) assertFalse(journal.events(task).stream().anyMatch(event -> Set.of("PREPARE_EXECUTING", "PREPARE_RESULT", "CANDIDATE").contains(event.type())));
            } else
                assertTrue(input.at("/currentRequest/currentCommandEvidence").isMissingNode(), "Only this actual verify carries its consumer evidence");
            var page = bodyPage(request, input, verification);
            if (page != null) return page;
            if (patchReviewDeniedJourney && input.at("/currentRequest/inputKind").asText().equals("SOURCE_PATCH")) {
                patchCommandVerdicts++;
                return verdict(patchCommandReviewDeniedJourney && patchCommandVerdicts == 1 ? "DENY" : "APPROVE");
            }
            if (verification) {
                verifyVerdicts++;
                if (mode == VerificationMode.DENY) return verdict("DENY");
                if (mode == VerificationMode.CONFIGURATION_CHANGED) revision.set("e".repeat(64));
            }
            if (input.at("/currentRequest/operation").asText().equals(gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.OPERATION)) {
                isolationVerdicts++;
                if (isolationReviewDeniedJourney && isolationVerdicts == 1) return verdict("DENY");
            }
            if (input.at("/currentRequest/operation").asText().equals("deployment.candidate.query.v1")) candidateQueryVerdicts++;
            if (isolationExistingToolchainJourney && input.at("/currentRequest/operation").asText().equals("deployment.agent.toolchain.prepare.v1")) {
                assertEquals(1, controllerReadinessSends);
                assertEquals(fixtureDigest(DeploymentAgentToolchainPrograms.existingController(
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original())), input.at("/executableInput/stdin/sha256").asText());
                if (isolationToolchainDeniedJourney && deniedPreparationExact.isEmpty()) {
                    var descriptor = input.at("/currentRequest/exactAction");
                    assertEquals("evidence", descriptor.path("tool").asText());
                    assertEquals("/exactAction", descriptor.at("/arguments/pointer").asText());
                    assertTrue(descriptor.path("characters").intValue() > 512);
                    assertTrue(descriptor.path("characters").intValue() <= 8000);
                    deniedExactPage = "denied_exact_" + approvalCalls;
                    return nativeCall(deniedExactPage, "evidence", (ObjectNode) descriptor.path("arguments").deepCopy());
                }
                existingPreparationVerdicts++;
                if (isolationToolchainDeniedJourney) {
                    assertFalse(deniedPreparationExact.isBlank());
                    var denied = JSON.createObjectNode().put("decision", "DENY").put("requiresUserConfirmation", false)
                        .put("reason", TOOLCHAIN_DENIAL_REASON).put("discussionStatus", "CLOSED");
                    denied.putArray("missingEvidence");
                    denied.putArray("adjustments");
                    denied.putArray("candidateCommands");
                    return nativeCall("denied_existing_" + approvalCalls, "submit_result", denied);
                }
            }
            return verdict("APPROVE");
        }

        AiResponse verdict(String decision) {
            var result = JSON.createObjectNode().put("decision", decision).put("requiresUserConfirmation", false)
                .put("reason", "Independent controlled current request assessment");
            if (decision.equals("DENY")) result.put("discussionStatus", "CLOSED");
            return nativeCall("v" + approvalCalls, "submit_result", result);
        }

        AiResponse bodyPage(AiRequest request, JsonNode input, boolean rereadInline) throws Exception {
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
                if (original.has("text") && !rereadInline || offset >= original.path("total").asInt()) continue;
                String id = "verification_page_" + ++pages;
                parts.put(id, part);
                return nativeCall(id, "request", JSON.createObjectNode().put("part", part).put("offset", offset).put("limit", 8000));
            }
            return null;
        }

        AiResponse decide(AiRequest request) throws Exception {
            if (isolationReadyJourney) return decideIsolatedFileTransfer(request);
            request.messages().stream().flatMap(m -> m.toolResult().stream()).reduce((a, b) -> b)
                .ifPresent(result -> {
                    if (patchReviewDeniedJourney && decisions == 4) {
                        assertTrue(result.error());
                        assertTrue(result.text().contains("APPROVAL_DENIED"), result.text());
                        assertEquals(1, patchVerdicts);
                        assertEquals(0, patchGrants + patchSends);
                    } else if (isolationReviewDeniedJourney && decisions == 4) {
                        assertTrue(result.error());
                        assertTrue(result.text().contains("APPROVAL_DENIED"));
                        assertTrue(result.text().contains("NOT_SENT"));
                        try {
                            assertEquals(1, journal.events(task).stream().filter(event -> event.type().equals("ACTION_REVIEW_NOT_SENT")).count());
                        } catch (Exception unavailable) { throw new AssertionError(unavailable); }
                        assertEquals(0, isolationSends + isolationGrants.size());
                    } else if (isolationRefusalJourney && (decisions == 4 || decisions == 6)) {
                        assertTrue(result.error());
                        assertTrue(result.text().contains("AGENT_ISOLATION_NOT_ADMITTED"));
                        assertTrue(result.text().contains("additionalDiskDemands"));
                        assertTrue(result.text().contains("67108864"));
                        assertTrue(result.text().contains("1073741824"));
                        assertTrue(result.text().contains("availableBytes"));
                        assertTrue(result.text().contains("requiredBytes"));
                        assertTrue(result.text().contains("reserveBytes"));
                    } else if (mode.confirmedRefusal() && decisions == 4) {
                        assertTrue(result.error());
                        assertTrue(result.text().contains("WORKSPACE_HOST_INSUFFICIENT"));
                        assertTrue(result.text().contains("availableBytes"));
                        assertTrue(result.text().contains("requiredBytes"));
                    } else assertFalse(result.error(), result.text());
                });
            int turn = decisions++;
            if (mode.confirmedRefusal() && turn == 4 || isolationRefusalJourney && turn == 6) throw stop;
            String tool;
            ObjectNode args;
            if (turn == 0) {
                tool = "read";
                args = JSON.createObjectNode().put("path", "index.html").put("offset", 0).put("limit", 100);
            } else if (turn == 1) {
                tool = "identify";
                args = (ObjectNode) JSON.readTree("{\"components\":[{\"component\":\"api\",\"root\":\".\",\"technology\":\"Actual static HTML\",\"runtime\":\"Source observation\",\"resources\":\"No database required\",\"uncertainties\":\"Unverified candidate\",\"evidence\":[\"o1\"]}]}");
            } else if (turn == 2 || isolationRefusalJourney && turn == 4) {
                tool = "plan";
                args = (ObjectNode) JSON.readTree("{\"applicationId\":\"demo\",\"explanation\":\"Plan the read source\",\"components\":[{\"id\":\"api\",\"sourceRoot\":\".\",\"dependencies\":[],\"evidence\":[\"o1\"],\"backend\":\"PROCESS\",\"mode\":\"DAEMON\",\"entrypoint\":\"current.sh\",\"arguments\":[],\"workingDirectory\":\"\",\"health\":{\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:8080/\",\"status\":200,\"timeout\":10},\"ports\":[],\"resources\":[],\"configuration\":[],\"databases\":[],\"secretReferences\":[]}],\"applicationHealth\":{\"component\":\"api\",\"health\":{\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:8080/\",\"status\":200,\"timeout\":10}}}");
            } else if (patchReviewDeniedJourney && (turn == 3 || turn == 4)) {
                tool = "patch";
                args = JSON.createObjectNode().put("component", "api").put("path", "index.html")
                    .put("beforeDigest", fixtureDigest("<html>actual controlled source</html>\n"))
                    .put("sourceRevision", DeploymentAgentSourceRevision.digest(material.snapshot().directory()));
                var edit = args.putArray("edits").addObject().put("line", 1);
                edit.putArray("removed").add("<html>actual controlled source</html>");
                edit.putArray("added").add(turn == 3 ? "<html>first proposal</html>" : "<html>different proposal</html>");
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
                assertEquals(isolationRefusalJourney && turn == 5 ? 5 : mode.name().startsWith("BUILD_") ? 6 : 3, turn);
                tool = "remote_read";
                args = JSON.createObjectNode().put("component", "api").put("path", "index.html").put("offset", 0).put("limit", 1);
            }
            if (tool.equals("plan")) {
                ((ObjectNode) args.path("components").get(0)).putObject("workspace").put("mode", "AUTO")
                    .put("minimumBuildBytes", 0).put("preferredBuildBytes", mode == VerificationMode.BUILD_LARGE ? 536870912 : isolationRefusalJourney && turn == 4 ? 0 : 134217728).put("explicitBytes", 0);
            }
            if (Set.of("plan", "prepare_tools", "build", "patch").contains(tool)) {
                var assessment = args.putObject("assessment");
                var basis = assessment.putArray("basis");
                var pending = JSON.readTree(request.messages().getLast().text()).at("/state/judgement/pending");
                if (pending.isEmpty()) basis.add("o1");
                else pending.forEach(id -> basis.add(id.asText()));
                assessment.put("suitability", "Use original observations").put("differences", "Candidate not yet observed").put("resolution", "RESOLVED").put("decision", "Read the source through this original proposal");
            }
            return nativeCall("verification_model_" + decisions, tool, args);
        }

        AiResponse decideIsolatedFileTransfer(AiRequest request) throws Exception {
            request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((first, last) -> last)
                .ifPresent(result -> {
                    if (isolationToolchainDeniedJourney && decisions == 6) {
                        assertTrue(result.error());
                        assertTrue(result.text().contains("APPROVAL_DENIED: the exact action was not sent"));
                        assertTrue(result.text().contains("Choose a different independently reviewed candidate action"));
                        assertTrue(result.text().contains("NOT_SENT"));
                        assertEquals(1, existingPreparationVerdicts, "The denied request was reviewed exactly once");
                        denialFeedbackObserved = true;
                    } else assertFalse(result.error(), result.text());
                });
            int turn = decisions++;
            String tool;
            ObjectNode args;
            if (turn < 2) {
                tool = "read";
                args = JSON.createObjectNode().put("path", turn == 0 ? "backend/main.go" : "web/server.mjs").put("offset", 0).put("limit", 100);
            } else if (turn == 2) {
                tool = "identify";
                args = (ObjectNode) JSON.readTree("{\"components\":[{\"component\":\"backend\",\"root\":\"backend\",\"technology\":\"Go HTTP source\",\"runtime\":\"Loopback HTTP\",\"resources\":\"Task-owned uploads\",\"uncertainties\":\"Unverified candidate\",\"evidence\":[\"o1\"]},{\"component\":\"web\",\"root\":\"web\",\"technology\":\"Node HTTP source\",\"runtime\":\"Public HTTP\",\"resources\":\"No persistent files\",\"uncertainties\":\"Unverified candidate\",\"evidence\":[\"o2\"]}]}");
            } else if (turn == 3) {
                tool = "plan";
                args = (ObjectNode) JSON.readTree("{\"applicationId\":\"demo\",\"explanation\":\"Source-backed Go backend and Node web graph\",\"components\":[],\"applicationHealth\":{\"component\":\"web\",\"health\":{\"type\":\"HTTP\",\"url\":\"http://127.0.0.1:49282/\",\"status\":200,\"timeout\":10}}}");
                var components = args.putArray("components");
                for (String id : List.of("backend", "web")) {
                    boolean backend = id.equals("backend");
                    int port = backend ? 49283 : 49282;
                    var part = components.addObject().put("id", id).put("sourceRoot", id).put("backend", "PROCESS")
                        .put("mode", "DAEMON").put("entrypoint", backend ? "backend" : "web.sh").put("workingDirectory", "");
                    part.putArray("dependencies");part.putArray("evidence").add(backend ? "o1" : "o2");part.putArray("arguments");
                    part.putObject("health").put("type", "HTTP").put("url", "http://127.0.0.1:" + port + "/").put("status", 200).put("timeout", 10);
                    part.putArray("ports").addObject().put("id", "http").put("protocol", "HTTP").put("host", port).put("target", port)
                        .put("exposure", backend ? "INTERNAL" : "EXTERNAL").put("accessUrl", backend ? "" : "http://192.0.2.10:49282/");
                    var resources = part.putArray("resources");
                    if (backend) resources.addObject().put("id", "uploads").put("path", "uploads").put("access", "READ_WRITE")
                        .put("schema", "v1").put("reversible", false).put("type", "FILE").put("seedFile", "").put("digest", "");
                    part.putArray("configuration").addObject().put("key", "PORT").put("value", port);
                    part.putArray("databases");part.putArray("secretReferences");
                    part.putObject("workspace").put("mode", "EXPLICIT").put("minimumBuildBytes", 0).put("preferredBuildBytes", 0)
                        .put("explicitBytes", backend ? 939524096L : 671088640L);
                }
                var assessment = args.putObject("assessment");
                var basis = assessment.putArray("basis");
                var pending = JSON.readTree(request.messages().getLast().text()).at("/state/judgement/pending");
                if (pending.isEmpty()) basis.add("o1").add("o2");else pending.forEach(id -> basis.add(id.asText()));
                assessment.put("suitability", "Use both observed sources").put("differences", "Candidates not yet observed")
                    .put("resolution", "RESOLVED").put("decision", "Prepare only the Go backend first");
            } else if (isolationExistingToolchainJourney && turn == 4) {
                tool = "analyze";
                args = JSON.createObjectNode().put("component", "backend").put("type", "GO_SERVICE");
            } else if (isolationExistingToolchainJourney && turn == 5) {
                tool = "prepare_tools";
                args = JSON.createObjectNode().put("component", "backend");
                args.putObject("recipe").put("version", "1.24").put("primary", "backend").put("secondary", "main.go")
                    .put("jvmTarget", "").put("servicePort", 49283);
                var assessment = args.putObject("assessment");
                var basis = assessment.putArray("basis");
                JSON.readTree(request.messages().getLast().text()).at("/state/judgement/pending").forEach(id -> basis.add(id.asText()));
                assessment.put("suitability", "Use the current Go module analysis").put("differences", "Existing immutable toolchain only")
                    .put("resolution", "RESOLVED").put("decision", "Probe the existing Go toolchain without shared installation");
            } else {
                assertEquals(isolationToolchainDeniedJourney ? 6 : 4, turn);
                tool = "remote_read";
                args = JSON.createObjectNode().put("component", "backend").put("path", "backend/main.go").put("offset", 0).put("limit", 1);
            }
            return nativeCall("verification_model_" + decisions, tool, args);
        }

        String expectedOutput() {
            return "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES=" + archive.byteCount() + "\nSHA256=" + archive.sha256() + "\nOWNER=0\n";
        }

        OperationResult<CommandResult> transport(CommandRequest request) throws Exception {
            if (patchReviewDeniedJourney && request.script().contains("'agent-patch'")) {
                patchSends++;
                assertEquals(2, patchVerdicts);
                assertEquals(1, patchGrants);
                assertTrue(new String(request.stdin(), StandardCharsets.UTF_8).contains("different proposal"));
                throw stop;
            }
            if (isolationExistingToolchainJourney) {
                var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                String input = new String(request.stdin(), StandardCharsets.UTF_8);
                if (input.equals(DeploymentAgentToolchainPrograms.resolver(layout))) {
                    var match = java.util.regex.Pattern.compile("'resolve' 'GO' '1.24' '-' '([0-9]+)' '([0-9a-f]{32})'$").matcher(request.script());
                    assertTrue(match.find());
                    var release = new TreeMap<String, String>();
                    release.put("format", "WTL_AGENT_RELEASE_1");release.put("operation", match.group(2));release.put("ecosystem", "GO");
                    release.put("branch", "1.24");release.put("requestedExact", "-");release.put("version", "1.24.13");
                    release.put("url", "https://go.dev/dl/go1.24.13.linux-amd64.tar.gz");release.put("algorithm", "sha256");release.put("digest", "c".repeat(64));
                    release.put("layout", "archive");release.put("platform", "linux-x86_64");release.put("resolverSourceSha256", fixtureDigest(input));
                    String encoded = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(release));
                    return reply("TOOLCHAIN_RELEASE=" + encoded + "\n");
                }
                if (request.script().contains(" 'readiness' '" + task.id() + "' '")) {
                    var parser = DeploymentAgentToolchainProgramStore.class.getDeclaredMethod("arguments", String.class,
                        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.class);
                    parser.setAccessible(true);
                    @SuppressWarnings("unchecked") var arguments = (List<String>) parser.invoke(null, request.script(), layout);
                    assertEquals(List.of("readiness", task.id().toString()), arguments.subList(0, 2));
                    controllerReadinessSends++;
                    return reply("AGENT_CONTROLLER_STORE=" + DeploymentAgentToolchainProgramStoreTest.observation(arguments.get(1), arguments.get(2), true) + "\n");
                }
                if (input.equals(DeploymentAgentToolchainPrograms.existingController(layout))) {
                    assertEquals(1, controllerReadinessSends);
                    assertEquals(1, existingPreparationVerdicts);
                    assertArrayEquals(DeploymentAgentToolchainPrograms.existingController(layout).getBytes(StandardCharsets.UTF_8), request.stdin());
                    assertTrue(request.script().contains("'prepare' 'GO' '1.24' '-'"));
                    existingPreparationSends++;
                    throw stop;
                }
            }
            if (request.script().startsWith("wtl_isolation='")) {
                assertTrue(isolationRefusalJourney || isolationUnknownJourney || isolationReadyJourney);
                var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                try (var decoded = gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.decode(request, layout)) {
                    isolationSends++;
                    assertTrue(isolationActions.add(decoded.action()));
                    assertEquals(isolationSends, isolationGrants.size());
                    var probe = new gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe(decoded.policy());
                    var facts = gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.reviewFacts(request, layout);
                    var allocated = isolationQuotaReplanJourney && creates > 0 ? Set.of(workspace.candidateId()) : Set.<String>of();
                    long required = decoded.scope().components().stream().mapToLong(part -> Math.multiplyExact(allocated.contains(part.candidate()) ? 1 : 2, part.workspaceBytes())).sum()
                        + decoded.scope().additionalDiskDemands().stream().mapToLong(demand -> demand.requiredBytes()).sum();
                    if (isolationReadyJourney) {
                        assertEquals(2, decoded.scope().components().size());
                        assertEquals(isolationQuotaReplanJourney ? 16 : 17, decoded.scope().components().stream().flatMap(part -> part.paths().stream()).distinct().count());
                        assertEquals(isolationQuotaReplanJourney ? 0 : 2, decoded.scope().components().stream().flatMap(part -> part.ports().stream()).distinct().count());
                        assertEquals(isolationQuotaReplanJourney ? (creates == 0 ? 1140850688L : 872415232L) : 3288334336L, required,
                            "Retain all unallocated candidates, future releases and additional PROBE demand");
                    }
                    isolationRequirements.add(required);
                    var body = JSON.createObjectNode().put("format", gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.FORMAT)
                        .put("sourceVersion", probe.sourceVersion()).put("action", decoded.action().toString()).put("bindingSha256", decoded.bindingSha256())
                        .put("policySha256", fixtureDigest(facts.get("trustedPolicy").toString())).put("scopeSha256", fixtureDigest(facts.get("acceptedScope").toString()))
                        .put("mode", decoded.mode().name()).put("queryTask", task.id().toString())
                        .put("status", isolationReadyJourney ? "READY" : isolationUnknownJourney ? "UNKNOWN" : "REFUSED")
                        .put("reason", isolationReadyJourney ? "READY" : isolationUnknownJourney ? "UNCONFIRMED" : "DISK_SPACE")
                        .put("pathsChecked", decoded.scope().components().stream().flatMap(part -> part.paths().stream()).distinct().count())
                        .put("unitsChecked", decoded.scope().components().size() * 2).put("portsChecked", decoded.scope().components().stream().flatMap(part -> part.ports().stream()).distinct().count());
                    var allocatedBody = body.putArray("allocatedCandidates");
                    allocated.stream().sorted().forEach(allocatedBody::add);
                    body.putArray("filesystems").addObject().put("device", "7").put("availableBytes", isolationQuotaReplanJourney ? isolationQuotaCapacityBytes : isolationReadyJourney ? 10 * 1073741824L : 1073741824L).put("requiredBytes", required).put("reserveBytes", 1073741824L);
                    body.putNull("memoryAvailableBytes").putNull("cpuCount").put("stable", false).putArray("protectedMetadata");
                    if (isolationReadyJourney) {
                        body.put("memoryAvailableBytes", 4 * decoded.scope().minimumMemAvailableBytes()).put("cpuCount", 2).put("stable", true);
                        var mandatory = gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.class
                            .getDeclaredMethod("protectedMandatoryPaths");
                        mandatory.setAccessible(true);
                        @SuppressWarnings("unchecked") var protectedPaths = (Set<String>) mandatory.invoke(decoded.policy());
                        var metadata = body.putArray("protectedMetadata");
                        for (String path : new TreeSet<>(protectedPaths)) metadata.addObject().put("path", path).put("kind", "ABSENT").put("identitySha256", "d".repeat(64));
                    }
                    String raw = body.toString();
                    var actual = reply(gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.FRAME + raw + "\n");
                    probe.parse(decoded, actual);
                    if (isolationUnknownJourney || isolationReadyJourney) assertThrows(SecurityException.class, () -> DeploymentAgentRemoteTools.confirmedIsolationRefusal(task, decoded, actual));
                    else {
                        isolationReceipts.put(decoded.action().toString(), raw);
                        assertEquals(raw, DeploymentAgentRemoteTools.confirmedIsolationRefusal(task, decoded, actual).receipt());
                        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentRemoteTools.confirmedIsolationRefusal(task, decoded,
                            succeeded(new CommandResult(Optional.of(0), gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.FRAME + raw + "\n", "", true))));
                        var foreign = body.deepCopy().put("action", UUID.randomUUID().toString());
                        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentRemoteTools.confirmedIsolationRefusal(task, decoded,
                            reply(gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.FRAME + foreign + "\n")));
                    }
                    if (isolationQuotaReplanJourney) isolationQuotaAdmitted = true;
                    return actual;
                }
            }
            if (new String(request.stdin(), StandardCharsets.UTF_8).equals(DeploymentAgentHelperReadiness.script())) {
                readinessReads++;
                if (isolationMissingHelperJourney || readinessReads == 1 && (mode == VerificationMode.CAPACITY_AFTER_INSTALL || mode == VerificationMode.CAPACITY_INSTALL_UNKNOWN))
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
                if (isolationQuotaReplanJourney) return reply(isolationQuotaCapacityBytes + " 1\n");
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
                assertEquals(isolationQuotaReplanJourney ? 268435456L : isolationReadyJourney ? 939524096L : mode == VerificationMode.BUILD_LARGE ? 671088640L : 268435456L, selectedQuota);
                if (isolationCancelAfterCreateJourney) requestCancel.run();
                return reply("CANDIDATE=" + workspace.candidateRoot() + "\n");
            }
            if (request.script().contains(" candidate-query-task ")) {
                candidateQuerySends++;
                return reply("CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n");
            }
            if (request.script().contains("'agent-open'")) {
                opens++;
                assertEquals(1, verifySends);
                return reply("SOURCE_REVISION=" + (patchReviewDeniedJourney
                    ? DeploymentAgentSourceRevision.digest(material.snapshot().directory()) : material.snapshot().sha256()) + "\n");
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
                if (isolationToolchainDeniedJourney) {
                    assertNotNull(alternativeGrant);
                    assertEquals(alternativeExact, gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, REVISION, request).exactAction());
                    alternativeSends++;
                }
                throw stop;
            }
            throw new AssertionError("Unrequested controlled operation");
        }
    }

    private static String fixtureDigest(String value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
