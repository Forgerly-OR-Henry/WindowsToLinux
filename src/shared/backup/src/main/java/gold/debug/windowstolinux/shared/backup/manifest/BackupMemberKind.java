package gold.debug.windowstolinux.shared.backup.manifest;

/** Stable archive member classification. / 稳定的归档成员分类。 */
public enum BackupMemberKind {
    RELEASE,
    CONFIGURATION,
    PERSISTENT_CONTENT,
    DATABASE,
    RUNTIME,
    ENCRYPTED_SECRETS
}
