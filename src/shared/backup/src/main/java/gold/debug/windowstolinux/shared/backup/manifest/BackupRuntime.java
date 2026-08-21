package gold.debug.windowstolinux.shared.backup.manifest;

import java.util.List;

/** Runtime and architecture evidence required before restore. / 恢复前所需的运行时与架构证据。 */
public record BackupRuntime(String runtimeKind, String runtimeVersion, String architecture, List<String> capabilities) {
    /** Validates bounded runtime compatibility evidence. / 校验有界运行时兼容性证据。 */
    public BackupRuntime {
        runtimeKind = BackupManifestRules.identifier(runtimeKind, "runtimeKind");
        runtimeVersion = BackupManifestRules.requiredText(runtimeVersion, "runtimeVersion", 128);
        architecture = BackupManifestRules.identifier(architecture, "architecture");
        capabilities = BackupManifestRules.distinctTexts(capabilities, "capabilities", 128, 128);
    }
}
