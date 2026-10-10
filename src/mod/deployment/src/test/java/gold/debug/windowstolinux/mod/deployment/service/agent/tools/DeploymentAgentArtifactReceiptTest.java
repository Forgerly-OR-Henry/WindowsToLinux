package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;

/** A structural artifact rejection cannot consume arbitrary command or source failures. / 结构性制品拒绝不能接管任意命令或来源失败。 */
public class DeploymentAgentArtifactReceiptTest {
    private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String CANDIDATE = "demo-api-" + "c".repeat(16), SOURCE = "a".repeat(64);

    @Test
    void receiptRequiresCompleteExclusiveIdentityAndOriginalTransportOutcome() throws Exception {
        String frame = frame(TASK, CANDIDATE, SOURCE);
        var valid = result(TaskResult.FAILED, 65, frame, "", false, "SSH_COMMAND_NONZERO", complete());
        var rejected = DeploymentAgentArtifactRejectedException.receipt(valid, TASK, CANDIDATE, SOURCE).orElseThrow();
        assertTrue(rejected.matches(TASK, CANDIDATE, SOURCE));
        var feedback = new ObjectMapper().readTree(rejected.feedback());
        assertEquals("HARD_LINK", feedback.path("code").asText());
        assertEquals("target/release/app", feedback.path("artifactRelativePath").asText());
        assertFalse(feedback.path("sealed").asBoolean());
        assertTrue(feedback.path("outputFrozen").asBoolean());
        for (var invalid : List.of(
            result(TaskResult.UNKNOWN, 65, frame, "", false, "SSH_COMMAND_NONZERO", complete()),
            result(TaskResult.CANCELLED, 65, frame, "", false, "SSH_COMMAND_NONZERO", complete()),
            result(TaskResult.SUCCEEDED, 65, frame, "", false, "SSH_COMMAND_NONZERO", complete()),
            result(TaskResult.FAILED, 64, frame, "", false, "SSH_COMMAND_NONZERO", complete()),
            result(TaskResult.FAILED, 65, frame, "warning", false, "SSH_COMMAND_NONZERO", complete()),
            result(TaskResult.FAILED, 65, frame, "", true, "SSH_COMMAND_NONZERO", complete()),
            result(TaskResult.FAILED, 65, frame, "", false, "OTHER", complete()),
            result(TaskResult.FAILED, 65, frame, "", false, "SSH_COMMAND_NONZERO", Map.of()),
            result(TaskResult.FAILED, 65, frame, "", false, "SSH_COMMAND_NONZERO", Map.of("inputState", "SENDING", "channelClosed", "true", "exitObserved", "true")),
            result(TaskResult.FAILED, 65, frame, "", false, "SSH_COMMAND_NONZERO", Map.of("inputState", "COMPLETED", "channelClosed", "false", "exitObserved", "true")),
            result(TaskResult.FAILED, 65, frame, "", false, "SSH_COMMAND_NONZERO", Map.of("inputState", "COMPLETED", "channelClosed", "true", "exitObserved", "false")))) {
            assertTrue(DeploymentAgentArtifactRejectedException.receipt(invalid, TASK, CANDIDATE, SOURCE).isEmpty());
        }
        for (String text : List.of("SOURCE_REJECT=invalid-or-conflicting-source\n", "noise\n" + frame, frame + frame,
            frame.stripTrailing(), frame.replace("HARD_LINK", "IO_FAILURE"), frame.replace("target/release/app", "../outside"),
            frame.replace("target/release/app", "/var/lib/other"), frame.replace("target/release/app", "bad\\npath"),
            frame.replace("{\"task\":", "{\"extra\":true,\"task\":"), frame.replace("{\"task\":", "{\"code\":\"EMPTY_TREE\",\"task\":"),
            frame.replace("\"code\":\"HARD_LINK\"", "\"code\":null"), frame.replace("}\n", "} {}\n"))) {
            assertTrue(DeploymentAgentArtifactRejectedException.receipt(result(TaskResult.FAILED, 65, text, "", false,
                "SSH_COMMAND_NONZERO", complete()), TASK, CANDIDATE, SOURCE).isEmpty(), text);
        }
        assertTrue(DeploymentAgentArtifactRejectedException.receipt(valid, UUID.randomUUID(), CANDIDATE, SOURCE).isEmpty());
        assertTrue(DeploymentAgentArtifactRejectedException.receipt(valid, TASK, "d".repeat(64), SOURCE).isEmpty());
        assertTrue(DeploymentAgentArtifactRejectedException.receipt(valid, TASK, CANDIDATE, "e".repeat(64)).isEmpty());
        assertTrue(DeploymentAgentArtifactRejectedException.receipt(new OperationResult<>(TaskResult.FAILED,
            Optional.of(new CommandResult(Optional.empty(), frame, "", false)), valid.problem()), TASK, CANDIDATE, SOURCE).isEmpty());
    }

    /** Provides a verified controlled receipt to the real Session test, without exposing a production constructor. */
    public static DeploymentAgentArtifactRejectedException controlledRejection(UUID task, String candidate, String source) {
        return DeploymentAgentArtifactRejectedException.receipt(result(TaskResult.FAILED, 65, frame(task, candidate, source), "", false,
            "SSH_COMMAND_NONZERO", complete()), task, candidate, source).orElseThrow();
    }

    private static String frame(UUID task, String candidate, String source) {
        return "ARTIFACT_REJECT={\"task\":\"" + task + "\",\"candidate\":\"" + candidate + "\",\"sourceRevision\":\"" + source
            + "\",\"code\":\"HARD_LINK\",\"path\":\"target/release/app\"}\n";
    }

    private static Map<String, String> complete() {
        return Map.of("inputState", "COMPLETED", "channelClosed", "true", "exitObserved", "true");
    }

    private static OperationResult<CommandResult> result(TaskResult result, int exit, String stdout, String stderr, boolean truncated,
                                                         String event, Map<String, String> context) {
        return new OperationResult<>(result, Optional.of(new CommandResult(Optional.of(exit), stdout, stderr, truncated)),
            result == TaskResult.SUCCEEDED ? Optional.empty() : Optional.of(new Problem(ProblemCode.IO_FAILURE, event, "Controlled helper result", context, Optional.empty())));
    }
}
