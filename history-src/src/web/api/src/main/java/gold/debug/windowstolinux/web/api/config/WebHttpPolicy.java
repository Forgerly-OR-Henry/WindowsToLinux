package gold.debug.windowstolinux.web.api.config;

import java.time.Duration;

/**
 * Defines allowed origins and bounded HTTP request limits for the local Web service.
 * <p>定义本地 Web 服务允许的来源及有界 HTTP 请求限制。
 *
 * @param requests requests / 请求集合
 * @param streams streams / 流集合
 * @param uploads uploads / 上传集合
 * @param jsonBytes json bytes / JSON字节
 * @param queryCharacters query characters / 查询字符集合
 * @param heartbeat heartbeat / 心跳
 * @param eventPoll event poll / 事件Poll
 * @param streamTimeout stream timeout / 流超时
 */
public record WebHttpPolicy(int requests, int streams, int uploads, int jsonBytes, int queryCharacters,
        Duration heartbeat, Duration eventPoll, Duration streamTimeout) {
    /**
     * Validates and binds the inputs required by web http policy.
     * <p>校验并绑定WebHTTP策略所需输入。
     *
     * @param requests requests / 请求集合
     * @param streams streams / 流集合
     * @param uploads uploads / 上传集合
     * @param jsonBytes json bytes / JSON字节
     * @param queryCharacters query characters / 查询字符集合
     * @param heartbeat heartbeat / 心跳
     * @param eventPoll event poll / 事件Poll
     * @param streamTimeout stream timeout / 流超时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public WebHttpPolicy {
        if (requests < 1 || streams < 1 || uploads < 1 || streams + uploads > requests || jsonBytes < 1
                || jsonBytes > 1_048_576 || queryCharacters < 1 || heartbeat == null || heartbeat.isNegative()
                || heartbeat.isZero() || eventPoll == null || eventPoll.isNegative() || eventPoll.isZero()
                || streamTimeout == null || streamTimeout.isNegative())
            throw new IllegalArgumentException("Invalid w2l.http limits");
    }
}
