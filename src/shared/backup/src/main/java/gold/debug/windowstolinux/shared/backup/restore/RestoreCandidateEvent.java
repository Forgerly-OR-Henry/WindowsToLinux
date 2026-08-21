package gold.debug.windowstolinux.shared.backup.restore;

import java.util.Objects;

/** One bounded evidence event in a restore attempt. / 一次恢复尝试中的单个有界证据事件。 */
public record RestoreCandidateEvent(RestoreCandidateState state, boolean succeeded, String evidence) {
    /** Validates a non-secret event. / 校验无秘密事件。 */
    public RestoreCandidateEvent {
        state = Objects.requireNonNull(state, "state");
        evidence = Objects.requireNonNull(evidence, "evidence").trim();
        if (evidence.isEmpty() || evidence.length() > 1024 || evidence.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("restore event evidence is invalid");
        }
    }
}
