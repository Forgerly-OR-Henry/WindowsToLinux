package gold.debug.windowstolinux.shared.ai.parser;

import gold.debug.windowstolinux.shared.ai.client.AiAnalysisException;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Parses one bounded Chat Completions response without accepting arbitrary JSON structures.
 *
 * <p>解析单个有界 Chat Completions 响应，不接受任意 JSON 结构。
 */
public final class ChatCompletionsResponseParser {
    private static final int MAX_RESPONSE_CHARS = 8_192;

    /**
     * Performs the {@code parse} operation.
     *
     * <p>执行 {@code parse} 操作。
     *
     * @param responseBody the {@code responseBody} value / {@code responseBody} 值
     * @return the operation result / 操作结果
     * @throws AiAnalysisException if the operation cannot be completed / 无法完成操作时
     */
    public AiStructuralAnalysis parse(String responseBody) throws AiAnalysisException {
        if (responseBody == null || responseBody.length() > 512 * 1024) {
            throw failure("ai.error.responseInvalid", "AI response is null or exceeds the accepted size limit");
        }
        int field = responseBody.indexOf("\"content\"");
        if (field < 0) {
            throw failure("ai.error.responseContentMissing", "AI response does not contain an explanation field");
        }
        int colon = responseBody.indexOf(':', field + 9);
        if (colon < 0) {
            throw failure("ai.error.responseFormatInvalid", "AI response has an invalid content field format");
        }
        int quote = colon + 1;
        while (quote < responseBody.length() && Character.isWhitespace(responseBody.charAt(quote))) {
            quote++;
        }
        if (quote >= responseBody.length() || responseBody.charAt(quote) != '"') {
            throw failure("ai.error.responseNotString", "AI response explanation is not a string");
        }
        StringBuilder value = new StringBuilder();
        for (int index = quote + 1; index < responseBody.length(); index++) {
            char current = responseBody.charAt(index);
            if (current == '"') {
                String text = value.toString().trim();
                if (text.isBlank()) {
                    throw failure("ai.error.responseEmpty", "AI response contains an empty explanation");
                }
                return new AiStructuralAnalysis(text.length() <= MAX_RESPONSE_CHARS
                        ? text : text.substring(0, MAX_RESPONSE_CHARS) + "…");
            }
            if (current != '\\') {
                value.append(current);
                continue;
            }
            if (++index >= responseBody.length()) {
                break;
            }
            char escaped = responseBody.charAt(index);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (index + 4 >= responseBody.length()) {
                        throw failure("ai.error.unicodeEscapeInvalid",
                                "AI response contains an invalid Unicode escape");
                    }
                    try {
                        value.append((char) Integer.parseInt(responseBody.substring(index + 1, index + 5), 16));
                    } catch (NumberFormatException exception) {
                        throw failure("ai.error.unicodeEscapeInvalid",
                                "AI response contains an invalid Unicode escape", exception);
                    }
                    index += 4;
                }
                default -> throw failure("ai.error.stringEscapeInvalid",
                        "AI response contains an invalid string escape");
            }
        }
        throw failure("ai.error.unterminatedString", "AI response contains an unterminated string");
    }

    private static AiAnalysisException failure(String key, String diagnostic) {
        return new AiAnalysisException(LocalizedMessage.of(key), diagnostic);
    }

    private static AiAnalysisException failure(String key, String diagnostic, Throwable cause) {
        return new AiAnalysisException(LocalizedMessage.of(key), diagnostic, cause);
    }
}
