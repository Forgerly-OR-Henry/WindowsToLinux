package gold.debug.windowstolinux.shared.model.message;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A user-facing message described without coupling domain code to a display language. <p>The desktop client resolves {@link #key()} and its named {@link #arguments()} through its locale catalog. Values in {@code arguments} are deliberately rendered as plain text; they are not format strings.</p>
 *
 * <p>不将领域代码与显示语言耦合的用户可见消息。<p>桌面客户端通过区域设置目录解析 {@link #key()} 及其命名 {@link #arguments()}。{@code arguments} 中的值被刻意按纯文本呈现，而不是格式字符串。</p>
 *
 * @param key the {@code key} value / {@code key} 值
 * @param arguments the {@code arguments} value / {@code arguments} 值
 */
public record LocalizedMessage(String key, Map<String, String> arguments) {
    /**
     * Creates a {@code LocalizedMessage} instance.
     *
     * <p>创建 {@code LocalizedMessage} 实例。
     *
     * @param key the {@code key} value / {@code key} 值
     * @param arguments the {@code arguments} value / {@code arguments} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
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
     * <p>通过 {@code of} 创建值。
     *
     * @param key the {@code key} value / {@code key} 值
     * @return the operation result / 操作结果
     */
    public static LocalizedMessage of(String key) {
        return new LocalizedMessage(key, Map.of());
    }

    /**
     * Creates a value through {@code of}.
     *
     * <p>通过 {@code of} 创建值。
     *
     * @param key the {@code key} value / {@code key} 值
     * @param argumentName the {@code argumentName} value / {@code argumentName} 值
     * @param argumentValue the {@code argumentValue} value / {@code argumentValue} 值
     * @return the operation result / 操作结果
     */
    public static LocalizedMessage of(String key, String argumentName, Object argumentValue) {
        return new LocalizedMessage(key, Map.of(argumentName, String.valueOf(argumentValue)));
    }

    /**
     * Creates a value through {@code of}.
     *
     * <p>通过 {@code of} 创建值。
     *
     * @param key the {@code key} value / {@code key} 值
     * @param arguments the {@code arguments} value / {@code arguments} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static LocalizedMessage of(String key, Map<String, ?> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        arguments.forEach((name, value) -> normalized.put(name, String.valueOf(value)));
        return new LocalizedMessage(key, normalized);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
