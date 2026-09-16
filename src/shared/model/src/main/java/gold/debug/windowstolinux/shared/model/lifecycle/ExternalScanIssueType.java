package gold.debug.windowstolinux.shared.model.lifecycle;

/** Non-secret reasons why a server scan is incomplete. / 服务器扫描不完整的非秘密原因。 */
public enum ExternalScanIssueType { SYSTEMD_UNAVAILABLE, SYSTEMD_PERMISSION, SYSTEMD_PARTIAL, DOCKER_UNAVAILABLE, DOCKER_PERMISSION, DOCKER_PARTIAL, LIMIT_REACHED }
