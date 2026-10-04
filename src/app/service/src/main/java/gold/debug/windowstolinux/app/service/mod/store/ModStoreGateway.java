package gold.debug.windowstolinux.app.service.mod.store;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Future store transport and trust adapter; no HTTP or signing implementation is supplied. / 后续商店传输及信任适配器，本期不提供 HTTP 或签名实现。 */
public interface ModStoreGateway {
    /** Advertised immutable release. / 商店声明的不可变发布。
     * @param owner stable Mod identity / 稳定 Mod 身份
     * @param version semantic release / 语义版本
     * @param title display title / 展示标题
     * @param description plain-text details / 纯文本详情 */
    record Release(String owner, String version, String title, String description) {
        /** Validates identities before any download. / 下载前验证身份。
         * @param owner stable identity / 稳定身份
         * @param version semantic version / 语义版本
         * @param title display title / 展示标题
         * @param description plain text / 纯文本 */
        public Release {
            if (owner == null || !owner.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*") || owner.length() > 512)
                throw new IllegalArgumentException("Invalid store Mod identity");
            gold.debug.windowstolinux.app.mod.version.SemanticVersion.parse(version);
            if (title == null || title.isBlank() || title.length() > 256 || description == null || description.length() > 32768)
                throw new IllegalArgumentException("Invalid store release details");
        }
    }

    /** Bounded search result. / 有界搜索结果。
     * @param releases advertised releases / 声明发布 */
    record QueryResult(List<Release> releases) {
        /** Copies releases and enforces the search result bound. / 复制发布并执行搜索结果界限。
         * @param releases bounded advertised releases copied immutably / 不可变复制的有界声明发布 */
        public QueryResult {
            releases = List.copyOf(releases);
            if (releases.size() > 1000) throw new IllegalArgumentException("Too many store results");
        }
    }

    /** Downloaded ordinary package result. / 下载普通包结果。
     * @param file exact destination / 精确目标 */
    record DownloadResult(Path file) {
        /** Requires an actual package position. / 要求实际包位置。
         * @param file required downloaded package position / 必需的下载包位置 */
        public DownloadResult {
            java.util.Objects.requireNonNull(file);
        }
    }

    /** Trust verification result, independent of package structural checks. / 独立于包结构检查的信任验证结果。
     * @param verified adapter-confirmed verification / 适配器确认验证
     * @param detail plain-text explanation / 纯文本说明 */
    record Verification(boolean verified, String detail) {
        /** Requires an explanation for the trust result. / 要求信任结果具备说明。
         * @param verified adapter-confirmed trust result / 适配器确认的信任结果
         * @param detail required plain-text explanation / 必需纯文本说明 */
        public Verification {
            java.util.Objects.requireNonNull(detail);
        }
    }

    /** Current transfer progress. / 当前传输进度。
     * @param completed bytes downloaded / 已下载字节
     * @param total known bytes, zero if unknown / 已知总字节，未知为零 */
    record Progress(long completed, long total) {
        /** Rejects negative or inconsistent transfer progress. / 拒绝负数或矛盾的传输进度。
         * @param completed downloaded byte count / 已下载字节数
         * @param total known total or zero when unknown / 已知总数，未知为零 */
        public Progress {
            if (completed < 0 || total < 0 || total != 0 && completed > total)
                throw new IllegalArgumentException("Invalid download progress");
        }
    }

    /** Queries on explicit user requests. / 仅由明确用户请求查询。
     * @param base configured base / 配置基址
     * @param keyword preserved search term / 保留搜索词
     * @return actual result / 实际结果
     * @throws Exception if service is unavailable / 服务不可用时 */
    QueryResult query(URI base, String keyword) throws Exception;

    /** Writes a new package to the requested destination without replacing existing files. / 向请求目标写新包，不覆盖既有文件。
     * @param base configured base / 配置基址
     * @param release confirmed release / 已确认发布
     * @param destination new ordinary mod-zip child / 新普通 mod-zip 子文件
     * @param progress transfer callback / 传输回调
     * @return actual downloaded package / 实际下载包
     * @throws Exception if download fails / 下载失败时 */
    DownloadResult download(URI base, Release release, Path destination, Consumer<Progress> progress) throws Exception;

    /** Verifies transport integrity and future trust policy before installation review. / 安装审阅前验证传输完整性及后续信任策略。
     * @param release confirmed declaration / 已确认声明
     * @param download actual package / 实际包
     * @return verified or rejected evidence / 验证或拒绝依据
     * @throws Exception if verification is unavailable / 验证不可用时 */
    Verification verify(Release release, DownloadResult download) throws Exception;
}
