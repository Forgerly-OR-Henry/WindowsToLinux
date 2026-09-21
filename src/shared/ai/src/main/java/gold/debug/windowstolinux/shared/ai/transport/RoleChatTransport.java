package gold.debug.windowstolinux.shared.ai.transport;

import java.io.IOException;
import java.net.URI;

/**
 * Transport for one already selected provider; it has no provider-discovery or fallback API. / 单个已选提供者的传输；不提供发现或回退 API。
 */
@FunctionalInterface
public interface RoleChatTransport {
    /**
     * Sends one request to the exact endpoint. / 向精确端点发送一次请求。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param apiKey api key / api键
     * @param requestBody request body / 请求正文
     * @return constructed or resolved role chat result / 构造或解析得到的角色Chat结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    RoleChatResult send(URI endpoint, char[] apiKey, String requestBody) throws IOException, InterruptedException;
}
