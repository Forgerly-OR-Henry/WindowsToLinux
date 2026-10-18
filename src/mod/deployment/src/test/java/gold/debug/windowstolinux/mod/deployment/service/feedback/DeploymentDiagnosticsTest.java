package gold.debug.windowstolinux.mod.deployment.service.feedback;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;

class DeploymentDiagnosticsTest {
    @Test
    void diagnosticsKeepActualIdentityAndValidationMetadataButExcludeBodiesAndCredentials() throws Exception {
        var saved = new ArrayList<LogEvent>();
        UUID taskId = UUID.randomUUID();
        var logs = (LogService) Proxy.newProxyInstance(LogService.class.getClassLoader(), new Class<?>[]{LogService.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "register" -> new LogTarget(UUID.randomUUID());
                case "write" -> {
                    saved.add((LogEvent) args[1]);
                    yield null;
                }
                default -> throw new AssertionError("Unexpected logging capability");
            });
        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                assertEquals("logs", method.getName());
                return logs;
            });
        var task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> {
                assertEquals("id", method.getName());
                return taskId;
            });
        var call = new AiToolCall("original_call", "plan", "{\"script\":\"argument fixture must not be logged\"}");
        DeploymentDiagnostics.agentToolRejected(context, task, call, new AiToolResult(call.id(),
            "TOOL_REJECTED: literal relative path required\napi_key=credential-fixture\n" + "x".repeat(900), true));
        DeploymentDiagnostics.agentToolRejected(context, task, call, new AiToolResult(call.id(), "accepted", false));
        DeploymentDiagnostics.agentToolRejected(context, task, call, new AiToolResult("foreign_call", "rejected", true));
        DeploymentDiagnostics.agentStopped(context, task, "NO_PROGRESS");
        DeploymentDiagnostics.agentStopped(context, task, "arbitrary untrusted text");
        DeploymentDiagnostics.agentFailure(context, task,
            new IllegalStateException("bounded context exceeded\npassword=credential-fixture"));
        DeploymentDiagnostics.agentFailure(context, task,
            new gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson.CapabilityOutcomeException(
                gold.debug.windowstolinux.appapi.task.result.TaskResult.FAILED,
                Optional.of(new gold.debug.windowstolinux.appapi.task.result.Problem(
                    gold.debug.windowstolinux.appapi.task.result.ProblemCode.IO_FAILURE,
                    "FIXTURE_RECEIPT_FAILURE", "not logged", Map.of("password", "credential-fixture"), Optional.empty()))));
        DeploymentDiagnostics.agentFailure(context, task,
            new gold.debug.windowstolinux.appapi.task.result.ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
                gold.debug.windowstolinux.appapi.task.result.ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "fixed review refused",
                Map.of("reviewReason", "ownership is unconfirmed\npassword=credential-fixture", "command", "argument fixture"), Optional.empty())));
        DeploymentDiagnostics.agentFailure(context, task,
            new gold.debug.windowstolinux.appapi.task.result.ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
                gold.debug.windowstolinux.appapi.task.result.ProblemCode.INVALID_INPUT, "DEPLOYMENT_COMMAND_REVIEW_INVALID", "fixed invalid review",
                Map.of("validationStage", "response", "fieldsExact", "false", "decisionForm", "STRING",
                    "reasonForm", "credential-fixture", "reasonCharacters", "2400", "decisionCharacters", "private-body",
                    "reason", "argument fixture", "password", "credential-fixture"), Optional.empty())));
        new DeploymentDiagnostics(context).effectResult(taskId,
            new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.UNKNOWN,
                Optional.empty(), Optional.of(new gold.debug.windowstolinux.appapi.task.result.Problem(
                gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNKNOWN, "FIXTURE_HELPER_UNCONFIRMED", "body excluded",
                Map.of("password", "credential-fixture"), Optional.empty()))));
        assertEquals(8, saved.size());
        var event = saved.getFirst();
        assertEquals(Optional.of(taskId), event.taskId());
        assertEquals(Optional.of(taskId), event.operationId());
        assertTrue(event.message().contains("call=original_call tool=plan"));
        assertTrue(event.message().contains("literal relative path required"));
        assertFalse(event.message().contains("credential-fixture"));
        assertFalse(event.message().contains("argument fixture"));
        assertTrue(event.message().length() < 600);
        assertTrue(event.details().isEmpty());
        assertTrue(event.outcome().isEmpty());
        assertEquals("NO_PROGRESS", saved.get(1).message());
        assertTrue(saved.get(2).message().contains("IllegalStateException: bounded context exceeded"));
        assertTrue(saved.get(3).message().contains("IO_FAILURE/FIXTURE_RECEIPT_FAILURE"));
        assertTrue(saved.get(4).message().contains("ACCESS_DENIED/DEPLOYMENT_COMMAND_REVIEW_DENIED"));
        assertTrue(saved.get(4).message().contains("ownership is unconfirmed"));
        assertEquals("DEPLOYMENT_AGENT_COMMAND_REVIEW_DENIED", saved.get(5).eventCode());
        assertTrue(saved.get(5).message().contains("ownership is unconfirmed"));
        assertTrue(saved.get(6).message().contains("validationStage=response fieldsExact=false decisionForm=STRING reasonCharacters=2400"));
        assertFalse(saved.get(6).message().contains("reasonForm="));
        assertFalse(saved.get(6).message().contains("decisionCharacters="));
        assertEquals("DEPLOYMENT_EFFECT_OUTCOME", saved.getLast().eventCode());
        assertEquals("FIXTURE_HELPER_UNCONFIRMED", saved.getLast().message());
        assertTrue(saved.getLast().outcome().isEmpty(), "UNKNOWN is an observation, not a terminal log outcome");
        assertEquals(Optional.of("{\"observedOutcome\":\"UNKNOWN\"}"), saved.getLast().details());
        var target = new gold.debug.windowstolinux.app.db.log.LogStore.Target(UUID.randomUUID(),
            "gold.debug.windowstolinux.mod.deployment", "v1", UUID.randomUUID(), "m_" + "a".repeat(64) + "_v1",
            "deployment", "deployment_log", "Deployment");
        for (var recorded : saved) {
            assertFalse(recorded.message().contains("argument fixture"));
            assertFalse(recorded.message().contains("credential-fixture"));
            assertFalse(recorded.message().contains("private-body"));
            var persisted = gold.debug.windowstolinux.app.db.log.LogRecordCodec.create(target, "1.0.0", recorded);
            assertEquals(persisted, gold.debug.windowstolinux.app.db.log.LogRecordCodec.decode(target,
                gold.debug.windowstolinux.app.db.log.LogRecordCodec.encode(target.key(), persisted)));
        }
    }

    @Test
    void publicCommandReviewReasonHasItsOwn4096CharacterRedactedWindowWithoutPrivateResponseFields() {
        var saved = new ArrayList<LogEvent>();
        UUID taskId = UUID.randomUUID();
        var logs = (LogService) Proxy.newProxyInstance(LogService.class.getClassLoader(), new Class<?>[]{LogService.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "register" -> new LogTarget(UUID.randomUUID());
                case "write" -> {
                    saved.add((LogEvent) args[1]);
                    yield null;
                }
                default -> throw new AssertionError("Unexpected logging capability");
            });
        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                assertEquals("logs", method.getName());
                return logs;
            });
        var task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> {
                assertEquals("id", method.getName());
                return taskId;
            });
        String review = "independent explanation ".repeat(40) + "\nThe management owner and project execution identity require separate verification.\n"
            + "api_key=public-credential-fixture\npassword: password-fixture\n"
            + "Authorization Bearer bearer-fixture\n-----BEGIN PRIVATE KEY-----\nprivate-key-fixture\n-----END PRIVATE KEY-----\n"
            + "Remaining host installation effects require review.\n" + "x".repeat(6000) + "unretained-tail";
        DeploymentDiagnostics.agentFailure(context, task,
            new gold.debug.windowstolinux.appapi.task.result.ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
                gold.debug.windowstolinux.appapi.task.result.ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED", "fixed review refused",
                Map.of("reviewReason", review, "reasoning", "private-reasoning-fixture", "rawResponse", "raw-response-fixture",
                    "toolBody", "tool-body-fixture"), Optional.empty())));
        assertEquals(2, saved.size());
        assertEquals("DEPLOYMENT_AGENT_FAILURE", saved.getFirst().eventCode());
        assertTrue(saved.getFirst().message().length() < 600);
        var denial = saved.getLast();
        assertEquals("DEPLOYMENT_AGENT_COMMAND_REVIEW_DENIED", denial.eventCode());
        assertEquals(4096, denial.message().length());
        assertTrue(denial.message().contains("management owner and project execution identity"));
        assertTrue(denial.message().contains("Remaining host installation effects"));
        assertTrue(denial.message().contains("[credential content excluded]"));
        assertFalse(denial.message().contains("unretained-tail"));
        assertEquals(Optional.of(taskId), denial.taskId());
        assertEquals(Optional.of(taskId), denial.operationId());
        assertTrue(denial.details().isEmpty());
        assertTrue(denial.outcome().isEmpty());
        for (var event : saved) {
            for (String excluded : List.of("public-credential-fixture", "password-fixture", "bearer-fixture", "private-key-fixture",
                "private-reasoning-fixture", "raw-response-fixture", "tool-body-fixture"))
                assertFalse(event.message().contains(excluded));
            assertFalse(event.message().contains("\n"));
        }
        for (String code : List.of("DEPLOYMENT_COMMAND_REVIEW_DENIED", "DEPLOYMENT_COMMAND_REVIEW_INVALID", "OTHER_FAILURE"))
            DeploymentDiagnostics.agentFailure(context, task,
                new gold.debug.windowstolinux.appapi.task.result.ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
                    gold.debug.windowstolinux.appapi.task.result.ProblemCode.ACCESS_DENIED, code, "fixed failure",
                    code.equals("DEPLOYMENT_COMMAND_REVIEW_DENIED") ? Map.of() : Map.of("reviewReason", "excluded-other-review"), Optional.empty())));
        assertEquals(5, saved.size(), "Only a command denial with a public reason receives the separate window");
        assertEquals(1, saved.stream().filter(event -> event.eventCode().equals("DEPLOYMENT_AGENT_COMMAND_REVIEW_DENIED")).count());
        assertTrue(saved.stream().noneMatch(event -> event.message().contains("excluded-other-review")));
    }
}
