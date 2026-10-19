package gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAssistedCatalogStateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void latestStateTracksPrepareBuildPublishWithoutRetainingAnObsoleteCatalog(int components) throws Exception {
        var fixture = new NativeAiFixture();
        var ids = components == 2 ? List.of("backend", "frontend") : List.of("scorer", "backend", "frontend");
        var prepared = new LinkedHashSet<String>();
        var built = new LinkedHashSet<String>();
        for (String action : List.of("prepare", "build"))
            for (String id : ids)
                fixture.nativeReply(new AiToolCall(action + id, action, "{\"component\":\"" + id + "\"}"));
        fixture.nativeReply(new AiToolCall("publish-final", "publish", "{}"));
        var result = DeploymentAssistedDeployment.run(context(fixture), () -> {
            throw new AssertionError("No SSH capability in catalog test");
        }, new ServerId("fixture"), fixture.task, () -> {
            throw new AssertionError("No host observation is needed in this scenario");
        }, () -> {
            var tools = new LinkedHashMap<String, DeploymentModelToolLoop.Tool<String>>();
            if (prepared.size() < ids.size())
                tools.put("prepare", componentTool(id -> {
                    assertTrue(prepared.add(id));
                    return DeploymentModelToolLoop.Feedback.observed("Component " + id + " prepared");
                }));
            else if (built.size() < ids.size())
                tools.put("build", componentTool(id -> {
                    assertTrue(prepared.contains(id));
                    assertTrue(built.containsAll(ids.subList(0, ids.indexOf(id))));
                    assertTrue(built.add(id));
                    return DeploymentModelToolLoop.Feedback.observed("Component " + id + " build verified");
                }));
            else tools.put("publish", new DeploymentModelToolLoop.Tool<>("Publish complete graph", args ->
                    DeploymentModelToolLoop.Feedback.ended(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of("published"), Optional.empty()))));
            return tools;
        }, () -> Map.of("prepared", List.copyOf(prepared), "built", List.copyOf(built), "availableTools", List.of("obsolete")));
        assertEquals(TaskResult.SUCCEEDED, result.outcome());
        assertEquals(2 * components + 1, fixture.requests.size());
        for (int turn = 0; turn < fixture.requests.size(); turn++) {
            var request = fixture.requests.get(turn);
            var current = JSON.readTree(request.messages().getLast().text()).path("state");
            assertEquals(request.tools().stream().map(AiToolDefinition::name).sorted().toList(),
                JSON.convertValue(current.path("availableTools"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                }));
            String expected = turn < components ? "prepare" : turn < 2 * components ? "build" : "publish";
            assertTrue(current.path("availableTools").toString().contains("\"" + expected + "\""));
            assertFalse(current.path("availableTools").toString().contains("obsolete"));
            assertTrue(request.messages().getFirst().text().contains("catalogs from earlier turns are obsolete"));
        }
    }

    @Test
    void proseAboutMissingToolsStillFailsWithoutExecutingOrRetrying() {
        var fixture = new NativeAiFixture();
        fixture.replies.add(new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new AiResponse("No build tool is available", "fixture", "stop",
                new AiUsage(Optional.of(1L), Optional.of(1L), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(), Optional.of(new AiContinuation(UUID.randomUUID(), 1)))), Optional.empty()));
        var effects = new AtomicInteger();
        var failure = assertThrows(IllegalStateException.class, () -> DeploymentAssistedDeployment.run(context(fixture),
            () -> {
                throw new AssertionError("No SSH");
            }, new ServerId("fixture"), fixture.task,
            () -> {
                throw new AssertionError("No host observation");
            },
            () -> Map.of("build", new DeploymentModelToolLoop.Tool<String>("Build prepared component", args -> {
                effects.incrementAndGet();
                return DeploymentModelToolLoop.Feedback.observed("must not execute");
            })), () -> Map.of("prepared", List.of("scorer", "backend", "frontend"))));
        assertEquals("Native tool action required; text cannot finish deployment", failure.getMessage());
        assertEquals(0, effects.get());
        assertEquals(1, fixture.requests.size());
    }

    private static DeploymentModelToolLoop.Tool<String> componentTool(java.util.function.Function<String, DeploymentModelToolLoop.Feedback<String>> action) {
        return new DeploymentModelToolLoop.Tool<>("Select component", "{\"type\":\"object\",\"properties\":{\"component\":{\"type\":\"string\"}},\"required\":[\"component\"],\"additionalProperties\":false}",
            args -> action.apply(args.path("component").asText()));
    }

    private static ModContext context(NativeAiFixture fixture) {
        var tasks = (TaskService) Proxy.newProxyInstance(TaskService.class.getClassLoader(), new Class<?>[]{TaskService.class},
            (proxy, method, args) -> {
                if (method.getName().equals("findCheckpoint") && args[0] == fixture.task) return Optional.empty();
                throw new AssertionError(method.getName());
            });
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("tasks")) return tasks;
                try {
                    return method.invoke(fixture.context, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }
}
