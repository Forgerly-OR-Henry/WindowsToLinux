package gold.debug.windowstolinux.shared.ai.parser;

import java.util.ArrayList;
import java.util.List;

import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.RoleAdviceAssessment;

/**
 * Strictly parses the exact fixed advisory JSON object and rejects extra fields. / 严格解析固定建议 JSON 对象并拒绝额外字段。
 */
public final class RoleAdviceParser {
    /**
     * Parses an exact decision, summary, and findings object. / 解析精确的决策、摘要与发现对象。
     *
     * @param json JSON serialization / JSON 序列化
     * @return an exact decision, summary, and findings object / 精确的决策、摘要与发现对象
     */
    public RoleAdviceAssessment parse(String json) {
        if (json == null || json.length() > 8_192)
            throw invalid();
        Cursor cursor = new Cursor(json);
        cursor.symbol('{');
        cursor.name("decision");
        AiAdviceDecision decision;
        try {
            decision = AiAdviceDecision.valueOf(cursor.string());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        cursor.comma();
        cursor.name("summary");
        String summary = cursor.string();
        cursor.comma();
        cursor.name("findings");
        List<String> findings = cursor.stringArray();
        cursor.symbol('}');
        cursor.end();
        try {
            return new RoleAdviceAssessment(decision, summary, findings);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /**
     * Creates the owning module's failure for rejected input or evidence.
     * <p>为被拒绝输入或证据创建所属模块的失败。
     *
     * @return the owning module's failure for rejected input or evidence / 为被拒绝输入或证据创建所属模块的失败
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AI role output does not match the fixed schema");
    }

    /**
     * Tracks parsing position inside bounded model advice text.
     * <p>跟踪有界模型建议文本中的解析位置。
     */
    private static final class Cursor {
        /**
         * Candidate content accepted or rejected by this contract.
         * <p>由当前契约接收或拒绝的候选内容。
         */
        private final String value;

        /**
         * Index.
         * <p>索引。
         */
        private int index;

        /**
         * Binds the supplied dependencies and state for cursor.
         * <p>为游标绑定传入的依赖及状态。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         */
        private Cursor(String value) {
            this.value = value;
        }

        /**
         * Requires the next JSON string to match the expected field name, followed by a colon.
         * <p>要求下一个 JSON 字符串匹配预期字段名，且其后为冒号。
         *
         * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
         */
        private void name(String expected) {
            if (!expected.equals(string()))
                throw invalid();
            symbol(':');
        }

        /**
         * Consumes the required comma separator.
         * <p>消费必需的逗号分隔符。
         */
        private void comma() {
            symbol(',');
        }

        /**
         * Checks symbol syntax and bounds before returning the admitted content.
         * <p>在返回已准入内容前检查符号语法及边界。
         *
         * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
         */
        private void symbol(char expected) {
            whitespace();
            if (index >= value.length() || value.charAt(index++) != expected)
                throw invalid();
        }

        /**
         * Reads a JSON string with supported escapes, rejecting malformed Unicode and unterminated text.
         * <p>读取具有受支持转义的 JSON 字符串，并拒绝格式无效 Unicode 及未终止文本。
         *
         * @return a JSON string with supported escapes, rejecting malformed Unicode and unterminated text / 具有受支持转义的 JSON 字符串，并拒绝格式无效 Unicode 及未终止文本
         */
        private String string() {
            whitespace();
            if (index >= value.length() || value.charAt(index++) != '"')
                throw invalid();
            StringBuilder result = new StringBuilder();
            while (index < value.length()) {
                char current = value.charAt(index++);
                if (current == '"')
                    return result.toString();
                if (current < 0x20)
                    throw invalid();
                if (current != '\\') {
                    result.append(current);
                    continue;
                }
                if (index >= value.length())
                    throw invalid();
                char escaped = value.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicode());
                    default -> throw invalid();
                }
            }
            throw invalid();
        }

        /**
         * Checks unicode syntax and bounds before returning the admitted content.
         * <p>在返回已准入内容前检查Unicode语法及边界。
         *
         * @return constructed or resolved char / 构造或解析得到的char
         */
        private char unicode() {
            if (index + 4 > value.length())
                throw invalid();
            try {
                char parsed = (char) Integer.parseInt(value.substring(index, index + 4), 16);
                index += 4;
                return parsed;
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        }

        /**
         * Checks string array syntax and bounds before returning the admitted content.
         * <p>在返回已准入内容前检查字符串数组语法及边界。
         *
         * @return constructed or resolved list / 构造或解析得到的列表
         */
        private List<String> stringArray() {
            symbol('[');
            whitespace();
            if (index < value.length() && value.charAt(index) == ']') {
                index++;
                return List.of();
            }
            List<String> result = new ArrayList<>();
            while (result.size() < 5) {
                result.add(string());
                whitespace();
                if (index < value.length() && value.charAt(index) == ']') {
                    index++;
                    return List.copyOf(result);
                }
                comma();
            }
            throw invalid();
        }

        /**
         * Checks end syntax and bounds before returning the admitted content.
         * <p>在返回已准入内容前检查结束语法及边界。
         */
        private void end() {
            whitespace();
            if (index != value.length())
                throw invalid();
        }

        /**
         * Advances the cursor past consecutive whitespace characters.
         * <p>将游标移过连续空白字符。
         */
        private void whitespace() {
            while (index < value.length() && Character.isWhitespace(value.charAt(index)))
                index++;
        }
    }
}
