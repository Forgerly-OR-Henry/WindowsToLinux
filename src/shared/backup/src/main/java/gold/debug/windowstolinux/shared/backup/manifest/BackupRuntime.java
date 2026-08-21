package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.List;

/** Distribution, runtime and architecture evidence required before restore. / 恢复前所需的发行版、运行时与架构证据。 */
public record BackupRuntime(
        String distroId,
        String distroVersion,
        String runtimeKind,
        String runtimeVersion,
        String architecture,
        List<String> capabilities
) {
    /** Validates bounded runtime compatibility evidence. / 校验有界运行时兼容性证据。 */
    public BackupRuntime {
        distroId = BackupManifestRules.identifier(distroId, "distroId");
        distroVersion = BackupManifestRules.requiredText(distroVersion, "distroVersion", 128);
        runtimeKind = BackupManifestRules.identifier(runtimeKind, "runtimeKind");
        runtimeVersion = BackupManifestRules.requiredText(runtimeVersion, "runtimeVersion", 128);
        architecture = BackupManifestRules.identifier(architecture, "architecture");
        capabilities = BackupManifestRules.distinctTexts(capabilities, "capabilities", 128, 128);
    }
}
