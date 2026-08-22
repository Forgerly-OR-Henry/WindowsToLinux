package gold.debug.windowstolinux.shared.linux.protocol.backup;

/** Closed set of remotely generated backup artifact formats. / 远端生成备份制品格式的封闭集合。 */
public enum RemoteBackupArtifactKind {
    /** PAX archive of one managed ordinary data tree. / 一个受管普通数据树的 PAX 归档。 */
    FILE_TREE,
    /** PAX archive of one current ordinary release tree. / 一个当前普通发布树的 PAX 归档。 */
    RELEASE_TREE,
    /** PAX archive of one owned container named volume. / 一个有归属容器命名卷的 PAX 归档。 */
    VOLUME,
    /** OCI archive of one verified current container image. / 一个已验证当前容器镜像的 OCI 归档。 */
    OCI_IMAGE
}
