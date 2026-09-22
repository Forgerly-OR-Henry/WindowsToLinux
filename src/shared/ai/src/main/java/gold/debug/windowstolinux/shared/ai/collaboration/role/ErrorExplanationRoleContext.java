package gold.debug.windowstolinux.shared.ai.collaboration.role;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Minimal controlled failure with credential-like text removed before retention or transmission. / 在保留或传输前移除类似凭据文本的最小受控失败。
 *
 * @param stepCode step code / 步骤代码
 * @param safeDiagnostic safe diagnostic / 安全诊断
 */
public record ErrorExplanationRoleContext(String stepCode, String safeDiagnostic) implements AiRoleContext {
    /**
     * Pattern recognizing pattern matching bearer authorization material for redaction.
     * <p>用于识别匹配 bearer 授权素材以供脱敏的模式的匹配模式。
     */
    private static final Pattern BEARER = Pattern.compile("(?i)(authorization\\s*:\\s*bearer)\\s+[^\\s,;]+",
            Pattern.MULTILINE);

    /**
     * Pattern recognizing pattern matching sensitive key-value assignments.
     * <p>用于识别匹配敏感键值赋值的模式的匹配模式。
     */
    private static final Pattern ASSIGNMENT = Pattern
            .compile("(?i)(password|passwd|api[_-]?key|token|secret)\\s*[:=]\\s*[^\\s,;]+", Pattern.MULTILINE);

    /**
     * Pattern recognizing PRIVATE KEY.
     * <p>用于识别私有键的匹配模式。
     */
    private static final Pattern PRIVATE_KEY = Pattern
            .compile("(?is)-----BEGIN [^-]{1,32}PRIVATE KEY-----.*?-----END [^-]{1,32}PRIVATE KEY-----");

    /**
     * Redacts and validates the bounded error-explanation context. / 脱敏并验证有界错误解释上下文。
     *
     * @param stepCode step code / 步骤代码
     * @param safeDiagnostic safe diagnostic / 安全诊断
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ErrorExplanationRoleContext {
        stepCode = Objects.requireNonNull(stepCode, "stepCode").trim();
        if (!stepCode.matches("[a-z0-9][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("stepCode is invalid");
        safeDiagnostic = redact(Objects.requireNonNull(safeDiagnostic, "safeDiagnostic"));
        if (safeDiagnostic.isBlank())
            safeDiagnostic = "Controlled operation failed without retained diagnostic text";
    }

    /**
     * Returns role.
     * <p>返回角色。
     *
     * @return role / 角色
     */
    @Override
    public AiCollaborationRoleKind role() {
        return AiCollaborationRoleKind.ERROR_EXPLANATION;
    }

    /**
     * Returns redacted summary.
     * <p>返回已脱敏摘要。
     *
     * @return redacted summary / 已脱敏摘要
     */
    @Override
    public String redactedSummary() {
        return "stepCode=" + stepCode + ";safeDiagnostic=" + safeDiagnostic;
    }

    /**
     * Redacts sensitive content from error explanation role context.
     * <p>脱敏错误解释角色上下文。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return redact text / 脱敏文本
     */
    private static String redact(String diagnostic) {
        String value = PRIVATE_KEY.matcher(diagnostic).replaceAll("[REDACTED_PRIVATE_KEY]");
        value = BEARER.matcher(value).replaceAll("$1 [REDACTED]");
        value = ASSIGNMENT.matcher(value).replaceAll("$1=[REDACTED]");
        value = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 1_024 ? value : value.substring(0, 1_024) + "...";
    }
}
