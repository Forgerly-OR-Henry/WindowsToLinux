package gold.debug.windowstolinux.shared.backup.contract.validation;

/** Signature state reported independently from archive integrity. / 与归档完整性独立报告的签名状态。 */
public enum BackupProvenanceStatus {
    NOT_PRESENT,
    NOT_VERIFIED,
    VERIFIED
}
