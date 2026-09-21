package gold.debug.windowstolinux.shared.backup.contract.validation;

/**
 * Closed locally verified managed artifact formats. / 本地已验证受管制品格式的封闭集合。
 */
public enum ManagedArtifactFormatType {
    /**
     * Strict ordinary tree PAX TAR. / 严格普通树 PAX TAR。
     */
    PAX_TAR,
    /**
     * Strict single-image OCI archive. / 严格单镜像 OCI 归档。
     */
    OCI_ARCHIVE
}
