package gold.debug.windowstolinux.shared.ai.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * JDK HTTP transport for the exact selected OpenAI-compatible endpoint. / 精确所选 OpenAI 兼容端点的 JDK HTTP 传输。
 */
public final class HttpRoleChatTransport implements RoleChatTransport {
    /**
     * Lazily shares the configured HTTP client across model requests.
     * <p>在模型请求间延迟共享已配置的 HTTP 客户端。
     */
    private static final class ClientHolder {
        /**
         * CLIENT.
         * <p>客户端。
         */
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }
    /**
     * Creates the JDK HTTP transport. / 创建 JDK HTTP 传输。
     */
    public HttpRoleChatTransport() {
    }

    /**
     * Sends role chat result.
     * <p>发送角色Chat结果。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param apiKey api key / api键
     * @param requestBody request body / 请求正文
     * @return constructed or resolved role chat result / 构造或解析得到的角色Chat结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    @Override
    public RoleChatResult send(URI endpoint, char[] apiKey, String requestBody)
            throws IOException, InterruptedException {
        Objects.requireNonNull(apiKey, "apiKey");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + new String(apiKey)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response = ClientHolder.CLIENT.send(request, ignored -> new LimitedHttpBodySubscriber());
        return new RoleChatResult(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }
}
