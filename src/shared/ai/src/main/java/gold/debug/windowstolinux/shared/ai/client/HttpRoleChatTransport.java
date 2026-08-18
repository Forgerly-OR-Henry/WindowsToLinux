package gold.debug.windowstolinux.shared.ai.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** JDK HTTP transport for the exact selected OpenAI-compatible endpoint. / 精确所选 OpenAI 兼容端点的 JDK HTTP 传输。 */
final class HttpRoleChatTransport implements RoleChatTransport {
    /** Performs the {@code send} operation. / 执行 {@code send} 操作。 */
    @Override public RoleChatResponse send(URI endpoint, char[] apiKey, String requestBody)
            throws IOException, InterruptedException {
        Objects.requireNonNull(apiKey, "apiKey");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + new String(apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new RoleChatResponse(response.statusCode(), response.body());
    }
}
