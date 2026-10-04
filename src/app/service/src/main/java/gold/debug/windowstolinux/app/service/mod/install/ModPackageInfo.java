package gold.debug.windowstolinux.app.service.mod.install;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable verified package observations, without installation authority. / 不携带安装权限的不可变已验证包观察。
 * @param archive original archive / 原归档
 * @param id exact Mod identity / 精确 Mod 身份
 * @param name author display name / 作者展示名
 * @param version exact release string / 精确发布字符串
 * @param archiveSha256 original archive digest / 原归档摘要
 * @param contentSha256 verified content digest / 已验证内容摘要 */
public record ModPackageInfo(Path archive, String id, String name, String version, String archiveSha256,
                             String contentSha256) {
    /** Requires complete observations. / 要求完整观察。
     * @param archive original archive / 原归档
     * @param id exact identity / 精确身份
     * @param name display name / 展示名
     * @param version release / 发布
     * @param archiveSha256 archive digest / 归档摘要
     * @param contentSha256 content digest / 内容摘要 */
    public ModPackageInfo {
        Objects.requireNonNull(archive);
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(version);
        Objects.requireNonNull(archiveSha256);
        Objects.requireNonNull(contentSha256);
    }
}
