package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeHistory;
import org.junit.jupiter.api.Test;

class DeploymentAgentProtocolRequestTest {
    @Test
    void deploymentRequestPrefersBriefPublicConclusionsWithoutChangingExecutablePayloads() throws Exception {
        var fixture = new NativeAiFixture();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String script = "printf '%s\\n' 'actual source fact'\n".repeat(20);
        String summary = "s".repeat(350);
        var args = json.createObjectNode().put("component", "api").put("script", script).put("seconds", 60);
        var assessment = args.putObject("assessment");
        assessment.putArray("basis").add("o1");
        assessment.put("suitability", summary).put("differences", "Current o1 source facts")
            .put("resolution", "RESOLVED").put("decision", "Run the independently reviewed source-backed command");
        String submitted = args.toString();
        fixture.nativeReply(new AiToolCall("reviewed-command", "command", submitted));
        var response = new DeploymentAgentProtocol(fixture.context, "fixture-route").decide(
            Map.of("components", List.of(Map.of("id", "api"))), new DeploymentNativeHistory(), 79, fixture.task);
        var request = fixture.requests.getFirst();
        assertEquals(AiRole.SYSTEM, request.messages().getFirst().role());
        String policy = request.messages().getFirst().text();
        assertTrue(policy.contains("public explanation, reason, assessment.suitability, assessment.differences and assessment.decision"));
        assertTrue(policy.contains("aim for at most 200 characters per field"));
        assertTrue(policy.contains("the conclusion, relevant current evidence IDs and the next action or unresolved gap"));
        assertTrue(policy.contains("does not shorten, omit or rewrite scripts, source content, health.expected or other typed payloads"));
        assertEquals(submitted, response.toolCalls().getFirst().arguments());
        var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class,
            DeploymentAgentProtocol.parse("command", json.readTree(response.toolCalls().getFirst().arguments())));
        assertEquals(script, assertInstanceOf(DeploymentAgentProposal.Command.class, assessed.action()).script());
        assertEquals(summary, assessed.assessment().suitability(), "The writing target does not alter an otherwise valid typed payload");
        assertEquals(1, fixture.requests.size());
    }

    @Test
    void databasePreparationRequestExposesNativeChoicesWithoutConfusingSqlitePlanBindings() throws Exception {
        var fixture = new NativeAiFixture();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var args = json.createObjectNode().put("component", "cli");
        args.putArray("selections");
        args.set("assessment", json.readTree("""
            {"basis":["o1"],"suitability":"Prepare inspected SQLite storage","differences":"No native instances to select",
             "resolution":"RESOLVED","decision":"Prepare actual selected declaration"}
            """));
        String submitted = args.toString();
        var state = Map.<String, Object>of("components", List.of(Map.of("id", "cli", "databases", List.of("snapshots"))));
        fixture.nativeReply(new AiToolCall("prepare-sqlite", "databases", submitted));
        var response = new DeploymentAgentProtocol(fixture.context, "fixture-route")
            .decide(state, new DeploymentNativeHistory(), 79, fixture.task);
        var request = fixture.requests.getFirst();
        var databases = request.tools().stream().filter(tool -> tool.name().equals("databases")).findFirst().orElseThrow();
        var schema = json.readTree(databases.inputSchema());
        var selections = schema.at("/properties/selections");
        assertEquals("array", selections.path("type").asText());
        assertEquals(0, selections.path("minItems").asInt(-1));
        assertEquals(32, selections.path("maxItems").asInt());
        assertTrue(selections.path("uniqueItems").asBoolean());
        var item = selections.path("items");
        assertEquals("object", item.path("type").asText());
        assertFalse(item.path("additionalProperties").asBoolean(true));
        var required = new HashSet<String>();
        item.path("required").forEach(field -> required.add(field.asText()));
        assertEquals(Set.of("databaseId", "instanceId"), required);
        var fields = new HashSet<String>();
        item.path("properties").fieldNames().forEachRemaining(fields::add);
        assertEquals(required, fields);
        var databaseId = item.at("/properties/databaseId");
        assertEquals(1, databaseId.path("minLength").asInt());
        assertEquals(32, databaseId.path("maxLength").asInt());
        assertEquals("[a-z][a-z0-9-]{0,31}", databaseId.path("pattern").asText());
        var instanceId = item.at("/properties/instanceId");
        assertEquals("string", instanceId.path("type").asText());
        assertEquals(0, instanceId.path("minLength").asInt(-1));
        assertEquals(256, instanceId.path("maxLength").asInt());
        assertFalse(instanceId.has("default"));
        assertFalse(instanceId.has("const"));
        assertFalse(instanceId.has("enum"));
        assertTrue(databaseId.path("description").asText().contains("databaseId must be unique"));
        assertTrue(instanceId.path("description").asText().contains("empty string proposes a new native installation"));
        for (String text : List.of(request.messages().getFirst().text(), databases.description())) {
            assertTrue(text.contains("SQLite-only inventories require selections:[]; this does not mean plan.databases is empty"));
            assertTrue(text.contains("native-only and mixed inventories"));
            assertTrue(text.contains("excluding SQLite rows"));
            assertTrue(text.contains("unique databaseId values and at most 32 selections"));
            assertTrue(text.contains("independent business/install approval"));
            assertTrue(text.contains("replan with all actual prepared IDs, including SQLite"));
        }
        assertTrue(request.messages().getFirst().text().contains("database_inspect/database_select_source/databases"));
        assertEquals(json.valueToTree(state), json.readTree(request.messages().getLast().text()).path("state"));
        assertEquals(submitted, response.toolCalls().getFirst().arguments(), "The model's empty native selection is never filled or changed");
        var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class,
            DeploymentAgentProtocol.parse("databases", json.readTree(response.toolCalls().getFirst().arguments())));
        var capability = assertInstanceOf(DeploymentAgentProposal.Capability.class, assessed.action());
        assertEquals("databases", capability.name());
        assertEquals("cli", capability.component());
        assertTrue(capability.arguments().path("selections").isArray());
        assertTrue(capability.arguments().path("selections").isEmpty());
        assertEquals(1, fixture.requests.size(), "Only the controlled native request runs; no database effects or authorization are created");
    }

    @Test
    void sealedDependencyRequestExplainsBuildOrderAndOwnSourceWithoutInventingArtifactAccess() throws Exception {
        var fixture = new NativeAiFixture();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String script = "printf '%s\\n' \"$WTL_SOURCE/native/CMakeLists.txt\" \"$WTL_OUTPUT/native/build/scan-worker\"";
        var args = json.createObjectNode().put("component", "cli").put("script", script).put("seconds", 60);
        args.set("assessment", json.readTree("""
            {"basis":["o1"],"suitability":"Inspect source-backed companion paths","differences":"Dependency already sealed",
             "resolution":"RESOLVED","decision":"Inspect own candidate paths"}
            """));
        String submitted = args.toString();
        String artifact = "a".repeat(64);
        var state = Map.<String, Object>of("components", List.of(
            Map.of("id", "native", "sourceRoot", "native", "dependencies", List.of()),
            Map.of("id", "cli", "sourceRoot", "cli", "dependencies", List.of("native"))), "sealed", Map.of("native", artifact));
        fixture.nativeReply(new AiToolCall("inspect-companion", "command", submitted));
        var response = new DeploymentAgentProtocol(fixture.context, "fixture-route")
            .decide(state, new DeploymentNativeHistory(), 79, fixture.task);
        var request = fixture.requests.getFirst();
        var command = request.tools().stream().filter(tool -> tool.name().equals("command")).findFirst().orElseThrow();
        for (String text : List.of(request.messages().getFirst().text(), command.description())) {
            assertTrue(text.contains("Dependencies enforce build order; they do not automatically mount other components' sealed artifacts"));
            assertTrue(text.contains("Each component's WTL_SOURCE initially contains the entire original archive as a read-only source tree"));
            assertTrue(text.contains("Build source-backed companion tools inside this component's WTL_OUTPUT"));
            assertTrue(text.contains("retain the actual artifact-relative runtime paths expected by its launcher"));
            assertTrue(text.contains("Another component's sealed mutable directory is root-owned mode 0700 and is not a readable dependency location"));
            assertTrue(text.contains("Never chmod or write WTL_SOURCE"));
        }
        var current = json.readTree(request.messages().getLast().text()).path("state");
        assertEquals(json.valueToTree(state), current, "Sealed identities remain facts; no artifact path or mount is invented");
        assertEquals(artifact, current.at("/sealed/native").asText());
        assertEquals(List.of("native"), json.convertValue(current.at("/components/1/dependencies"), List.class));
        assertEquals(submitted, response.toolCalls().getFirst().arguments());
        var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class,
            DeploymentAgentProtocol.parse("command", json.readTree(response.toolCalls().getFirst().arguments())));
        assertEquals(script, assertInstanceOf(DeploymentAgentProposal.Command.class, assessed.action()).script());
        assertEquals(1, fixture.requests.size(), "The controlled request cannot mount, execute or grant remote access");
    }

    @Test
    void commandRequestExplainsProtectedCopyModesWithoutRewritingScriptOrSchema() throws Exception {
        var fixture = new NativeAiFixture();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String script = "set -eu\ncp -a \"$WTL_SOURCE/native\" \"$WTL_OUTPUT/native\"\n"
            + "cd \"$WTL_OUTPUT/native\"\ncmake --preset w2l-release\n";
        var args = json.createObjectNode().put("component", "native").put("script", script).put("seconds", 60);
        args.set("assessment", json.readTree("""
            {"basis":["o1"],"suitability":"Build inspected native source","differences":"Copy protected input",
             "resolution":"RESOLVED","decision":"Run reviewed build"}
            """));
        String submitted = args.toString();
        fixture.nativeReply(new AiToolCall("copy-native", "command", submitted));
        var response = new DeploymentAgentProtocol(fixture.context, "fixture-route").decide(
            Map.of("components", List.of(Map.of("id", "native"))), new DeploymentNativeHistory(), 79, fixture.task);
        var request = fixture.requests.getFirst();
        var command = request.tools().stream().filter(tool -> tool.name().equals("command")).findFirst().orElseThrow();
        for (String text : List.of(request.messages().getFirst().text(), command.description())) {
            assertTrue(text.contains("WTL_SOURCE directories have mode 0555 and files 0444"));
            assertTrue(text.contains("cp -a preserves those read-only permission bits"));
            assertTrue(text.contains("owner read/write permissions on copied files"));
            assertTrue(text.contains("owner read/write/search permissions on copied directories only beneath WTL_OUTPUT"));
            assertTrue(text.contains("Never chmod or write WTL_SOURCE"));
        }
        try (var input = getClass().getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/linux/helper/workspace/source-operations.py")) {
            assertNotNull(input);
            String source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(source.contains("destination.chmod(0o444)"));
            assertTrue(source.contains("path.chmod(0o555)"));
            assertTrue(source.contains("root.chmod(0o555)"));
        }
        var schema = json.readTree(command.inputSchema());
        var fields = new HashSet<String>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("component", "script", "seconds", "assessment"), fields);
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertFalse(schema.at("/properties/script").has("default"));
        assertFalse(schema.at("/properties/script").has("const"));
        assertEquals(submitted, response.toolCalls().getFirst().arguments());
        var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class,
            DeploymentAgentProtocol.parse("command", json.readTree(response.toolCalls().getFirst().arguments())));
        assertEquals(script, assertInstanceOf(DeploymentAgentProposal.Command.class, assessed.action()).script());
    }

    @Test
    void latestCompletedBatchCanLeaveActualRequestWithoutLosingRejectionOrOriginalReadIdentity() throws Exception {
        var fixture = new NativeAiFixture();
        var protocol = new DeploymentAgentProtocol(fixture.context, "fixture-route");
        var history = new DeploymentNativeHistory();
        var evidence = new DeploymentAgentEvidence();
        String revision = "a".repeat(64);
        String read = evidence.observe("", revision, 0, "READ", "api/main.py", false);
        evidence.output(read, "api/main.py\noriginal source body");
        var observed = new AiResponse("", "model", "tool_calls",
            new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
            List.of(new AiToolCall("read-original", "read", "{\"path\":\"api/main.py\",\"offset\":0,\"limit\":10}")),
            Optional.of(new AiContinuation(UUID.randomUUID(), 1)));
        history.retain("state", observed, List.of(new AiToolResult("read-original", read + "\noriginal source body", false)));
        String reason = "TOOL_REJECTED: HTTP health endpoint must target the candidate host loopback address";
        var rejected = new AiResponse("", "model", "tool_calls", observed.usage(),
            List.of(new AiToolCall("rejected-plan", "plan", "{}")), Optional.of(new AiContinuation(UUID.randomUUID(), gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().history().payloadBytes() - 2048)));
        history.retain("state", rejected, List.of(new AiToolResult("rejected-plan", reason, true)));
        var correction = new LinkedHashMap<String, Object>();
        correction.put("callId", "rejected-plan");
        correction.put("tool", "plan");
        correction.put("reason", reason);
        correction.put("evidenceId", null);
        var state = Map.<String, Object>of("judgement", evidence.state(), "evidenceIndex", evidence.latestIndex(),
            "recentRejections", List.of(correction), "sourceRevision", revision);
        String query = "{\"id\":\"" + read + "\",\"offset\":0,\"limit\":6000}";
        fixture.nativeReply(new AiToolCall("recover-original", "observation", query));
        var response = protocol.decide(state, history, 78, fixture.task);
        var request = fixture.requests.getFirst();
        assertTrue(request.messages().stream().flatMap(message -> message.toolCalls().stream()).noneMatch(call -> call.id().equals("rejected-plan")));
        assertTrue(request.messages().stream().flatMap(message -> message.toolResult().stream()).noneMatch(result -> result.callId().equals("rejected-plan")));
        assertEquals(observed.continuation(), request.messages().stream().filter(message -> !message.toolCalls().isEmpty()).findFirst().orElseThrow().continuation());
        var current = new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.messages().getLast().text());
        assertEquals(reason, current.at("/state/recentRejections/0/reason").asText());
        assertEquals("rejected-plan", current.at("/state/recentRejections/0/callId").asText());
        assertTrue(current.at("/state/recentRejections/0/evidenceId").isNull());
        assertEquals(read, current.at("/state/evidenceIndex/entries/0/id").asText());
        assertEquals(1, current.at("/state/evidenceIndex/entries").size());
        assertEquals(78, current.path("remaining").asInt());
        assertEquals(query, response.toolCalls().getFirst().arguments());
        assertInstanceOf(DeploymentAgentProposal.Observation.class,
            DeploymentAgentProtocol.parse("observation", new com.fasterxml.jackson.databind.ObjectMapper().readTree(query)));
        assertTrue(evidence.read(read, 0, 6000).contains("original source body"));
        assertThrows(IllegalArgumentException.class, () -> evidence.read("rejected-plan", 0, 6000));
    }

    @Test
    void nativeCatalogMatchesCurrentExecutionAvailability() throws Exception {
        var fixture = new NativeAiFixture();
        var protocol = new DeploymentAgentProtocol(fixture.context, "fixture-route");
        fixture.nativeReply(new AiToolCall("first", "read", "{}"));
        protocol.decide(Map.of(), new DeploymentNativeHistory(), 79, fixture.task);
        var planned = Map.<String, Object>of("components", List.of(Map.of("id", "app")));
        fixture.nativeReply(new AiToolCall("second", "read", "{}"));
        protocol.decide(planned, new DeploymentNativeHistory(), 78, fixture.task);
        assertEquals(DeploymentAgentProtocol.catalog(Map.of()), fixture.requests.getFirst().tools());
        assertEquals(DeploymentAgentProtocol.catalog(planned), fixture.requests.getLast().tools());
        assertFalse(fixture.requests.getFirst().tools().stream().anyMatch(t -> t.name().equals("deliver")));
        assertTrue(fixture.requests.getLast().tools().stream().anyMatch(t -> t.name().equals("deliver")));
        assertFalse(DeploymentAgentProtocol.catalog(Map.of()).stream().anyMatch(t -> t.name().equals("deliver")));
        assertTrue(DeploymentAgentProtocol.catalog(planned).stream().anyMatch(t -> t.name().equals("deliver")));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertFalse(json.readTree(fixture.requests.getFirst().messages().getLast().text()).path("availableTools").toString().contains("\"deliver\""));
        assertTrue(json.readTree(fixture.requests.getLast().messages().getLast().text()).path("availableTools").toString().contains("\"deliver\""));
        var indexTool = fixture.requests.getFirst().tools().stream().filter(tool -> tool.name().equals("evidence_index")).findFirst().orElseThrow();
        assertEquals(gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().maxObservations(),
            json.readTree(indexTool.inputSchema()).at("/properties/offset/maximum").asInt());
    }

    @Test
    void pendingIdsStayInCurrentStateAndHostChecksThemWithoutFillingModelBasis() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var evidence = new DeploymentAgentEvidence();
        String revision = "a".repeat(64);
        String read = evidence.observe("", revision, 0, "READ", "api/main.py", false);
        var pending = List.of(evidence.observe("api", revision, 1, "TOOL_check", "", true),
            evidence.observe("cpp", revision, 1, "TOOL_check", "", true),
            evidence.observe("python", revision, 1, "TOOL_check", "", true));
        var state = Map.<String, Object>of("components", List.of(Map.of("id", "api")), "judgement", evidence.state());
        var args = json.readTree("""
            {"component":"api","recipe":{"version":"","primary":"python3","secondary":"","jvmTarget":"","servicePort":0},
             "assessment":{"basis":[],"suitability":"Prepare the selected component from actual checks",
             "differences":"Other component checks also need evaluation","resolution":"RESOLVED","decision":"Prepare current toolchain"}}
            """);
        ((com.fasterxml.jackson.databind.node.ArrayNode) args.at("/assessment/basis")).add(read);
        String submitted = args.toString();
        var fixture = new NativeAiFixture();
        fixture.nativeReply(new AiToolCall("incomplete", "prepare_tools", submitted));
        var response = new DeploymentAgentProtocol(fixture.context, "fixture-route")
            .decide(state, new DeploymentNativeHistory(), 79, fixture.task);
        var request = fixture.requests.getFirst();
        var facts = json.readTree(request.messages().getLast().text());
        assertEquals(request.tools().stream().map(AiToolDefinition::name).toList(),
            json.convertValue(facts.path("availableTools"), List.class));
        assertEquals(pending, json.convertValue(facts.at("/state/judgement/pending"), List.class));
        for (var tool : request.tools())
            if (DeploymentAgentProtocol.critical(tool.name())) {
                var basis = json.readTree(tool.inputSchema()).at("/properties/assessment/properties/basis");
                assertEquals(1, basis.path("minItems").asInt());
                assertEquals(32, basis.path("maxItems").asInt());
                assertFalse(basis.has("const"), "Additional valid current READ evidence remains permitted");
                assertFalse(basis.has("allOf"), "Pending identities belong to the latest state, not the stable native schema");
                assertTrue(tool.description().contains("ALL current state.judgement.pending IDs across every component"));
            }
        assertTrue(request.messages().getFirst().text().contains("Submit at most ONE critical action that adds pending observations"));
        assertEquals(submitted, response.toolCalls().getFirst().arguments(), "The product never repairs the model's omitted evidence IDs");
        var rejected = assertInstanceOf(DeploymentAgentProposal.Assessed.class,
            DeploymentAgentProtocol.parse("prepare_tools", json.readTree(response.toolCalls().getFirst().arguments())));
        var revisions = Map.of("api", revision, "cpp", revision, "python", revision);
        assertThrows(IllegalArgumentException.class,
            () -> evidence.assess("Capability", rejected.assessment(), revisions, revision, 1));
        assertEquals(pending, evidence.state().get("pending"));
        var corrected = (com.fasterxml.jackson.databind.node.ObjectNode) args.deepCopy();
        var basis = ((com.fasterxml.jackson.databind.node.ObjectNode) corrected.get("assessment")).putArray("basis");
        basis.add(read);
        pending.forEach(basis::add);
        var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class, DeploymentAgentProtocol.parse("prepare_tools", corrected));
        evidence.assess("Capability", assessed.assessment(), revisions, revision, 1);
        assertTrue(((List<?>) evidence.state().get("pending")).isEmpty());
    }

    @Test
    void resolvedPendingRemovesConstraintsAndKeepsCurrentReadBasisAvailable() throws Exception {
        var fixture = new NativeAiFixture();
        var protocol = new DeploymentAgentProtocol(fixture.context, "fixture-route");
        var evidence = new DeploymentAgentEvidence();
        String revision = "a".repeat(64);
        String read = evidence.observe("", revision, 0, "READ", "api/main.py", false);
        String pending = evidence.observe("api", revision, 1, "TOOL_prepare_tools", "", true);
        var components = List.of(Map.of("id", "api"));
        fixture.nativeReply(new AiToolCall("pending", "read", "{}"));
        protocol.decide(Map.of("components", components, "judgement", evidence.state()), new DeploymentNativeHistory(), 79, fixture.task);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var before = fixture.requests.getFirst().tools().stream().filter(tool -> tool.name().equals("build")).findFirst().orElseThrow();
        var beforeBasis = json.readTree(before.inputSchema()).at("/properties/assessment/properties/basis");
        assertFalse(beforeBasis.has("allOf"));
        assertEquals(List.of(pending), json.convertValue(json.readTree(fixture.requests.getFirst().messages().getLast().text())
            .at("/state/judgement/pending"), List.class));
        assertThrows(IllegalArgumentException.class, () -> evidence.assess("Build", new DeploymentAgentAssessment(List.of(read),
                "Current source", "Pending actual result omitted", DeploymentAgentAssessment.ResolutionType.RESOLVED, "Build current source"),
            Map.of("api", revision), revision, 1));
        evidence.assess("Build", new DeploymentAgentAssessment(List.of(pending, read), "Current toolchain ready",
                "Actual preparation feedback evaluated", DeploymentAgentAssessment.ResolutionType.RESOLVED, "Build current source"),
            Map.of("api", revision), revision, 1);
        fixture.nativeReply(new AiToolCall("read-basis", "command", "{\"component\":\"api\",\"script\":\"printf artifact\",\"seconds\":10,\"assessment\":{\"basis\":[\""
            + read + "\"],\"suitability\":\"Current source\",\"differences\":\"No pending results\",\"resolution\":\"RESOLVED\",\"decision\":\"Build source\"}}"));
        var state = Map.<String, Object>of("components", components, "judgement", evidence.state());
        var response = protocol.decide(state, new DeploymentNativeHistory(), 78, fixture.task);
        for (var tool : fixture.requests.getLast().tools())
            if (DeploymentAgentProtocol.critical(tool.name())) {
                var basis = json.readTree(tool.inputSchema()).at("/properties/assessment/properties/basis");
                assertFalse(basis.has("allOf"));
                assertFalse(basis.has("const"));
                assertEquals(1, basis.path("minItems").asInt());
                assertFalse(tool.description().contains("Required pending assessment.basis IDs for this request"));
            }
        assertEquals(DeploymentAgentProtocol.catalog(Map.of("components", components)), fixture.requests.getLast().tools(),
            "A prior dynamic catalog must not mutate the registered schema or constrain later read evidence");
        assertEquals(fixture.requests.getFirst().tools(), fixture.requests.getLast().tools(), "Pending changes do not rewrite native contracts");
        var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class,
            DeploymentAgentProtocol.parse("command", json.readTree(response.toolCalls().getFirst().arguments())));
        assertEquals(List.of(read), assessed.assessment().basis());
        evidence.assess("Command", assessed.assessment(), Map.of("api", revision), revision, 1);
        String next = evidence.observe("api", revision, 1, "TOOL_prepare_tools", "", true);
        assertThrows(IllegalArgumentException.class,
            () -> evidence.assess("Command", assessed.assessment(), Map.of("api", revision), revision, 1),
            "A later critical action must wait for and evaluate the actual new result ID");
        assertEquals(List.of(next), evidence.state().get("pending"));
    }

    @Test
    void batchIsReturnedUnchangedAndMalformedParametersAreNotRewritten() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.nativeReply(new AiToolCall("r1", "read", "{\"path\":\"app.py\"}"), new AiToolCall("r2", "list", "{\"path\":\".\",\"offset\":0,\"limit\":10}"));
        var protocol = new DeploymentAgentProtocol(fixture.context, "fixture-route");
        var response = protocol.decide(Map.of(), new DeploymentNativeHistory(), 79, fixture.task);
        assertEquals(2, response.toolCalls().size());
        assertEquals(1, fixture.requests.size());
        assertTrue(protocol.used());
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("read", new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.toolCalls().getFirst().arguments())));
    }
}
