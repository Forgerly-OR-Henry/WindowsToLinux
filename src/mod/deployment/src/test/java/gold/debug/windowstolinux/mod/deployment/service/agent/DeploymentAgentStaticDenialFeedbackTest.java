package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentStaticDenialFeedbackTest extends DeploymentRuntimeFixture {
    private static final String REVISION = "a".repeat(64);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void staticInvalidFeedbackIsBoundedAndPrecedesAllPreparationAndJournalEffects() throws Exception {
        inTask((actual, task, browser, journal) -> {
            var effects = new AtomicInteger();
            var port = port((method, args) -> {
                if (method.getName().equals("approvalConversation")) return Optional.empty();
                effects.incrementAndGet();
                throw new AssertionError("Static denial cannot call " + method.getName());
            });
            var session = session(actual, task, browser, journal, port);
            var execute = DeploymentAgentSession.class.getDeclaredMethod("executeCommand", DeploymentAgentProposal.Command.class, boolean.class);
            execute.setAccessible(true);
            var before = journal.events(task);
            for (String script : Arrays.asList(null, "", " \n", "printf fixture-sensitive\0body", "x".repeat(24001))) {
                var thrown = assertThrows(InvocationTargetException.class,
                    () -> execute.invoke(session, new DeploymentAgentProposal.Command("cli", script, 30), false));
                var invalid = assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
                assertEquals("DEPLOYMENT_COMMAND_STATIC_INVALID", invalid.getCause().getMessage());
                var feedback = DeploymentToolScheduler.rejected(new AiToolCall("invalid-input", "command", "{}"), invalid);
                assertTrue(feedback.error());
                assertEquals("invalid-input", feedback.callId());
                assertTrue(feedback.text().startsWith("TOOL_REJECTED: DEPLOYMENT_COMMAND_STATIC_INVALID: NOT_SENT task=" + task.id() + " component=cli scriptSha256="));
                assertTrue(feedback.text().length() <= 512);
                assertFalse(feedback.text().contains("fixture-sensitive"));
                assertEquals(script == null ? "ABSENT" : sha(script), feedback.text().substring(feedback.text().lastIndexOf('=') + 1));
                assertEquals(before, journal.events(task));
                assertEquals(0, effects.get());
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"IDENTITY_AUTHORIZATION_DENIED", "DEPLOYMENT_COMMAND_STATIC_DENIED", "DEPLOYMENT_COMMAND_STATIC_INVALID"})
    void securityExceptionOutsideStaticGateEscapesWithoutNextNativeDispatch(String code) throws Exception {
        inTask((actual, task, browser, journal) -> {
            boolean unknown = code.equals("CONTROLLED_COMMAND_UNKNOWN");
            var original = new SecurityException(code);
            var calls = new AtomicInteger();
            var commands = new AtomicInteger();
            var ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
                if (method.getName().equals("configurationRevision")) return REVISION;
                assertEquals("call", method.getName());
                var request = (AiRequest) args[0];
                assertEquals(AiPurpose.LANGUAGE, request.purpose());
                int turn = calls.getAndIncrement();
                assertTrue(turn < 4, "A command-side security failure must not reach a fifth model request");
                var state = json.readTree(request.messages().getLast().text()).path("state");
                String name = List.of("read", "identify", "plan", "command").get(turn);
                ObjectNode parameters = switch (name) {
                    case "read" -> json.createObjectNode().put("path", "source.txt").put("offset", 0).put("limit", 100);
                    case "identify" -> (ObjectNode) json.readTree("""
                        {"components":[{"component":"cli","root":".","technology":"Actual source READ","runtime":"Explicit literal invocation","resources":"Explicit empty declaration","uncertainties":"Runtime remains to be tested","evidence":["o1"]}]}
                        """);
                    case "plan" -> {
                        var declaration = DeploymentAgentRuntimeValidationTest.declaration("tool", List.of(), "", "ok");
                        ((ObjectNode) declaration.path("components").get(0)).set("workspace", json.valueToTree(DeploymentWorkspaceQuota.Request.explicit(DeploymentWorkspaceQuota.UNIT)));
                        yield declaration;
                    }
                    default ->
                        json.createObjectNode().put("component", "cli").put("script", "printf legal-command").put("seconds", 30);
                };
                if (DeploymentAgentProtocol.critical(name)) {
                    var assessment = parameters.putObject("assessment");
                    var basis = assessment.putArray("basis");
                    var pending = state.path("judgement").path("pending");
                    if (pending.isEmpty()) basis.add("o1");
                    else pending.forEach(id -> basis.add(id.asText()));
                    assessment.put("suitability", "Current original observations").put("differences", "Runtime not yet verified")
                        .put("resolution", "RESOLVED").put("decision", "One exact controlled action");
                }
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
                    new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                    List.of(new AiToolCall("boundary_" + turn, name, parameters.toString())), Optional.empty())), Optional.empty());
            });
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("ai")) return ai;
                try {
                    return method.invoke(actual, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
            var port = port((method, args) -> switch (method.getName()) {
                case "approvalConversation" -> Optional.empty();
                case "source" -> browser;
                case "selectWorkspaces" -> {
                    var plan = (DeploymentAgentPlan) args[0];
                    var inputs = plan.components().stream().map(part -> new DeploymentWorkspaceQuota.Input(part.id(), 0, 0, part.workspaceRequest().orElseThrow())).toList();
                    var selections = DeploymentWorkspaceQuota.select(inputs, 8L * 1024 * 1024 * 1024,
                        new DeploymentWorkspaceQuota.Policy(DeploymentWorkspaceQuota.MAXIMUM, 1024L * 1024 * 1024), DeploymentWorkspaceQuota.Cost.AGENT);
                    yield plan.components().stream().map(part -> part.withWorkspace(selections.get(part.id()))).toList();
                }
                case "acceptWorkspaces" -> null;
                case "prepare" ->
                    new DeploymentAgentDeliveryPort.Prepared(new DeploymentRemoteWorkspace("controlled-cli", "c".repeat(64)), browser.revision(),
                        ((DeploymentAgentPlan.Part) args[1]).workspaceSelection().orElseThrow());
                case "command" -> {
                    commands.incrementAndGet();
                    if (unknown)
                        yield new DeploymentAgentDeliveryPort.CommandFeedback(false, false, -1, "Controlled unknown effect");
                    throw original;
                }
                default -> throw new AssertionError("Unexpected boundary capability: " + method.getName());
            });
            if (unknown)
                assertEquals("Unknown agent command must not be replayed", assertThrows(IllegalStateException.class,
                    () -> session(context, task, browser, journal, port).run()).getMessage());
            else
                assertSame(original, assertThrows(SecurityException.class, () -> session(context, task, browser, journal, port).run()));
            assertEquals(4, calls.get());
            assertEquals(1, commands.get());
            assertEquals("UNKNOWN", journal.find(task).orElseThrow().state());
            var events = journal.events(task);
            assertEquals(1, events.stream().filter(event -> event.type().equals("COMMAND_EXECUTING")).count());
            assertTrue(events.stream().noneMatch(event -> Set.of("COMMAND_RESULT", "COMMAND_NOT_SENT", "COMMAND_DISPATCH_NOT_SENT").contains(event.type())));
            assertFalse(DeploymentAgentActionLog.effectsSettled(events));
        });
    }

    @Test
    void unknownCommandStopsNativeSessionWithoutSettlementOrReplay() throws Exception {
        securityExceptionOutsideStaticGateEscapesWithoutNextNativeDispatch("CONTROLLED_COMMAND_UNKNOWN");
    }

    private DeploymentAgentSession session(ModContext context, TaskContext task, DeploymentSourceBrowser browser,
                                           DeploymentAgentActionLog journal, DeploymentAgentDeliveryPort port) {
        return new DeploymentAgentSession(context, task, new ServerId("static-feedback-local"), Map.of("goal", "Verify refusal boundary"),
            browser, port, journal, "MANUAL_REVIEW", true, REVISION, () -> REVISION);
    }

    private interface PortCall {
        Object call(Method method, Object[] args) throws Throwable;
    }

    private static DeploymentAgentDeliveryPort port(PortCall call) {
        return (DeploymentAgentDeliveryPort) Proxy.newProxyInstance(DeploymentAgentDeliveryPort.class.getClassLoader(),
            new Class<?>[]{DeploymentAgentDeliveryPort.class}, (proxy, method, args) -> {
                if (method.isDefault() && !Set.of("approvalConversation", "source", "selectWorkspaces", "acceptWorkspaces").contains(method.getName()))
                    return InvocationHandler.invokeDefault(proxy, method, args);
                return call.call(method, args);
            });
    }

    private interface Boundary {
        void run(ModContext context, TaskContext task, DeploymentSourceBrowser browser, DeploymentAgentActionLog journal) throws Exception;
    }

    private void inTask(Boundary check) throws Exception {
        var source = Files.createDirectories(root.resolve("source"));
        Files.writeString(source.resolve("source.txt"), "original source facts\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = actual.tasks().submit(new TaskRequest("agent-static-feedback-local", "Static refusal feedback", List.of(), false), task -> {
                try {
                    var setup = new DeploymentSourceSetup(actual.file());
                    try (var material = setup.prepare(actual.file().sources().selectDirectory(source, task), 1048576, 100, task)) {
                        var browser = new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
                        var journal = new DeploymentAgentActionLog(actual.db());
                        journal.create(task, new DeploymentAgentActionLog.Identity("static-feedback-local", "b".repeat(64), REVISION, "MANUAL_REVIEW", true));
                        check.run(actual, task, browser, journal);
                    }
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError failed) {
                    failure.set(failed);
                    throw failed;
                } finally {
                    try {
                        actual.file().closeTaskResources(task);
                    } catch (Exception cleanup) {
                        failure.compareAndSet(null, cleanup);
                        throw cleanup;
                    } finally {
                        finished.complete(null);
                    }
                }
            });
            finished.get(30, TimeUnit.SECONDS);
            if (failure.get() != null) throw new AssertionError("Static feedback boundary failed", failure.get());
            var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
        }
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
