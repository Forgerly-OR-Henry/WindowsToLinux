package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentReview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAgentFeedbackTest {
    private static final String ALTERNATIVE_PREFIX = "APPROVAL_DENIED: the exact action was not sent. Independent review feedback DATA:\n";

    @Test
    void alternativeRefusalPreservesNonsecretDataAndRedactsOnlyCredentialLeaves() throws Exception {
        var json = new ObjectMapper();
        var task = refusalTask();
        String reason = "getent passwd is an identity observation; independently review any replacement action.";
        var lists = Map.of(
            "missingEvidence", List.of("Confirm the runtime isolation.", "API_TOKEN is only a variable name."),
            "adjustments", List.of("Keep bounded source checks.", "secret=protected-adjustment\nThis separate line is still data."),
            "candidateCommands", List.of("getent passwd", "TOKEN=protected-fixture", "printf bounded-metadata"));
        var context = new HashMap<String, String>();
        lists.forEach((key, values) -> context.put(key, json.valueToTree(values).toString()));
        context.put("task", task.id().toString());
        context.put("reviewReason", reason);
        context.put("reviewBinding", "a".repeat(64));
        context.put("discussionStatus", "OPEN");
        var denied = new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED",
            "Independent action denied", context, Optional.of(task.id())));
        var refusal = DeploymentAgentAlternativeRequiredException.fromReview(task, denied);
        String original = refusal.feedback();
        String safe = DeploymentAgentFeedback.safe("ALTERNATIVE_REQUIRED", ALTERNATIVE_PREFIX + original);
        assertTrue(safe.startsWith(ALTERNATIVE_PREFIX));
        var data = json.readTree(safe.substring(ALTERNATIVE_PREFIX.length()));
        assertEquals("DENY", data.path("outcome").asText());
        assertEquals("NOT_SENT", data.path("dispatch").asText());
        assertEquals("NONE; every replacement needs fresh exact independent review", data.path("authority").asText());
        assertEquals(reason, data.path("reason").asText());
        assertEquals(task.id().toString(), data.path("suggestions").path("task").asText());
        assertEquals("a".repeat(64), data.path("suggestions").path("reviewBinding").asText());
        assertEquals("OPEN", data.path("suggestions").path("discussionStatus").asText());
        for (var entry : lists.entrySet()) {
            var values = json.readTree(data.path("suggestions").path(entry.getKey()).textValue());
            assertEquals(entry.getValue().size(), values.size());
            for (int i = 0; i < values.size(); i++)
                assertEquals(DeploymentAgentReview.denialReason(entry.getValue().get(i)), values.get(i).textValue());
        }
        assertEquals("getent passwd", json.readTree(data.path("suggestions").path("candidateCommands").textValue()).get(0).textValue());
        assertEquals("[credential content excluded]", json.readTree(data.path("suggestions").path("candidateCommands").textValue()).get(1).textValue());
        assertFalse(safe.contains("protected-fixture"));
        assertFalse(safe.contains("protected-adjustment"));
        assertEquals(original, refusal.feedback(), "Projection never rewrites the original refusal or its receipt data");
        assertEquals(safe, DeploymentAgentFeedback.safe("ALTERNATIVE_REQUIRED", safe));
        assertEquals(data, json.readTree(DeploymentAgentFeedback.safe("ALTERNATIVE_REQUIRED", ALTERNATIVE_PREFIX
            + DeploymentAgentAlternativeRequiredException.beforePreparation(task, refusal).feedback()).substring(ALTERNATIVE_PREFIX.length())));
        for (String tool : List.of("READ", "COMMAND_OUTPUT", "ALTERNATIVE_REQUIRED_OTHER")) {
            String raw = DeploymentAgentFeedback.safe(tool, ALTERNATIVE_PREFIX + original);
            assertFalse(raw.contains("protected-fixture"));
            assertFalse(raw.contains("Confirm the runtime isolation."), "A label embedded in arbitrary output cannot bypass the original line filter");
            assertTrue(raw.contains("[credential content excluded]"));
        }
        var minimal = new DeploymentAgentAlternativeRequiredException(task, "A distinct action needs independent review");
        var minimalData = json.readTree(DeploymentAgentFeedback.safe("ALTERNATIVE_REQUIRED", ALTERNATIVE_PREFIX
            + minimal.feedback()).substring(ALTERNATIVE_PREFIX.length()));
        assertEquals(json.valueToTree(Map.of("task", task.id().toString())), minimalData.path("suggestions"));
        var bounded = (ObjectNode) minimalData.deepCopy();
        bounded.put("reason", "r".repeat(512));
        var boundedSuggestions = (ObjectNode) bounded.path("suggestions");
        boundedSuggestions.put("candidateCommands", json.writeValueAsString(List.of("c".repeat(512))));
        boundedSuggestions.put("adjustments", "[]");
        boundedSuggestions.put("discussionStatus", "CLOSED");
        var boundedData = json.readTree(DeploymentAgentFeedback.safe("ALTERNATIVE_REQUIRED", ALTERNATIVE_PREFIX
            + bounded).substring(ALTERNATIVE_PREFIX.length()));
        assertEquals(bounded, boundedData, "The original 512-character leaf bounds remain usable without truncation");
        bounded.put("reason", "password=protected-reason");
        assertEquals("[credential content excluded]", json.readTree(DeploymentAgentFeedback.safe("ALTERNATIVE_REQUIRED",
            ALTERNATIVE_PREFIX + bounded).substring(ALTERNATIVE_PREFIX.length())).path("reason").textValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUTCOME", "DISPATCH", "AUTHORITY", "DUPLICATE_ROOT", "DUPLICATE_SUGGESTIONS", "TRAILING_ROOT",
        "UNKNOWN_ROOT", "MISSING_ROOT", "ROOT_ARRAY", "ROOT_NULL", "MALFORMED", "REASON_TYPE", "REASON_BLANK", "REASON_LONG", "REASON_NUL",
        "SUGGESTIONS_TYPE", "UNKNOWN_SUGGESTION", "TASK_MISSING", "TASK_INVALID", "TASK_NONCANONICAL", "BINDING", "STATUS",
        "LIST_TYPE", "LIST_OBJECT", "LIST_MEMBER", "LIST_BLANK", "LIST_COUNT", "LIST_LONG", "LIST_TRAILING", "LIST_MALFORMED", "LIST_NUL", "PREFIX", "BUDGET"})
    void alternativeRefusalRejectsMalformedOrForgedEnvelopes(String variant) throws Exception {
        var json = new ObjectMapper();
        var root = (ObjectNode) json.readTree(new DeploymentAgentAlternativeRequiredException(refusalTask(), "Independent review denied").feedback());
        var suggestions = (ObjectNode) root.path("suggestions");
        String body = switch (variant) {
            case "OUTCOME" -> root.put("outcome", "APPROVE").toString();
            case "DISPATCH" -> root.put("dispatch", "SENT").toString();
            case "AUTHORITY" -> root.put("authority", "APPROVE").toString();
            case "DUPLICATE_ROOT" -> root.toString().replaceFirst("\\{", "{\"outcome\":\"DENY\",");
            case "DUPLICATE_SUGGESTIONS" ->
                root.toString().replace("\"task\":", "\"task\":\"" + refusalTask().id() + "\",\"task\":");
            case "TRAILING_ROOT" -> root + "{}";
            case "UNKNOWN_ROOT" -> root.put("extra", "secret=protected-extra").toString();
            case "MISSING_ROOT" -> {
                root.remove("dispatch");
                yield root.toString();
            }
            case "ROOT_ARRAY" -> "[]";
            case "ROOT_NULL" -> "null";
            case "MALFORMED" -> "{\"reason\":\"secret=protected-malformed";
            case "REASON_TYPE" -> root.put("reason", true).toString();
            case "REASON_BLANK" -> root.put("reason", " ").toString();
            case "REASON_LONG" -> root.put("reason", "r".repeat(513)).toString();
            case "REASON_NUL" -> root.put("reason", "reason\0hidden").toString();
            case "SUGGESTIONS_TYPE" -> root.put("suggestions", "secret=protected-extra").toString();
            case "UNKNOWN_SUGGESTION" -> {
                suggestions.put("secret", "protected-extra");
                yield root.toString();
            }
            case "TASK_MISSING" -> {
                suggestions.remove("task");
                yield root.toString();
            }
            case "TASK_INVALID" -> {
                suggestions.put("task", "secret=protected-extra");
                yield root.toString();
            }
            case "TASK_NONCANONICAL" -> {
                suggestions.put("task", refusalTask().id().toString().toUpperCase(Locale.ROOT));
                yield root.toString();
            }
            case "BINDING" -> {
                suggestions.put("reviewBinding", "secret=protected-extra");
                yield root.toString();
            }
            case "STATUS" -> {
                suggestions.put("discussionStatus", "APPROVE");
                yield root.toString();
            }
            case "LIST_TYPE" -> {
                suggestions.putArray("candidateCommands").add("printf bounded");
                yield root.toString();
            }
            case "LIST_OBJECT" -> {
                suggestions.put("candidateCommands", "{}");
                yield root.toString();
            }
            case "LIST_MEMBER" -> {
                suggestions.put("candidateCommands", "[1]");
                yield root.toString();
            }
            case "LIST_BLANK" -> {
                suggestions.put("candidateCommands", "[\" \"]");
                yield root.toString();
            }
            case "LIST_COUNT" -> {
                suggestions.put("candidateCommands", json.writeValueAsString(Collections.nCopies(5, "printf bounded")));
                yield root.toString();
            }
            case "LIST_LONG" -> {
                suggestions.put("candidateCommands", json.writeValueAsString(List.of("c".repeat(513))));
                yield root.toString();
            }
            case "LIST_TRAILING" -> {
                suggestions.put("candidateCommands", "[]{}");
                yield root.toString();
            }
            case "LIST_MALFORMED" -> {
                suggestions.put("candidateCommands", "[\"secret=protected-malformed");
                yield root.toString();
            }
            case "LIST_NUL" -> {
                suggestions.put("candidateCommands", json.writeValueAsString(List.of("suggestion\0hidden")));
                yield root.toString();
            }
            case "PREFIX" -> root.toString();
            case "BUDGET" -> root + " ".repeat(6000);
            default -> throw new AssertionError(variant);
        };
        String output = variant.equals("PREFIX") ? "APPROVAL_DENIED: unrecognized framing\n" + body : ALTERNATIVE_PREFIX + body;
        var failure = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentFeedback.safe("ALTERNATIVE_REQUIRED", output));
        assertEquals("Invalid alternative refusal feedback", failure.getMessage());
        assertNull(failure.getCause(), "Malformed JSON must not retain parser input or credential text through a cause chain");
    }

    private static TaskContext refusalTask() {
        return (TaskContext) java.lang.reflect.Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("id")) return UUID.fromString("a943941c-5a35-4a75-9901-9f0a9dc6a3bd");
                throw new AssertionError("Refusal formatting cannot invoke a task capability");
            });
    }

    @Test
    void preservesOnlyExactProductReferenceMetadataAndRedactsUntrustedCredentials() throws Exception {
        String metadata = "{\"references\":[{\"identifier\":\"api-token\",\"revision\":2,\"purpose\":\"deployment-runtime\"}]}";
        assertTrue(DeploymentAgentFeedback.safe("TOOL_secret_select", metadata).contains("api-token"));
        assertFalse(DeploymentAgentFeedback.safe("READ", "password=protected-fixture\nnormal source\n").contains("protected-fixture"));
        assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe("TOOL_secret_create", metadata.replace("\"revision\":2", "\"revision\":2,\"value\":\"protected-fixture\"")));
        assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe("TOOL_secret_select", metadata.replace("deployment-runtime", "other-purpose")));
        assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe("TOOL_secret_select", metadata.replace("\"revision\":2", "\"revision\":0")));
    }

    @Test
    void keepsDatabaseCredentialVariableNamesButRejectsPlaintextFields() throws Exception {
        String metadata = "{\"sourceRevision\":\"" + "a".repeat(64) + "\",\"component\":\"api\",\"declarationRoot\":\".\",\"scope\":\"REMOTE\",\"serverInventory\":\"OBSERVED\",\"sourceCoverage\":\"EXPLICIT_MANIFEST_AND_SUPPORTED_CONVENTIONS_ONLY\",\"status\":\"READY\",\"candidateManifests\":[],\"questions\":[],\"schemaReviewRequired\":false,\"endpointConfirmationRequired\":false,\"databases\":[{\"databaseId\":\"main\",\"engine\":\"POSTGRESQL\",\"environmentPrefix\":\"APP\",\"credentialEnvironmentName\":\"APP_PASSWORD\",\"initializationFiles\":[],\"instanceIds\":[],\"instances\":[],\"requestedVersion\":\"\",\"sqlitePath\":\"\",\"sqlitePathEnvironment\":\"\",\"sqliteSeedFile\":\"\"}]}";
        assertTrue(DeploymentAgentFeedback.safe("TOOL_database_inspect", metadata).contains("APP_PASSWORD"));
        assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe("TOOL_database_select_source", metadata));
        var source = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(metadata);
        source.put("scope", "SOURCE").put("serverInventory", "NOT_OBSERVED").put("status", "SOURCE_INSPECTED");
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.path("databases").get(0)).remove(java.util.List.of("instances", "instanceIds"));
        assertTrue(DeploymentAgentFeedback.safe("TOOL_database_select_source", source.toString()).contains("APP_PASSWORD"));
        assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe("TOOL_database_inspect", source.toString().replace("NOT_OBSERVED", "OBSERVED")));
        assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe("TOOL_database_inspect", metadata.replace("\"instanceIds\":[]", "\"instanceIds\":[],\"password\":\"protected-fixture\"")));
    }

    @Test
    void unresolvedDeclarationQuestionsAreDataAndNeverAcceptCredentialValues() throws Exception {
        String metadata = """
            {"sourceRevision":"%s","component":"cli","declarationRoot":"cli","scope":"SOURCE","serverInventory":"NOT_OBSERVED","sourceCoverage":"EXPLICIT_MANIFEST_AND_SUPPORTED_CONVENTIONS_ONLY","status":"NEEDS_SOURCE_SELECTION",
            "candidateManifests":[{"path":"windowstolinux-db.properties","declarationRoot":".","databaseIds":["snapshots"]}],
            "questions":[{"key":"database.main.engine","value":"","choices":["SQLITE"]}],"databases":[],"schemaReviewRequired":false,"endpointConfirmationRequired":false}
            """.formatted("a".repeat(64));
        for (String tool : java.util.List.of("TOOL_database_inspect", "TOOL_database_select_source")) {
            String safe = DeploymentAgentFeedback.safe(tool, metadata);
            assertTrue(safe.contains("NEEDS_SOURCE_SELECTION"));
            assertTrue(safe.contains("snapshots"));
            assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe(tool, metadata.replace("database.main.engine", "database.main.password")));
            assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe(tool, metadata.replace("\"value\":\"\"", "\"value\":\"\",\"password\":\"protected-fixture\"")));
        }
    }

    @Test
    void sourceObservationRejectsForgedReadyUnknownDuplicateAndCutMetadata() throws Exception {
        String metadata = """
            {"sourceRevision":"%s","component":"cli","declarationRoot":"cli","scope":"SOURCE","serverInventory":"NOT_OBSERVED",
            "sourceCoverage":"EXPLICIT_MANIFEST_AND_SUPPORTED_CONVENTIONS_ONLY","status":"SOURCE_INSPECTED","databases":[],
            "candidateManifests":[],"questions":[],"schemaReviewRequired":false,"endpointConfirmationRequired":false}
            """.formatted("a".repeat(64));
        assertTrue(DeploymentAgentFeedback.safe("TOOL_database_inspect", metadata).contains("NOT_OBSERVED"));
        for (String invalid : java.util.List.of(metadata.replace("SOURCE_INSPECTED", "READY"),
            metadata.replace("NOT_OBSERVED", "OBSERVED"), metadata.replace("\"scope\":\"SOURCE\"", "\"scope\":\"SOURCE\",\"scope\":\"REMOTE\""),
            metadata.replace("\"databases\":[]", "\"databases\":[],\"businessDatabaseAbsent\":true"),
            metadata.substring(0, metadata.lastIndexOf('}')), metadata + "{}"))
            assertThrows(Exception.class, () -> DeploymentAgentFeedback.safe("TOOL_database_inspect", invalid));
    }
}
