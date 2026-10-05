package gold.debug.windowstolinux.app.service.mod.store;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import gold.debug.windowstolinux.app.config.ConfigLoader;
import gold.debug.windowstolinux.app.mod.install.ModPackageReader;
import gold.debug.windowstolinux.app.windows.file.WindowsFilePublish;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;

/** Actual same-origin HTTP transport with a whole-body deadline and no redirects. / 实际同源 HTTP 传输，具备完整 body 期限且不重定向。 */
public final class HttpModStoreGateway implements ModStoreGateway {
    /** Original whole-request deadline, independent of the JDK header timer. / 原完整请求期限，独立于 JDK headers 计时器。 */
    private final Duration timeout;

    /** Captures the default bounded whole-body budget. / 捕获默认有界完整 body 预算。 */
    public HttpModStoreGateway() {
        this(Duration.ofSeconds(60));
    }

    /** Captures a positive testable whole-body budget. / 捕获可测试的正完整 body 预算。
     * @param timeout original complete-body deadline / 原完整 body 期限 */
    public HttpModStoreGateway(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive deadline required");
        this.timeout = timeout;
    }

    /** Fetches one complete strict catalog. / 获取一份完整严格目录。
     * @param base configured root / 配置根
     * @param cancellation cooperative request / 协作请求
     * @return complete actual catalog / 完整实际目录
     * @throws Exception if protocol or transport fails / 协议或传输失败时 */
    public Catalog catalog(URI base, CancelToken cancellation) throws Exception {
        URI endpoint = root(base).resolve("catalog.json");
        try (var body = new ModStoreBody(ModStoreProtocol.MAX_CATALOG_BYTES, 0, null, ignored -> {
        })) {
            return ModStoreProtocol.read(fetch(endpoint, body, cancellation).bytes());
        }
    }

    /** Streams to an owned hidden temporary and publishes only verified complete bytes. / 流入所属隐藏临时文件，仅发布核验完整字节。
     * @param base configured root / 配置根
     * @param release exact declaration / 精确声明
     * @param destination new final original / 新最终原包
     * @param progress actual received bytes / 实际收到字节
     * @param cancellation cooperative request / 协作请求
     * @return complete actual original / 完整实际原包
     * @throws Exception if transfer, verification or cleanup fails / 传输、核验或清理失败时 */
    public DownloadResult download(URI base, Release release, Path destination, Consumer<Progress> progress,
                                   CancelToken cancellation) throws Exception {
        ModStoreProtocol.relative(release.packageUri());
        URI origin = root(base), endpoint = origin.resolve(release.packageUri());
        if (!endpoint.getPath().startsWith(origin.getPath()))
            throw new IOException("Package leaves configured store root");
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = target.resolveSibling(".store-" + UUID.randomUUID() + ".download");
        if (!target.getFileName().toString().endsWith(".wtlmod") || Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("New ordinary .wtlmod destination required");
        try (var body = new ModStoreBody(release.bytes(), release.bytes(), temporary, progress)) {
            try {
                var result = fetch(endpoint, body, cancellation);
                if (!result.sha256().equals(release.sha256()))
                    throw new IOException("Package SHA256 differs from catalog");
                ModStoreFacts.cancellation(cancellation);
                WindowsFilePublish.publish(temporary, target, false);
                return new DownloadResult(target);
            } catch (Exception | Error failure) {
                try {
                    body.discard();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }

    /** Rechecks size and digest from the actual ordinary published file. / 从实际普通发布文件复核大小及摘要。
     * @param release exact declaration / 精确声明
     * @param download actual original / 实际原包
     * @return actual integrity result / 实际完整性结果
     * @throws Exception if bytes cannot be inspected / 无法检验字节时 */
    public Verification verify(Release release, DownloadResult download) throws Exception {
        var before = Files.readAttributes(download.file(), java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther() || before.size() != release.bytes())
            return new Verification(false, "Actual package kind or length differs");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var channel = FileChannel.open(download.file(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.allocate(64 * 1024);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        var after = Files.readAttributes(download.file(), java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        boolean unchanged = Objects.equals(before.fileKey(), after.fileKey()) && before.size() == after.size()
            && before.lastModifiedTime().equals(after.lastModifiedTime());
        return new Verification(unchanged && HexFormat.of().formatHex(digest.digest()).equals(release.sha256()),
            "Exact byte integrity only; no publisher signature");
    }

    /** Performs one response whose completion includes the entire bounded body. / 执行一个完成包含全部有界 body 的响应。
     * @param endpoint exact same-origin URI / 精确同源 URI
     * @param body actual bounded output owner / 实际有界输出所有者
     * @param cancellation original cooperative request / 原协作请求
     * @return complete EOF facts / 完整 EOF 事实
     * @throws Exception if original deadline or request fails / 原期限或请求失败时 */
    private ModStoreBody.Result fetch(URI endpoint, ModStoreBody body, CancelToken cancellation) throws Exception {
        ModStoreFacts.cancellation(cancellation);
        long deadline = System.nanoTime() + timeout.toNanos();
        var client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        CompletableFuture<HttpResponse<ModStoreBody.Result>> response = null;
        Throwable originalFailure = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Accept-Encoding", "identity").GET().build();
            response = client.sendAsync(request, info -> {
                if (info.statusCode() != 200 || info.headers().firstValue("Content-Encoding").filter(v -> !v.equalsIgnoreCase("identity")).isPresent())
                    body.onError(new IOException("Store HTTP status or content encoding rejected"));
                return body;
            });
            for (; ; ) {
                ModStoreFacts.cancellation(cancellation);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new HttpTimeoutException("Store complete-body deadline expired");
                try {
                    return response.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS).body();
                } catch (
                    TimeoutException waiting) { /* Recheck complete-body deadline. / 复核完整 body 期限。 */ } catch (
                    ExecutionException failure) {
                    if (failure.getCause() instanceof Exception original) throw original;
                    throw failure;
                }
            }
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            cleanup(client, body, response, originalFailure);
        }
    }

    /** Releases all actual HTTP owners without replacing the original failure. / 释放全部实际 HTTP 所有者，不替换原失败。
     * @param client actual request client / 实际请求客户端
     * @param body actual body owner / 实际 body 所有者
     * @param response original request future / 原请求 future
     * @param original original failure or absent / 原失败或缺省
     * @throws Exception if cleanup alone failed / 仅清理失败时 */
    private static void cleanup(HttpClient client, ModStoreBody body, CompletableFuture<?> response, Throwable original) throws Exception {
        Exception cleanup = null;
        if (response != null && !response.isDone()) response.cancel(true);
        try {
            body.close();
        } catch (IOException failure) {
            cleanup = failure;
        }
        client.shutdownNow();
        try {
            if (!client.awaitTermination(Duration.ofSeconds(20)))
                throw new IOException("Actual HTTP client did not terminate");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (cleanup == null) cleanup = failure;
            else cleanup.addSuppressed(failure);
        }
        if (cleanup != null) {
            if (original == null) throw cleanup;
            original.addSuppressed(cleanup);
        }
    }

    /** Uses the single configuration policy and a directory-shaped URI root. / 使用唯一配置策略及目录形状 URI 根。
     * @param base configured base / 配置基址
     * @return validated normalized root / 已校验规范根
     * @throws IOException if root is invalid / 根无效时 */
    private static URI root(URI base) throws IOException {
        URI validated = ConfigLoader.storeUrl(base.toString()).orElseThrow(() -> new IOException("Store root absent"));
        String path = validated.getRawPath();
        if (path.contains("%") || path.contains("\\") || !validated.normalize().equals(validated))
            throw new IOException("Store root traversal is forbidden");
        return URI.create(validated.toString() + (path.endsWith("/") ? "" : "/"));
    }
}
