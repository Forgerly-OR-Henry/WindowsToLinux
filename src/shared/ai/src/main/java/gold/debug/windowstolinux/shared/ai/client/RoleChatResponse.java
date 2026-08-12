package gold.debug.windowstolinux.shared.ai.client;

/** Bounded HTTP response returned by the selected-provider transport. / 所选提供者传输返回的有界 HTTP 响应。 */
public record RoleChatResponse(int statusCode, String body) { }
