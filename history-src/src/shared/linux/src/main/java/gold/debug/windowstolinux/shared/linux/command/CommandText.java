package gold.debug.windowstolinux.shared.linux.command;

import java.util.Map;

/** Literal shell arguments and bounded protocol parsing. / Shell 字面参数与有界协议解析。 */
public final class CommandText {
    /** No instances. / 不创建实例。 */
    private CommandText() {
    }

    /**
     * Parses trimmed key-value lines at the first equals sign, retaining the first value for duplicate keys.
     * <p>按首个等号解析去除首尾空白的键值行，并为重复键保留首个值。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return the operation result collection / 操作结果集合
     */
    public static Map<String, String> lines(String text) {
        return text.lines().map(String::trim).filter(line -> line.contains("=")).map(line -> line.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts[1],
                        (first, ignored) -> first));
    }

    /**
     * Returns the trimmed first line, or an empty string for empty input.
     * <p>返回去除首尾空白的首行；输入为空时返回空字符串。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return the operation result / 操作结果
     */
    public static String firstLine(String text) {
        return text.lines().findFirst().map(String::trim).orElse("");
    }

    /**
     * Parses long.
     * <p>解析长整型。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return the operation result / 操作结果
     */
    public static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            // Invalid bounded numeric evidence is treated as unavailable, not propagated to users. / 无效的有界数字证据视为不可用，不向用户传播。
            return 0;
        }
    }

    /**
     * Quotes a literal argument for the fixed command-rendering boundary.
     * <p>为固定命令渲染边界引用字面参数。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return the operation result / 操作结果
     */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

}
