package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.io.IOException;
import java.util.List;
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

        private final Map<String, String> counts;

        private final String evidenceBlock;

        public Failure(FailureStage stage, String tool, Throwable cause) {
            this(stage, tool, "REVIEW_EXCEPTION", cause);
        }

        public Failure(FailureStage stage, String tool, String code, Throwable cause) {
            this(stage, tool, code, cause, -1, -1, -1);
        }

        public Failure(FailureStage stage, String tool, String code, Throwable cause, String evidenceBlock) {
            this(stage, tool, code, cause, -1, -1, -1, evidenceBlock);
        }

        public Failure(FailureStage stage, String tool, String code, Throwable cause,
                       int pageCount, int newlyVisibleCharacters, int repeatedBatches) {
            this(stage, tool, code, cause, pageCount, newlyVisibleCharacters, repeatedBatches, "");
        }

        private Failure(FailureStage stage, String tool, String code, Throwable cause,
                       int pageCount, int newlyVisibleCharacters, int repeatedBatches, String evidenceBlock) {
            super("Independent command review failed at a protected boundary", cause);
            this.stage = Objects.requireNonNull(stage);
            this.tool = safeTools().contains(tool == null ? "" : tool) ? tool : "";
            this.code = safeCodes().contains(code == null ? "" : code) ? code : "REVIEW_EXCEPTION";
            var observed = new LinkedHashMap<String, String>();
            retainCount(observed, "reviewPageCount", Integer.toString(pageCount));
            retainCount(observed, "reviewNewCharacters", Integer.toString(newlyVisibleCharacters));
            retainCount(observed, "reviewRepeatedBatches", Integer.toString(repeatedBatches));
            this.counts = Map.copyOf(observed);
            this.evidenceBlock = safeEvidenceBlock(evidenceBlock) ? evidenceBlock : "";
        }

        public boolean evidenceReadMadeNoProgress() {
            return stage == FailureStage.EVIDENCE_READ && tool.equals("evidence") && code.equals("EVIDENCE_NO_PROGRESS");
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
        if (boundary != null) values.putAll(boundary.counts);
        if (boundary != null && !boundary.evidenceBlock.isEmpty()) values.put("reviewEvidenceBlock", boundary.evidenceBlock);
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
        if ("EVIDENCE_READ".equals(stage) && "EVIDENCE_NO_PROGRESS".equals(code)) {
            for (String field : List.of("reviewPageCount", "reviewNewCharacters", "reviewRepeatedBatches"))
                retainCount(safe, field, details.get(field));
        }
        String evidenceBlock = details.get("reviewEvidenceBlock");
        if ("VERDICT".equals(stage) && "EVIDENCE_INCOMPLETE_VERDICT".equals(code) && safeEvidenceBlock(evidenceBlock))
            safe.put("reviewEvidenceBlock", evidenceBlock);
        return Map.copyOf(safe);
    }

    private static boolean safeEvidenceBlock(String value) {
        return value != null && Set.of("READ_REJECTED", "REQUIRED_SOURCE_MISSING", "REQUESTED_SOURCE_MISSING").contains(value);
    }

    private static void retainCount(Map<String, String> safe, String field, String value) {
        if (value != null && value.matches("(?:0|[1-9][0-9]{0,6})") && Integer.parseInt(value) <= 1048576)
            safe.put(field, value);
    }

    /** Canonical projection of paginator failures for the protected operation receipt. */
    static String evidenceFailureMetadata(Map<String, String> details) {
        var safe = safeFailureDiagnostics(details);
        String stage = safe.getOrDefault("reviewStage", "");
        String code = safe.getOrDefault("reviewCode", "");
        if (!(stage.equals("EVIDENCE_READ") && Set.of("EVIDENCE_NO_PROGRESS", "EVIDENCE_TOOL_BUDGET_EXHAUSTED").contains(code))
            && !(stage.equals("VERDICT") && code.equals("EVIDENCE_INCOMPLETE_VERDICT")))
            return "";
        var result = new StringBuilder(" reviewStage=").append(stage).append(" reviewCode=").append(code);
        for (String field : List.of("reviewPageCount", "reviewNewCharacters", "reviewRepeatedBatches", "reviewEvidenceBlock"))
            if (safe.containsKey(field)) result.append(' ').append(field).append('=').append(safe.get(field));
        return result.toString();
    }

    private static Set<String> safeTools() {
        return Set.of("request", "skill", "mirror_sources", "evidence", "receipts", "contract", "source_list", "source_read", "source_search", "submit_result");
    }

    private static Set<String> safeCodes() {
        return Set.of("REVIEW_EXCEPTION", "NATIVE_BATCH_INCOMPLETE", "NATIVE_CALL_ID_REPEATED", "VERDICT_BATCH_MIXED",
            "VERDICT_SCHEMA_INVALID", "TASK_TOOL_BUDGET_EXHAUSTED", "EVIDENCE_NO_PROGRESS",
            "EVIDENCE_TOOL_BUDGET_EXHAUSTED", "EVIDENCE_INCOMPLETE_VERDICT");
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
            DeploymentReviewDecision.parse(ai.askCommand(instructions, facts, revision, task, DeploymentReviewDecision::malformed, intent, request)), Map.of());
    }
}
