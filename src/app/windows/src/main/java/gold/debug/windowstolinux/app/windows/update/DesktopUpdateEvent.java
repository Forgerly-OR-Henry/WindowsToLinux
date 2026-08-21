package gold.debug.windowstolinux.app.windows.update;

import java.util.Objects;

/** One bounded desktop update evidence event. / 单个有界桌面更新证据事件。 */
public record DesktopUpdateEvent(DesktopUpdateState state, boolean succeeded, String evidence) {
    /** Validates non-secret event evidence. / 校验无秘密事件证据。 */
    public DesktopUpdateEvent {
        state = Objects.requireNonNull(state, "state");
        evidence = Objects.requireNonNull(evidence, "evidence").trim();
        if (evidence.isEmpty() || evidence.length() > 1024 || evidence.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("update event evidence is invalid");
        }
    }
}
