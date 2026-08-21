package gold.debug.windowstolinux.app.windows.uninstall;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Exact jpackage, data and credential boundaries for one uninstall request. / 单次卸载请求的精确 jpackage、数据及凭据边界。 */
public record DesktopUninstallRequest(
        Optional<DesktopUninstallDecisionType> decision,
        Path installRoot,
        Path dataRoot,
        String credentialNamespace
) {
    /** Validates fixed application-owned local paths without choosing a default decision. / 校验固定应用本地路径且不选择默认决定。 */
    public DesktopUninstallRequest {
        decision = Objects.requireNonNull(decision, "decision");
        installRoot = normalized(installRoot, "installRoot");
        dataRoot = normalized(dataRoot, "dataRoot");
        if (installRoot.getParent() == null || !dataRoot.equals(installRoot.resolve("data"))) {
            throw new IllegalArgumentException("desktop data root must be the fixed data child of the install root");
        }
        credentialNamespace = Objects.requireNonNull(credentialNamespace, "credentialNamespace").trim();
        if (!credentialNamespace.matches("WindowsToLinux/[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("credential namespace is outside the WindowsToLinux boundary");
        }
    }

    private static Path normalized(Path value, String field) {
        Path path = Objects.requireNonNull(value, field).toAbsolutePath().normalize();
        if (path.getParent() == null) throw new IllegalArgumentException(field + " must not be a filesystem root");
        return path;
    }
}
