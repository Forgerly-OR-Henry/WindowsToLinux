package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract;

import java.time.Instant;
import java.util.Objects;

/** Non-authorizing record of a user's exact source and target review; remote effects still need host grants. / 用户对精确来源和目标的审阅记录；远端副作用仍需宿主授权。
 * @param applicationId reviewed managed application / 已审阅受管应用
 * @param serverId exact platform server identifier / 精确平台服务器标识
 * @param sourceManifestSha256 frozen source digest / 冻结源码摘要
 * @param archiveSha256 actual tar.gz digest / 实际 tar.gz 摘要
 * @param confirmedAt review time / 审阅时间 */
public record DeploymentReviewConsent(String applicationId, String serverId, String sourceManifestSha256,
                                      String archiveSha256, Instant confirmedAt) {
    /** Validates a review without treating it as an ApprovalGrant. / 校验审阅记录，不将其作为授权令牌。
     * @param applicationId reviewed application / 已审阅应用
     * @param serverId exact server ID / 精确服务器ID
     * @param sourceManifestSha256 original source digest / 原源码摘要
     * @param archiveSha256 compressed archive digest / 压缩归档摘要
     * @param confirmedAt review time / 审阅时间 */
    public DeploymentReviewConsent {
        if (applicationId == null || !applicationId.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Invalid reviewed application");
        if (serverId == null || serverId.isBlank() || serverId.length() > 512 || serverId.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid platform server identity");
        if (sourceManifestSha256 == null || !sourceManifestSha256.matches("[0-9a-f]{64}") || archiveSha256 == null
            || !archiveSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid reviewed source identities");
        Objects.requireNonNull(confirmedAt);
    }
}
