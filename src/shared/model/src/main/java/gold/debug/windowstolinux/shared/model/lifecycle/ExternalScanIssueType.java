package gold.debug.windowstolinux.shared.model.lifecycle;

/**
 * Non-secret reasons why a server scan is incomplete. / 服务器扫描不完整的非秘密原因。
 */
public enum ExternalScanIssueType {
/**
 * SYSTEMD UNAVAILABLE classification within external scan issue type.
 * <p>外部扫描问题类型中的SYSTEMD不可用分类。
 */
 SYSTEMD_UNAVAILABLE,
/**
 * SYSTEMD PERMISSION classification within external scan issue type.
 * <p>外部扫描问题类型中的SYSTEMD权限分类。
 */
 SYSTEMD_PERMISSION,
/**
 * SYSTEMD PARTIAL classification within external scan issue type.
 * <p>外部扫描问题类型中的SYSTEMD部分分类。
 */
 SYSTEMD_PARTIAL,
/**
 * DOCKER UNAVAILABLE classification within external scan issue type.
 * <p>外部扫描问题类型中的DOCKER不可用分类。
 */
 DOCKER_UNAVAILABLE,
/**
 * DOCKER PERMISSION classification within external scan issue type.
 * <p>外部扫描问题类型中的DOCKER权限分类。
 */
 DOCKER_PERMISSION,
/**
 * DOCKER PARTIAL classification within external scan issue type.
 * <p>外部扫描问题类型中的DOCKER部分分类。
 */
 DOCKER_PARTIAL,
/**
 * LIMIT REACHED classification within external scan issue type.
 * <p>外部扫描问题类型中的限制已达到分类。
 */
 LIMIT_REACHED }
