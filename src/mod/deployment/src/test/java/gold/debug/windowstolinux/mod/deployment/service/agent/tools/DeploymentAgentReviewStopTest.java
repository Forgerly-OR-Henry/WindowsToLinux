package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentSession;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentRecovery;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentInstallReviewIntent;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainReleaseTest;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Actual Agent native session, shared recipe renderer and complete fixed APP, with local controlled provider/transport only. */
class DeploymentAgentReviewStopTest extends DeploymentRuntimeFixture {
    enum Boundary {
        COMMAND_INVALID, BUILD_INVALID, COMMAND_REVIEW_UNKNOWN, BUILD_REVIEW_UNKNOWN, COMMAND_REVIEW_CANCELLED,
        COMMAND_EXECUTE_FAILURE, COMMAND_WRONG_SCRIPT, OPEN_INVALID,
        INSTALL_DENY, INSTALL_INVALID, INSTALL_REVIEW_FAILED, INSTALL_REVIEW_CANCELLED, INSTALL_REVIEW_UNKNOWN,
        INSTALL_GUARD_FAILURE, INSTALL_EXECUTE_SAME_EVENT, INSTALL_POSTPROBE_DENY,
        INSTALL_WRONG_TASK, INSTALL_WRONG_COMPONENT, INSTALL_WRONG_SOURCE,
        INSPECT_OPEN, INSPECT_CLOSED_REPEAT, INSPECT_UNSETTLED;

        boolean inspection() {
            return name().startsWith("INSPECT_");
        }

        boolean install() {
            return name().startsWith("INSTALL_");
        }

        boolean installUnsent() {
            return Set.of(INSTALL_DENY, INSTALL_INVALID, INSTALL_REVIEW_FAILED, INSTALL_REVIEW_CANCELLED, INSTALL_REVIEW_UNKNOWN).contains(this);
        }

        boolean build() {
            return this == BUILD_INVALID || this == BUILD_REVIEW_UNKNOWN;
        }

        boolean reviewOutcome() {
            return this == COMMAND_REVIEW_UNKNOWN || this == BUILD_REVIEW_UNKNOWN || this == COMMAND_REVIEW_CANCELLED;
        }
    }

    private static final String REVISION = "a".repeat(64);

    private static final String SCRIPT = "printf artifact > $WTL_OUTPUT/run.sh";

    private final ObjectMapper json = new ObjectMapper();

    @Override
    protected gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts interactionPrompts() {
        return (gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts) Proxy.newProxyInstance(
            gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts.class},
            (proxy, method, args) -> {
                if (method.getName().equals("approve")) return true;
                throw new AssertionError("No text, transfer or other unrequested interaction");
            });
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {"COMMAND_INVALID", "BUILD_INVALID", "COMMAND_REVIEW_UNKNOWN", "BUILD_REVIEW_UNKNOWN",
        "COMMAND_REVIEW_CANCELLED", "COMMAND_EXECUTE_FAILURE", "COMMAND_WRONG_SCRIPT", "OPEN_INVALID"})
    void realDispatcherProofSettlesOnlyMatchingUnsentCommandOrSingleRecipeBuild(Boundary boundary) throws Exception {
        runBoundary(boundary);
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {"INSTALL_EXECUTE_SAME_EVENT", "INSTALL_POSTPROBE_DENY",
        "INSTALL_WRONG_TASK", "INSTALL_WRONG_COMPONENT", "INSTALL_WRONG_SOURCE"})
    void actualFirstInstallReviewProofDoesNotCoverGuardExecuteProbeOrWrongPreparationIdentity(Boundary boundary) throws Exception {
        runBoundary(boundary);
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {"INSTALL_DENY", "INSTALL_INVALID", "INSTALL_REVIEW_FAILED", "INSTALL_REVIEW_CANCELLED", "INSTALL_REVIEW_UNKNOWN"})
    void actualUnsentFirstInstallReviewProofRetainsStrictIdentityAndSettlesOnlyItsOriginalAttempt(Boundary boundary) throws Exception {
        runBoundary(boundary);
    }

    @Test
    void installSendGuardFailureAfterIndependentGrantIsNotAnUnsentApprovalProof() throws Exception {
        runBoundary(Boundary.INSTALL_GUARD_FAILURE);
    }

    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {"INSPECT_OPEN", "INSPECT_CLOSED_REPEAT", "INSPECT_UNSETTLED"})
    void inspectRefusalReturnsNativeFeedbackButRequiresFreshReviewAndSettledEffects(Boundary boundary) throws Exception {
        runBoundary(boundary);
    }

    private void runBoundary(Boundary boundary) throws Exception {
        var source = Files.createDirectories(root.resolve("source"));
        Files.writeString(source.resolve("index.html"), "<html>actual source</html>\n");
        Files.writeString(Files.createDirectories(source.resolve("public")).resolve("style.css"), "body {color:black}\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("boundary-server");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var retained = new AtomicReference<Frame>();
            var finished = new CompletableFuture<Void>();
            try (var recovery = actual.tasks().registerRecovery("agent-review-stop-local", (previous, original) -> {
                assertEquals(previous.id(), original.id());
                try {
                    return retained.get().recover(original);
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    throw error;
                }
            })) {
                var handle = actual.tasks().submit(new TaskRequest("agent-review-stop-local", "Exact Agent dispatcher boundary",
                    List.of(server.id()), true), task -> {
                    try {
                        var selection = actual.file().sources().selectDirectory(source, task);
                        try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task);
                             var archive = new DeploymentSourceArchiver(actual.file()).prepare(material, 1048576, task)) {
                            var frame = new Frame(boundary, actual, task, server, material, archive);
                            retained.set(frame);
                            frame.check();
                        }
                        if (retained.get().needsRecovery) {
                            var frame = retained.get();
                            var original = frame.installStop == null ? frame.dispatcherStop.originalFailure() : frame.installStop.originalFailure();
                            var problem = original instanceof ApiException api ? Optional.of(api.problem())
                                : ((DeploymentAiJson.CapabilityOutcomeException) original).problem();
                            return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), problem);
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
                finished.get(30, TimeUnit.SECONDS);
                if (failure.get() != null)
                    throw new AssertionError("Controlled dispatcher boundary failed: " + boundary, failure.get());
                if (retained.get().needsRecovery) {
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (handle.snapshot().state() != TaskState.UNKNOWN && System.nanoTime() < until)
                        Thread.sleep(20);
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
                    var recovered = reconcileReady(runtime, handle);
                    if (failure.get() != null)
                        throw new AssertionError("Original dispatcher recovery failed", failure.get());
                    assertEquals(TaskState.ENDED, recovered.state(), recovered.blockers().toString());
                    assertEquals(Optional.of(TaskResult.FAILED), recovered.outcome());
                } else assertEquals(TaskResult.SUCCEEDED,
                    handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
            }
        }
    }

    private final class Frame {
        final Boundary boundary;

        final ModContext actual;

        final TaskContext task;

        final ServerId server;

        final DeploymentSourceMaterial material;

        final DeploymentSourceArchive archive;

        final DeploymentRemoteWorkspace workspace;

        final DeploymentAgentActionLog journal;

        final String sourceRevision;

        final ApiException executeFailure = new ApiException(new Problem(ProblemCode.IO_FAILURE,
            "CONTROLLED_EXECUTE_FAILURE", "Actual execute was called before its failure", Map.of(), Optional.empty()));

        final Problem reviewFailure;

        DeploymentAgentCommandNotSentException dispatcherStop;

        DeploymentAgentPrepareNotSentException installStop;

        final ApiException installBoundaryFailure = new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
            "DEPLOYMENT_COMMAND_REVIEW_DENIED", "Controlled failure outside installer approval", Map.of(), Optional.empty()));

        int languageCalls, reviews, agentReviews, agentSends;

        int installReviews, installSends, probes;

        int installAuthorizations;

        int inspectionAuthorizations, inspectionSends;

        int inspectionStatusAuthorizations, inspectionStatusSends;

        String inspectionBinding = "", inspectionRequest = "", inspectionObservation = "";

        String inspectionReceipt = "", reviewedStatusRequest = "";

        Set<ActionTarget> reviewedInstallTargets = Set.of();

        boolean present, recovering, needsRecovery;

        String reviewedScript = "", reviewedRequest = "";

        final RemoteSession peer;

        final ModContext context;

        Frame(Boundary boundary, ModContext actual, TaskContext task, ServerId server,
              DeploymentSourceMaterial material, DeploymentSourceArchive archive) throws Exception {
            this.boundary = boundary;
            this.actual = actual;
            this.task = task;
            this.server = server;
            this.material = material;
            this.archive = archive;
            reviewFailure = new Problem(boundary == Boundary.COMMAND_REVIEW_CANCELLED || boundary == Boundary.INSTALL_REVIEW_CANCELLED
                ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                "CONTROLLED_AI_TRANSPORT_STOP", "Original approval transport did not return a decision", Map.of(), Optional.empty());
            workspace = new DeploymentRemoteWorkspace("demo-api", archive.sha256());
            sourceRevision = DeploymentAgentSourceRevision.digest(material.snapshot().directory());
            journal = new DeploymentAgentActionLog(actual.db());
            peer = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "facts" -> new RemoteFacts("Linux", "x86_64", "SHA256:" + "b".repeat(43));
                    case "close" -> null;
                    case "execute" -> execute((CommandRequest) args[0]);
                    default -> throw new AssertionError("No real SSH operation: " + method.getName());
                });
            var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("configurationRevision")) {
                        if (boundary == Boundary.INSTALL_GUARD_FAILURE && installAuthorizations > 0 && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                            frame.getClassName().equals(DeploymentAgentRemoteTools.class.getName()) && frame.getMethodName().startsWith("lambda$new$"))))
                            throw installBoundaryFailure;
                        return REVISION;
                    }
                    assertEquals("call", method.getName());
                    assertSame(task, args[args.length - 1]);
                    var request = (AiRequest) args[0];
                    return request.purpose() == AiPurpose.APPROVAL ? review(request) : response(decision(request));
                });
            var linux = (LinuxService) Proxy.newProxyInstance(LinuxService.class.getClassLoader(), new Class<?>[]{LinuxService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("pendingTransfers")) return List.of();
                    assertEquals("connect", method.getName());
                    assertEquals(server, args[0]);
                    assertSame(task, args[1]);
                    return peer;
                });
            context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("ai")) return ai;
                    if (method.getName().equals("linux")) return linux;
                    try {
                        return method.invoke(actual, args);
                    } catch (InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
        }

        void check() throws Exception {
            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), DeploymentAgentInputs.targetDigest(server, peer),
                REVISION, "FULL_CONTROL", true));
            journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
            journal.event(task, "TARGET_PROFILE", "", DeploymentAgentInputs.targetProfile(actual.servers().get(server, task)));
            journal.event(task, "REMOTE_TARGET", "", DeploymentAgentInputs.physicalTarget(actual.servers().get(server, task)));
            var reviewed = new DeploymentCommandReviewScope(context, actual.interaction(), task, "FULL_CONTROL", true, () -> REVISION);
            var projects = new DeploymentAgentProjectChannel(peer, reviewed.interaction(), server, task, REVISION, () -> REVISION);
            var installHost = (gold.debug.windowstolinux.appapi.task.action.InteractionService) Proxy.newProxyInstance(
                gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("approveReviewedAction")) {
                        var intent = (gold.debug.windowstolinux.appapi.task.action.ActionIntent) args[0];
                        if (boundary.inspection()) {
                            String operation = intent.semantics().orElseThrow().operation();
                            if (operation.equals("deployment.model.command.status.v1")) {
                                inspectionStatusAuthorizations++;
                                assertEquals(reviewedStatusRequest, hash(intent.exactAction()));
                            } else {
                                inspectionAuthorizations++;
                                assertEquals("deployment.model.command.v1", operation, "Denied baseline never reaches host authority");
                                assertEquals(reviewedRequest, hash(intent.exactAction()));
                            }
                        }
                        if (intent.semantics().orElseThrow().operation().equals("deployment.helper.install.files.custom.v1")) {
                            installAuthorizations++;
                            assertSame(task, args[args.length - 1]);
                            assertEquals(reviewedRequest, intent.exactAction());
                            assertEquals(reviewedInstallTargets, intent.semantics().orElseThrow().targets(),
                                "The host grant must review the same complete Agent target set as APP");
                        }
                    }
                    try {
                        return method.invoke(actual.interaction(), args);
                    } catch (InvocationTargetException failed) {
                        throw failed.getCause();
                    }
                });
            try (var sources = new DeploymentAgentSources(reviewed, task, material);
                 var remote = boundary.install() || boundary.inspection() ? new DeploymentAgentRemoteTools(context, peer, installHost, server, task,
                     archive, material, REVISION, () -> REVISION, journal, Optional.empty(), "FULL_CONTROL", true) : null) {
                if (boundary.inspection()) {
                    checkInspection(remote);
                    return;
                }
                var buildTools = new DeploymentAgentBuildTools(reviewed, task, peer, server, REVISION, () -> {
                }, sources, projects);
                var port = new DeploymentAgentDeliveryPort() {
                    public DeploymentSourceBrowser source(String component) throws Exception {
                        return sources.browser(component);
                    }

                    public Map<String, String> inspect() {
                        throw new AssertionError("No invented host analysis");
                    }

                    public Prepared prepare(String application, DeploymentAgentPlan.Part component) throws Exception {
                        assertEquals("demo", application);
                        assertEquals("api", component.id());
                        if (remote != null) {
                            try {
                                return remote.prepare(application, component);
                            } catch (DeploymentAgentPrepareNotSentException stopped) {
                                installStop = stopped;
                                if (Set.of(Boundary.INSTALL_WRONG_TASK, Boundary.INSTALL_WRONG_COMPONENT, Boundary.INSTALL_WRONG_SOURCE).contains(boundary)) {
                                    TaskContext boundTask = boundary == Boundary.INSTALL_WRONG_TASK ? (TaskContext) Proxy.newProxyInstance(
                                        TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class}, (proxy, method, args) -> method.invoke(task, args)) : task;
                                    throw new DeploymentAgentPrepareNotSentException(stopped.originalFailure(), boundTask,
                                        boundary == Boundary.INSTALL_WRONG_COMPONENT ? "other" : component.id(),
                                        boundary == Boundary.INSTALL_WRONG_SOURCE ? "f".repeat(64) : material.snapshot().sha256(),
                                        server, REVISION, installerDigest(), hash(reviewedRequest));
                                }
                                throw stopped;
                            }
                        }
                        present = true;
                        return new Prepared(workspace, projects.open(workspace, "ordinary"));
                    }

                    public void validateCapability(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part) throws Exception {
                        buildTools.validateCapability(request, part);
                    }

                    public String capability(DeploymentAgentProposal.Capability request, DeploymentAgentPlan.Part part,
                                             Prepared candidate, String revision, String binding, String applicationId) throws Exception {
                        try {
                            return buildTools.run(request, part, candidate, revision);
                        } catch (DeploymentAgentCommandNotSentException stopped) {
                            dispatcherStop = stopped;
                            throw stopped;
                        }
                    }

                    public CommandFeedback command(TaskContext original, Prepared candidate, String revision, String script, Duration timeout) throws Exception {
                        assertSame(task, original);
                        try {
                            return projects.command(candidate, revision,
                                boundary == Boundary.COMMAND_WRONG_SCRIPT ? "printf different" : script, timeout);
                        } catch (DeploymentAgentCommandNotSentException stopped) {
                            dispatcherStop = stopped;
                            throw stopped;
                        }
                    }

                    public String read(TaskContext original, Prepared candidate, String revision, String path, int offset, int limit) {
                        throw new AssertionError();
                    }

                    public String patch(TaskContext original, Prepared candidate, String revision, DeploymentAgentPatch patch) {
                        throw new AssertionError();
                    }

                    public String seal(TaskContext original, Prepared candidate, String revision) {
                        throw new AssertionError("Failed review creates no artifact");
                    }

                    public OperationResult<Published> deliver(TaskContext original, DeploymentAgentPlan plan, Map<String, Prepared> candidates,
                                                              Map<String, String> revisions, Map<String, String> artifacts, int remaining) {
                        throw new AssertionError("Failed review never publishes");
                    }

                    public void close() {
                    }
                };
                var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Build actual static source"),
                    new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()), port, journal,
                    "FULL_CONTROL", true, REVISION, () -> REVISION);
                Exception error;
                try (var workflow = DeploymentWorkflowAuthorization.open(context, task, server, REVISION, "FULL_CONTROL", true,
                    "source=" + material.snapshot().sha256() + "\narchive=" + archive.sha256())) {
                    error = assertThrows(Exception.class, session::run);
                }
                var events = journal.events(task);
                if (boundary.install()) {
                    checkInstall(error, events);
                    return;
                }
                assertEquals(boundary == Boundary.OPEN_INVALID ? 0 : 1, agentReviews, "The actual complete APP reaches its selected boundary");
                assertEquals(boundary.build() ? 6 : 4, languageCalls, "The stop never sends correction or retries the model decision");
                assertTrue(events.stream().noneMatch(event -> event.type().equals("SEALED_ARTIFACT")
                    || event.type().equals("AGENT_OBSERVATION") && event.action().equals("ALTERNATIVE_REQUIRED")));
                if (boundary == Boundary.COMMAND_EXECUTE_FAILURE) {
                    assertSame(executeFailure, error);
                    assertEquals(1, agentSends);
                    assertTrue(events.stream().noneMatch(event -> event.type().endsWith("_NOT_SENT")));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                    return;
                }
                assertEquals(0, agentSends);
                if (boundary.reviewOutcome()) {
                    var classified = assertInstanceOf(DeploymentAiJson.CapabilityOutcomeException.class, error);
                    assertEquals(boundary == Boundary.COMMAND_REVIEW_CANCELLED ? TaskResult.CANCELLED : TaskResult.UNKNOWN, classified.outcome());
                    assertSame(reviewFailure, classified.problem().orElseThrow());
                } else
                    assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", assertInstanceOf(ApiException.class, error).problem().eventCode());
                if (boundary == Boundary.OPEN_INVALID) {
                    assertNull(dispatcherStop);
                    assertTrue(events.stream().noneMatch(event -> event.type().endsWith("_NOT_SENT")));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                    return;
                }
                var stopped = Objects.requireNonNull(dispatcherStop, "The actual dispatcher alone produces unsent proof");
                assertSame(error, stopped.originalFailure());
                assertSame(error, stopped.getCause());
                assertEquals(hash(reviewedScript), stopped.scriptSha256());
                int seconds = boundary.build() ? 60 : 10;
                assertTrue(stopped.matches(task.id(), workspace.candidateId(), sourceRevision, seconds));
                assertFalse(stopped.matches(UUID.randomUUID(), workspace.candidateId(), sourceRevision, seconds));
                assertFalse(stopped.matches(task.id(), "other-candidate", sourceRevision, seconds));
                assertFalse(stopped.matches(task.id(), workspace.candidateId(), "f".repeat(64), seconds));
                assertFalse(stopped.matches(task.id(), workspace.candidateId(), sourceRevision, seconds + 1));
                assertTrue(stopped.dispatcherProof().contains("requestSha256=" + hash(reviewedRequest)));
                assertTrue(stopped.dispatcherProof().length() < 600);
                assertFalse(stopped.dispatcherProof().contains(reviewedScript));
                if (boundary == Boundary.COMMAND_WRONG_SCRIPT) {
                    assertNotEquals(hash(SCRIPT), stopped.scriptSha256());
                    assertTrue(events.stream().noneMatch(event -> event.type().endsWith("_NOT_SENT")));
                    assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                    return;
                }
                String kind = boundary.build() ? "CAPABILITY" : "COMMAND";
                var executing = events.stream().filter(event -> event.type().equals(kind + "_EXECUTING")
                    && (kind.equals("COMMAND") || event.detail().equals("build"))).findFirst().orElseThrow();
                assertEquals(1, events.stream().filter(event -> event.type().equals(kind + "_NOT_SENT")
                    && event.action().equals(executing.action()) && event.detail().equals(DeploymentAgentCommandNotSentException.DETAIL)).count());
                assertEquals(stopped.dispatcherProof(), events.stream().filter(event -> event.type().equals(kind + "_DISPATCH_NOT_SENT")
                    && event.action().equals(executing.action())).findFirst().orElseThrow().detail());
                assertTrue(DeploymentAgentActionLog.effectsSettled(events));
                assertTrue(present, "A settled unsent action does not dispose its actual original candidate");
                journal.state(task, "UNKNOWN");
                needsRecovery = true;
            }
        }

        private void checkInspection(DeploymentAgentRemoteTools remote) throws Exception {
            if (boundary == Boundary.INSPECT_UNSETTLED)
                journal.event(task, "PREPARE_EXECUTING", "controlled-unclosed", "b".repeat(64));
            var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Evaluate this controlled source"),
                new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()), remote, journal,
                "FULL_CONTROL", true, REVISION, () -> REVISION);
            try (var workflow = DeploymentWorkflowAuthorization.open(context, task, server, REVISION, "FULL_CONTROL", true,
                "source=" + material.snapshot().sha256() + "\narchive=" + archive.sha256())) {
                if (boundary == Boundary.INSPECT_UNSETTLED) {
                    var failure = assertThrows(ApiException.class, session::run);
                    assertEquals("DEPLOYMENT_ALTERNATIVE_PATH_REQUIRES_RECOVERY", failure.problem().eventCode());
                } else if (boundary == Boundary.INSPECT_CLOSED_REPEAT) {
                    var failure = assertThrows(IllegalStateException.class, session::run);
                    assertTrue(failure.getMessage().contains("Repeated rejected inspect parameters without new validated progress"));
                } else {
                    var failure = assertThrows(IllegalStateException.class, session::run);
                    assertEquals("Deployment model cannot continue", failure.getMessage(), "Controlled model ends unable after exercising feedback, never publishes");
                }
            }
            var events = journal.events(task);
            assertEquals(boundary == Boundary.INSPECT_UNSETTLED ? 1 : boundary == Boundary.INSPECT_CLOSED_REPEAT ? 3 : 4, languageCalls);
            assertEquals(boundary == Boundary.INSPECT_OPEN ? 3 : 1, reviews, "OPEN selects a fresh action and its independently reviewed status; CLOSED cannot re-review unchanged facts");
            assertEquals(boundary == Boundary.INSPECT_OPEN ? 1 : 0, inspectionAuthorizations);
            assertEquals(boundary == Boundary.INSPECT_OPEN ? 1 : 0, inspectionSends);
            assertEquals(boundary == Boundary.INSPECT_OPEN ? 1 : 0, inspectionStatusAuthorizations);
            assertEquals(boundary == Boundary.INSPECT_OPEN ? 1 : 0, inspectionStatusSends);
            assertEquals(0, installSends);
            assertTrue(events.stream().noneMatch(event -> Set.of("PREPARE_RESULT", "CANDIDATE", "SEALED_ARTIFACT", "PUBLISHED").contains(event.type())));
            assertEquals(boundary != Boundary.INSPECT_UNSETTLED, DeploymentAgentActionLog.effectsSettled(events));
            assertEquals(boundary == Boundary.INSPECT_UNSETTLED ? 0 : boundary == Boundary.INSPECT_OPEN ? 1 : 2,
                events.stream().filter(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("ALTERNATIVE_REQUIRED")).count());
            if (boundary == Boundary.INSPECT_OPEN)
                gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.releaseKnown(context, task);
        }

        OperationResult<Void> recover(TaskContext original) throws Exception {
            assertSame(task, original, "Only the host's actual original reconciliation callback may recover");
            assertTrue(needsRecovery);
            int oldReviews = agentReviews, oldCalls = languageCalls;
            recovering = true;
            var recovered = new DeploymentAgentRecovery(context, journal).reconcile(original, journal.find(original).orElseThrow());
            assertEquals(TaskResult.FAILED, recovered.outcome(), recovered.problem().toString());
            assertFalse(present);
            if (boundary.install())
                assertEquals(0, probes, "An exact unsent installation needs no installed helper, query or cleanup");
            assertEquals(oldReviews, agentReviews, "Original recovery never replays or approves the failed executable");
            assertEquals(oldCalls, languageCalls);
            return recovered;
        }

        private void checkInstall(Exception error, List<DeploymentAgentActionLog.Event> events) throws Exception {
            assertEquals(boundary == Boundary.INSTALL_DENY ? 5 : 4, languageCalls, "Only a complete native denial reaches the model for its own next choice");
            assertEquals(Set.of(Boundary.INSTALL_INVALID, Boundary.INSTALL_WRONG_TASK, Boundary.INSTALL_WRONG_COMPONENT, Boundary.INSTALL_WRONG_SOURCE).contains(boundary) ? 2 : 1, installReviews, "Native schema correction stays in the original review before stopping");
            assertEquals(Set.of(Boundary.INSTALL_GUARD_FAILURE, Boundary.INSTALL_EXECUTE_SAME_EVENT, Boundary.INSTALL_POSTPROBE_DENY).contains(boundary)
                ? 1 : 0, installAuthorizations);
            assertEquals(0, agentReviews);
            assertEquals(0, agentSends);
            assertTrue(events.stream().noneMatch(event -> Set.of("CANDIDATE", "PREPARE_RESULT", "COMMAND_EXECUTING").contains(event.type())
                || boundary != Boundary.INSTALL_DENY && event.type().equals("AGENT_OBSERVATION") && event.action().equals("ALTERNATIVE_REQUIRED")));
            if (boundary == Boundary.INSTALL_GUARD_FAILURE || boundary == Boundary.INSTALL_EXECUTE_SAME_EVENT) {
                assertSame(installBoundaryFailure, error, "Identical event names outside approval do not establish unsent proof");
                assertEquals(boundary == Boundary.INSTALL_EXECUTE_SAME_EVENT ? 1 : 0, installSends);
                assertNull(installStop);
            } else if (boundary == Boundary.INSTALL_POSTPROBE_DENY) {
                var classified = assertInstanceOf(DeploymentAiJson.CapabilityOutcomeException.class, error);
                assertEquals(TaskResult.UNKNOWN, classified.outcome());
                assertEquals("DEPLOYMENT_HELPER_POSTINSTALL_UNCONFIRMED", classified.problem().orElseThrow().eventCode());
                assertEquals(1, installSends);
                assertNull(installStop);
            } else {
                assertEquals(0, installSends);
                assertNotNull(installStop);
                assertSame(installStop.originalFailure(), installStop.getCause());
                if (boundary == Boundary.INSTALL_DENY) {
                    assertInstanceOf(IllegalStateException.class, error);
                    assertTrue(events.stream().anyMatch(event -> event.type().equals("AGENT_OBSERVATION") && event.action().equals("ALTERNATIVE_REQUIRED")));
                } else assertSame(error, installStop.originalFailure());
                if (Set.of(Boundary.INSTALL_REVIEW_FAILED, Boundary.INSTALL_REVIEW_CANCELLED, Boundary.INSTALL_REVIEW_UNKNOWN).contains(boundary)) {
                    var classified = assertInstanceOf(DeploymentAiJson.CapabilityOutcomeException.class, error);
                    assertEquals(installOutcome(), classified.outcome());
                    assertSame(reviewFailure, classified.problem().orElseThrow());
                } else
                    assertEquals(boundary == Boundary.INSTALL_DENY ? "DEPLOYMENT_COMMAND_REVIEW_DENIED" : "DEPLOYMENT_COMMAND_REVIEW_INVALID",
                        assertInstanceOf(ApiException.class, installStop.originalFailure()).problem().eventCode());
            }
            if (!boundary.installUnsent()) {
                assertTrue(events.stream().noneMatch(event -> event.type().endsWith("_NOT_SENT")));
                assertFalse(DeploymentAgentActionLog.effectsSettled(events));
                return;
            }
            assertTrue(installStop.matches(task, "api", material.snapshot().sha256()));
            assertFalse(installStop.matches(task, "other", material.snapshot().sha256()));
            assertFalse(installStop.matches(task, "api", "f".repeat(64)));
            var executing = events.stream().filter(event -> event.type().equals("PREPARE_EXECUTING")).findFirst().orElseThrow();
            assertEquals(1, events.stream().filter(event -> event.type().equals("PREPARE_NOT_SENT") && event.action().equals(executing.action())
                && event.detail().equals(DeploymentAgentPrepareNotSentException.DETAIL)).count());
            String proof = events.stream().filter(event -> event.type().equals("PREPARE_DISPATCH_NOT_SENT")
                && event.action().equals(executing.action())).findFirst().orElseThrow().detail();
            assertEquals(installStop.dispatcherProof(), proof);
            assertTrue(proof.contains("installerSha256=" + installerDigest()));
            byte[] installer = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.render().getBytes(StandardCharsets.UTF_8);
            try (var request = new CommandRequest("exec /bin/bash --noprofile --norc -s", installer, Duration.ofHours(2), 131072);
                 var changed = new CommandRequest("exec /bin/bash --noprofile --norc -s", "different input".getBytes(StandardCharsets.UTF_8), Duration.ofHours(2), 131072)) {
                var intent = RemoteActions.command(server, REVISION, request).withSemantics(new ActionSemantics("deployment.helper.install.v1",
                    gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper.installationTargets(server, gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original())));
                assertTrue(DeploymentAgentRemoteTools.ownsInstallReview(intent, request, task, task, server, REVISION));
                assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(RemoteActions.command(server, REVISION, request),
                    request, task, task, server, REVISION));
                assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(DeploymentAgentInstallReviewIntent.upgrade(intent, request, server, REVISION),
                    request, task, task, server, REVISION), "The expanded intent cannot be reused as the original preparation proof");
                var extraTargets = new HashSet<>(intent.semantics().orElseThrow().targets());
                extraTargets.add(new ActionTarget(server.id(), "file", "/unrelated"));
                assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(intent.withSemantics(new ActionSemantics("deployment.helper.install.v1",
                    extraTargets)), request, task, task, server, REVISION));
                assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(intent.withSemantics(new ActionSemantics("deployment.helper.install.tracked.v1",
                        intent.semantics().orElseThrow().targets())),
                    request, task, task, server, REVISION));
                assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(intent, changed, task, task, server, REVISION), "Changed complete input cannot be the reviewed install");
                assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(intent, request, task, task, new ServerId("other"), REVISION));
                assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(intent, request, task, task, server, "f".repeat(64)));
                assertFalse(DeploymentAgentRemoteTools.ownsInstallReview(intent.withSemantics(new ActionSemantics("deployment.helper.probe.v1",
                    intent.semantics().orElseThrow().targets())), request, task, task, server, REVISION));
            } finally {
                Arrays.fill(installer, (byte) 0);
            }
            assertTrue(proof.contains("requestSha256=" + hash(reviewedRequest)));
            assertTrue(proof.length() < 600);
            assertFalse(proof.contains("exec /bin/bash"));
            assertTrue(DeploymentAgentPrepareNotSentException.matchesProof(proof, task.id(), "api", material.snapshot().sha256(), server.id(), REVISION));
            assertFalse(DeploymentAgentPrepareNotSentException.matchesProof(proof + " extra", task.id(), "api", material.snapshot().sha256(), server.id(), REVISION));
            assertTrue(DeploymentAgentActionLog.effectsSettled(events));
            journal.state(task, "UNKNOWN");
            needsRecovery = true;
        }

        private TaskResult installOutcome() {
            return boundary == Boundary.INSTALL_REVIEW_CANCELLED ? TaskResult.CANCELLED
                : boundary == Boundary.INSTALL_REVIEW_FAILED ? TaskResult.FAILED : TaskResult.UNKNOWN;
        }

        private String installerDigest() throws Exception {
            var match = java.util.regex.Pattern.compile("stdinSha256=([0-9a-f]{64})").matcher(reviewedRequest);
            assertTrue(match.find());
            return match.group(1);
        }

        OperationResult<AiResponse> review(AiRequest request) throws Exception {
            assertEquals(AiPurpose.APPROVAL, request.purpose());
            var input = json.readTree(request.messages().getLast().text());
            if (boundary.inspection()) {
                assertEquals("FIRST_USER_MESSAGE_AFTER_BASE_SYSTEM", input.path("currentEvidence").asText());
                assertFalse(input.has("currentRequest"), "The final USER contains dynamic review state, not the immutable action");
                assertEquals(AiRole.USER, request.messages().get(1).role());
                var fixed = json.readTree(request.messages().get(1).text());
                assertEquals(task.id().toString(), fixed.path("task").asText());
                assertEquals(fixed.path("reviewBinding"), input.path("reviewBinding"));
                assertTrue(fixed.path("reviewBinding").asText().matches("[0-9a-f]{64}"));
                assertEquals(json.valueToTree(List.of(server.id())), fixed.at("/currentRequest/serverTargets"));
                input = fixed;
            }
            var facts = input.has("currentRequest") ? input.path("currentRequest") : input;
            assertEquals(server.id(), facts.path("serverTargets").get(0).asText());
            if (boundary.inspection()) return inspectionReview(request, input, facts);
            boolean installation = facts.path("operation").asText().equals("deployment.helper.install.files.custom.v1");
            boolean executable = facts.path("exactAction").asText().contains("'agent-run'");
            boolean refused = installation && Set.of(Boundary.INSTALL_REVIEW_FAILED, Boundary.INSTALL_REVIEW_CANCELLED,
                Boundary.INSTALL_REVIEW_UNKNOWN, Boundary.INSTALL_DENY, Boundary.INSTALL_INVALID, Boundary.INSTALL_WRONG_TASK,
                Boundary.INSTALL_WRONG_COMPONENT, Boundary.INSTALL_WRONG_SOURCE).contains(boundary)
                || boundary == Boundary.INSTALL_POSTPROBE_DENY && facts.path("operation").asText().equals("deployment.helper.probe.v1")
                || executable && (boundary.reviewOutcome() || boundary != Boundary.COMMAND_EXECUTE_FAILURE)
                || boundary == Boundary.OPEN_INVALID && !recovering && facts.path("exactAction").asText().contains("'agent-open'");
            if (input.has("currentRequest") && !refused) {
                var page = executablePage(request, input);
                if (page != null) return page;
            }
            reviews++;
            if (facts.path("operation").asText().equals("deployment.helper.install.files.custom.v1")) {
                installReviews++;
                assertEquals("EXECUTABLE_SCRIPT", facts.path("inputKind").asText());
                assertEquals(REVISION, facts.path("businessRevision").asText());
                var targets = new HashSet<ActionTarget>();
                for (var row : facts.path("resourceTargets"))
                    targets.add(new ActionTarget(row.path("serverId").asText(), row.path("kind").asText(), row.path("id").asText()));
                reviewedInstallTargets = Set.copyOf(targets);
                assertEquals(gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentHelperBootstrapEvidence.targets(server), targets);
                assertTrue(targets.stream().allMatch(target -> target.serverId().equals(server.id())));
                assertTrue(targets.contains(new ActionTarget(server.id(), "deployment-helper", DeploymentManagedHelperBundle.PATH)));
                assertTrue(targets.contains(new ActionTarget(server.id(), "file", DeploymentManagedHelperBundle.BUILD_ENTRY_PATH)));
                reviewedRequest = facts.path("exactAction").asText();
                if (Set.of(Boundary.INSTALL_REVIEW_FAILED, Boundary.INSTALL_REVIEW_CANCELLED, Boundary.INSTALL_REVIEW_UNKNOWN).contains(boundary))
                    return new OperationResult<>(installOutcome(), Optional.empty(), Optional.of(reviewFailure));
                if (boundary == Boundary.INSTALL_DENY)
                    return reviewResponse(request, "review_" + reviews,
                        "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Exact installation was refused\"}");
                if (Set.of(Boundary.INSTALL_INVALID, Boundary.INSTALL_WRONG_TASK, Boundary.INSTALL_WRONG_COMPONENT, Boundary.INSTALL_WRONG_SOURCE).contains(boundary))
                    return reviewResponse(request, "review_" + reviews, "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false}");
            }
            if (boundary == Boundary.INSTALL_POSTPROBE_DENY && facts.path("operation").asText().equals("deployment.helper.probe.v1"))
                return reviewResponse(request, "review_" + reviews,
                    "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Post-install probe refused\"}");
            boolean agentRun = facts.path("exactAction").asText().contains("'agent-run'");
            if (agentRun) {
                agentReviews++;
                assertEquals("EXECUTABLE_SCRIPT", facts.path("inputKind").asText());
                assertEquals(DeploymentManagedHelperBundle.PATH, facts.path("installedHelperPath").asText());
                assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, facts.path("installedHelperSha256").asText());
                assertEquals(DeploymentManagedHelperBundle.BUILD_ENTRY_PATH, facts.path("installedBuildEntryPath").asText());
                assertEquals(DeploymentManagedHelperBundle.buildEntrySha256(), facts.path("installedBuildEntrySha256").asText());
                assertEquals(server.id(), facts.path("resourceTargets").get(0).path("serverId").asText());
                reviewedScript = facts.path("completeExecutableInput").asText();
                reviewedRequest = facts.path("exactAction").asText();
                assertTrue(reviewedRequest.contains("'" + workspace.candidateId() + "' '" + task.id() + "' '" + sourceRevision + "'"));
                assertTrue(reviewedRequest.contains("stdinSha256=" + hash(reviewedScript)));
                if (boundary.build()) {
                    assertTrue(reviewedScript.contains("WTL_AGENT_SOURCE=\"$WTL_SOURCE\""));
                    assertTrue(reviewedScript.contains("cp -R -- \"$WTL_AGENT_SOURCE/.\""));
                    assertTrue(reviewedScript.contains("if [ \"$used\" -gt "
                        + gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().workspaceBytes() + " ]; then"));
                }
            }
            if (agentRun && boundary.reviewOutcome())
                return new OperationResult<>(boundary == Boundary.COMMAND_REVIEW_CANCELLED ? TaskResult.CANCELLED : TaskResult.UNKNOWN,
                    Optional.empty(), Optional.of(reviewFailure));
            boolean invalid = agentRun && boundary != Boundary.COMMAND_EXECUTE_FAILURE
                || boundary == Boundary.OPEN_INVALID && !recovering && facts.path("exactAction").asText().contains("'agent-open'");
            String result = invalid ? "{\"decision\":\"DENY\",\"requiresUserConfirmation\":true}"
                : "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Exact controlled original request\"}";
            return reviewResponse(request, "review_" + reviews, result);
        }

        private OperationResult<AiResponse> inspectionReview(AiRequest request, JsonNode input, JsonNode facts) throws Exception {
            assertTrue(input.has("currentRequest"));
            var page = executablePage(request, input);
            if (page != null) return page;
            String operation = facts.path("operation").asText();
            reviews++;
            var verdict = json.createObjectNode().put("requiresUserConfirmation", false);
            for (String key : List.of("missingEvidence", "adjustments", "candidateCommands")) verdict.putArray(key);
            if (operation.equals("deployment.candidate.host-probe.v1")) {
                assertEquals("EMPTY", facts.path("inputKind").asText());
                assertFalse(input.path("executableInput").has("stdin"));
                inspectionRequest = inspectionActionDigest(facts);
                inspectionBinding = input.path("reviewBinding").asText();
                verdict.put("decision", "DENY").put("reason", "Controlled independent refusal of this exact baseline");
                verdict.put("discussionStatus", boundary == Boundary.INSPECT_CLOSED_REPEAT ? "CLOSED" : "OPEN");
            } else {
                assertEquals(Boundary.INSPECT_OPEN, boundary);
                assertNotEquals(inspectionRequest, inspectionActionDigest(facts));
                assertNotEquals(inspectionBinding, input.path("reviewBinding").asText());
                if (operation.equals("deployment.model.command.status.v1")) {
                    assertEquals(1, inspectionSends);
                    assertEquals(1, inspectionAuthorizations);
                    assertEquals(0, inspectionStatusAuthorizations);
                    assertEquals(0, inspectionStatusSends);
                    assertEquals("EMPTY", facts.path("inputKind").asText());
                    reviewedStatusRequest = inspectionActionDigest(facts);
                } else {
                    assertEquals("deployment.model.command.v1", operation);
                    assertEquals(0, inspectionSends);
                    assertEquals(0, inspectionAuthorizations);
                    assertEquals("EXECUTABLE_SCRIPT", facts.path("inputKind").asText());
                    reviewedRequest = inspectionActionDigest(facts);
                }
                verdict.put("decision", "APPROVE").put("reason", "Complete newly selected controlled observation independently reviewed");
            }
            return response(nativeResponse("inspect_verdict_" + reviews, "submit_result", verdict.toString()));
        }

        private String inspectionActionDigest(JsonNode facts) throws Exception {
            var action = facts.path("exactAction");
            if (action.isTextual()) return hash(action.asText());
            assertEquals("evidence", action.path("tool").asText());
            assertEquals("/exactAction", action.path("pointer").asText());
            assertTrue(action.path("sha256").asText().matches("[0-9a-f]{64}"));
            return action.path("sha256").asText();
        }

        String pageBinding = "";

        final Map<String, Integer> pageOffsets = new java.util.HashMap<>();

        final Map<String, String> pageParts = new java.util.HashMap<>();

        final Map<String, StringBuilder> inspectionPageText = new java.util.HashMap<>();

        int bodyPageCalls;

        private OperationResult<AiResponse> executablePage(AiRequest request, JsonNode input) throws Exception {
            String binding = input.path("reviewBinding").asText();
            if (!binding.equals(pageBinding)) {
                pageBinding = binding;
                pageOffsets.clear();
                pageParts.clear();
                inspectionPageText.clear();
            }
            var prior = request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((a, b) -> b);
            if (prior.isPresent() && pageParts.containsKey(prior.orElseThrow().callId())) {
                var result = prior.orElseThrow();
                assertFalse(result.error());
                var envelope = json.readTree(result.text());
                assertEquals(binding, envelope.path("reviewBinding").asText());
                String part = pageParts.get(result.callId());
                var original = input.path("executableInput").path(part);
                assertEquals(original.path("sha256"), envelope.at("/value/sha256"));
                assertEquals(original.path("total"), envelope.at("/value/total"));
                if (boundary.inspection()) {
                    var value = envelope.path("value");
                    assertTrue(value.path("text").isTextual());
                    assertTrue(value.path("offset").isIntegralNumber() && value.path("next").isIntegralNumber());
                    int offset = value.path("offset").asInt(), next = value.path("next").asInt();
                    assertEquals(pageOffsets.getOrDefault(part, 0).intValue(), offset);
                    assertTrue(next > offset && next <= original.path("total").asInt());
                    assertEquals(next - offset, value.path("text").textValue().length());
                    assertTrue(next - offset <= 8000, "The fake reviewer requests only bounded original body pages");
                    var seen = inspectionPageText.computeIfAbsent(part, ignored -> new StringBuilder());
                    assertEquals(offset, seen.length());
                    seen.append(value.path("text").textValue());
                }
                pageOffsets.put(part, envelope.at("/value/next").asInt());
            }
            for (String part : List.of("script", "stdin")) {
                var original = input.path("executableInput").path(part);
                int offset = pageOffsets.getOrDefault(part, 0);
                if (boundary.inspection()) {
                    if (original.isMissingNode()) {
                        assertEquals("stdin", part);
                        assertEquals("EMPTY", input.at("/currentRequest/inputKind").asText());
                        continue;
                    }
                    assertTrue(original.isObject() && original.path("total").isIntegralNumber());
                    assertTrue(original.path("sha256").asText().matches("[0-9a-f]{64}"));
                    if (original.has("text")) {
                        assertTrue(original.path("text").isTextual());
                        assertEquals(original.path("total").asInt(), original.path("text").textValue().length());
                        assertEquals(original.path("sha256").asText(), hash(original.path("text").textValue()));
                    } else if (offset >= original.path("total").asInt()) {
                        String seen = inspectionPageText.getOrDefault(part, new StringBuilder()).toString();
                        assertEquals(original.path("total").asInt(), offset);
                        assertEquals(offset, seen.length());
                        assertEquals(original.path("sha256").asText(), hash(seen));
                    }
                }
                if (original.has("text") || offset >= original.path("total").asInt()) continue;
                String id = "body_" + ++bodyPageCalls;
                pageParts.put(id, part);
                return response(nativeResponse(id, "request", json.createObjectNode().put("part", part).put("offset", offset).put("limit", 8000).toString()));
            }
            return null;
        }

        private OperationResult<AiResponse> reviewResponse(AiRequest request, String id, String result) throws Exception {
            var args = (ObjectNode) json.readTree(result);
            var input = json.readTree(request.messages().getLast().text());
            if (input.has("currentRequest") && args.has("reason")) {
                for (String field : List.of("missingEvidence", "adjustments", "candidateCommands"))
                    args.putArray(field);
                if (args.path("decision").asText().equals("DENY")) args.put("discussionStatus", "CLOSED");
            }
            return response(nativeResponse(id, "submit_result", args.toString()));
        }

        AiResponse decision(AiRequest request) throws Exception {
            assertFalse(recovering, "Recovery may not call the deployment model");
            if (boundary.inspection()) return inspectionDecision(request);
            var state = json.readTree(request.messages().getLast().text()).path("state");
            int turn = languageCalls++;
            String tool;
            ObjectNode args;
            if (turn == 0) {
                tool = "read";
                args = (ObjectNode) json.readTree("{\"path\":\"index.html\",\"offset\":0,\"limit\":100}");
            } else if (turn == 1) {
                tool = "identify";
                args = (ObjectNode) json.readTree("{\"components\":[{\"component\":\"api\",\"root\":\".\",\"technology\":\"Actual static HTML\",\"runtime\":\"Review output before sealing\",\"resources\":\"No database declared\",\"uncertainties\":\"Build remains unverified\",\"evidence\":[\"o1\"]}]}");
            } else if (turn == 2) {
                tool = "plan";
                args = (ObjectNode) json.readTree("""
                    {"applicationId":"demo","explanation":"Build the actually read static source","components":[{"id":"api","sourceRoot":".","dependencies":[],"evidence":["o1"],
                    "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10},
                    "ports":[],"resources":[],"configuration":[],"databases":[],"secretReferences":[]}],"applicationHealth":{"component":"api","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10}}}
                    """);
            } else if (boundary == Boundary.INSTALL_DENY && turn == 4) {
                assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream())
                    .anyMatch(result -> result.text().contains("APPROVAL_DENIED") && result.text().contains("CLOSED")));
                tool = "unable";
                args = json.createObjectNode().put("reason", "The exact native CLOSED installation denial remains unresolved");
            } else if (boundary.build()) {
                tool = switch (turn) {
                    case 3 -> "analyze";
                    case 4 -> "prepare_tools";
                    case 5 -> "build";
                    default -> throw new AssertionError("Build failure must hard stop");
                };
                args = (ObjectNode) json.readTree(switch (turn) {
                    case 3 -> "{\"component\":\"api\",\"type\":\"STATIC_SITE\"}";
                    case 4 ->
                        "{\"component\":\"api\",\"recipe\":{\"version\":\"\",\"primary\":\"public\",\"secondary\":\"\",\"jvmTarget\":\"\",\"servicePort\":0}}";
                    default -> "{\"component\":\"api\",\"seconds\":60}";
                });
            } else {
                assertEquals(3, turn, "Command failure must hard stop");
                tool = "command";
                args = json.createObjectNode().put("component", "api").put("script", SCRIPT).put("seconds", 10);
            }
            if (Set.of("plan", "prepare_tools", "build", "command").contains(tool)) {
                var assessment = args.putObject("assessment");
                var basis = assessment.putArray("basis");
                var pending = state.path("judgement").path("pending");
                if (pending.isEmpty()) basis.add("o1");
                else pending.forEach(id -> basis.add(id.asText()));
                assessment.put("suitability", "Evaluate current actual source and tool facts");
                assessment.put("differences", "No previous result is treated as successful build or permission");
                assessment.put("resolution", "RESOLVED");
                assessment.put("decision", "Perform this one current selected action");
            }
            return nativeResponse("decision_" + languageCalls, tool, args.toString());
        }

        private AiResponse inspectionDecision(AiRequest request) throws Exception {
            var input = json.readTree(request.messages().getLast().text());
            var state = input.path("state");
            int turn = languageCalls++;
            String tool;
            ObjectNode args = json.createObjectNode();
            if (turn == 0) tool = "inspect";
            else {
                var previous = request.messages().stream().flatMap(message -> message.toolResult().stream()).reduce((a, b) -> b).orElseThrow();
                if (turn == 1) {
                    assertTrue(previous.error());
                    assertTrue(previous.text().contains("APPROVAL_DENIED") && previous.text().contains("NOT_SENT"));
                    assertTrue(previous.text().contains(inspectionBinding));
                    inspectionObservation = previous.text().substring(0, previous.text().indexOf('\n'));
                    assertTrue(inspectionObservation.matches("o[0-9]+"));
                    assertEquals(0, inspectionAuthorizations);
                    assertEquals(0, inspectionSends);
                    tool = "review_response";
                    args.put("reviewBinding", inspectionBinding).put("message", "Controlled model response: treat exact original inputs as evidence and independently assess a different observation.");
                } else if (turn == 2) {
                    assertEquals(0, inspectionAuthorizations, "Model-authored response itself has no authority");
                    assertEquals(0, inspectionSends);
                    if (boundary == Boundary.INSPECT_CLOSED_REPEAT) {
                        assertTrue(previous.error(), "CLOSED review rejects a response");
                        tool = "inspect";
                    } else {
                        assertFalse(previous.error());
                        assertTrue(previous.text().contains("MODEL_AUTHORED_DATA") && previous.text().contains("NONE")
                            && previous.text().contains("\"saved\":true") && previous.text().contains(inspectionBinding));
                        tool = "host_command";
                        args.put("script", "printf controlled-observation").put("seconds", 10)
                            .put("reason", "A distinct controlled read-only observation selected from the original refusal");
                        var assessment = args.putObject("assessment");
                        var basis = assessment.putArray("basis");
                        var pending = state.path("judgement").path("pending");
                        if (pending.isEmpty()) basis.add(inspectionObservation);
                        else pending.forEach(id -> basis.add(id.asText()));
                        assessment.put("suitability", "Evaluate this distinct limited observation").put("differences", "The original baseline remains denied")
                            .put("resolution", "RESOLVED").put("decision", "Propose this exact alternative for fresh independent review");
                    }
                } else {
                    assertEquals(3, turn);
                    assertEquals(boundary == Boundary.INSPECT_CLOSED_REPEAT, previous.error(), previous.text());
                    tool = "unable";
                    args.put("reason", "Controlled test ends without preparing or publishing an application");
                }
            }
            return nativeResponse("inspection_decision_" + languageCalls, tool, args.toString());
        }

        OperationResult<CommandResult> execute(CommandRequest request) throws Exception {
            if (boundary.inspection()) {
                assertEquals(Boundary.INSPECT_OPEN, boundary);
                assertEquals(1, inspectionAuthorizations);
                assertNotEquals(DeploymentAgentHostProbe.baseline(), request.script(), "Denied baseline never dispatches");
                if (request.script().equals(gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.LAUNCHER)) {
                    assertEquals(reviewedRequest, hash(RemoteActions.command(server, REVISION, request).exactAction()));
                    var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(actual.tasks(), task);
                    var original = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.executableFacts(new String(request.stdin(), StandardCharsets.UTF_8), layout);
                    assertEquals(task.id().toString(), original.get("task"));
                    inspectionReceipt = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.header(task.id(),
                        UUID.fromString(original.get("operation").toString()), original.get("bodySha256").toString(), layout) + "EXIT=0\n";
                    inspectionSends++;
                    return reply("controlled observation\n");
                }
                assertEquals(1, inspectionStatusAuthorizations);
                assertEquals(reviewedStatusRequest, hash(RemoteActions.command(server, REVISION, request).exactAction()));
                assertFalse(inspectionReceipt.isEmpty());
                inspectionStatusSends++;
                return reply(inspectionReceipt);
            }
            String script = request.script();
            String program = new String(request.stdin(), StandardCharsets.UTF_8);
            if (program.equals(DeploymentAgentHelperReadiness.script()))
                return reply("FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=MISSING\nREASON=HELPER_MISSING\nPACKAGED_PROTOCOL="
                    + DeploymentManagedHelperBundle.PROTOCOL_VERSION
                    + "\nHELPER=ABSENT\nBUILD_ENTRY=ABSENT\nDEPENDENCIES=ABSENT\nROOTS=VALID_OR_ABSENT\nRECEIPTS=ABSENT\n");
            if (boundary.install() && script.equals(DeploymentAgentHelperInstallerScript.LAUNCHER)) {
                DeploymentAgentHelperInstallerScript.parseOperation(program);
                installSends++;
                assertEquals(reviewedRequest, RemoteActions.command(server, REVISION, request).exactAction());
                if (boundary == Boundary.INSTALL_EXECUTE_SAME_EVENT) throw installBoundaryFailure;
                return reply("HELPER_INSTALL=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            }
            if (script.contains("'agent-run'")) {
                agentSends++;
                assertEquals(reviewedRequest, gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, REVISION, request).exactAction());
                throw executeFailure;
            }
            if (script.contains("'agent-open'")) return reply("SOURCE_REVISION=" + sourceRevision + "\n");
            if (program.equals(DeploymentAgentToolchainPrograms.dependencies())) {
                var match = java.util.regex.Pattern.compile("'PYTHON' '([0-9a-f]{32})'$").matcher(script);
                assertTrue(match.find());
                assertEquals(DeploymentAgentToolchainPrograms.command(List.of("PYTHON", match.group(1))), script);
                var packages = new TreeMap<String, String>();
                DeploymentAgentToolchainPrograms.packages("APT", "PYTHON").forEach(name -> packages.put(name, "controlled-version"));
                return reply("TOOLCHAIN_DEPENDENCIES=" + json.writeValueAsString(Map.of("operation", match.group(1),
                    "ecosystem", "PYTHON", "manager", "APT", "packages", packages)) + "\n");
            }
            if (program.equals(DeploymentAgentToolchainPrograms.resolver())) {
                var match = java.util.regex.Pattern.compile("'resolve' 'PYTHON' '([^']+)' '([^']+)' '([0-9]+)' '([0-9a-f]{32})'$").matcher(script);
                assertTrue(match.find());
                assertEquals(DeploymentAgentToolchainPrograms.command(List.of("resolve", "PYTHON", match.group(1),
                    match.group(2), match.group(3), match.group(4))), script);
                return reply("TOOLCHAIN_RELEASE=" + DeploymentAgentToolchainReleaseTest.encoded("PYTHON", match.group(1),
                    match.group(2), match.group(4)) + "\n");
            }
            if (program.equals(DeploymentAgentToolchainPrograms.controller()) && script.contains("'prepare' 'PYTHON'")) {
                var match = java.util.regex.Pattern.compile("'prepare' 'PYTHON' '([^']+)' '([^']+)' '([0-9]+)' '-' '([0-9a-f]{32})' '([A-Za-z0-9+/=]+)'$").matcher(script);
                assertTrue(match.find());
                assertEquals(DeploymentAgentToolchainPrograms.command(List.of("prepare", "PYTHON", match.group(1),
                    match.group(2), match.group(3), "-", match.group(4), match.group(5))), script);
                var release = json.readTree(Base64.getDecoder().decode(match.group(5)));
                assertEquals(match.group(4), release.path("operation").asText());
                return reply("TOOLCHAIN=PYTHON|" + release.path("version").asText()
                    + "|/usr/local/lib/windowstolinux/toolchains/versions/python-" + release.path("digest").asText()
                    + "|MANAGED|" + release.path("url").asText() + "|" + release.path("digest").asText() + "\n");
            }
            if (program.equals(DeploymentAgentToolchainPrograms.controller()) && script.contains("'bind'")) {
                var match = java.util.regex.Pattern.compile("'bind' '([A-Za-z0-9+/=]+)' '([0-9]+)' '([0-9a-f]{32})'$").matcher(script);
                assertTrue(match.find());
                assertEquals(DeploymentAgentToolchainPrograms.command(List.of("bind", match.group(1), match.group(2), match.group(3))), script);
                var binding = DeploymentToolchainBindingCodec.decode(new String(Base64.getDecoder().decode(match.group(1)), StandardCharsets.UTF_8));
                return reply("TOOLCHAIN_BINDING=" + DeploymentToolchainBindingCodec.identity(binding) + "\n");
            }
            if (script.contains("build-preflight")) return reply("");
            if (script.contains("candidate-query-task")) {
                assertTrue(script.contains("'" + workspace.candidateId() + "' '" + task.id() + "'"));
                return reply("CANDIDATE_STATE=" + (present ? "owned" : "absent") + "\nBUILD_ACTIVE=no\n");
            }
            if (script.contains("candidate-cleanup-task")) {
                assertTrue(recovering);
                assertTrue(script.contains("'" + workspace.candidateId() + "' '" + task.id() + "'"));
                present = false;
                return reply("CANDIDATE_CLEANED=1\n");
            }
            if (script.contains(" probe\n")) {
                probes++;
                return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION
                    + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
            }
            throw new AssertionError("Unexpected controlled helper request");
        }
    }

    private static AiResponse nativeResponse(String id, String tool, String args) {
        return new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall(id, tool, args)), Optional.empty());
    }

    private static OperationResult<AiResponse> response(AiResponse value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static OperationResult<CommandResult> reply(String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), stdout, "", false)), Optional.empty());
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
