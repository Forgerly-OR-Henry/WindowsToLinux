package gold.debug.windowstolinux.shared.ai.redaction;

/** Bounded redaction for explicitly selected diagnostic excerpts, never whole source or configuration. / 仅为明确选取的诊断片段提供有界脱敏，绝不处理完整源码或配置。 */
public final class AgentEvidenceText {
    /** Prevents construction. / 禁止实例化。 */
    private AgentEvidenceText() {
    }

    /** Removes common credential forms and terminal controls before bounding a diagnostic excerpt. / 截断诊断片段前移除常见凭据形式及终端控制符。
     * @param text selected diagnostic excerpt / 选取的诊断片段
     * @return sanitized bounded text / 脱敏有界文本
     */
    public static String redact(String text) {
        String value = java.util.Objects.requireNonNull(text)
                .replaceAll("(?is)-----BEGIN[^-]*PRIVATE KEY-----.*?(?:-----END[^-]*PRIVATE KEY-----|$)", "[redacted]")
                .replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer [redacted]")
                .replaceAll(
                        "(?i)(password|passwd|secret|token|api[-_ ]?key|authorization|\u5bc6\u7801|\u5bc6\u94a5)\\s*[:=：]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
                        "$1=[redacted]")
                .replaceAll("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s]+", "[redacted-uri]")
                .replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return value.length() > 2048 ? value.substring(0, 2048) + " [truncated]" : value;
    }
}
