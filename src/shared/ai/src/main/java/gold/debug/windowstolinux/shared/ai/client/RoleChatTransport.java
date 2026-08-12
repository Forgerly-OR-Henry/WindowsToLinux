package gold.debug.windowstolinux.shared.ai.client;

import java.io.IOException;
import java.net.URI;

/** Transport for one already selected provider; it has no provider-discovery or fallback API. / 单个已选提供者的传输；不提供发现或回退 API。 */
@FunctionalInterface
public interface RoleChatTransport {
    /** Sends one request to the exact endpoint. / 向精确端点发送一次请求。 */
    RoleChatResponse send(URI endpoint, char[] apiKey, String requestBody) throws IOException, InterruptedException;
}
