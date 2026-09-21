package gold.debug.windowstolinux.shared.backup.restore;

/**
 * Restore material strategy with different compatibility rules. / 具有不同兼容性规则的恢复材料策略。
 */
public enum RestoreMaterialKind {
    /**
     * SOURCE REBUILD classification within restore material kind.
     * <p>恢复素材种类中的源码重建分类。
     */
    SOURCE_REBUILD,
    /**
     * BINARY RELEASE classification within restore material kind.
     * <p>恢复素材种类中的二进制发布分类。
     */
    BINARY_RELEASE
}
