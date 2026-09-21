
package gold.debug.windowstolinux.shared.config.contract.definition;

import gold.debug.windowstolinux.shared.config.ConfigurationException;
import gold.debug.windowstolinux.shared.config.ConfigurationFailureType;

import java.util.Objects;

/**
 * A typed non-secret configuration value that cannot carry a shell fragment.
 *
 *  <p>类型化的非秘密配置值，不能携带 Shell 片段。
 */
public sealed interface ConfigurationValue permits ConfigurationValue.Text, ConfigurationValue.Number, ConfigurationValue.Flag {
    /**
     * Renders the canonical value used only for immutable snapshot hashing.
     *
     *  <p>渲染仅用于不可变快照摘要的规范值。
     *
     * @return the canonical non-secret representation / 规范的非秘密表示
     */
    String canonicalValue();

    /**
     * A bounded text value without line breaks or shell control characters.
     *
     *  <p>不含换行或 Shell 控制字符的有界文本值。
     *
     * @param value the value / 值
     */
    record Text(String value) implements ConfigurationValue {
        /**
         * Creates a {@code Text} value.
         *
         *  <p>创建 {@code Text} 值。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Text {
            value = Objects.requireNonNull(value, "value").trim();
            if (value.isBlank() || value.length() > 1024 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                    || value.indexOf('\0') >= 0) {
                throw ConfigurationException.create(ConfigurationFailureType.TEXT_VALUE_INVALID, "Text configuration values must be bounded single-line text");
            }
        }

        /**
         * Returns candidate content accepted or rejected by this contract.
         * <p>返回由当前契约接收或拒绝的候选内容。
         *
         * @return candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         */
        @Override
        public String canonicalValue() {
            return value;
        }
    }

    /**
     * An integer value with an explicit inclusive range.
     *
     *  <p>具有显式闭区间的整数值。
     *
     * @param value the value / 值
     */
    record Number(long value) implements ConfigurationValue {
        /**
         * Validates and produces canonical value for the next contract boundary.
         * <p>校验并生成供下一契约边界使用的规范内容。
         *
         * @return canonical value text / 规范内容文本
         */
        @Override
        public String canonicalValue() {
            return Long.toString(value);
        }
    }

    /**
     * A boolean value.
     *
     *  <p>布尔值。
     *
     * @param value the value / 值
     */
    record Flag(boolean value) implements ConfigurationValue {
        /**
         * Validates and produces canonical value for the next contract boundary.
         * <p>校验并生成供下一契约边界使用的规范内容。
         *
         * @return canonical value text / 规范内容文本
         */
        @Override
        public String canonicalValue() {
            return Boolean.toString(value);
        }
    }
}
