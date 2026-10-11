package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

/** Model verdict only; exact request validation and host grants remain in the shared approval boundary. */
@FunctionalInterface
public interface DeploymentCommandReviewModel {
    enum FailureStage {
        SHARED_REVIEW, BINDING_SNAPSHOT, HISTORY_CONTEXT, RESPONSE_CHECK, EVIDENCE_READ, HISTORY_RETAIN, VERDICT
    }

    /** A diagnostic boundary, never model data or a replacement authorization result. */
    final class Failure extends IOException {
        private final FailureStage stage;

        private final String tool;

        private final String code;

        public Failure(FailureStage stage, String tool, Throwable cause) {
            this(stage, tool, "REVIEW_EXCEPTION", cause);
        }

        public Failure(FailureStage stage, String tool, String code, Throwable cause) {
            super("Independent command review failed at a protected boundary", cause);
            this.stage = Objects.requireNonNull(stage);
            this.tool = safeTools().contains(tool == null ? "" : tool) ? tool : "";
            this.code = safeCodes().contains(code == null ? "" : code) ? code : "REVIEW_EXCEPTION";
        }
    }

    static Map<String, String> failureDiagnostics(Throwable failure) {
        var values = new LinkedHashMap<String, String>();
        var boundary = failure instanceof Failure value ? value : null;
        values.put("reviewStage", (boundary == null ? FailureStage.SHARED_REVIEW : boundary.stage).name());
        values.put("reviewCode", boundary == null ? "REVIEW_EXCEPTION" : boundary.code);
        Throwable cause = boundary == null ? failure : boundary.getCause();
        String type = cause instanceof java.nio.charset.CharacterCodingException ? "CharacterCodingException"
            : cause instanceof com.fasterxml.jackson.core.JsonProcessingException ? "JsonProcessingException"
            : cause instanceof SecurityException ? "SecurityException"
            : cause instanceof IOException ? "IOException"
            : cause instanceof IllegalArgumentException ? "IllegalArgumentException"
            : cause instanceof IllegalStateException ? "IllegalStateException"
            : cause instanceof NullPointerException ? "NullPointerException"
            : cause instanceof InterruptedException ? "InterruptedException" : "OtherException";
        values.put("reviewFailureType", type);
        if (boundary != null && !boundary.tool.isEmpty()) values.put("reviewTool", boundary.tool);
        return Map.copyOf(values);
    }

    /** Revalidates even caller-supplied exception context before it reaches public diagnostics. */
    static Map<String, String> safeFailureDiagnostics(Map<String, String> details) {
        var safe = new LinkedHashMap<String, String>();
        String stage = details.get("reviewStage");
        if (java.util.Arrays.stream(FailureStage.values()).anyMatch(value -> value.name().equals(stage)))
            safe.put("reviewStage", stage);
        String code = details.get("reviewCode"), tool = details.get("reviewTool"), type = details.get("reviewFailureType");
        if (safeCodes().contains(code == null ? "" : code)) safe.put("reviewCode", code);
        if (safeTools().contains(tool == null ? "" : tool)) safe.put("reviewTool", tool);
        if (Set.of("CharacterCodingException", "JsonProcessingException", "SecurityException", "IOException", "IllegalArgumentException",
            "IllegalStateException", "NullPointerException", "InterruptedException", "OtherException").contains(type == null ? "" : type))
            safe.put("reviewFailureType", type);
        return Map.copyOf(safe);
    }

    private static Set<String> safeTools() {
        return Set.of("request", "skill", "evidence", "receipts", "contract", "source_list", "source_read", "source_search", "submit_result");
    }

    private static Set<String> safeCodes() {
        return Set.of("REVIEW_EXCEPTION", "NATIVE_BATCH_INCOMPLETE", "NATIVE_CALL_ID_REPEATED", "VERDICT_BATCH_MIXED", "VERDICT_SCHEMA_INVALID", "TASK_TOOL_BUDGET_EXHAUSTED");
    }

    default String responseInstructions() {
        return DeploymentReviewDecision.INSTRUCTION;
    }

    Result review(String instructions, Map<String, ?> facts, ActionIntent intent, CommandRequest request,
                  String revision, TaskContext task) throws ApiException, DeploymentAiJson.CapabilityOutcomeException, IOException;

    record Result(DeploymentReviewDecision decision, Map<String, String> refusalData) {
        public Result {
            Objects.requireNonNull(decision);
            if (!java.util.Set.of("APPROVE", "DENY").contains(decision.decision()) || decision.reason() == null
                || decision.reason().isBlank() || decision.reason().length() > 2000)
                throw new IllegalArgumentException("Invalid independent model verdict");
            refusalData = Map.copyOf(refusalData);
            if (!java.util.Set.of("missingEvidence", "adjustments", "candidateCommands", "reviewBinding", "discussionStatus").containsAll(refusalData.keySet())
                || refusalData.values().stream().anyMatch(value -> value == null || value.length() > 4096))
                throw new IllegalArgumentException("Invalid bounded model refusal data");
            if (decision.decision().equals("APPROVE") && !refusalData.isEmpty())
                throw new IllegalArgumentException("Approval cannot authorize suggested replacements");
        }
    }

    static DeploymentCommandReviewModel submission(DeploymentAiJson ai) {
        return (instructions, facts, intent, request, revision, task) -> new Result(
            DeploymentReviewDecision.parse(ai.askCommand(instructions, facts, revision, task, DeploymentReviewDecision::malformed)), Map.of());
    }
}
