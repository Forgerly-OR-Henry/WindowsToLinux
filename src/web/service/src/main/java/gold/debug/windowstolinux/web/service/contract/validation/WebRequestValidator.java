package gold.debug.windowstolinux.web.service.contract.validation;

import tools.jackson.databind.JsonNode;
import java.util.Set;

/**
 * Validates bounded request fields before Web operations consume them. / 在 Web 操作使用字段前校验其封闭输入边界。
 */
public final class WebRequestValidator {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private WebRequestValidator() { }
    /**
     * Rejects a request containing fields outside the explicit allowlist.
     * <p>拒绝包含显式白名单之外字段的请求。
     *
     * @param body JSON request object whose property names are checked / 待检查属性名称的 JSON 请求对象
     * @param permitted unique allowed property names; this does not require their presence / 不重复的允许属性名，不要求这些属性全部出现
     * @throws IllegalArgumentException if the body is not an object, contains an unknown field, or the allowlist contains duplicates / 正文不是对象、含未知字段，或白名单含重复项时
     */
    public static void fields(JsonNode body, String... permitted) {
        if (!body.isObject()) throw new IllegalArgumentException("Expected a JSON object");
        Set<String> allowed = Set.of(permitted);
        body.propertyNames().forEach(name -> { if (!allowed.contains(name)) throw new IllegalArgumentException("Unexpected request field"); });
    }
    /**
     * Requires bounded textual request content and returns its validated text.
     * <p>要求有界的文本请求内容并返回已校验文本。
     *
     * @param node JSON object containing the requested field / 包含目标字段的 JSON 对象
     * @param key required field name / 必需字段的名称
     * @param limit maximum string length in UTF-16 code units / 字符串长度上限，以 UTF-16 代码单元计
     * @return the original untrimmed text after validation / 校验通过的原始文本，保留首尾空白
     * @throws IllegalArgumentException if the field is absent, non-textual, blank, too long, or contains a character below U+0020 / 字段缺失、不是文本、全为空白、过长，或含低于 U+0020 的字符时
     */
    public static String text(JsonNode node, String key, int limit) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > limit
                || value.textValue().chars().anyMatch(ch -> ch < 32)) throw new IllegalArgumentException("Invalid " + key);
        return value.textValue();
    }
    /**
     * Validates configuration entries and versioned secret references in recognized task fields without changing their values. Other fields are handled by their own request contracts.
     * <p>校验已识别任务字段中的配置项及带版本秘密引用，不修改其值。其他字段由各自请求契约处理。
     *
     * @param values task input properties, including scoped names ending in {@code /configuration} or {@code /secrets} / 任务输入属性，包含以 {@code /configuration} 或 {@code /secrets} 结尾的作用域名称
     * @throws IllegalArgumentException if a recognized configuration entry or secret reference violates its parser contract / 已识别配置项或秘密引用违反对应解析契约时
     */
    public static void validateNonSecretInputs(JsonNode values) {
        for(var field:values.properties()) {
            String id=field.getKey(),value=field.getValue().asText();
            if(id.equals("configuration") || id.endsWith("/configuration")) gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser.parse(value);
            if(id.equals("secrets") || id.endsWith("/secrets")) gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser.secrets(value);
        }
    }
}
