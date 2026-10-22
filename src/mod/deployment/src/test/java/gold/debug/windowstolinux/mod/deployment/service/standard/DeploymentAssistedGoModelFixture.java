package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReviewCodec;

/** Controlled source evidence and available-tool replies for Go publication regression fixtures. */
final class DeploymentAssistedGoModelFixture {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeploymentAssistedGoModelFixture() {
    }

    static AiResponse nativeResponse(AiRequest request, String model, String response) throws Exception {
        var value = JSON.readTree(response);
        String name = value.has("tool") ? value.path("tool").textValue() : "submit_result";
        String arguments = value.has("tool") ? value.path("arguments").toString() : response;
        assertTrue(request.tools().stream().anyMatch(tool -> tool.name().equals(name)),
            "The controlled response must use an actually offered native tool: " + name);
        return new AiResponse("", model, "tool_calls", new AiUsage(java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()),
            List.of(new AiToolCall("controlled-" + java.util.UUID.randomUUID(), name, arguments)), java.util.Optional.empty());
    }

    static String diagnostics(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, java.util.UUID task) {
        var result = new StringBuilder("Original task evidence for ").append(task);
        try (var connection = runtime.persistence().database().openConnection()) {
            try (var query = connection.prepareStatement("SELECT state,progress_text,result_json FROM platform_task WHERE task_id=?")) {
                query.setObject(1, task);
                try (var rows = query.executeQuery()) { if (rows.next()) result.append("\nstate=").append(rows.getString(1))
                    .append(" progress=").append(rows.getString(2)).append(" result=").append(rows.getString(3)); }
            }
            try (var query = connection.prepareStatement("SELECT event_code,message,SUBSTRING(details,1,12000) FROM runtime_log WHERE task_id=? ORDER BY recorded_at DESC LIMIT 30")) {
                query.setObject(1, task);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) result.append("\n").append(rows.getString(1)).append(": ").append(rows.getString(2)).append(" ").append(rows.getString(3));
                }
            }
            try (var query = connection.prepareStatement("SELECT resource_kind,resource_id,SUBSTRING(recovery_json,1,16000) FROM platform_task_resource WHERE task_id=? AND released_at IS NULL AND resource_kind='CUSTOM' ORDER BY resource_id")) {
                query.setObject(1, task);
                try (var rows = query.executeQuery()) { while (rows.next()) result.append("\nheld=").append(rows.getString(1)).append(":")
                    .append(rows.getString(2)).append(" ").append(rows.getString(3)); }
            }
        } catch (Exception unreadable) { result.append("\nDiagnostics unavailable: ").append(unreadable); }
        return result.toString();
    }

    static String sourceScan(JsonNode facts, int count) throws Exception {
        var components = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < count; index++) {
            String root = count == 1 ? "." : "component" + index;
            String manifest = count == 1 ? "go.mod" : root + "/go.mod";
            boolean read = false;
            for (var observation : facts.path("observations"))
                if (observation.path("tool").asText().equals("read")
                    && observation.path("path").asText().equals(manifest)) {
                    assertTrue(observation.path("output").toString().contains("module example.test/component"));
                    read = true;
                }
            if (!read)
                return JSON.writeValueAsString(Map.of("tool", "read", "arguments",
                    Map.of("path", manifest, "offset", 0, "limit", 100)));
            components.add(Map.of("id", "component" + index, "root", root, "type", "GO_SERVICE",
                "evidence", List.of(manifest)));
        }
        return JSON.writeValueAsString(Map.of("tool", "identify", "arguments", Map.of("components", components)));
    }

    static String publicationTool(JsonNode facts) throws Exception {
        var tools = new ArrayList<String>();
        var offered = facts.path("state").path("availableTools");
        assertTrue(offered.isArray(), "The publication loop supplies its native catalog in state.availableTools");
        offered.forEach(tool -> { assertTrue(tool.isTextual()); tools.add(tool.textValue()); });
        assertFalse(tools.isEmpty());
        assertEquals(tools.size(), new java.util.HashSet<>(tools).size());
        String selected = tools.stream().min(Comparator.comparingInt(DeploymentAssistedGoModelFixture::priority)
            .thenComparing(Comparator.naturalOrder())).orElseThrow();
        Map<String, String> arguments = facts.path("state").has("reviewedGraph")
            && Set.of("prepare", "build").contains(selected)
            ? Map.of("component", availableComponents(facts.path("state"), selected).getFirst()) : Map.of();
        return JSON.writeValueAsString(Map.of("tool", selected, "arguments", arguments));
    }

    static String publicationTool(AiRequest request, JsonNode facts) throws Exception {
        var declared = new ArrayList<String>();
        facts.path("state").path("availableTools").forEach(tool -> { assertTrue(tool.isTextual()); declared.add(tool.textValue()); });
        assertEquals(request.tools().stream().map(tool -> tool.name()).sorted().toList(), declared.stream().sorted().toList(),
            "Publication state and actual native tool definitions must describe the same complete catalog");
        String response = publicationTool(facts);
        var selected = JSON.readTree(response);
        var definition = request.tools().stream().filter(tool -> tool.name().equals(selected.path("tool").textValue()))
            .findFirst().orElseThrow();
        var schema = JSON.readTree(definition.inputSchema());
        DeploymentToolSchemas.validate(schema, selected.path("arguments"));
        if (selected.path("arguments").has("component")) {
            assertEquals(DeploymentToolSchemas.object("component"), schema);
            String verb = definition.name();
            assertEquals((verb.equals("prepare") ? "Prepare" : "Build")
                + " an explicitly selected available component: " + availableComponents(facts.path("state"), verb),
                definition.description(), "Native component choices must exactly match the original graph guards");
        }
        return response;
    }

    private static List<String> availableComponents(JsonNode state, String verb) throws Exception {
        assertTrue(state.path("reviewedGraph").isTextual());
        var graph = DeploymentAssistedBuildReviewCodec.decodeGraph(state.path("reviewedGraph").textValue());
        assertEquals(graph.applicationId(), state.path("application").textValue());
        assertEquals(JSON.readTree(JSON.writeValueAsString(graph.dependencies())), state.path("dependencies"));
        var prepared = componentSet(state.path("prepared"));
        var built = componentSet(state.path("built"));
        assertTrue(new HashSet<>(graph.order()).containsAll(prepared));
        assertTrue(prepared.containsAll(built));
        var available = graph.order().stream().filter(component -> verb.equals("prepare")
            ? !prepared.contains(component)
            : prepared.size() == graph.order().size() && !built.contains(component)
                && built.containsAll(graph.dependencies().get(component))).toList();
        assertFalse(available.isEmpty(), "The selected graph verb must have a currently available component");
        return available;
    }

    private static Set<String> componentSet(JsonNode values) {
        assertTrue(values.isArray());
        var components = new HashSet<String>();
        values.forEach(value -> {
            assertTrue(value.isTextual());
            assertTrue(components.add(value.textValue()), "Component state cannot contain duplicates");
        });
        return components;
    }

    private static int priority(String tool) {
        if (tool.equals("stop")) return 0;
        if (tool.equals("retry")) return 1;
        if (tool.startsWith("prepare")) return 2;
        if (tool.startsWith("build")) return 3;
        if (tool.equals("publish")) return 4;
        if (tool.equals("finish")) return 5;
        if (tool.equals("inspect-host")) return 6;
        if (tool.equals("supplement")) return 7;
        throw new AssertionError("Unexpected publication tool: " + tool);
    }
}
