package gold.debug.windowstolinux.app.service.mod.store;

import java.net.URI;
import java.nio.file.*;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;

/** Composes confirmed downloads, verification and the existing local installer. / 装配已确认下载、验证及已有本地安装器。 */
public final class ModStoreService {
    /** Optional configured HTTPS store root. / 可选的已配置 HTTPS 商店根地址。 */
    private final Optional<URI> base;

    /** Store transport and trust adapter. / 商店传输及信任适配器。 */
    private final ModStoreGateway gateway;

    /** Existing installer, absent in CLASS mode. / 既有安装器，CLASS 模式下缺省。 */
    private final Optional<PlatformModInstallation> installation;

    /** Current user privacy and operation admission boundary. / 当前用户隐私及操作准入边界。 */
    private final DesktopSecurityService security;

    /** Captures startup configuration and actual installation capability. / 捕获启动配置和实际安装能力。
     * @param base configured optional URL / 配置可选地址
     * @param gateway transport and trust adapter / 传输和信任适配器
     * @param installation absent in CLASS / CLASS 中缺省
     * @param security current privacy scope / 当前隐私作用域 */
    public ModStoreService(Optional<URI> base, ModStoreGateway gateway, Optional<PlatformModInstallation> installation,
                           DesktopSecurityService security) {
        this.base = java.util.Objects.requireNonNull(base);
        this.gateway = java.util.Objects.requireNonNull(gateway);
        this.installation = java.util.Objects.requireNonNull(installation);
        this.security = java.util.Objects.requireNonNull(security);
    }

    /** Reports source-mode installation capability. / 报告来源模式安装能力。
     * @return false in CLASS / CLASS 为假 */
    public boolean canInstall() {
        return installation.isPresent();
    }

    /** Searches once, retaining the caller's keyword. / 搜索一次，保留调用方搜索词。
     * @param keyword requested search text / 请求搜索词
     * @return actual advertised releases / 实际声明发布
     * @throws Exception if unavailable or locked / 不可用或锁定时 */
    public ModStoreGateway.QueryResult query(String keyword) throws Exception {
        if (keyword == null || keyword.length() > 256)
            throw new IllegalArgumentException("Search term exceeds 256 characters");
        try (var operation = security.enterOperation()) {
            return gateway.query(address(), keyword);
        }
    }

    /** Installs an explicitly confirmed release without enabling it or switching selected versions. / 安装明确已确认发布，不启用或切换所选版本。
     * @param release explicitly confirmed release / 明确已确认发布
     * @param progress download progress / 下载进度
     * @return original installer outcome / 原安装器结果
     * @throws Exception if download, trust, identity or installation fails / 下载、信任、身份或安装失败时 */
    public OperationResult<PlatformModInstallation.Receipt> install(ModStoreGateway.Release release,
                                                                    Consumer<ModStoreGateway.Progress> progress) throws Exception {
        var installer = installation.orElseThrow(() -> new IllegalStateException("CLASS mode does not install release packages"));
        try (var operation = security.enterOperation()) {
            URI address = address();
            Path directory = installer.originalsDirectory();
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))
                throw new java.io.IOException("Invalid store download directory");
            Path target = directory.resolve("store-" + UUID.randomUUID() + ".wtlmod");
            var download = gateway.download(address, release, target, progress);
            if (!target.equals(download.file()) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new java.io.IOException("Store adapter did not return the requested ordinary package");
            var verified = gateway.verify(release, download);
            if (!verified.verified())
                throw new java.io.IOException("Store package verification rejected: " + verified.detail());
            var review = installer.review(download.file());
            var manifest = review.packageInfo();
            if (!manifest.id().equals(release.owner()) || !manifest.version().toString().equals(release.version()))
                throw new java.io.IOException("Downloaded package identity differs from the confirmed release");
            return installer.install(review);
        }
    }

    /** Requires an explicitly configured store root. / 要求明确已配置的商店根地址。
     * @return configured root / 配置根地址
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if no store is configured / 未配置商店时 */
    private URI address() throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        return base.orElseThrow(UnavailableModStoreGateway::unavailable);
    }
}
