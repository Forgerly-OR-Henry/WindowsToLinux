package gold.debug.windowstolinux.app.service.mod.store;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Complete catalog and ordinary package transport boundary. / 完整目录及普通包传输边界。 */
public interface ModStoreGateway {
    /** One complete obtainable declaration. / 一份完整可获取声明。
     * @param manifest strict author declaration / 严格作者声明
     * @param packageUri relative same-origin package URI / 相对同源包 URI
     * @param bytes exact archive size / 精确归档大小
     * @param sha256 exact archive digest / 精确归档摘要
     * @param purposes explicit dependency purposes / 明确依赖用途 */
    record Release(ModManifest manifest, URI packageUri, long bytes, String sha256, Map<String, String> purposes) {
        /** Copies bounded declarations before requests. / 请求前复制有界声明。
         * @param manifest strict declaration / 严格声明
         * @param packageUri relative package URI / 相对包 URI
         * @param bytes exact archive size / 精确归档大小
         * @param sha256 exact archive digest / 精确归档摘要
         * @param purposes explicit dependency purposes / 明确依赖用途 */
        public Release {
            java.util.Objects.requireNonNull(manifest);
            ModStoreProtocol.relative(packageUri);
            if (bytes < 1 || bytes > gold.debug.windowstolinux.app.mod.install.ModPackageReader.MAX_BYTES
                || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid package size or SHA256");
            purposes = Map.copyOf(purposes);
            for (var purpose : purposes.entrySet())
                if (purpose.getValue().isBlank() || purpose.getValue().length() > 4096
                    || manifest.dependencies().stream().noneMatch(d -> d.id().equals(purpose.getKey())))
                    throw new IllegalArgumentException("Purpose must name an actual dependency");
        }

        /** Returns exact identity. / 返回精确身份。
         * @return author Mod ID / 作者 Mod ID */
        public String owner() {
            return manifest.id();
        }

        /** Returns canonical version. / 返回规范版本。
         * @return canonical version / 规范版本 */
        public String version() {
            return manifest.version().toString();
        }

        /** Returns author display name. / 返回作者展示名。
         * @return plain display name / 纯文本展示名 */
        public String title() {
            return manifest.name();
        }

        /** Returns actual author description. / 返回实际作者说明。
         * @return declared description or empty / 声明说明或空 */
        public String description() {
            return manifest.description().orElse("");
        }
    }

    /** Complete transport snapshot. / 完整传输快照。
     * @param sha256 complete catalog byte digest / 完整目录字节摘要
     * @param releases all advertised versions / 全部声明版本 */
    record Catalog(String sha256, List<Release> releases) {
        /** Copies the bounded catalog. / 复制有界目录。
         * @param sha256 complete byte digest / 完整字节摘要
         * @param releases all releases / 全部发布 */
        public Catalog {
            releases = List.copyOf(releases);
            if (releases.size() > 1000) throw new IllegalArgumentException("Catalog exceeds 1000 releases");
        }
    }

    /** Actual download position. / 实际下载位置。
     * @param file exact requested ordinary file / 精确请求普通文件 */
    record DownloadResult(Path file) {
    }

    /** Integrity result, not a publisher signature. / 完整性结果，不是发布者签名。
     * @param verified whether bytes and digest match / 字节及摘要是否匹配
     * @param detail plain explanation / 纯文本说明 */
    record Verification(boolean verified, String detail) {
    }

    /** Actual transfer progress. / 实际传输进度。
     * @param completed received bytes / 已收字节
     * @param total declared exact size / 声明精确大小 */
    record Progress(long completed, long total) {
        /** Requires actual consistent byte observations. / 要求实际一致的字节观察。
         * @param completed actual received bytes / 实际收到字节
         * @param total exact expected size or zero / 精确预期大小或零 */
        public Progress {
            if (completed < 0 || total < 0 || total != 0 && completed > total)
                throw new IllegalArgumentException("Invalid actual transfer progress");
        }
    }

    /** Fetches the complete catalog. / 获取完整目录。
     * @param base validated root / 已校验根
     * @param cancellation cooperative request / 协作请求
     * @return complete actual catalog / 完整实际目录
     * @throws Exception if acquisition fails / 获取失败时 */
    Catalog catalog(URI base, CancelToken cancellation) throws Exception;

    /** Writes the requested new package. / 写请求的新包。
     * @param base validated root / 已校验根
     * @param release exact confirmed release / 精确确认发布
     * @param destination new ordinary child / 新普通子文件
     * @param progress actual transfer observer / 实际传输观察者
     * @param cancellation cooperative request / 协作请求
     * @return actual complete package / 实际完整包
     * @throws Exception if transfer fails or is cancelled / 传输失败或取消时 */
    DownloadResult download(URI base, Release release, Path destination, Consumer<Progress> progress,
                            CancelToken cancellation) throws Exception;

    /** Rechecks file integrity before review. / 审阅前复核文件完整性。
     * @param release confirmed declaration / 已确认声明
     * @param download actual file / 实际文件
     * @return actual integrity result / 实际完整性结果
     * @throws Exception if verification fails / 核验失败时 */
    Verification verify(Release release, DownloadResult download) throws Exception;
}
