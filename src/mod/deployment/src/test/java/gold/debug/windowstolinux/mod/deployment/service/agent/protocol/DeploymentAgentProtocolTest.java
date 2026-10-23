package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCommand;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import org.junit.jupiter.api.Test;

class DeploymentAgentProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reviewResponseSchemaMatchesLocalExclusiveAdmissionAndBothMessageLimits() throws Exception {
        var definition = DeploymentAgentProtocol.catalog().stream().filter(tool -> tool.name().equals("review_response")).findFirst().orElseThrow();
        var schema = json.readTree(definition.inputSchema());
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals(64, schema.at("/properties/reviewBinding/minLength").asInt());
        assertEquals(64, schema.at("/properties/reviewBinding/maxLength").asInt());
        assertEquals(0, schema.at("/properties/message/minLength").asInt());
        assertEquals(3000, schema.at("/properties/message/maxLength").asInt());
        assertFalse(schema.path("properties").has("assessment"));
        assertFalse(DeploymentAgentProtocol.critical("review_response"));
        assertEquals(gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Execution.EXCLUSIVE,
            DeploymentAgentProtocol.execution("review_response"));
        assertFalse(DeploymentAgentProtocol.available("review_response", java.util.Map.of()));
        assertTrue(DeploymentAgentProtocol.available("review_response", java.util.Map.of("reviewResponseAvailable", true)));
        var args = json.createObjectNode().put("reviewBinding", "a".repeat(64)).put("message", "");
        for (String valid : java.util.List.of("", "x".repeat(3000), "中".repeat(2000))) {
            args.put("message", valid);
            gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.validate(schema, args);
            var parsed = assertInstanceOf(DeploymentAgentProposal.ReviewResponse.class, DeploymentAgentProtocol.parse("review_response", args));
            assertEquals(valid, parsed.message());
        }
        for (String invalid : java.util.List.of("x".repeat(3001), "中".repeat(2001))) {
            args.put("message", invalid);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("review_response", args));
        }
        args.put("message", "bounded");
        for (String invalid : java.util.List.of("a".repeat(63), "a".repeat(65), "G".repeat(64))) {
            args.put("reviewBinding", invalid);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("review_response", args));
        }
        args.put("reviewBinding", "a".repeat(64)).put("unexpected", "data");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("review_response", args));
        var unavailable = new gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort() {
            public java.util.Map<String, String> inspect() {
                throw new AssertionError();
            }

            public Prepared prepare(String id, DeploymentAgentPlan.Part part) {
                throw new AssertionError();
            }

            public CommandFeedback command(gold.debug.windowstolinux.appapi.task.run.TaskContext task, Prepared candidate, String revision, String script, java.time.Duration timeout) {
                throw new AssertionError();
            }

            public String read(gold.debug.windowstolinux.appapi.task.run.TaskContext task, Prepared candidate, String revision, String path, int offset, int limit) {
                throw new AssertionError();
            }

            public String patch(gold.debug.windowstolinux.appapi.task.run.TaskContext task, Prepared candidate, String revision, DeploymentAgentPatch patch) {
                throw new AssertionError();
            }

            public String seal(gold.debug.windowstolinux.appapi.task.run.TaskContext task, Prepared candidate, String revision) {
                throw new AssertionError();
            }

            public gold.debug.windowstolinux.appapi.task.result.OperationResult<Published> deliver(gold.debug.windowstolinux.appapi.task.run.TaskContext task, DeploymentAgentPlan plan, java.util.Map<String, Prepared> candidates, java.util.Map<String, String> revisions, java.util.Map<String, String> artifacts, int remaining) {
                throw new AssertionError();
            }

            public void close() {
            }
        };
        assertFalse(unavailable.reviewResponseAvailable());
        assertThrows(IllegalArgumentException.class, () -> unavailable.reviewResponse(null, "a".repeat(64), "bounded"));
    }

    @Test
    void pagingSchemasAndAdmissionUseActualOffsetAndLimitBounds() throws Exception {
        var offsets = java.util.Map.of("evidence_index", 400, "observation", 12000, "plan_state", 1000000, "read", 1000000);
        var limits = java.util.Map.of("evidence_index", 50, "observation", 6000, "plan_state", 6000, "read", 100);
        for (var tool : DeploymentAgentProtocol.catalog()) {
            if (!offsets.containsKey(tool.name())) continue;
            var schema = json.readTree(tool.inputSchema());
            assertEquals(0, schema.at("/properties/offset/minimum").asInt());
            assertEquals(offsets.get(tool.name()).intValue(), schema.at("/properties/offset/maximum").asInt());
            assertEquals(1, schema.at("/properties/limit/minimum").asInt());
            assertEquals(limits.get(tool.name()).intValue(), schema.at("/properties/limit/maximum").asInt());
            var args = json.createObjectNode().put("offset", offsets.get(tool.name())).put("limit", limits.get(tool.name()));
            if (tool.name().equals("observation")) args.put("id", "o1");
            if (tool.name().equals("plan_state")) args.put("component", "api");
            if (tool.name().equals("read")) args.put("path", "native/src/worker.c");
            assertDoesNotThrow(() -> DeploymentAgentProtocol.parse(tool.name(), args));
            for (int offset : java.util.List.of(-1, offsets.get(tool.name()) + 1)) {
                args.put("offset", offset);
                assertEquals("Invalid parameter at /offset: expected integer in [0, " + offsets.get(tool.name()) + "]",
                    assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse(tool.name(), args)).getMessage());
            }
            args.put("offset", 0);
            for (int limit : java.util.List.of(0, limits.get(tool.name()) + 1)) {
                args.put("limit", limit);
                assertEquals("Invalid parameter at /limit: expected integer in [1, " + limits.get(tool.name()) + "]",
                    assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse(tool.name(), args)).getMessage());
            }
        }
    }

    @Test
    void parameterFeedbackUsesOnlyKnownPathsAndBoundsWithoutEchoingInput() throws Exception {
        var args = json.createObjectNode().put("path", "native/src/worker.c").put("offset", 100).put("limit", 102);
        String expected = "Invalid parameter at /limit: expected integer in [1, 100]";
        assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("read", args)).getMessage());
        args.put("limit", "fixture-sensitive-body");
        assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("read", args)).getMessage());
        args.put("limit", 1.5);
        assertEquals(expected, assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("read", args)).getMessage());
        args.remove("limit");
        assertEquals("Invalid parameter at /limit: required parameter",
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("read", args)).getMessage());
        args.put("limit", 100).put("private-arbitrary-field", "fixture-sensitive-body");
        var failure = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("read", args));
        assertEquals("Invalid parameter at /: expected an object with exactly [limit, offset, path]; all fields are required and additional fields are forbidden",
            failure.getMessage());
        assertFalse(failure.getMessage().contains("private-arbitrary-field"));
        assertFalse(failure.getMessage().contains("fixture-sensitive-body"));
        assertTrue(failure.getMessage().length() <= 256);
        args.remove("private-arbitrary-field");
        assertDoesNotThrow(() -> DeploymentAgentProtocol.parse("read", args));
    }

    @Test
    void agentTextSchemasAndAdmissionMatchExistingParserLengthsWithoutChangingSharedDefaults() throws Exception {
        var maxima = java.util.Map.of("component", 63, "path", 500, "query", 256, "question", 2000, "reason", 2000,
            "identifier", 128, "purpose", 128, "beforeDigest", 64, "sourceRevision", 64);
        for (var tool : DeploymentAgentProtocol.catalog()) {
            var schema = json.readTree(tool.inputSchema());
            for (var entry : maxima.entrySet()) {
                if (!schema.path("properties").has(entry.getKey())) continue;
                assertEquals(entry.getValue().intValue(), schema.path("properties").path(entry.getKey()).path("maxLength").asInt(),
                    tool.name() + "/" + entry.getKey());
            }
        }
        var cases = java.util.List.of(
            new Object[]{"read", "path", 500, "{\"path\":\"p\",\"offset\":0,\"limit\":1}"},
            new Object[]{"search", "query", 256, "{\"query\":\"q\",\"offset\":0,\"limit\":1}"},
            new Object[]{"plan_state", "component", 63, "{\"component\":\"api\",\"offset\":0,\"limit\":1}"},
            new Object[]{"observation", "id", 8, "{\"id\":\"o1\",\"offset\":0,\"limit\":1}"},
            new Object[]{"input", "question", 2000, "{\"question\":\"q\"}"},
            new Object[]{"unable", "reason", 2000, "{\"reason\":\"r\"}"});
        for (var sample : cases) {
            String tool = (String) sample[0], field = (String) sample[1];
            int maximum = (int) sample[2];
            var args = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree((String) sample[3]);
            args.put(field, "x".repeat(maximum));
            assertDoesNotThrow(() -> DeploymentAgentProtocol.parse(tool, args));
            args.put(field, "x".repeat(maximum + 1));
            assertEquals("Invalid parameter at /" + field + ": expected string length in [1, " + maximum + "]",
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse(tool, args)).getMessage());
        }
        var shared = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("path", "offset:integer", "limit:integer");
        assertEquals(4000, shared.at("/properties/path/maxLength").asInt());
        assertEquals(1000000, shared.at("/properties/offset/maximum").asInt());
        assertEquals(6000, shared.at("/properties/limit/maximum").asInt());
    }

    @Test
    void patchRevisionLengthRemainsExactAndNeverAdmitsAnIncompleteDigest() throws Exception {
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
            {"component":"api","path":"worker.c","beforeDigest":"","sourceRevision":"",
             "edits":[{"line":1,"removed":[],"added":["replacement"]}],
             "assessment":{"basis":["o1"],"suitability":"Current file","differences":"Reviewed replacement","resolution":"RESOLVED","decision":"Apply exact patch"}}
            """);
        for (String field : java.util.List.of("beforeDigest", "sourceRevision")) args.put(field, "a".repeat(64));
        assertDoesNotThrow(() -> DeploymentAgentProtocol.parse("patch", args));
        for (String field : java.util.List.of("beforeDigest", "sourceRevision")) {
            for (int length : java.util.List.of(63, 65)) {
                args.put(field, "a".repeat(length));
                assertEquals("Invalid parameter at /" + field + ": expected string length in [64, 64]",
                    assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("patch", args)).getMessage());
            }
            args.put(field, "a".repeat(64));
        }
        args.put("beforeDigest", "g".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("patch", args));
    }

    @Test
    void assessmentUnknownFieldsHaveFixedNestedFeedbackWithoutEchoingInput() throws Exception {
        var assessment = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
            {"basis":["o1"],"suitability":"Current evidence","differences":"None","resolution":"CONSISTENT","decision":"Continue"}
            """);
        assessment.put("private-arbitrary-field", "fixture-sensitive-body");
        var failure = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentAssessment.parse(assessment));
        assertTrue(failure.getMessage().contains("/assessment"));
        assertTrue(failure.getMessage().contains("[basis, decision, differences, resolution, suitability]"));
        assertTrue(failure.getMessage().contains("additional fields are forbidden"));
        assertTrue(failure.getMessage().length() <= 256);
        assertFalse(failure.getMessage().contains("private-arbitrary-field"));
        assertFalse(failure.getMessage().contains("fixture-sensitive-body"));
        assessment.remove("private-arbitrary-field");
        assertDoesNotThrow(() -> DeploymentAgentAssessment.parse(assessment));
        assessment.remove("decision");
        assertEquals(failure.getMessage(), assertThrows(IllegalArgumentException.class,
            () -> DeploymentAgentAssessment.parse(assessment)).getMessage());
    }

    @Test
    void agentAssessmentSchemaAndParserShareTheActualSummaryLengthBoundary() throws Exception {
        var shared = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.assessment();
        var catalog = DeploymentAgentProtocol.catalog();
        for (var tool : catalog) {
            if (!DeploymentAgentProtocol.critical(tool.name())) continue;
            var schema = json.readTree(tool.inputSchema()).path("properties").path("assessment");
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            for (String field : java.util.List.of("suitability", "differences", "decision"))
                assertEquals(1500, schema.path("properties").path(field).path("maxLength").asInt(), tool.name() + "/assessment/" + field);
        }
        assertEquals(shared, gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.assessment());
        assertEquals(4000, shared.at("/properties/decision/maxLength").asInt());
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
            {"component":"cli","scope":"REMOTE","assessment":{"basis":["o1"],"suitability":"Current evidence","differences":"None","resolution":"CONSISTENT","decision":"Continue"}}
            """);
        var assessment = (com.fasterxml.jackson.databind.node.ObjectNode) args.path("assessment");
        for (String field : java.util.List.of("suitability", "differences", "decision")) {
            assessment.put(field, "x".repeat(1500));
            assertDoesNotThrow(() -> DeploymentAgentProtocol.parse("database_inspect", args));
            assessment.put(field, "x".repeat(1501));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("database_inspect", args));
            assessment.put(field, "Bounded current conclusion");
        }
    }

    @Test
    void databaseSourceSelectionIsStrictCriticalAndInspectRequiresExplicitScope() throws Exception {
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
            {"component":"cli","sourceSelection":{"declarationRoot":".","databaseIds":["snapshots"],"declarationEvidence":["o1"],"consumptionEvidence":["o2"]},
            "assessment":{"basis":["o1","o2"],"suitability":"Actual source declaration and consumer","differences":"Manifest is above component build root","resolution":"RESOLVED","decision":"Assign snapshots only to cli"}}
            """);
        var parsed = (DeploymentAgentProposal.Assessed) DeploymentAgentProtocol.parse("database_select_source", args);
        assertEquals("database_select_source", ((DeploymentAgentProposal.Capability) parsed.action()).name());
        assertTrue(DeploymentAgentProtocol.critical("database_select_source"));
        assertEquals(gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Execution.EXCLUSIVE, DeploymentAgentProtocol.execution("database_select_source"));
        assertFalse(DeploymentAgentProtocol.available("database_select_source", java.util.Map.of()));
        var state = java.util.Map.<String, Object>of("components", java.util.List.of("cli"), "judgement", java.util.Map.of("pending", java.util.List.of("o3", "o4")));
        var selected = DeploymentAgentProtocol.catalog(state).stream().filter(t -> t.name().equals("database_select_source")).findFirst().orElseThrow();
        var schema = json.readTree(selected.inputSchema());
        assertTrue(schema.at("/properties/assessment/properties/basis/allOf").isMissingNode());
        assertEquals(1, schema.at("/properties/sourceSelection/properties/databaseIds/minItems").asInt());
        assertTrue(schema.at("/properties/sourceSelection/properties/declarationRoot/description").asText().contains("does not change"));
        var inspect = args.deepCopy();
        inspect.remove("sourceSelection");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("database_inspect", inspect));
        inspect.put("scope", "SOURCE");
        assertDoesNotThrow(() -> DeploymentAgentProtocol.parse("database_inspect", inspect));
        inspect.put("scope", "REMOTE");
        assertDoesNotThrow(() -> DeploymentAgentProtocol.parse("database_inspect", inspect));
        inspect.put("scope", "DEFAULT");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("database_inspect", inspect));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("database_select_source", inspect));
        for (String field : java.util.List.of("declarationEvidence", "consumptionEvidence", "databaseIds")) {
            var invalid = args.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("sourceSelection")).putArray(field);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("database_select_source", invalid));
        }
        var invalid = args.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("sourceSelection")).put("declarationRoot", "../other");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("database_select_source", invalid));
        ((com.fasterxml.jackson.databind.node.ObjectNode) args.path("sourceSelection")).put("password", "controlled-sensitive-value");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("database_select_source", args));
    }

    @Test
    void pendingObservationsChangeCurrentFactsWithoutChangingOfferedToolDefinitions() {
        var first = DeploymentAgentProtocol.catalog(java.util.Map.of("components", java.util.List.of("cli"),
            "judgement", java.util.Map.of("pending", java.util.List.of("o3", "o4"))));
        var next = DeploymentAgentProtocol.catalog(java.util.Map.of("components", java.util.List.of("cli"),
            "judgement", java.util.Map.of("pending", java.util.List.of("o99"))));
        var settled = DeploymentAgentProtocol.catalog(java.util.Map.of("components", java.util.List.of("cli"),
            "judgement", java.util.Map.of("pending", java.util.List.of())));
        assertEquals(first, next);
        assertEquals(first, settled);
        assertTrue(first.stream().filter(tool -> DeploymentAgentProtocol.critical(tool.name()))
            .allMatch(tool -> tool.description().contains("ALL current state.judgement.pending")));
        assertTrue(DeploymentAgentProtocol.catalog(java.util.Map.of()).stream()
            .noneMatch(tool -> tool.name().equals("command") || tool.name().equals("plan")));
        assertTrue(first.stream().anyMatch(tool -> tool.name().equals("command")));
        assertFalse(first.stream().anyMatch(tool -> tool.name().equals("plan")));
    }

    @Test
    void fixedSchemasStillRequireAllPendingComponentsAndCurrentEvidenceBeforeEffects() {
        var evidence = new DeploymentAgentEvidence();
        String revision = "a".repeat(64);
        String first = evidence.observe("cli", revision, 2, "COMMAND_RESULT", "", true);
        String second = evidence.observe("worker", revision, 2, "TOOL_analyze", "", true);
        var current = java.util.Map.of("cli", revision, "worker", revision);
        var partial = new DeploymentAgentAssessment(java.util.List.of(first), "Observed result", "No discrepancy",
            DeploymentAgentAssessment.ResolutionType.CONSISTENT, "Continue");
        var complete = new DeploymentAgentAssessment(java.util.List.of(first, second), "Both observed results", "No discrepancy",
            DeploymentAgentAssessment.ResolutionType.CONSISTENT, "Continue");
        assertThrows(IllegalArgumentException.class, () -> evidence.assess("Command", partial, current, revision, 2));
        assertThrows(IllegalArgumentException.class, () -> evidence.assess("Command", complete, current, revision, 3));
        assertThrows(IllegalArgumentException.class, () -> evidence.assess("Command", complete,
            java.util.Map.of("cli", revision, "worker", "b".repeat(64)), revision, 2));
        var unknown = new DeploymentAgentAssessment(java.util.List.of(first, second, "o99"), "Observed results", "No discrepancy",
            DeploymentAgentAssessment.ResolutionType.CONSISTENT, "Continue");
        assertThrows(IllegalArgumentException.class, () -> evidence.assess("Command", unknown, current, revision, 2));
        assertEquals(java.util.List.of(first, second), evidence.state().get("pending"));
        assertDoesNotThrow(() -> evidence.assess("Command", complete, current, revision, 2));
        assertEquals(java.util.List.of(), evidence.state().get("pending"));
    }

    @Test
    void nativeTimeoutBoundsMatchActualHealthAndBuildCommandAdmission() throws Exception {
        var definitions = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        for (var tool : DeploymentAgentProtocol.catalog())
            definitions.put(tool.name(), json.readTree(tool.inputSchema()));
        for (var health : definitions.get("plan").at("/properties/components/items/properties/health/oneOf")) {
            assertEquals(1, health.at("/properties/timeout/minimum").asInt());
            assertEquals(300, health.at("/properties/timeout/maximum").asInt());
            if (health.path("properties").has("stability"))
                assertEquals(300, health.at("/properties/stability/maximum").asInt());
        }
        var factories = java.util.List.<java.util.function.IntFunction<DeploymentHealthCheck>>of(
            timeout -> new DeploymentHealthCheck.Http(java.net.URI.create("http://localhost:8080/health"), 200, timeout),
            timeout -> new DeploymentHealthCheck.Tcp(8080, timeout, 1),
            timeout -> new DeploymentHealthCheck.Process(timeout, 1),
            timeout -> new DeploymentHealthCheck.Command(new DeploymentCommand("run.sh", java.util.List.of()), "OK", timeout));
        for (var factory : factories) {
            assertDoesNotThrow(() -> factory.apply(300));
            assertThrows(IllegalArgumentException.class, () -> factory.apply(301));
        }
        assertThrows(IllegalArgumentException.class, () -> new DeploymentHealthCheck.Process(10, 11));
        assertTrue(definitions.get("plan").at("/properties/components/items/properties/health/oneOf/0/properties/stability/description")
            .asText().contains("must not exceed"));
        for (String name : java.util.List.of("command", "host_command", "build")) {
            int minimum = name.equals("build") ? 60 : 1;
            assertEquals(minimum, definitions.get(name).at("/properties/seconds/minimum").asInt());
            assertEquals(7200, definitions.get(name).at("/properties/seconds/maximum").asInt());
            var args = json.createObjectNode().put("seconds", minimum);
            if (!name.equals("host_command")) args.put("component", "api");
            if (!name.equals("build")) args.put("script", "printf OK");
            if (name.equals("host_command")) args.put("reason", "checked prerequisite");
            args.set("assessment", json.readTree("""
                {"basis":["o1"],"suitability":"current evidence","differences":"none","resolution":"CONSISTENT","decision":"selected action"}
                """));
            assertDoesNotThrow(() -> DeploymentAgentProtocol.parse(name, args));
            args.put("seconds", 7200);
            assertDoesNotThrow(() -> DeploymentAgentProtocol.parse(name, args));
            args.put("seconds", 7201);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse(name, args));
            args.put("seconds", minimum - 1);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse(name, args));
        }
    }

    @Test
    void httpHealthSchemaDescribesRealLoopbackConstructorWithoutRewritingUrls() throws Exception {
        var plan = json.readTree(DeploymentAgentProtocol.catalog().stream().filter(tool -> tool.name().equals("plan"))
            .findFirst().orElseThrow().inputSchema());
        var healths = java.util.List.of(plan.at("/properties/components/items/properties/health/oneOf"),
            plan.at("/properties/applicationHealth/properties/health/oneOf"));
        for (var variants : healths) {
            var http = java.util.stream.StreamSupport.stream(variants.spliterator(), false)
                .filter(variant -> variant.at("/properties/type/enum/0").asText().equals("HTTP")).findFirst().orElseThrow();
            String description = http.at("/properties/url/description").asText();
            assertTrue(description.contains("127.0.0.1 or localhost"));
            assertTrue(description.contains("http/https"));
            assertTrue(description.contains("No user-info or fragment"));
            assertFalse(http.at("/properties/url").has("default"));
        }
        for (String value : java.util.List.of("http://127.0.0.1:8080/health", "https://localhost:8443/health")) {
            var uri = java.net.URI.create(value);
            var health = new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Http(uri, 200, 10);
            assertEquals(uri, health.endpoint());
        }
        for (String value : java.util.List.of("http://service.example:8080/health", "http://0.0.0.0:8080/health",
            "http://user@localhost:8080/health", "http://localhost:8080/health#fragment", "ftp://localhost/health"))
            assertThrows(IllegalArgumentException.class,
                () -> new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Http(java.net.URI.create(value), 200, 10));
    }

    @Test
    void recipeCatalogExplainsActualTypeFieldsWithoutSupplyingRecipeValues() throws Exception {
        var tool = DeploymentAgentProtocol.catalog(java.util.Map.of("components", java.util.List.of("api"))).stream()
            .filter(candidate -> candidate.name().equals("prepare_tools")).findFirst().orElseThrow();
        var recipe = json.readTree(tool.inputSchema()).at("/properties/recipe");
        assertEquals(5, recipe.path("required").size());
        assertFalse(recipe.path("additionalProperties").asBoolean());
        assertEquals(0, recipe.at("/properties/servicePort/minimum").asInt(-1));
        for (String field : java.util.List.of("version", "primary", "secondary", "jvmTarget", "servicePort")) {
            assertFalse(recipe.at("/properties/" + field + "/description").asText().isBlank(), field);
            assertFalse(recipe.at("/properties/" + field).has("default"), field);
        }
        String description = recipe.path("description").asText();
        var kinds = gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.values();
        assertEquals(15, java.util.Arrays.stream(kinds).filter(type -> type.deployable()
            || type == gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.RECOGNITION_PREVIEW).count());
        for (var type : kinds)
            if (type.deployable() || type == gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.RECOGNITION_PREVIEW)
                assertTrue(description.contains(DeploymentAgentProtocol.recipeContract(type)), type.name());
        assertTrue(description.contains("a .rb file with servicePort=0"));
        assertTrue(description.contains("servicePort=0 for CLI"));
        assertTrue(description.contains("jvmTarget=explicit Java version"));
        assertTrue(description.contains("primary=explicit Python minor version"));
        assertTrue(description.contains("empty for no Node build"));
        assertTrue(description.contains("Admission alone does not validate an arbitrary recipe"));
        assertTrue(tool.description().contains("not the publication launcher"));
    }

    @Test
    void analyzeCatalogNamesActualCheckerTypesAndRejectsLanguageOrBuildToolAliases() throws Exception {
        var catalog = DeploymentAgentProtocol.catalog(java.util.Map.of("components", java.util.List.of("api")));
        var analyze = catalog.stream().filter(tool -> tool.name().equals("analyze")).findFirst().orElseThrow();
        var types = json.readTree(analyze.inputSchema()).at("/properties/type/enum");
        var expected = java.util.Set.of("SPRING_BOOT", "JAVA_JAR", "JAVA_SOURCE", "NODE_SERVICE", "PYTHON_SERVICE",
            "STATIC_SITE", "DOCKERFILE_CONTAINER", "GO_SERVICE", "RUST_SERVICE", "DOTNET_SERVICE", "KOTLIN_SERVICE",
            "PHP_SERVICE", "RUBY_SERVICE", "CMAKE_SERVICE", "RECOGNITION_PREVIEW");
        assertEquals(expected, java.util.stream.StreamSupport.stream(types.spliterator(), false).map(com.fasterxml.jackson.databind.JsonNode::asText)
            .collect(java.util.stream.Collectors.toSet()));
        for (String type : expected) {
            var result = assertInstanceOf(DeploymentAgentProposal.Capability.class, DeploymentAgentProtocol.parse("analyze",
                json.readTree("{\"component\":\"api\",\"type\":\"" + type + "\"}")));
            assertEquals(type, result.arguments().path("type").asText());
            assertDoesNotThrow(() -> gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.core.DeploymentTypeCheckerRegistry.defaults()
                .require(gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.valueOf(type)));
        }
        for (String type : java.util.List.of("gradle", "vite", "ruby", "GRADLE", "VITE", "RUBY", "MANAGED_PROCESS"))
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("analyze",
                json.readTree("{\"component\":\"api\",\"type\":\"" + type + "\"}")));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("analyze", json.readTree("{\"component\":\"api\",\"type\":15}")));
        assertTrue(analyze.description().contains("RECOGNITION_PREVIEW"));
        assertTrue(catalog.stream().filter(tool -> tool.name().equals("check")).findFirst().orElseThrow().description().contains("cannot substitute for analyze"));
        assertTrue(catalog.stream().filter(tool -> tool.name().equals("prepare_tools")).findFirst().orElseThrow().description().contains("before helper installation"));
    }

    @Test
    void pathIndexIsBoundedLocalMetadataBeforeIdentification() throws Exception {
        assertInstanceOf(DeploymentAgentProposal.SourceIndex.class, DeploymentAgentProtocol.parse("source_index",
            json.readTree("{\"path\":\".\",\"offset\":0,\"limit\":100}")));
        assertThrows(Exception.class, () -> DeploymentAgentProtocol.parse("source_index",
            json.readTree("{\"path\":\".\",\"offset\":0,\"limit\":101}")));
        assertEquals(gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Execution.LOCAL_READ,
            DeploymentAgentProtocol.execution("source_index"));
        assertTrue(DeploymentAgentProtocol.available("source_index", java.util.Map.of()));
    }

    @Test
    void nativePlanAndRecipeExposeTheStrictNestedContracts() throws Exception {
        var schemas = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        for (var tool : DeploymentAgentProtocol.catalog()) schemas.put(tool.name(), json.readTree(tool.inputSchema()));
        var component = schemas.get("plan").at("/properties/components/items");
        assertEquals(15, component.path("required").size());
        assertFalse(component.path("additionalProperties").asBoolean());
        assertEquals(4, component.at("/properties/health/oneOf").size());
        assertEquals(0, component.at("/properties/workingDirectory/minLength").asInt(-1));
        assertTrue(component.at("/properties/databases/type").toString().contains("null"));
        assertEquals(8, component.at("/properties/resources/items/required").size());
        assertEquals(2, schemas.get("plan").at("/properties/applicationHealth/required").size());
        var recipe = schemas.get("prepare_tools").at("/properties/recipe");
        assertEquals(5, recipe.path("required").size());
        assertEquals(0, recipe.at("/properties/version/minLength").asInt(-1));
        assertEquals(0, recipe.at("/properties/servicePort/minimum").asInt(-1));
        var plan = json.readTree("""
            {"applicationId":"demo","explanation":"Plan from actual READ; database preparation remains unknown",
             "components":[{"id":"api","sourceRoot":"api","dependencies":[],"evidence":["o1"],"backend":"PROCESS",
             "mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
             "health":{"type":"PROCESS","timeout":30,"stability":3},"ports":[],"resources":[],"configuration":[],
             "databases":null,"secretReferences":[]}],"applicationHealth":{"component":"api","health":{"type":"PROCESS","timeout":30,"stability":3}},
             "assessment":{"basis":["o1"],"suitability":"literal artifact launcher","differences":"database capability follows initial plan",
             "resolution":"RESOLVED","decision":"plan current source"}}
            """);
        var admitted = assertInstanceOf(DeploymentAgentProposal.Assessed.class, DeploymentAgentProtocol.parse("plan", plan));
        var graph = assertInstanceOf(DeploymentAgentProposal.Plan.class, admitted.action());
        assertTrue(graph.plan().components().getFirst().databaseIds().isEmpty());
        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.at("/components/0")).put("entrypoint", "/usr/bin/python3");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("plan", plan));
    }

    @Test
    void assessmentBelongsToNativeParametersAndLegacyEnvelopeIsRejected() throws Exception {
        var args = json.readTree("{\"component\":\"api\",\"script\":\"printf result\",\"seconds\":60}");
        assertThrows(Exception.class, () -> DeploymentAgentProtocol.parse("command", args));
        ((com.fasterxml.jackson.databind.node.ObjectNode) args).set("assessment", json.readTree("{\"basis\":[\"o1\"],\"suitability\":\"custom generator\",\"differences\":\"reviewed\",\"resolution\":\"RESOLVED\",\"decision\":\"run scoped command\"}"));
        var result = assertInstanceOf(DeploymentAgentProposal.Assessed.class, DeploymentAgentProtocol.parse("command", args));
        assertInstanceOf(DeploymentAgentProposal.Command.class, result.action());
        assertThrows(Exception.class, () -> DeploymentAgentProtocol.parse("command", json.readTree("{\"tool\":\"command\",\"arguments\":{}}")));
    }

    @Test
    void badTypesMissingParametersAndOutOfRangePagesFailBeforeExecution() throws Exception {
        assertInstanceOf(DeploymentAgentProposal.ReadSource.class, DeploymentAgentProtocol.parse("read", json.readTree("{\"path\":\"app.py\",\"offset\":0,\"limit\":100}")));
        for (String args : java.util.List.of("{\"path\":\"app.py\"}", "{\"path\":\"app.py\",\"offset\":\"0\",\"limit\":10}", "{\"path\":\"app.py\",\"offset\":0,\"limit\":101}"))
            assertThrows(Exception.class, () -> DeploymentAgentProtocol.parse("read", json.readTree(args)));
        assertInstanceOf(DeploymentAgentProposal.EvidenceIndex.class, DeploymentAgentProtocol.parse("evidence_index", json.readTree("{\"offset\":0,\"limit\":50}")));
    }

    @Test
    void registeredAvailabilityAndUnknownCategoriesAreCheckedExplicitly() {
        assertFalse(DeploymentAgentProtocol.available("command", java.util.Map.of()));
        assertTrue(DeploymentAgentProtocol.available("command", java.util.Map.of("components", java.util.List.of("api"))));
        assertFalse(DeploymentAgentProtocol.available("unknown", java.util.Map.of()));
        assertEquals(gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Execution.EXCLUSIVE, DeploymentAgentProtocol.execution("unknown"));
        assertEquals(gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Execution.LOCAL_READ, DeploymentAgentProtocol.execution("read"));
    }

    @Test
    void nativeCatalogDescribesIdentificationAndActualBrowseBounds() throws Exception {
        var definitions = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        for (var tool : DeploymentAgentProtocol.catalog())
            definitions.put(tool.name(), json.readTree(tool.inputSchema()));
        var components = definitions.get("identify").at("/properties/components");
        assertEquals(1, components.path("minItems").asInt());
        assertEquals(16, components.path("maxItems").asInt());
        var finding = components.path("items");
        assertFalse(finding.path("additionalProperties").asBoolean());
        assertEquals(7, finding.path("required").size());
        for (String field : java.util.List.of("technology", "runtime", "resources", "uncertainties")) {
            assertEquals("string", finding.at("/properties/" + field + "/type").asText());
            assertEquals(1, finding.at("/properties/" + field + "/minLength").asInt());
            assertEquals(1000, finding.at("/properties/" + field + "/maxLength").asInt());
        }
        assertEquals("string", finding.at("/properties/evidence/items/type").asText());
        for (String tool : java.util.List.of("source", "list", "read", "search", "remote_read"))
            assertEquals(100, definitions.get(tool).at("/properties/limit/maximum").asInt());
        assertEquals(50, definitions.get("evidence_index").at("/properties/limit/maximum").asInt());
        var valid = json.readTree("{\"components\":[{\"component\":\"api\",\"root\":\"backend\",\"technology\":\"Python\",\"runtime\":\"verified interpreter\",\"resources\":\"no persistent storage observed\",\"uncertainties\":\"runtime version still to inspect\",\"evidence\":[\"o1\"]}]}");
        assertInstanceOf(DeploymentAgentProposal.Identify.class, DeploymentAgentProtocol.parse("identify", valid));
        ((com.fasterxml.jackson.databind.node.ObjectNode) valid.at("/components/0")).putArray("resources");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("identify", valid));
    }
}
