package gold.debug.windowstolinux.app.service.mod.store;

import java.net.URI;
import java.util.List;

import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.appapi.task.result.Problem;

/** Complete remote catalog and independently verified installed observations. / 完整远端目录及独立核验安装观察。
 * @param revision service snapshot revision / 服务快照修订
 * @param source validated store root / 已校验商店根
 * @param releases all obtainable releases / 全部可获取发布
 * @param installed physically verified READY releases / 物理核验的 READY 发布
 * @param installedIssues actual installed verification failures / 实际安装核验失败
 */
public record ModStoreCatalog(long revision, URI source, List<ModStoreGateway.Release> releases,
                              List<Installed> installed, List<InstalledIssue> installedIssues) {
    /** Freezes complete observations without granting installation authority. / 冻结完整观察，不授予安装权限。
     * @param revision service snapshot revision / 服务快照修订
     * @param source validated store root / 已校验商店根
     * @param releases complete remote releases / 完整远端发布
     * @param installed verified installed releases / 已核验安装发布
     * @param installedIssues actual verification failures / 实际核验失败
     */
    public ModStoreCatalog {
        releases = List.copyOf(releases);
        installed = List.copyOf(installed);
        installedIssues = List.copyOf(installedIssues);
    }

    /** An actual READY program whose complete bytes were verified. / 完整字节已核验的实际 READY 程序。
     * @param manifest actual installed declaration / 实际安装声明
     * @param contentSha256 registered verified digest / 登记且核验的摘要
     * @param enabled actual saved owner enable choice / 所有者实际已存启用选择
     * @param selected whether this is the exact saved release selection / 是否为精确已存发布选择
     */
    public record Installed(ModManifest manifest, String contentSha256, boolean enabled, boolean selected) {
    }

    /** A registered release that cannot be reused safely. / 无法安全复用的登记发布。
     * @param key original registered identity / 原登记身份
     * @param problem actual verification rejection / 实际核验拒绝
     */
    public record InstalledIssue(ModStoreSelection.ReleaseKey key, Problem problem) {
    }
}
