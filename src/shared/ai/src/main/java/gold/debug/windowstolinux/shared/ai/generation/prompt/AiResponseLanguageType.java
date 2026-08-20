package gold.debug.windowstolinux.shared.ai.generation.prompt;

import java.util.Locale;

/**
 * Supported languages for optional AI-generated explanations.
 *
 * <p>可选 AI 生成解释所支持的语言。
 */
public enum AiResponseLanguageType {
    /**
     * Represents the {@code ENGLISH} option.
     *
     * <p>表示 {@code ENGLISH} 选项。
     */
    ENGLISH("English"),
    /**
     * Represents the {@code SIMPLIFIED_CHINESE} option.
     *
     * <p>表示 {@code SIMPLIFIED_CHINESE} 选项。
     */
    SIMPLIFIED_CHINESE("Simplified Chinese");

    /** Represents the {@code promptName} value. / 表示 {@code promptName} 值。 */
    private final String promptName;

    AiResponseLanguageType(String promptName) {
        this.promptName = promptName;
    }

    /**
     * Performs the {@code promptName} operation.
     *
     * <p>执行 {@code promptName} 操作。
     *
     * @return the operation result / 操作结果
     */
    public String promptName() {
        return promptName;
    }

    /**
     * Creates a value through {@code fromLanguageTag}.
     *
     * <p>通过 {@code fromLanguageTag} 创建值。
     *
     * @param languageTag the {@code languageTag} value / {@code languageTag} 值
     * @return the operation result / 操作结果
     */
    public static AiResponseLanguageType fromLanguageTag(String languageTag) {
        Locale locale = Locale.forLanguageTag(languageTag == null ? "" : languageTag);
        return locale.getLanguage().equals(Locale.CHINESE.getLanguage())
                ? SIMPLIFIED_CHINESE : ENGLISH;
    }
}
