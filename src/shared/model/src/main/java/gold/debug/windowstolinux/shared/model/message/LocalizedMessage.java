package gold.debug.windowstolinux.shared.model.message;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A user-facing message described without coupling domain code to a display language. <p>The desktop client resolves {@link #key()} and its named {@link #arguments()} through its locale catalog. Values in {@code arguments} are deliberately rendered as plain text; they are not format strings.</p>
 *
 *  <p>不将领域代码与显示语言耦合的用户可见消息。<p>桌面客户端通过区域设置目录解析 {@link #key()} 及其命名 {@link #arguments()}。{@code arguments} 中的值被刻意按纯文本呈现，而不是格式字符串。</p>
 *
 * @param key lookup key within the current contract / 当前契约内的查找键
 * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
 */
public record LocalizedMessage(String key, Map<String, String> arguments) {
    /**
     * Validates and binds the inputs required by localized message.
     * <p>校验并绑定本地化消息所需输入。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public LocalizedMessage {
        key = requireText(key, "key");
        Objects.requireNonNull(arguments, "arguments");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        arguments.forEach((name, value) -> copy.put(requireText(name, "argument name"),
                Objects.requireNonNull(value, "argument value")));
        arguments = Map.copyOf(copy);
    }

    /**
     * Creates a value through {@code of}.
     *
     *  <p>通过 {@code of} 创建值。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the operation result / 操作结果
     */
    public static LocalizedMessage of(String key) {
        return new LocalizedMessage(key, Map.of());
    }

    /**
     * Creates a value through {@code of}.
     *
     *  <p>通过 {@code of} 创建值。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param argumentName argument name / 参数名称
     * @param argumentValue argument value / 参数内容
     * @return the operation result / 操作结果
     */
    public static LocalizedMessage of(String key, String argumentName, Object argumentValue) {
        return new LocalizedMessage(key, Map.of(argumentName, String.valueOf(argumentValue)));
    }

    /**
     * Creates a value through {@code of}.
     *
     *  <p>通过 {@code of} 创建值。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static LocalizedMessage of(String key, Map<String, ?> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        arguments.forEach((name, value) -> normalized.put(name, String.valueOf(value)));
        return new LocalizedMessage(key, normalized);
    }

    /**
     * Requires nonblank text and returns the original content.
     * <p>要求非空白文本，并返回原始内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require text text / 要求文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
