package gold.debug.windowstolinux.mod.deployment.ai.json;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;

/** Pure strict JSON field validation shared by protocols. / 协议共用的纯JSON字段校验。 */
public final class DeploymentJsonFields {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentJsonFields() {
    }

    /** Reads a required bounded nonempty JSON text field. / 读取必需的有界非空 JSON 文本字段。
     * @param node untrusted parsed JSON value / 不可信已解析 JSON 值
     * @param key exact stored document field / 精确存储文档字段
     * @param limit original limit bound to this operation / 当前操作绑定的原limit值
     * @return required bounded field text / 必需有界字段文本 */
    public static String text(JsonNode node, String key, int limit) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > limit
            || value.textValue().indexOf('\0') >= 0)
            throw new IllegalArgumentException("Expected nonempty text for " + key + " (max " + limit + " chars, no NUL)");
        return value.textValue();
    }

    /** Rejects JSON objects whose field set differs from the expected contract. / 拒绝字段集合不同于预期合同的 JSON 对象。
     * @param node untrusted parsed JSON value / 不可信已解析 JSON 值
     * @param keys complete permitted JSON key set / 完整允许 JSON 键集合 */
    public static void exact(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Agent object required");
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(keys)) {
            var missing = new TreeSet<>(keys);
            missing.removeAll(actual);
            actual.removeAll(keys);
            throw new IllegalArgumentException("Expected fields " + new TreeSet<>(keys) + "; missing " + missing
                + "; unexpected count " + actual.size());
        }
    }

    /** Reads an integral JSON field within the permitted interval. / 读取允许区间内的整型 JSON 字段。
     * @param node untrusted parsed JSON value / 不可信已解析 JSON 值
     * @param key exact stored document field / 精确存储文档字段
     * @param minimum inclusive permitted lower bound / 允许闭区间下限
     * @param maximum inclusive permitted upper bound / 允许闭区间上限
     * @return integral field value in the permitted interval / 允许区间内整数字段值 */
    public static int number(JsonNode node, String key, int minimum, int maximum) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum
            || value.intValue() > maximum)
            throw new IllegalArgumentException("Expected integer for " + key + " in [" + minimum + ", " + maximum + "]");
        return value.intValue();
    }
}
