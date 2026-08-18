package gold.debug.windowstolinux.shared.ai.collaboration;

import java.util.Objects;
import java.util.regex.Pattern;

/** Minimal controlled failure with credential-like text removed before retention or transmission. / 在保留或传输前移除类似凭据文本的最小受控失败。 */
public record ErrorExplanationRoleContext(String stepCode, String safeDiagnostic) implements AiRoleContext {
    private static final Pattern BEARER = Pattern.compile("(?i)(authorization\\s*:\\s*bearer)\\s+[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|api[_-]?key|token|secret)\\s*[:=]\\s*[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?is)-----BEGIN [^-]{1,32}PRIVATE KEY-----.*?-----END [^-]{1,32}PRIVATE KEY-----");

    /** Redacts and validates the bounded error-explanation context. / 脱敏并验证有界错误解释上下文。 */
    public ErrorExplanationRoleContext {
        stepCode = Objects.requireNonNull(stepCode, "stepCode").trim();
        if (!stepCode.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IllegalArgumentException("stepCode is invalid");
        safeDiagnostic = redact(Objects.requireNonNull(safeDiagnostic, "safeDiagnostic"));
        if (safeDiagnostic.isBlank()) safeDiagnostic = "Controlled operation failed without retained diagnostic text";
    }

    /** Performs the {@code role} operation. / 执行 {@code role} 操作。 */
    @Override public AiCollaborationRole role() { return AiCollaborationRole.ERROR_EXPLANATION; }

    /** Performs the {@code redactedSummary} operation. / 执行 {@code redactedSummary} 操作。 */
    @Override public String redactedSummary() { return "stepCode=" + stepCode + ";safeDiagnostic=" + safeDiagnostic; }

    private static String redact(String diagnostic) {
        String value = PRIVATE_KEY.matcher(diagnostic).replaceAll("[REDACTED_PRIVATE_KEY]");
        value = BEARER.matcher(value).replaceAll("$1 [REDACTED]");
        value = ASSIGNMENT.matcher(value).replaceAll("$1=[REDACTED]");
        value = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 1_024 ? value : value.substring(0, 1_024) + "...";
    }
}
