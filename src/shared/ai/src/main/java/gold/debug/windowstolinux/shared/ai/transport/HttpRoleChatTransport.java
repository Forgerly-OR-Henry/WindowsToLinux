package gold.debug.windowstolinux.shared.ai.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** JDK HTTP transport for the exact selected OpenAI-compatible endpoint. / 精确所选 OpenAI 兼容端点的 JDK HTTP 传输。 */
public final class HttpRoleChatTransport implements RoleChatTransport {
    private static final class ClientHolder {
        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }
    /** Creates the JDK HTTP transport. / 创建 JDK HTTP 传输。 */
    public HttpRoleChatTransport() {
    }

    /** Performs the {@code send} operation. / 执行 {@code send} 操作。 */
    @Override public RoleChatResult send(URI endpoint, char[] apiKey, String requestBody)
            throws IOException, InterruptedException {
        Objects.requireNonNull(apiKey, "apiKey");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + new String(apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response = ClientHolder.CLIENT.send(request, ignored -> new LimitedHttpBodySubscriber());
        return new RoleChatResult(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }
}
