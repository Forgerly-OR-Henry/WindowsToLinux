package gold.debug.windowstolinux.shared.ai.parser;

import gold.debug.windowstolinux.shared.ai.AiAnalysisException;
import gold.debug.windowstolinux.shared.ai.AiAnalysisFailureType;
import gold.debug.windowstolinux.shared.ai.AiStructuralAssessment;

/**
 * Parses one bounded Chat Completions response without accepting arbitrary JSON structures.
 *
 *  <p>解析单个有界 Chat Completions 响应，不接受任意 JSON 结构。
 */
public final class ChatCompletionResponseParser {
    /**
     * MAX RESPONSE CHARS.
     * <p>最大响应CHARS。
     */
    private static final int MAX_RESPONSE_CHARS = 8_192;

    /**
     * Validates the bounded provider response and extracts the allowed structural assessment, rejecting malformed or incomplete advice.
     * <p>校验有界提供者响应并提取允许的结构评估，拒绝格式无效或不完整建议。
     *
     * @param responseBody response body / 响应正文
     * @return the operation result / 操作结果
     * @throws AiAnalysisException if the ai analysis boundary rejects the operation / AI分析边界拒绝当前操作时
     */
    public AiStructuralAssessment parse(String responseBody) throws AiAnalysisException {
        if (responseBody == null || responseBody.length() > 512 * 1024) {
            throw failure(AiAnalysisFailureType.RESPONSE_INVALID,
                    "AI response is null or exceeds the accepted size limit");
        }
        int field = responseBody.indexOf("\"content\"");
        if (field < 0) {
            throw failure(AiAnalysisFailureType.RESPONSE_CONTENT_MISSING,
                    "AI response does not contain an explanation field");
        }
        int colon = responseBody.indexOf(':', field + 9);
        if (colon < 0) {
            throw failure(AiAnalysisFailureType.RESPONSE_FORMAT_INVALID,
                    "AI response has an invalid content field format");
        }
        int quote = colon + 1;
        while (quote < responseBody.length() && Character.isWhitespace(responseBody.charAt(quote))) {
            quote++;
        }
        if (quote >= responseBody.length() || responseBody.charAt(quote) != '"') {
            throw failure(AiAnalysisFailureType.RESPONSE_NOT_STRING, "AI response explanation is not a string");
        }
        StringBuilder value = new StringBuilder();
        for (int index = quote + 1; index < responseBody.length(); index++) {
            char current = responseBody.charAt(index);
            if (current == '"') {
                String text = value.toString().trim();
                if (text.isBlank()) {
                    throw failure(AiAnalysisFailureType.RESPONSE_EMPTY, "AI response contains an empty explanation");
                }
                return new AiStructuralAssessment(
                        text.length() <= MAX_RESPONSE_CHARS ? text : text.substring(0, MAX_RESPONSE_CHARS) + "…");
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
                        throw failure(AiAnalysisFailureType.UNICODE_ESCAPE_INVALID,
                                "AI response contains an invalid Unicode escape");
                    }
                    try {
                        value.append((char) Integer.parseInt(responseBody.substring(index + 1, index + 5), 16));
                    } catch (NumberFormatException exception) {
                        throw failure(AiAnalysisFailureType.UNICODE_ESCAPE_INVALID,
                                "AI response contains an invalid Unicode escape", exception);
                    }
                    index += 4;
                }
                default -> throw failure(AiAnalysisFailureType.STRING_ESCAPE_INVALID,
                        "AI response contains an invalid string escape");
            }
        }
        throw failure(AiAnalysisFailureType.UNTERMINATED_STRING, "AI response contains an unterminated string");
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static AiAnalysisException failure(AiAnalysisFailureType type, String diagnostic) {
        return AiAnalysisException.create(type, diagnostic);
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static AiAnalysisException failure(AiAnalysisFailureType type, String diagnostic, Throwable cause) {
        return AiAnalysisException.create(type, diagnostic, cause);
    }
}
