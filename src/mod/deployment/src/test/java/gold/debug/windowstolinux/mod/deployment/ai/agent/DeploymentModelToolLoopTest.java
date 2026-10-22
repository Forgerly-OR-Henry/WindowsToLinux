package gold.debug.windowstolinux.mod.deployment.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import org.junit.jupiter.api.Test;

class DeploymentModelToolLoopTest {
    private static final String REVISION = "a".repeat(64);

    private final List<String> selected = new ArrayList<>();

    private final AtomicInteger calls = new AtomicInteger();

    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

    @Test
    void injectedModelAllowanceStopsBeforeProviderAndToolDispatch() {
        var fixture = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        var checks = new AtomicInteger();
        var loop = new DeploymentModelToolLoop<String>(fixture.context, fixture.task, fixture.configurationRevision,
            "Owned model allowance", 2, () -> {
                checks.incrementAndGet();
                throw new IllegalStateException("Owner allowance exhausted");
            }, (name, output) -> output);
        var failure = assertThrows(IllegalStateException.class, () -> loop.run(() -> Map.of("done",
            new DeploymentModelToolLoop.Tool<>("Must not dispatch", args -> {
                fail("Owner allowance must precede tool dispatch");
                return DeploymentModelToolLoop.Feedback.ended(success("done"));
            })), Map::of));
        assertEquals("Owner allowance exhausted", failure.getMessage());
        assertEquals(1, checks.get());
        assertTrue(fixture.requests.isEmpty());
    }

    @Test
    void eachLoopUsesOnlyItsOwningModelAccountingAndFeedbackProjection() throws Exception {
        var first = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        var second = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        var firstChecks = new AtomicInteger();
        var secondChecks = new AtomicInteger();
        for (var fixture : List.of(first, second)) {
            fixture.nativeReply(new AiToolCall("observe", "read", "{}"));
            fixture.nativeReply(new AiToolCall("finish", "done", "{}"));
        }
        var tools = Map.of("read", new DeploymentModelToolLoop.Tool<String>("Owner feedback", args ->
                DeploymentModelToolLoop.Feedback.observed("raw-owner-value")),
            "done", new DeploymentModelToolLoop.Tool<String>("Finish locally", args -> DeploymentModelToolLoop.Feedback.ended(success("done"))));
        var firstLoop = new DeploymentModelToolLoop<String>(first.context, first.task, first.configurationRevision,
            "First protocol", 2, firstChecks::incrementAndGet, (name, output) -> "FIRST_PROTOCOL");
        var secondLoop = new DeploymentModelToolLoop<String>(second.context, second.task, second.configurationRevision,
            "Second protocol", 2, secondChecks::incrementAndGet, (name, output) -> "SECOND_PROTOCOL");
        assertEquals(TaskResult.SUCCEEDED, firstLoop.run(() -> tools, Map::of).outcome());
        assertEquals(2, firstChecks.get());
        assertEquals(0, secondChecks.get());
        assertEquals(TaskResult.SUCCEEDED, secondLoop.run(() -> tools, Map::of).outcome());
        assertEquals(2, firstChecks.get());
        assertEquals(2, secondChecks.get());
        assertEquals(List.of("FIRST_PROTOCOL"), first.requests.getLast().messages().stream()
            .flatMap(message -> message.toolResult().stream()).map(AiToolResult::text).toList());
        assertEquals(List.of("SECOND_PROTOCOL"), second.requests.getLast().messages().stream()
            .flatMap(message -> message.toolResult().stream()).map(AiToolResult::text).toList());
    }

    @Test
    void configuredObservationIndexSchemaAllowsActualEvidenceBeyondFourHundred() throws Exception {
        var fixture = new gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture();
        var evidence = new gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentEvidence();
        for (int index = 0; index <= 500; index++) evidence.observe("", REVISION, 0, "LIST", "source-" + index, false);
        fixture.nativeReply(new AiToolCall("index", "evidence_index", "{\"offset\":500,\"limit\":1}"));
        fixture.nativeReply(new AiToolCall("finish", "finish", "{}"));
        var reads = new AtomicInteger();
        var review = new DeploymentModelToolLoop.SelectionReview() {
            public boolean required(String name) {
                return false;
            }

            public void assess(String name, com.fasterxml.jackson.databind.JsonNode summary) {
                fail("No assessment authority is requested");
            }

            public String observed(String name, String output) {
                String id = evidence.observe("", REVISION, 0, "TOOL_" + name, "", false);
                evidence.output(id, output);
                return id;
            }

            public Map<String, Object> state() {
                return Map.of();
            }

            public String observation(String id, int offset, int limit) {
                return evidence.read(id, offset, limit);
            }

            public Map<String, Object> evidenceIndex(int offset, int limit) {
                assertEquals(500, offset);
                reads.incrementAndGet();
                return evidence.index(offset, limit);
            }
        };
        var loop = new DeploymentModelToolLoop<String>(fixture.context, fixture.task, fixture.configurationRevision,
            "Read actual original observation metadata", 2, review, () -> "", () -> {}, (name, output) -> output);
        var result = loop.run(() -> Map.of("finish", new DeploymentModelToolLoop.Tool<String>("Finish local selection",
            args -> DeploymentModelToolLoop.Feedback.ended(success("done")))), Map::of);
        assertEquals(TaskResult.SUCCEEDED, result.outcome());
        assertEquals(1, reads.get());
        assertEquals(2, fixture.requests.size());
        assertTrue(fixture.requests.getLast().messages().stream().flatMap(message -> message.toolResult().stream())
            .anyMatch(value -> value.text().contains("o501")), "The actual page reaches the next model request");
        var indexTool = fixture.requests.getFirst().tools().stream().filter(tool -> tool.name().equals("evidence_index")).findFirst().orElseThrow();
        assertEquals(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().maxObservations(),
            new ObjectMapper().readTree(indexTool.inputSchema()).at("/properties/offset/maximum").asInt());
    }

    @Test
    void modelChoosesIndependentOrderAndReceivesActualFeedback() throws Exception {
        var completed = new LinkedHashSet<String>();
        var loop = loop(List.of("web", "api", "publish"));
        var result = loop.run(() -> {
            var tools = new LinkedHashMap<String, DeploymentModelToolLoop.Tool<String>>();
            for (String id : List.of("api", "web"))
                if (!completed.contains(id))
                    tools.put(id, new DeploymentModelToolLoop.Tool<>("Build independent " + id, args -> {
                        DeploymentJsonFields.exact(args, Set.of());
                        selected.add(id);
                        completed.add(id);
                        return DeploymentModelToolLoop.Feedback.observed("Actual " + id + " build succeeded");
                    }));
            if (completed.size() == 2)
                tools.put("publish", new DeploymentModelToolLoop.Tool<>("Publish verified graph", args -> {
                    selected.add("publish");
                    return DeploymentModelToolLoop.Feedback.ended(success("published"));
                }));
            return tools;
        }, () -> Map.of("completed", List.copyOf(completed)));
        assertEquals(TaskResult.SUCCEEDED, result.outcome());
        assertEquals(List.of("web", "api", "publish"), selected);
        assertEquals(3, calls.get());
    }

    @Test
    void unavailableToolNeverExecutesAndStopsAfterBoundedRejections() throws Exception {
        var loop = loop(Collections.nCopies(6, "publish"));
        assertThrows(IllegalStateException.class, () -> loop.run(() -> Map.of(
            "prepare", new DeploymentModelToolLoop.Tool<>("Prepare only", args -> {
                selected.add("prepare");
                return DeploymentModelToolLoop.Feedback.observed("ready");
            })), Map::of));
        assertTrue(selected.isEmpty());
        assertEquals(6, calls.get());
    }

    @Test
    void observedActionsCannotExceedTheOriginalDecisionBudget() throws Exception {
        var effects = new AtomicInteger();
        var loop = loop(Collections.nCopies(6, "prepare"));
        var failure = assertThrows(IllegalStateException.class, () -> loop.run(() -> Map.of(
            "prepare", new DeploymentModelToolLoop.Tool<>("Prepare only", args -> {
                effects.incrementAndGet();
                return DeploymentModelToolLoop.Feedback.observed("Actual preparation observation");
            })), Map::of));
        assertEquals("Model tool decision or attempt budget exhausted", failure.getMessage());
        assertEquals(6, calls.get());
        assertEquals(6, effects.get());
    }

    @Test
    void cancellationPrecedesModelDispatchAndActionSelection() throws Exception {
        cancelled.set(true);
        var loop = loop(List.of());
        assertThrows(java.util.concurrent.CancellationException.class, () -> loop.run(() -> {
            throw new AssertionError("Cancelled session cannot inspect or execute available actions");
        }, Map::of));
        assertEquals(0, calls.get());
        assertTrue(selected.isEmpty());
    }

    private DeploymentModelToolLoop<String> loop(List<String> replies) {
        return loop(replies, 6);
    }

    @Test
    void configuredLocalBudgetCanPassEightyTurnsAndNestedScopeCannotReplenishIt() throws Exception {
        var loop = loop(Collections.nCopies(81, "advance"), 81);
        try (var outer = DeploymentToolBudget.open(90)) {
            assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("outer", "read", "{}"))));
            var result = loop.run(() -> Map.of("advance", new DeploymentModelToolLoop.Tool<>("Observe independent progress", args -> {
                selected.add("advance");
                return selected.size() == 81
                    ? DeploymentModelToolLoop.Feedback.ended(success("done"))
                    : DeploymentModelToolLoop.Feedback.observed("Actual observed progress " + selected.size());
            })), Map::of);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals(81, calls.get());
            assertEquals(8, DeploymentToolBudget.remaining());
            assertEquals(90, DeploymentToolBudget.maximum());
        }
    }

    private DeploymentModelToolLoop<String> loop(List<String> replies, int budget) {
        var ai = (AiService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AiService.class}, (p, m, a) -> {
            if (m.getName().equals("configurationRevision")) return REVISION;
            assertEquals("call", m.getName());
            int turn = calls.getAndIncrement();
            var request = (AiRequest) a[0];
            assertEquals(AiPurpose.LANGUAGE, request.purpose());
            var payload = new ObjectMapper().readTree(request.messages().getLast().text());
            assertEquals(budget - 1 - turn, payload.path("remaining").asInt());
            assertFalse(request.jsonObjectResponse());
            assertFalse(request.tools().isEmpty());
            if (!selected.isEmpty())
                assertTrue(request.messages().stream().anyMatch(message -> message.toolResult().isPresent()));
            return success(new AiResponse("", "controlled", "tool_calls", new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(new AiToolCall("call_" + turn, replies.get(turn), "{}")), Optional.of(new AiContinuation(UUID.randomUUID(), 1))));
        });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (p, m, a) -> {
            if (m.getName().equals("ai")) return ai;
            if (m.getName().equals("logs"))
                return (gold.debug.windowstolinux.appapi.task.log.LogService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{gold.debug.windowstolinux.appapi.task.log.LogService.class}, (peer, method, arguments) ->
                        method.getName().equals("register") ? new gold.debug.windowstolinux.appapi.task.log.LogTarget(java.util.UUID.randomUUID()) : null);
            throw new AssertionError(m.getName());
        });
        var task = (TaskContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TaskContext.class}, (p, m, a) -> switch (m.getName()) {
            case "cancelToken" -> (CancelToken) cancelled::get;
            case "progress" -> null;
            case "id" -> java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
            default -> throw new AssertionError(m.getName());
        });
        return new DeploymentModelToolLoop<>(context, task, REVISION, "Test model-selected tools", budget,
            () -> {}, (name, output) -> output);
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
