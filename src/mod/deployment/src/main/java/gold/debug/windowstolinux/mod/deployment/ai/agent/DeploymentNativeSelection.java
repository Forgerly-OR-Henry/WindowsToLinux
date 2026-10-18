package gold.debug.windowstolinux.mod.deployment.ai.agent;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

/** Bounded native read/submit conversation for independent source comparison. */
public final class DeploymentNativeSelection {
    public static final class RejectedBatchException extends IllegalArgumentException {
        private RejectedBatchException(int maximum) {
            super("Source comparison batch rejected without reading source; maximum " + maximum + " calls and only currently offered tools");
        }
    }

    private final DeploymentAiJson ai;

    private final DeploymentNativeHistory history = new DeploymentNativeHistory();

    private AiResponse pending;

    private String input;

    private final List<AiToolResult> results = new ArrayList<>();

    private int cursor;

    public DeploymentNativeSelection(ModContext context) {
        ai = new DeploymentAiJson(context);
    }

    public AiToolCall select(String policy, Map<String, ?> facts, String revision, TaskContext task, boolean calibration) throws Exception {
        if (pending != null && cursor > results.size()) {
            String feedback = DeploymentAiJson.facts(facts, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().history().characters());
            results.add(new AiToolResult(pending.toolCalls().get(cursor - 1).id(),
                "CURRENT_VERIFIED_STATE\n" + feedback.substring(0, Math.min(6000, feedback.length())), false));
        }
        if (pending != null && cursor < pending.toolCalls().size()) return pending.toolCalls().get(cursor++);
        if (pending != null) {
            history.retain(input, pending, results);
            results.clear();
        }
        int remaining = facts.get("remaining") instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
        int maximum = Math.min(4, Math.max(1, remaining - 1));
        var tools = new ArrayList<AiToolDefinition>();
        for (String name : List.of("list", "read", "search")) {
            var schema = DeploymentToolSchemas.object(name.equals("search") ? "query" : "path", "offset:integer", "limit:integer");
            ((com.fasterxml.jackson.databind.node.ObjectNode) schema.at("/properties/offset")).put("minimum", 0).put("maximum", 1000000);
            ((com.fasterxml.jackson.databind.node.ObjectNode) schema.at("/properties/limit")).put("minimum", 1).put("maximum", 100);
            tools.add(new AiToolDefinition(name, "Read bounded frozen source evidence",
                schema.toString()));
        }
        var submission = calibration ? DeploymentToolSchemas.object("decision", "reason", "evidence:array")
            : DeploymentToolSchemas.object("components:array");
        var evidenceOwner = submission;
        if (calibration) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) submission.at("/properties/decision")).putArray("enum")
                .add("STATIC").add("AI").add("UNRESOLVED");
            ((com.fasterxml.jackson.databind.node.ObjectNode) submission.at("/properties/reason")).put("maxLength", 2000);
        } else {
            evidenceOwner = DeploymentToolSchemas.object("id", "root", "type", "evidence:array");
            ((com.fasterxml.jackson.databind.node.ObjectNode) evidenceOwner.at("/properties/id"))
                .put("maxLength", 63).put("pattern", "[a-z0-9][a-z0-9-]{0,62}");
            ((com.fasterxml.jackson.databind.node.ObjectNode) evidenceOwner.at("/properties/root")).put("maxLength", 500);
            if (facts.get("projectTypes") instanceof Collection<?> types && !types.isEmpty()) {
                var allowed = ((com.fasterxml.jackson.databind.node.ObjectNode) evidenceOwner.at("/properties/type")).putArray("enum");
                types.forEach(type -> allowed.add(type.toString()));
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) submission.at("/properties/components"))
                .put("minItems", 1).put("maxItems", 16).set("items", evidenceOwner);
        }
        var evidence = (com.fasterxml.jackson.databind.node.ObjectNode) evidenceOwner.at("/properties/evidence");
        evidence.put("minItems", calibration ? 0 : 1).put("maxItems", 8);
        var cite = evidence.putObject("items").put("type", "string");
        if (facts.get("readPaths") instanceof Collection<?> paths && !paths.isEmpty()) {
            var allowed = cite.putArray("enum");
            paths.forEach(path -> allowed.add(path.toString()));
        }
        tools.add(new AiToolDefinition(calibration ? "conclude" : "identify", "Submit independent source findings using only readPaths as evidence; business validators decide acceptance",
            submission.toString()));
        if (!calibration)
            tools.add(new AiToolDefinition("unable", "Report inability to identify", DeploymentToolSchemas.object("reason").toString()));
        input = DeploymentAiJson.facts(facts, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().history().characters());
        pending = ai.call(AiPurpose.LANGUAGE, history.messages(policy + "\nReturn at most four native calls per response, and fewer than remaining when remaining > 1. remaining counts individual tools, including queued calls. Reserve the final turn for identify/unable or conclude. Rejected batches read no source and consume an original turn.", input), tools, revision, task, false);
        cursor = 0;
        if (!pending.finishReason().equals("tool_calls") || pending.toolCalls().isEmpty())
            throw new IllegalArgumentException("Source comparison requires one to four complete native calls");
        if (pending.toolCalls().size() > maximum || pending.toolCalls().stream()
            .anyMatch(call -> tools.stream().noneMatch(tool -> tool.name().equals(call.name())))) {
            history.retain(input, pending, pending.toolCalls().stream().map(call -> new AiToolResult(call.id(),
                "BATCH_REJECTED: maximum " + maximum + " calls using only currently offered tools; no source was read. Resubmit within the remaining original turns.", true)).toList());
            pending = null;
            throw new RejectedBatchException(maximum);
        }
        return pending.toolCalls().get(cursor++);
    }
}
