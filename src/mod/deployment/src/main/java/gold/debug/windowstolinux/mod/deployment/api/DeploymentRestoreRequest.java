package gold.debug.windowstolinux.mod.deployment.api;

import java.util.List;

import gold.debug.windowstolinux.appapi.server.model.ServerId;

/** Reviewed target candidate from a verified backup archive. / 经验证备份归档生成的已审阅目标候选。
 * @param archivedApplication archived immutable application snapshot / 已归档不可变应用快照
 * @param target exact target server / 精确目标服务器
 * @param files verified local source files / 已验证本地来源文件
 * @param archiveSha256 complete archive digest / 完整归档摘要
 * @param reviewedTargetSha256 target review digest / 目标审阅摘要 */
public record DeploymentRestoreRequest(DeploymentApplicationSnapshot archivedApplication, ServerId target,
                                       List<RestoreFile> files, String archiveSha256, String reviewedTargetSha256) {
    /** Copies the verified source list. / 复制已验证来源列表。
     * @param archivedApplication archived immutable application snapshot / 已归档不可变应用快照
     * @param target exact target server / 精确目标服务器
     * @param files verified local source files / 已验证本地来源文件
     * @param archiveSha256 complete archive digest / 完整归档摘要
     * @param reviewedTargetSha256 target review digest / 目标审阅摘要 */
    public DeploymentRestoreRequest {
        files = List.copyOf(files);
    }
}
