package gold.debug.windowstolinux.shared.ai.collaboration;

import java.util.List;
import java.util.Objects;

/** Validated bounded advisory output from one fixed role. / 来自一个固定角色的已验证有界建议输出。 */
public record RoleAdvice(AiAdviceDecision decision, String summary, List<String> findings) {
    /** Validates the bounded output after strict parsing. / 严格解析后验证有界输出。 */
    public RoleAdvice {
        decision = Objects.requireNonNull(decision, "decision");
        summary = bounded(summary, "summary", 512);
        findings = Objects.requireNonNull(findings, "findings");
        if (findings.size() > 5) throw new IllegalArgumentException("too many findings");
        findings = findings.stream().map(value -> bounded(value, "finding", 256)).toList();
    }

    private static String bounded(String value, String name, int maximum) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
