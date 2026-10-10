package gold.debug.windowstolinux.appapi.ai.model;

import java.util.Objects;

/** A result for one original call, including validation rejection. / 一个原调用的结果，包含参数校验拒绝。
 * @param callId original call identity / 原调用标识
 * @param text bounded actual result / 有界实际结果
 * @param error whether this call was rejected or failed / 此调用是否被拒绝或失败 */
public record AiToolResult(String callId, String text, boolean error) {
    public AiToolResult {
        Objects.requireNonNull(callId);
        Objects.requireNonNull(text);
        if (!callId.matches("[A-Za-z0-9_-]{1,128}") || text.length() > 1024 * 1024)
            throw new IllegalArgumentException("Invalid native tool result");
    }
}
