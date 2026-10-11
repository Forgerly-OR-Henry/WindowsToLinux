package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Separates real measured progress from request bookkeeping and persuasive deployment text. */
final class DeploymentAgentApprovalProgress {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> FACTS = Set.of("SOURCE", "DERIVATIVE_SOURCE", "SOURCE_REVISION", "PLAN", "PLAN_APPLICATION",
        "IDENTIFIED", "COMMAND_RESULT", "SEAL_RESULT", "SEALED_ARTIFACT", "HELPER_READINESS_OBSERVED", "AI_PUBLICATION_VERIFIED",
        "AI_PUBLICATION_CLEANED", "CAPABILITY_RESULT", "PATCH_RESULT");

    private static final Set<String> MODEL_STATEMENTS = Set.of("ALTERNATIVE_REQUIRED", "PROPOSAL_REJECTED", "PLAN", "IDENTIFY", "HANDOFF");

    private DeploymentAgentApprovalProgress() {
    }

    static JsonNode receipts(JsonNode complete) {
        if (!complete.has("events")) return complete;
        var progress = JSON.createObjectNode();
        var measured = new TreeSet<String>();
        for (var event : complete.path("events")) {
            String type = event.path("type").asText(), action = event.path("action").asText(), detail = event.path("detail").asText();
            boolean observation = type.equals("AGENT_OBSERVATION") && !MODEL_STATEMENTS.contains(action);
            if ((FACTS.contains(type) || observation) && !Set.of("REJECTED", "ALTERNATIVE_REQUIRED").contains(detail))
                measured.add(JSON.valueToTree(List.of(type, action, detail)).toString());
        }
        progress.set("measured", JSON.valueToTree(measured));
        var commands = progress.putArray("endedModelCommands");
        for (var command : complete.path("modelCommandReceipt").path("commands"))
            if (command.path("phase").asText().equals("ENDED")) commands.add(command);
        return progress;
    }

    static JsonNode facts(JsonNode current) {
        if (!current.path("operation").asText().equals("deployment.model.command.v1")) return current;
        var result = (ObjectNode) current.deepCopy();
        result.remove(List.of("exactAction", "completeExecutableInput", "effectSummary"));
        if (result.path("modelCommand").isObject())
            ((ObjectNode) result.get("modelCommand")).remove(List.of("operation", "rationale"));
        return result;
    }
}
