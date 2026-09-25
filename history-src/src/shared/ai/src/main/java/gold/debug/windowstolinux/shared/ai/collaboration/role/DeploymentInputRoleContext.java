package gold.debug.windowstolinux.shared.ai.collaboration.role;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;

/**
 * Bounded deployment questions; no source contents, input values or credential fields are transmitted. / 有界部署问题，不传输源码内容、输入值或凭据字段。
 *
 * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
 * @param question question / 问题
 * @param history history / 历史
 */
public record DeploymentInputRoleContext(List<DeploymentInputField> fields, String question,
        List<String> history) implements AiRoleContext {
    /**
     * Removes values and scrubs user-authored text before any provider or audit receives it. / 在提供方或审计接收前移除值，并清理用户编写的文本。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param question question / 问题
     * @param history history / 历史
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DeploymentInputRoleContext {
        if (fields.size() > 64 || history.size() > 12)
            throw new IllegalArgumentException("assistance context exceeds limits");
        fields = fields.stream().map(
                field -> new DeploymentInputField(field.id(), field.labelKey(), field.helpKey(), "", field.choices()))
                .toList();
        question = redact(question);
        history = history.stream().map(DeploymentInputRoleContext::redact).toList();
    }

    /**
     * Uses the provider explicitly assigned to project analysis. / 使用明确分配给项目分析的提供方。
     *
     * @return constructed or resolved ai collaboration role kind / 构造或解析得到的AICollaboration角色种类
     */
    @Override
    public AiCollaborationRoleKind role() {
        return AiCollaborationRoleKind.PROJECT_ANALYSIS;
    }

    /**
     * Serializes only bounded field identifiers, enumerated candidates and scrubbed conversation text. / 仅序列化有界字段标识、枚举候选和已清理的对话文本。
     *
     * @return redacted summary text / 已脱敏摘要文本
     */
    @Override
    public String redactedSummary() {
        return "fields=" + fields.stream().map(field -> field.id() + " choices=" + field.choices()).toList()
                + "; question=" + question + "; conversation=" + history;
    }

    /**
     * Redacts sensitive content from deployment input role context.
     * <p>脱敏部署输入角色上下文。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return redact text / 脱敏文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String redact(String text) {
        text = Objects.requireNonNull(text);
        if (text.length() > 2048)
            text = text.substring(0, 2048);
        return text
                .replaceAll("(?is)-----BEGIN[^-]*PRIVATE KEY-----.*?(?:-----END[^-]*PRIVATE KEY-----|$)", "[redacted]")
                .replaceAll(
                        "(?i)(password|passwd|secret|token|api[-_ ]?key|\u5bc6\u7801|\u5bc6\u94a5)\\s*[:=\uff1a]\\s*[^\\s,;]+",
                        "$1=[redacted]")
                .replaceAll("(?i)Bearer\\s+\\S+", "Bearer [redacted]").replaceAll("\\bsk-[A-Za-z0-9_-]+", "[redacted]")
                .replaceAll("(?i)(https?://)[^/\\s@]+@", "$1[redacted]@")
                .replaceAll("(?i)[A-Z]:[\\\\/][^\\s]+", "[local-path]");
    }
}
