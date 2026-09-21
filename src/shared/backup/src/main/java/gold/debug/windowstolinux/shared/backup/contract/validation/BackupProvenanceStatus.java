package gold.debug.windowstolinux.shared.backup.contract.validation;

/**
 * Signature state reported independently from archive integrity. / 与归档完整性独立报告的签名状态。
 */
public enum BackupProvenanceStatus {
    /**
     * NOT PRESENT classification within backup provenance status.
     * <p>备份来源证据状态中的未存在分类。
     */
    NOT_PRESENT,
    /**
     * NOT VERIFIED classification within backup provenance status.
     * <p>备份来源证据状态中的未已验证分类。
     */
    NOT_VERIFIED,
    /**
     * VERIFIED classification within backup provenance status.
     * <p>备份来源证据状态中的已验证分类。
     */
    VERIFIED
}
