package gold.debug.windowstolinux.app.service.mod.store;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Explicit unavailable source; never invents transport results. / 明确不可用来源，从不伪造传输结果。 */
public final class UnavailableModStoreGateway implements ModStoreGateway {
    /** Rejects catalog acquisition. / 拒绝目录获取。
     * @param base configured root / 配置根
     * @param cancellation cooperative request / 协作请求
     * @return no fabricated catalog / 不返回伪造目录
     * @throws ApiException always unavailable / 始终不可用 */
    public Catalog catalog(URI base, CancelToken cancellation) throws ApiException {
        throw unavailable();
    }

    /** Rejects download without creating a file. / 不创建文件而拒绝下载。
     * @param base configured root / 配置根
     * @param release confirmed release / 确认发布
     * @param destination requested child / 请求子文件
     * @param progress actual observer / 实际观察者
     * @param cancellation cooperative request / 协作请求
     * @return no fabricated package / 不返回伪造包
     * @throws ApiException always unavailable / 始终不可用 */
    public DownloadResult download(URI base, Release release, Path destination, Consumer<Progress> progress,
                                   CancelToken cancellation) throws ApiException {
        throw unavailable();
    }

    /** Rejects unavailable verification. / 拒绝不可用核验。
     * @param release confirmed declaration / 确认声明
     * @param download actual file / 实际文件
     * @return no fabricated verification / 不返回伪造核验
     * @throws ApiException always unavailable / 始终不可用 */
    public Verification verify(Release release, DownloadResult download) throws ApiException {
        throw unavailable();
    }

    /** Creates the truthful original unavailable problem. / 创建真实原不可用问题。
     * @return actual rejection / 实际拒绝 */
    public static ApiException unavailable() {
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE, "MOD_STORE_UNAVAILABLE",
            "No store source is configured", Map.of(), Optional.empty()));
    }
}
