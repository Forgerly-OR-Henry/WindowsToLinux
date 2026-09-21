package gold.debug.windowstolinux.shared.backup.manifest;

/**
 * Stable archive member classification. / 稳定的归档成员分类。
 */
public enum BackupMemberKind {
    /**
     * RELEASE classification within backup member kind.
     * <p>备份成员种类中的发布分类。
     */
    RELEASE,
    /**
     * CONFIGURATION classification within backup member kind.
     * <p>备份成员种类中的配置分类。
     */
    CONFIGURATION,
    /**
     * PERSISTENT CONTENT classification within backup member kind.
     * <p>备份成员种类中的持久化内容分类。
     */
    PERSISTENT_CONTENT,
    /**
     * DATABASE classification within backup member kind.
     * <p>备份成员种类中的数据库分类。
     */
    DATABASE,
    /**
     * RUNTIME classification within backup member kind.
     * <p>备份成员种类中的运行时分类。
     */
    RUNTIME,
    /**
     * ENCRYPTED SECRETS classification within backup member kind.
     * <p>备份成员种类中的加密秘密集合分类。
     */
    ENCRYPTED_SECRETS
}
