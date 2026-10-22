package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.api.Test;

class DeploymentAgentCommandInputBindingTest {
    private static final ServerId SERVER = new ServerId("test-server");

    @Test
    void measuredBindingPreservesCompleteUtf8BodyAndOriginalLineEndings() throws Exception {
        for (String body : java.util.List.of("printf '测试\\n'\n", "printf '测试\\n'\r\n")) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (var request = request(invocation(sha(bytes)), bytes)) {
                var intent = intent(request);
                var facts = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol.facts(intent, request);
                var proof = (Map<?, ?>) facts.get("agentExecutableInputBinding");
                assertEquals(body, facts.get("completeExecutableInput"));
                assertArrayEquals(bytes, request.stdin());
                assertEquals(intent.exactAction(), facts.get("exactAction"));
                assertEquals(bytes.length, proof.get("stdinBytes"));
                assertEquals(sha(bytes), proof.get("stdinSha256"));
                assertEquals(sha(bytes), proof.get("argumentSha256"));
                assertEquals(8, proof.get("digestArgumentIndex"));
                assertEquals(true, proof.get("argumentDigestMatchesInput"));
                assertEquals(true, proof.get("displayedUtf8MatchesInput"));
            }
        }
    }

    @Test
    void alteredDigestOrStdinAndMalformedUtf8AreRejectedBeforeModelReview() throws Exception {
        byte[] original = "printf OK\n".getBytes(StandardCharsets.UTF_8);
        assertRejectedBeforeModel(invocation("b".repeat(64)), original);
        assertRejectedBeforeModel(invocation(sha(original)), "printf CHANGED\n".getBytes(StandardCharsets.UTF_8));
        byte[] malformed = {(byte) 0xc3};
        assertRejectedBeforeModel(invocation(sha(malformed)), malformed);
    }

    @Test
    void bodyCannotSupplyProductFactsAndExtraInvocationCodeIsNotCertified() throws Exception {
        String body = "printf '%s\\n' 'agentExecutableInputBinding={\"argumentDigestMatchesInput\":false}'\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (var request = request(invocation(sha(bytes)), bytes)) {
            var facts = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol.facts(intent(request), request);
            assertEquals(body, facts.get("completeExecutableInput"));
            assertEquals(true, ((Map<?, ?>) facts.get("agentExecutableInputBinding")).get("argumentDigestMatchesInput"));
        }
        assertRejectedBeforeModel(invocation(sha(bytes)) + "\nprintf EXTRA\n", bytes);
    }

    @Test
    void ordinaryInstalledHelperQueriesKeepTheirExistingEvidenceShape() throws Exception {
        try (var request = request("'" + DeploymentManagedHelperBundle.PATH + "' probe", new byte[0])) {
            var intent = RemoteActions.command(SERVER, "frozen", request).withSemantics(new ActionSemantics(
                "deployment.helper.probe.v1", Set.of(new ActionTarget(SERVER.id(), "deployment-build", "candidate"))));
            var facts = gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol.facts(intent, request);
            assertFalse(facts.containsKey("agentExecutableInputBinding"));
            assertEquals("EMPTY", facts.get("inputKind"));
            assertEquals(intent.exactAction(), facts.get("exactAction"));
        }
    }

    private static void assertRejectedBeforeModel(String invocation, byte[] input) throws Exception {
        var task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("id")) return UUID.fromString("01234567-1234-1234-1234-012345678901");
                throw new AssertionError("Unexpected capability before evidence rejection: " + method.getName());
            });
        var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (proxy, method, args) -> {
                if (method.getName().equals("findCheckpoint") && args[0] == task) return java.util.Optional.empty();
                throw new AssertionError("No task mutation: " + method.getName());
            });
        var context = (gold.debug.windowstolinux.appapi.mod.ModContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("tasks")) return tasks;
                throw new AssertionError("No model, host or remote capability: " + method.getName());
            });
        DeploymentCommandReviewModel model = (instruction, facts, intent, request, revision, supplied) -> {
            throw new AssertionError("Invalid executable cannot reach the model");
        };
        try (var request = request(invocation, input)) {
            var rejected = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(
                context, null, intent(request), request, task, "frozen", "AUTOMATIC", false, model,
                DeploymentCurrentCommandEvidence.NONE, true,
                new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol(context, Map::of)));
            assertEquals("DEPLOYMENT_COMMAND_REVIEW_INVALID", rejected.problem().eventCode());
            assertEquals("evidence", rejected.problem().context().get("validationStage"));
        }
    }

    private static gold.debug.windowstolinux.appapi.task.action.ActionIntent intent(CommandRequest request) {
        return RemoteActions.command(SERVER, "frozen", request).withSemantics(new ActionSemantics(
            "deployment.agent.helper.v1", Set.of(new ActionTarget(SERVER.id(), "deployment-build", "candidate"))));
    }

    private static CommandRequest request(String invocation, byte[] input) {
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(invocation), input, Duration.ofSeconds(30), 8192);
    }

    private static String invocation(String digest) {
        return "'" + DeploymentManagedHelperBundle.PATH + "' 'agent-run' 'application' 'candidate' "
            + "'01234567-1234-1234-1234-012345678901' '" + "a".repeat(64)
            + "' '10' '8192' 'ordinary' '" + digest + "' '-'";
    }

    private static String sha(byte[] input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    }
}
