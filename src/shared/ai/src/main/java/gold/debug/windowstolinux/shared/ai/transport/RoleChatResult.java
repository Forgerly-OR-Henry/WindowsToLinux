package gold.debug.windowstolinux.shared.ai.transport;

/**
 * Bounded HTTP response returned by the selected-provider transport. / 所选提供者传输返回的有界 HTTP 响应。
 *
 * @param statusCode status code / 状态代码
 * @param body body / 正文
 */
public record RoleChatResult(int statusCode, String body) {
}
