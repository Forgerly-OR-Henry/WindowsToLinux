package gold.debug.windowstolinux.shared.ai.collaboration.role;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import java.util.List;
import java.util.Objects;

/** Bounded deployment questions; no source contents, input values or credential fields are transmitted. */
public record DeploymentInputRoleContext(List<DeploymentInputField> fields, String question, List<String> history)
        implements AiRoleContext {
    /** Removes values and scrubs user-authored text before any provider or audit receives it. */
    public DeploymentInputRoleContext {
        if (fields.size() > 64 || history.size() > 12) throw new IllegalArgumentException("assistance context exceeds limits");
        fields = fields.stream().map(field -> new DeploymentInputField(field.id(), field.labelKey(), field.helpKey(), "", field.choices())).toList();
        question = redact(question);
        history = history.stream().map(DeploymentInputRoleContext::redact).toList();
    }

    /** Uses the provider explicitly assigned to project analysis. */
    @Override public AiCollaborationRoleKind role() { return AiCollaborationRoleKind.PROJECT_ANALYSIS; }

    /** Serializes only bounded field identifiers, enumerated candidates and scrubbed conversation text. */
    @Override public String redactedSummary() {
        return "fields=" + fields.stream().map(field -> field.id() + " choices=" + field.choices()).toList()
                + "; question=" + question + "; conversation=" + history;
    }

    private static String redact(String text) {
        text = Objects.requireNonNull(text);
        if (text.length() > 2048) text = text.substring(0, 2048);
        return text.replaceAll("(?is)-----BEGIN[^-]*PRIVATE KEY-----.*?(?:-----END[^-]*PRIVATE KEY-----|$)", "[redacted]")
                .replaceAll("(?i)(password|passwd|secret|token|api[-_ ]?key|\u5bc6\u7801|\u5bc6\u94a5)\\s*[:=\uff1a]\\s*[^\\s,;]+", "$1=[redacted]")
                .replaceAll("(?i)Bearer\\s+\\S+", "Bearer [redacted]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]+", "[redacted]")
                .replaceAll("(?i)(https?://)[^/\\s@]+@", "$1[redacted]@")
                .replaceAll("(?i)[A-Z]:[\\\\/][^\\s]+", "[local-path]");
    }
}
