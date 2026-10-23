package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;

/** Explicit source declaration ownership; it grants no database effects. / 明确源码声明归属，不授予数据库效果。
 * @param declarationRoot frozen declaration directory / 冻结声明目录
 * @param databaseIds selected actual database identifiers / 选中的实际数据库标识
 * @param declarationEvidence original declaration reads / 原声明读取依据
 * @param consumptionEvidence original component storage reads / 原组件存储读取依据 */
public record DeploymentAgentDatabaseSourceSelection(String declarationRoot, List<String> databaseIds,
                                                     List<String> declarationEvidence,
                                                     List<String> consumptionEvidence) {
    /** Validates explicit bounded selections without inferring ownership. / 校验明确有界选择，不推断归属。
     * @param declarationRoot frozen declaration directory / 冻结声明目录
     * @param databaseIds selected actual database identifiers / 选中的实际数据库标识
     * @param declarationEvidence original declaration reads / 原声明读取依据
     * @param consumptionEvidence original component storage reads / 原组件存储读取依据
     * @throws IllegalArgumentException invalid root, identity or evidence list / 根、身份或依据列表无效时 */
    public DeploymentAgentDatabaseSourceSelection {
        declarationRoot = DeploymentAgentIdentification.sourceRoot(declarationRoot);
        databaseIds = members(databaseIds, "[a-z][a-z0-9-]{0,31}", 16).stream().sorted().toList();
        declarationEvidence = members(declarationEvidence, "o[1-9][0-9]{0,5}", 32);
        consumptionEvidence = members(consumptionEvidence, "o[1-9][0-9]{0,5}", 32);
    }

    /** Returns the exact snapshot-relative manifest path. / 返回精确快照相对声明路径。
     * @return original declaration member / 原声明成员 */
    public String declarationPath() {
        return declarationRoot.equals(".") ? "windowstolinux-db.properties" : declarationRoot + "/windowstolinux-db.properties";
    }

    /** Parses only the exact model-selected fields. / 只解析模型明确选择的字段。
     * @param node exact native selection arguments / 精确原生选择参数
     * @return validated explicit selection / 已校验的明确选择
     * @throws IllegalArgumentException missing, foreign or invalid fields / 字段缺失、额外或无效时 */
    public static DeploymentAgentDatabaseSourceSelection parse(JsonNode node) {
        DeploymentJsonFields.exact(node, Set.of("declarationRoot", "databaseIds", "declarationEvidence", "consumptionEvidence"));
        return new DeploymentAgentDatabaseSourceSelection(DeploymentJsonFields.text(node, "declarationRoot", 500),
            array(node.get("databaseIds")), array(node.get("declarationEvidence")), array(node.get("consumptionEvidence")));
    }

    private static List<String> array(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > 32)
            throw new IllegalArgumentException("Bounded source-selection list required");
        var values = new ArrayList<String>();
        for (var value : node) {
            if (!value.isTextual()) throw new IllegalArgumentException("Source-selection members must be text");
            values.add(value.textValue());
        }
        return values;
    }

    private static List<String> members(List<String> values, String pattern, int maximum) {
        if (values.isEmpty() || values.size() > maximum || values.stream().anyMatch(v -> v == null || !v.matches(pattern))
            || new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException("Source-selection lists must be nonempty, unique and bounded");
        return List.copyOf(values);
    }
}
