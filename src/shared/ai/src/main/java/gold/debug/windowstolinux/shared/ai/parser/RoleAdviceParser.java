package gold.debug.windowstolinux.shared.ai.parser;

import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.RoleAdviceAssessment;

import java.util.ArrayList;
import java.util.List;

/** Strictly parses the exact fixed advisory JSON object and rejects extra fields. / 严格解析固定建议 JSON 对象并拒绝额外字段。 */
public final class RoleAdviceParser {
    /** Parses an exact decision, summary, and findings object. / 解析精确的决策、摘要与发现对象。 */
    public RoleAdviceAssessment parse(String json) {
        if (json == null || json.length() > 8_192) throw invalid();
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

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AI role output does not match the fixed schema");
    }

    private static final class Cursor {
        private final String value;
        private int index;

        private Cursor(String value) { this.value = value; }

        private void name(String expected) {
            if (!expected.equals(string())) throw invalid();
            symbol(':');
        }

        private void comma() { symbol(','); }

        private void symbol(char expected) {
            whitespace();
            if (index >= value.length() || value.charAt(index++) != expected) throw invalid();
        }

        private String string() {
            whitespace();
            if (index >= value.length() || value.charAt(index++) != '"') throw invalid();
            StringBuilder result = new StringBuilder();
            while (index < value.length()) {
                char current = value.charAt(index++);
                if (current == '"') return result.toString();
                if (current < 0x20) throw invalid();
                if (current != '\\') {
                    result.append(current);
                    continue;
                }
                if (index >= value.length()) throw invalid();
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

        private char unicode() {
            if (index + 4 > value.length()) throw invalid();
            try {
                char parsed = (char) Integer.parseInt(value.substring(index, index + 4), 16);
                index += 4;
                return parsed;
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        }

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

        private void end() {
            whitespace();
            if (index != value.length()) throw invalid();
        }

        private void whitespace() {
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        }
    }
}
