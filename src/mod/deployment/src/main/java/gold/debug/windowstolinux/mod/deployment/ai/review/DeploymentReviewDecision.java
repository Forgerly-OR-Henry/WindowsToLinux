package gold.debug.windowstolinux.mod.deployment.ai.review;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;

/** Shared independent review contract; policy affects human questions only. / 共享独立审核合同；策略仅决定人工问题。
 * @param decision independently reviewed action classification / 独立审核动作分类
 * @param requiresUserConfirmation independent review requirement for explicit human confirmation / 独立审核要求的显式人工确认
 * @param reason bounded evidence-supported review explanation / 有界依据支持的审核说明 */
public record DeploymentReviewDecision(String decision, boolean requiresUserConfirmation, String reason) {
    /** Fixed independent command review response instruction. / 固定独立命令审核响应指令。 */
    public static final String INSTRUCTION = DeploymentSkillResources.text(DeploymentReviewDecision.class, "review-response.md");

    /** The shared safety and human-confirmation semantics, independent of a model's response protocol. */
    public static String safetyInstructions() {
        int start = INSTRUCTION.indexOf("Apply identical safety criteria");
        if (start < 0) throw new IllegalStateException("Shared review safety contract is missing");
        return INSTRUCTION.substring(start);
    }

    /** Validates the independent review response without granting execution authority. / 校验独立审核响应，不授予执行权限。
     * @param node untrusted parsed JSON value / 不可信已解析 JSON 值
     * @return independent checked action review decision / 独立已检查动作审核决定
     * @throws IOException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static DeploymentReviewDecision parse(JsonNode node) throws IOException {
        var fields = new HashSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("decision", "requiresUserConfirmation", "reason"))
            || !node.path("requiresUserConfirmation").isBoolean())
            throw new IOException("Invalid independent review fields");
        try {
            String decision = DeploymentJsonFields.text(node, "decision", 16);
            String reason = DeploymentJsonFields.text(node, "reason", 2000);
            if (!Set.of("APPROVE", "DENY").contains(decision))
                throw new IOException("Invalid independent review decision");
            return new DeploymentReviewDecision(decision, node.path("requiresUserConfirmation").booleanValue(), reason);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid independent review value", invalid);
        }
    }

    /** Identifies response shapes eligible for one existing format correction. / 标识符合一次既有格式纠正条件的响应结构。
     * @param node untrusted parsed JSON value / 不可信已解析 JSON 值
     * @return whether one known-format correction is permitted / 是否允许一次已知格式纠正 */
    public static boolean malformed(JsonNode node) {
        if (Set.of("DENY", "UNKNOWN").contains(node.path("decision").asText())) return false;
        try {
            DeploymentReviewDecision.parse(node);
            return false;
        } catch (IOException invalid) {
            return true;
        }
    }
}
