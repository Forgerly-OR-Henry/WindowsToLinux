package gold.debug.windowstolinux.app.service.backup;

import java.util.List;
import java.util.Objects;

/** Read-only target evidence collected before any restore input is staged. / 在暂存任何恢复输入前收集的只读目标证据。 */
public record ManagedRestorePreflightOutcome(
        String targetServerId,
        String applicationId,
        String archiveSha256,
        long availableBytes,
        List<String> evidence
) {
    /** Requires complete bounded preflight evidence. / 要求完整且有界的前置证据。 */
    public ManagedRestorePreflightOutcome {
        targetServerId = identifier(targetServerId, "targetServerId");
        applicationId = identifier(applicationId, "applicationId");
        archiveSha256 = Objects.requireNonNull(archiveSha256, "archiveSha256");
        if (!archiveSha256.matches("[0-9a-f]{64}") || availableBytes < 0) {
            throw new IllegalArgumentException("restore preflight identity or capacity is invalid");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.isEmpty() || evidence.size() > 64 || evidence.stream().anyMatch(value -> value == null
                || value.isBlank() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("restore preflight evidence is invalid");
        }
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }
}
