package gold.debug.windowstolinux.app.service.mod.store;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Explicit unavailable compatibility implementation; it performs no network calls. / 明确不可用的兼容实现，不发出网络请求。 */
public final class UnavailableModStoreGateway implements ModStoreGateway {
    /** Rejects search while the store transport is disconnected. / 商店传输未接通时拒绝搜索。
     * @param base configured root / 配置根地址
     * @param keyword requested keyword / 请求搜索词
     * @return no result because transport is unavailable / 传输不可用故无结果
     * @throws ApiException always, with the unavailable contract / 始终抛出不可用合同 */
    @Override
    public QueryResult query(URI base, String keyword) throws ApiException {
        throw unavailable();
    }

    /** Rejects download without creating a package. / 拒绝下载且不创建包。
     * @param base configured root / 配置根地址
     * @param release confirmed release / 已确认发布
     * @param destination requested package position / 请求包位置
     * @param progress transfer callback / 传输回调
     * @return no package because transport is unavailable / 传输不可用故无包
     * @throws ApiException always, with the unavailable contract / 始终抛出不可用合同 */
    @Override
    public DownloadResult download(URI base, Release release, Path destination, Consumer<Progress> progress) throws ApiException {
        throw unavailable();
    }

    /** Rejects trust verification while no adapter is connected. / 适配器未接通时拒绝信任验证。
     * @param release confirmed release / 已确认发布
     * @param download downloaded package / 下载包
     * @return no verification because the adapter is unavailable / 适配器不可用故无验证
     * @throws ApiException always, with the unavailable contract / 始终抛出不可用合同 */
    @Override
    public Verification verify(Release release, DownloadResult download) throws ApiException {
        throw unavailable();
    }

    /** Creates truthful service-unavailable feedback. / 创建真实服务不可用反馈。
     * @return public rejection / 公开拒绝 */
    public static ApiException unavailable() {
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE, "MOD_STORE_UNAVAILABLE",
            "The Mod store transport is not connected", Map.of(), Optional.empty()));
    }
}
