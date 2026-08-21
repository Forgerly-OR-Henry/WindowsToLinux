package gold.debug.windowstolinux.app.windows.uninstall;

import java.util.Objects;

/** One bounded desktop uninstall event. / 单个有界桌面卸载事件。 */
public record DesktopUninstallEvent(DesktopUninstallState state, boolean succeeded, String evidence) {
    /** Validates non-secret event evidence. / 校验无秘密事件证据。 */
    public DesktopUninstallEvent {
        state = Objects.requireNonNull(state, "state");
        evidence = Objects.requireNonNull(evidence, "evidence").trim();
        if (evidence.isEmpty() || evidence.length() > 1024 || evidence.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("uninstall event evidence is invalid");
        }
    }
}
