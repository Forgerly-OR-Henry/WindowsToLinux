package gold.debug.windowstolinux.shared.ai.generation.prompt;

import java.util.Locale;

/**
 * Supported languages for optional AI-generated explanations.
 *
 *  <p>可选 AI 生成解释所支持的语言。
 */
public enum AiResponseLanguageType {
    /**
     * Represents the {@code ENGLISH} option.
     *
     *  <p>表示 {@code ENGLISH} 选项。
     */
    ENGLISH("English"),
    /**
     * Represents the {@code SIMPLIFIED_CHINESE} option.
     *
     *  <p>表示 {@code SIMPLIFIED_CHINESE} 选项。
     */
    SIMPLIFIED_CHINESE("Simplified Chinese");

    /**
     * Prompt name.
     * <p>提示名称。
     */
    private final String promptName;

    /**
     * Binds the supplied dependencies and state for ai response language type.
     * <p>为AI响应语言类型绑定传入的依赖及状态。
     *
     * @param promptName prompt name / 提示名称
     */
    AiResponseLanguageType(String promptName) {
        this.promptName = promptName;
    }

    /**
     * Returns prompt name.
     * <p>返回提示名称。
     *
     * @return the operation result / 操作结果
     */
    public String promptName() {
        return promptName;
    }

    /**
     * Creates a value through {@code fromLanguageTag}.
     *
     *  <p>通过 {@code fromLanguageTag} 创建值。
     *
     * @param languageTag language tag / 语言标签
     * @return the operation result / 操作结果
     */
    public static AiResponseLanguageType fromLanguageTag(String languageTag) {
        Locale locale = Locale.forLanguageTag(languageTag == null ? "" : languageTag);
        return locale.getLanguage().equals(Locale.CHINESE.getLanguage()) ? SIMPLIFIED_CHINESE : ENGLISH;
    }
}
