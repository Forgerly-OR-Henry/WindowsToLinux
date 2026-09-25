package gold.debug.windowstolinux.shared.ai.execution.protocol;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;

/** Strict JSON shape checks. / 严格 JSON 结构检查。 */
public final class StrictJson {
    /** Prevents construction. / 禁止实例化。 */
    private StrictJson() {
    }

    /** Requires exactly the documented fields. / 要求字段与文档完全一致。
     * @param node parsed object / 已解析对象
     * @param fields exact field names / 精确字段名
     */
    public static void exact(JsonNode node, Set<String> fields) {
        var found = new HashSet<String>();
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("agent object required");
        node.fieldNames().forEachRemaining(found::add);
        if (!found.equals(fields))
            throw new IllegalArgumentException("agent fields do not match schema");
    }

    /** Reads a required bounded string. / 读取必需有界字符串。
     * @param node containing object / 包含对象
     * @param field field name / 字段名
     * @return exact string / 精确字符串
     */
    public static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > 2048)
            throw new IllegalArgumentException("invalid agent string");
        return value.textValue();
    }
}
