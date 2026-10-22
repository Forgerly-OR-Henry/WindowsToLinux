package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.linux.LinuxService;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerService;
import gold.debug.windowstolinux.appapi.server.model.*;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentSession;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import org.junit.jupiter.api.Test;

/** Real native Agent dispatch and independent approval, with owned local source and controlled transport. */
class DeploymentAgentPlanReconnectTest extends DeploymentRuntimeFixture {
    private static final String REVISION = "a".repeat(64);

    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void hostCommandStaticRuleFeedbackDoesNotBypassReview() throws Exception {
        Path source = Files.createDirectories(root.resolve("host-static-source"));
        Files.writeString(source.resolve("run.sh"), "#!/bin/sh\nexec sleep 1\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var server = new ServerId("host-static-local");
            var profile = new ServerProfile(server.id(), "127.0.0.1", 22, "fixture", "fixture");
            runtime.servers().saveServerProfile(profile, "fixture".toCharArray());
            var failed = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-host-static-local", "Bounded rule feedback", List.of(server.id()), true), task -> {
                var journal = new DeploymentAgentActionLog(actual.db());
                boolean journalCreated = false;
                try {
                    var selection = actual.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task);
                         var archive = new DeploymentSourceArchiver(actual.file()).prepare(material, 1048576, task)) {
                        journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "d".repeat(64), REVISION, "AUTOMATIC", false));
                        journalCreated = true;
                        journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
                        var approvals = new AtomicInteger();
                        var sends = new AtomicInteger();
                        String corrected = "df -Pk /";
                        var ai = (AiService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AiService.class}, (p, m, args) -> {
                            if (m.getName().equals("configurationRevision")) return REVISION;
                            assertEquals("call", m.getName());
                            assertSame(task, args[args.length - 1]);
                            var request = (AiRequest) args[0];
                            assertEquals(AiPurpose.APPROVAL, request.purpose());
                            assertTrue(request.messages().stream().anyMatch(message -> message.text().contains(corrected)), "Independent APP sees the corrected exact body");
                            assertEquals(1, approvals.incrementAndGet());
                            return response("host_static_app", "submit_result", JSON.readTree("""
                                {"decision":"DENY","requiresUserConfirmation":false,"reason":"Controlled independent refusal of the exact corrected command",
                                 "missingEvidence":[],"adjustments":[],"candidateCommands":[]}
                                """));
                        });
                        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (p, m, args) ->
                            m.getName().equals("ai") ? ai : invoke(m, actual, args));
                        var remote = peer((request, grant) -> {
                            sends.incrementAndGet();
                            throw new AssertionError("Neither static refusal nor independent APP denial grants a command");
                        }, () -> {
                        });
                        try (var workflow = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.open(
                            context, task, server, REVISION, "AUTOMATIC", false, "Controlled original source=" + material.snapshot().sha256());
                             var tools = new DeploymentAgentRemoteTools(context, remote, actual.interaction(), server, task, archive, material, REVISION, () -> REVISION,
                                 journal, Optional.empty(), "AUTOMATIC", false)) {
                            var blocked = Map.of(
                                "rm -rf /var/log/*; printf '119-sensitive'", "PROTECTED_RECURSIVE_TARGET",
                                "cd /var/log; rm -rf *; printf '119-sensitive'", "PROTECTED_RELATIVE_RECURSIVE_TARGET",
                                "dd if=/dev/zero of=/dev/sda; printf '119-sensitive'", "CATASTROPHIC_COMMAND",
                                "printf '119-sensitive' >/dev/sda", "DESTRUCTIVE_FORM",
                                "printf '119-sensitive'\0body", "INVALID_SHAPE");
                            var before = journal.events(task);
                            for (var entry : blocked.entrySet()) {
                                var refusal = assertThrows(IllegalArgumentException.class, () -> tools.hostCommand(task, entry.getKey(), 30, "Controlled observed host diagnosis"));
                                var gate = assertInstanceOf(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate.Rejection.class, refusal.getCause());
                                assertEquals(entry.getValue(), gate.rule().name());
                                assertEquals(entry.getValue().equals("INVALID_SHAPE") ? "DEPLOYMENT_COMMAND_STATIC_INVALID" : "DEPLOYMENT_COMMAND_STATIC_DENIED", gate.getMessage());
                                var feedback = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.rejected(new AiToolCall("blocked-host", "host_command", "{}"), refusal);
                                assertTrue(feedback.error());
                                assertTrue(feedback.text().contains("NOT_SENT task=" + task.id() + " rule=" + entry.getValue()));
                                assertTrue(feedback.text().contains("bodySha256="));
                                assertTrue(feedback.text().contains("envelopeSha256="));
                                assertTrue(feedback.text().length() <= 512);
                                assertFalse(feedback.text().contains("119-sensitive"));
                                assertFalse(feedback.text().contains("/var/log"));
                                assertFalse(feedback.text().contains("/dev/sda"));
                                assertEquals(0, approvals.get());
                                assertEquals(0, sends.get());
                                assertEquals(before, journal.events(task));
                            }
                            var free = assertThrows(gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate.Rejection.class,
                                () -> gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate.requireAllowed("sudo true"));
                            assertEquals("DISALLOWED_PRIVILEGED_COMMAND", free.rule().name());
                            assertEquals("DEPLOYMENT_COMMAND_STATIC_DENIED", free.getMessage());
                            var denied = assertThrows(gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException.class,
                                () -> tools.hostCommand(task, corrected, 30, "Measure current capacity for this deployment"));
                            assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", assertInstanceOf(ApiException.class, denied.getCause()).problem().eventCode());
                            assertEquals(1, approvals.get());
                            assertEquals(0, sends.get());
                            assertFalse(gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.unconfirmed(context, task));
                        }
                    }
                    gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.releaseKnown(actual, task);
                    journal.state(task, "FAILED");
                    actual.file().closeTaskResources(task);
                    assertTrue(actual.tasks().resourcesReleased(task));
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError failure) {
                    failed.set(failure);
                    try {
                        gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.releaseKnown(actual, task);
                        if (journalCreated) journal.state(task, "FAILED");
                        actual.file().closeTaskResources(task);
                    } catch (Exception cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(60, TimeUnit.SECONDS);
            if (failed.get() != null) throw new AssertionError("Host static rule feedback", failed.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void planCapacityNotSentRequiresExplicitModelReconnectAndFreshApproval() throws Exception {
        Path source = Files.createDirectories(root.resolve("reconnect-source"));
        Files.writeString(source.resolve("run.sh"), "#!/bin/sh\nexec sleep 1\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            for (String scenario : List.of("RECONNECT", "UNKNOWN", "CLOSE", "PROFILE", "CONFIG", "REVOKED", "UNSETTLED")) {
                var server = new ServerId("reconnect-" + scenario.toLowerCase(Locale.ROOT));
                var originalProfile = new ServerProfile(server.id(), "127.0.0.1", 22, "fixture", "fixture");
                runtime.servers().saveServerProfile(originalProfile, "fixture".toCharArray());
                var failed = new AtomicReference<Throwable>();
                var finished = new CompletableFuture<Void>();
                var handle = actual.tasks().submit(new TaskRequest("agent-reconnect-local", scenario, List.of(server.id()), true), task -> {
                    var journal = new DeploymentAgentActionLog(actual.db());
                    boolean journalCreated = false;
                    try {
                        var selection = actual.file().sources().selectDirectory(source, task);
                        try (var material = new DeploymentSourceSetup(actual.file()).prepare(selection, 1048576, 100, task);
                             var archive = new DeploymentSourceArchiver(actual.file()).prepare(material, 1048576, task)) {
                            journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), REVISION, "AUTOMATIC", false));
                            journalCreated = true;
                            journal.event(task, "SOURCE", archive.sha256(), material.snapshot().sha256());
                            var profile = new AtomicReference<>(new ServerInfo(server, "fixture", originalProfile.host(), originalProfile.sshPort(), originalProfile.username(), Optional.of(FINGERPRINT), Optional.empty()));
                            var revision = new AtomicReference<>(REVISION);
                            var approvals = new AtomicInteger();
                            var mainTurns = new AtomicInteger();
                            var connections = new AtomicInteger();
                            var oldCalls = new AtomicInteger();
                            var newCalls = new AtomicInteger();
                            var oldClosed = new AtomicBoolean();
                            var replacementClosed = new AtomicBoolean();
                            var resourceHeld = new AtomicBoolean(true);
                            var usedGrants = new HashSet<ApprovalGrant>();
                            var original = peer((request, grant) -> {
                                assertTrue(usedGrants.add(grant), "The original single-use grant cannot be reused");
                                assertEquals(1, oldCalls.incrementAndGet(), "No automatic replay on the failed connection");
                                if (scenario.equals("UNSETTLED"))
                                    journal.event(task, "COMMAND_EXECUTING", "other-command", "unconfirmed");
                                if (scenario.equals("PROFILE"))
                                    profile.set(new ServerInfo(server, "fixture", "127.0.0.2", 22, "fixture", Optional.of(FINGERPRINT), Optional.empty()));
                                if (scenario.equals("REVOKED"))
                                    profile.set(new ServerInfo(server, "fixture", "127.0.0.1", 22, "fixture", Optional.empty(), Optional.empty()));
                                if (scenario.equals("CONFIG")) revision.set("c".repeat(64));
                                return absence(scenario.equals("UNKNOWN") ? TaskResult.UNKNOWN : TaskResult.FAILED);
                            }, () -> {
                                if (scenario.equals("CLOSE"))
                                    throw new ApiException(new Problem(ProblemCode.BUSY, "SSH_CLOSE_PENDING", "Controlled local resource remains", Map.of(), Optional.of(task.id())));
                                oldClosed.set(true);
                                resourceHeld.set(false);
                            });
                            var replacement = peer((request, grant) -> {
                                assertTrue(oldClosed.get());
                                assertFalse(resourceHeld.get(), "Physical close and owned resource release precede admission");
                                assertTrue(usedGrants.add(grant), "A newly reviewed distinct grant is required");
                                assertEquals(2, approvals.get(), "The new capacity command receives a fresh independent APP call");
                                newCalls.incrementAndGet();
                                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), "1048576 4096\n", "", false)), Optional.empty());
                            }, () -> replacementClosed.set(true));
                            var linux = (LinuxService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{LinuxService.class}, (p, m, args) -> {
                                if (m.getName().equals("pendingTransfers")) return List.of();
                                assertEquals("connect", m.getName());
                                assertSame(task, args[1]);
                                assertEquals(new RemoteTarget(server, task.id(), originalProfile.host(), originalProfile.sshPort(), originalProfile.username(), Optional.of(FINGERPRINT)), args[0]);
                                assertTrue(oldClosed.get());
                                assertFalse(resourceHeld.get());
                                assertEquals(0, newCalls.get(), "Reconnection authenticates only; it never replays the plan command");
                                assertEquals(1, connections.incrementAndGet());
                                return replacement;
                            });
                            var servers = (ServerService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ServerService.class}, (p, m, args) -> {
                                if (m.getName().equals("get")) {
                                    assertEquals(server, args[0]);
                                    assertSame(task, args[1]);
                                    return profile.get();
                                }
                                return invoke(m, actual.servers(), args);
                            });
                            var ai = (AiService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AiService.class}, (p, m, args) -> {
                                if (m.getName().equals("configurationRevision")) return revision.get();
                                assertEquals("call", m.getName());
                                assertSame(task, args[args.length - 1]);
                                var request = (AiRequest) args[0];
                                if (request.purpose() == AiPurpose.APPROVAL) {
                                    int index = approvals.incrementAndGet();
                                    assertEquals(index == 1 ? 0 : 1, connections.get());
                                    return response("approval_" + index, "submit_result", JSON.readTree("""
                                        {"decision":"APPROVE","requiresUserConfirmation":false,"reason":"Review the exact controlled capacity command",
                                         "missingEvidence":[],"adjustments":[],"candidateCommands":[]}
                                        """));
                                }
                                assertEquals(AiPurpose.LANGUAGE, request.purpose());
                                int turn = mainTurns.getAndIncrement();
                                var state = JSON.readTree(request.messages().getLast().text()).path("state");
                                assertEquals(task.id().toString(), state.path("task").asText());
                                assertEquals(material.snapshot().sha256(), state.path("sourceRevision").asText());
                                if (turn == 3) {
                                    var feedback = request.messages().stream().flatMap(message -> message.toolResult().stream()).filter(value -> value.callId().equals("main_2")).findFirst().orElseThrow();
                                    assertTrue(feedback.error());
                                    assertTrue(feedback.text().contains("authorPayloadState"));
                                    assertTrue(feedback.text().contains("NOT_SENT"));
                                    assertTrue(state.path("reconnectAvailable").asBoolean());
                                    assertEquals(0, state.path("planRevision").asInt());
                                    assertTrue(state.path("components").isEmpty());
                                    assertTrue(oldClosed.get());
                                    assertEquals(0, connections.get());
                                    assertEquals(0, newCalls.get());
                                }
                                if (turn == 4) {
                                    var rejected = request.messages().stream().flatMap(message -> message.toolResult().stream()).filter(value -> value.callId().equals("main_3")).findFirst().orElseThrow();
                                    assertTrue(rejected.error());
                                    assertTrue(rejected.text().contains("RECONNECT_REQUIRED"));
                                    assertTrue(state.path("reconnectAvailable").asBoolean(), "Local ordering rejection preserves the actual absence proof");
                                    assertEquals(1, approvals.get(), "A local refusal sends no command and requires no new APP review");
                                    assertEquals(0, connections.get());
                                    assertEquals(0, newCalls.get());
                                }
                                if (turn == 5) {
                                    assertEquals(1, connections.get());
                                    assertEquals(0, newCalls.get(), "The model must separately choose a new plan after reconnect");
                                    assertEquals(0, state.path("planRevision").asInt());
                                    assertFalse(state.path("reconnectAvailable").asBoolean());
                                }
                                if (turn == 6) {
                                    assertEquals(1, state.path("planRevision").asInt());
                                    assertEquals(1, newCalls.get());
                                }
                                String tool = switch (turn) {
                                    case 0 -> "read";
                                    case 1 -> "identify";
                                    case 2, 5 -> "plan";
                                    case 3 -> "inspect";
                                    case 4 -> "reconnect";
                                    case 6 -> "unable";
                                    default -> throw new AssertionError("Unexpected model continuation");
                                };
                                ObjectNode params = switch (tool) {
                                    case "read" ->
                                        JSON.createObjectNode().put("path", "run.sh").put("offset", 0).put("limit", 100);
                                    case "identify" -> (ObjectNode) JSON.readTree("""
                                        {"components":[{"component":"web","root":".","technology":"Read shell entrypoint","runtime":"Process",
                                         "resources":"None","uncertainties":"Controlled transport proves no deployment","evidence":["o1"]}]}
                                        """);
                                    case "plan" -> declaration();
                                    case "reconnect", "inspect" -> JSON.createObjectNode();
                                    case "unable" ->
                                        JSON.createObjectNode().put("reason", "Controlled test ends after a newly accepted plan; no deployment requested");
                                    default -> throw new AssertionError(tool);
                                };
                                if (tool.equals("plan")) {
                                    var assessment = params.putObject("assessment");
                                    var basis = assessment.putArray("basis");
                                    var pending = state.path("judgement").path("pending");
                                    if (pending.isEmpty()) basis.add("o1");
                                    else pending.forEach(id -> basis.add(id.asText()));
                                    assessment.put("suitability", "Current original controlled observations").put("differences", "Transport facts require explicit model continuation")
                                        .put("resolution", "RESOLVED").put("decision", "Submit this exact source-backed plan");
                                }
                                return response("main_" + turn, tool, params);
                            });
                            var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (p, m, args) -> switch (m.getName()) {
                                case "ai" -> ai;
                                case "linux" -> linux;
                                case "servers" -> servers;
                                default -> invoke(m, actual, args);
                            });
                            try (var workflow = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization.open(
                                context, task, server, REVISION, "AUTOMATIC", false, "Controlled original source=" + material.snapshot().sha256());
                                 var tools = new DeploymentAgentRemoteTools(context, original, actual.interaction(), server, task, archive, material, REVISION, revision::get, journal, Optional.empty(), "AUTOMATIC", false)) {
                                var session = new DeploymentAgentSession(context, task, server, Map.of("goal", "Assess source-backed plan only"),
                                    new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256()), tools, journal, "AUTOMATIC", false, REVISION, revision::get);
                                if (scenario.equals("RECONNECT")) {
                                    assertEquals("Deployment model cannot continue", assertThrows(IllegalStateException.class, session::run).getMessage());
                                    assertEquals(2, approvals.get());
                                    assertEquals(2, usedGrants.size());
                                    assertEquals(1, connections.get());
                                    assertEquals(1, oldCalls.get());
                                    assertEquals(1, newCalls.get());
                                } else {
                                    var stopped = assertThrows(Exception.class, session::run);
                                    if (scenario.equals("UNKNOWN") || scenario.equals("UNSETTLED"))
                                        assertEquals(scenario.equals("UNKNOWN") ? TaskResult.UNKNOWN : TaskResult.FAILED,
                                            assertInstanceOf(gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException.class, stopped).outcome());
                                    else {
                                        var problem = assertInstanceOf(ApiException.class, stopped).problem();
                                        assertEquals(switch (scenario) {
                                            case "CLOSE" -> "SSH_CLOSE_PENDING";
                                            case "PROFILE", "REVOKED" -> "DEPLOYMENT_AGENT_RECONNECT_UNSETTLED";
                                            case "CONFIG" -> "DEPLOYMENT_AGENT_CONFIGURATION_CHANGED";
                                            default -> throw new AssertionError(scenario);
                                        }, problem.eventCode());
                                    }
                                    assertFalse(tools.reconnectAvailable());
                                    assertEquals(0, connections.get());
                                    assertEquals(0, newCalls.get());
                                    assertEquals(1, approvals.get());
                                    if (scenario.equals("CLOSE")) assertTrue(resourceHeld.get());
                                }
                            }
                            if (scenario.equals("RECONNECT"))
                                assertTrue(replacementClosed.get(), "The replacement is actually closed by the tools owner");
                        }
                        journal.state(task, "FAILED");
                        actual.file().closeTaskResources(task);
                        assertTrue(actual.tasks().resourcesReleased(task), "Local fixture resources must release before its conclusion");
                        System.out.println("POINT117_SCENARIO scenario=" + scenario + " task=" + task.id() + " status=BODY_PASSED");
                        return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    } catch (Exception | AssertionError failure) {
                        failed.set(failure);
                        System.out.println("POINT117_SCENARIO scenario=" + scenario + " task=" + task.id() + " status=BODY_FAILED type=" + failure.getClass().getSimpleName());
                        try {
                            if (journalCreated) journal.state(task, "FAILED");
                        } catch (Exception cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        try {
                            actual.file().closeTaskResources(task);
                        } catch (Exception cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    } finally {
                        finished.complete(null);
                    }
                });
                finished.get(60, TimeUnit.SECONDS);
                OperationResult<?> conclusion;
                try {
                    conclusion = handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
                } catch (TimeoutException timeout) {
                    if (failed.get() != null) timeout.addSuppressed(failed.get());
                    throw timeout;
                }
                if (failed.get() != null) throw new AssertionError(scenario, failed.get());
                assertEquals(TaskResult.SUCCEEDED, conclusion.outcome());
            }
        }
    }

    private static Object invoke(Method method, Object owner, Object[] args) throws Throwable {
        try {
            return method.invoke(owner, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static ObjectNode declaration() throws Exception {
        return (ObjectNode) JSON.readTree("""
            {"applicationId":"reconnect-demo","explanation":"Read source-backed process","components":[{"id":"web","sourceRoot":".",
             "dependencies":[],"evidence":["o1"],"backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],
             "workingDirectory":"","health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[],
             "databases":[],"secretReferences":[],"workspace":{"mode":"AUTO","minimumBuildBytes":67108864,"preferredBuildBytes":134217728,"explicitBytes":0}}],
             "applicationHealth":{"component":"web","health":{"type":"PROCESS","timeout":30,"stability":3}}}
            """);
    }

    private static OperationResult<AiResponse> response(String id, String tool, JsonNode params) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), List.of(new AiToolCall(id, tool, params.toString())), Optional.empty())), Optional.empty());
    }

    private static OperationResult<CommandResult> absence(TaskResult outcome) {
        return new OperationResult<>(outcome, Optional.empty(), Optional.of(new Problem(outcome == TaskResult.UNKNOWN ? ProblemCode.UNKNOWN : ProblemCode.IO_FAILURE,
            outcome == TaskResult.UNKNOWN ? "SSH_COMMAND_TRANSPORT" : "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT", "Controlled transport observation",
            Map.of("authorPayloadState", outcome == TaskResult.UNKNOWN ? "MAY_HAVE_BEEN_SENT" : "NOT_SENT", "inputSenderCreated", Boolean.toString(outcome == TaskResult.UNKNOWN),
                "inputState", outcome == TaskResult.UNKNOWN ? "SENDING" : "NOT_STARTED", "commandOpenRequested", "true"), Optional.empty())));
    }

    private static RemoteSession peer(Execution execution, Closing closing) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (p, m, args) -> switch (m.getName()) {
            case "facts" -> new RemoteFacts("Linux", "x86_64", FINGERPRINT);
            case "execute" -> execution.run((CommandRequest) args[0], (ApprovalGrant) args[1]);
            case "close" -> {
                closing.run();
                yield null;
            }
            default -> throw new AssertionError("No transfer or other remote operation: " + m.getName());
        });
    }

    @FunctionalInterface
    private interface Execution {
        OperationResult<CommandResult> run(CommandRequest request, ApprovalGrant grant) throws Exception;
    }

    @FunctionalInterface
    private interface Closing {
        void run() throws ApiException;
    }
}
