package gold.debug.windowstolinux.shared.backup.manifest;

/** Stable managed-application and server identity. / 稳定的受管应用与服务器身份。 */
public record BackupIdentity(String applicationId, String serverId, String managedRoot, String releaseIdentity) {
    /** Validates all identity fields as bounded non-secret text. / 将所有身份字段校验为有界无秘密文本。 */
    public BackupIdentity {
        applicationId = BackupManifestRules.identifier(applicationId, "applicationId");
        serverId = BackupManifestRules.identifier(serverId, "serverId");
        managedRoot = BackupManifestRules.requiredText(managedRoot, "managedRoot", 512);
        releaseIdentity = BackupManifestRules.identifier(releaseIdentity, "releaseIdentity");
    }
}
