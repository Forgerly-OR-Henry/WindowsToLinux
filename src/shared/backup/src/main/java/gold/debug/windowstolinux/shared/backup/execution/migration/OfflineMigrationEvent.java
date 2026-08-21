package gold.debug.windowstolinux.shared.backup.execution.migration;

import java.util.Objects;

/** One bounded non-secret migration evidence event. / 单个有界无秘密迁移证据事件。 */
public record OfflineMigrationEvent(OfflineMigrationState state, boolean succeeded, String evidence) {
    /** Validates bounded event evidence. / 校验有界事件证据。 */
    public OfflineMigrationEvent {
        state = Objects.requireNonNull(state, "state");
        evidence = Objects.requireNonNull(evidence, "evidence").trim();
        if (evidence.isEmpty() || evidence.length() > 1024 || evidence.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("migration event evidence is invalid");
        }
    }
}
