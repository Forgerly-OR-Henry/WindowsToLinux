package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;

/** Bounded decision summary, never execution authority or private reasoning. / 有界决策摘要，不是执行授权或私有推理。
 * @param basis actual observation IDs / 真实观察标识
 * @param suitability tool applicability / 工具适用性
 * @param differences evaluated discrepancies / 已评估差异
 * @param resolution discrepancy state / 差异状态
 * @param decision selected next action / 选定下一步 */
public record DeploymentAgentAssessment(List<String> basis, String suitability, String differences,
                                        ResolutionType resolution, String decision) {
    /** Discrepancies must be resolved before effects. / 有副作用前差异须解决。 */
    public enum ResolutionType {
        /** Findings agree. / 发现一致。 */
        CONSISTENT,
        /** Differences were resolved. / 差异已解决。 */
        RESOLVED,
        /** Differences require investigation. / 差异须查证。 */
        UNRESOLVED
    }

    /** Copies bounded public summaries. / 复制有界公开摘要。
     * @param basis Actual observation IDs / 实际观察标识
     * @param suitability Tool applicability summary / 工具适用性摘要
     * @param differences Observed discrepancies and handling / 已观察差异及处理
     * @param resolution Current discrepancy status / 当前差异状态
     * @param decision Selected next action / 选定下一动作
     */
    public DeploymentAgentAssessment {
        basis = List.copyOf(basis);
        Objects.requireNonNull(resolution);
        if (basis.isEmpty() || basis.size() > 32 || new HashSet<>(basis).size() != basis.size())
            throw new IllegalArgumentException("Actual decision evidence required");
        for (String value : List.of(suitability, differences, decision))
            if (value == null || value.isBlank() || value.length() > 1500)
                throw new IllegalArgumentException("Bounded decision summary required");
    }

    /** Decodes exact fields without assigning permissions. / 解码精确字段而不赋予权限。
     * @param node model summary / 模型摘要
     * @return validated value / 已校验值
     * @throws java.io.IOException invalid shape / 无效形状 */
    public static DeploymentAgentAssessment parse(JsonNode node) throws java.io.IOException {
        try {
            DeploymentJsonFields.exact(node, Set.of("basis", "suitability", "differences", "resolution", "decision"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid parameter at /assessment: expected an object with exactly "
                + "[basis, decision, differences, resolution, suitability]; all fields are required and additional fields are forbidden");
        }
        var ids = new ArrayList<String>();
        var array = node.get("basis");
        if (array == null || !array.isArray() || array.size() > 32)
            throw new IllegalArgumentException("Decision evidence array required");
        for (var id : array) {
            if (!id.isTextual() || !id.textValue().matches("o[1-9][0-9]{0,5}"))
                throw new IllegalArgumentException("Invalid observation ID");
            ids.add(id.textValue());
        }
        return new DeploymentAgentAssessment(ids, DeploymentJsonFields.text(node, "suitability", 1500),
            DeploymentJsonFields.text(node, "differences", 1500),
            ResolutionType.valueOf(DeploymentJsonFields.text(node, "resolution", 16)),
            DeploymentJsonFields.text(node, "decision", 1500));
    }
}
