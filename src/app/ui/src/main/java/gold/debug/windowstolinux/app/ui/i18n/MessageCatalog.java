package gold.debug.windowstolinux.app.ui.i18n;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/**
 * Resolves application message keys and non-recursive named placeholders from a locale bundle.
 *
 *  <p>从区域设置资源包解析应用消息键和非递归命名占位符。
 */
public final class MessageCatalog {
    /**
     * Exposes the {@code ENGLISH_TAG} constant.
     *
     *  <p>公开 {@code ENGLISH_TAG} 常量。
     */
    public static final String ENGLISH_TAG = "en";

    /**
     * Exposes the {@code SIMPLIFIED_CHINESE_TAG} constant.
     *
     *  <p>公开 {@code SIMPLIFIED_CHINESE_TAG} 常量。
     */
    public static final String SIMPLIFIED_CHINESE_TAG = "zh-CN";

    /**
     * SUPPORTED LANGUAGE TAGS.
     * <p>受支持语言标签集合。
     */
    private static final Set<String> SUPPORTED_LANGUAGE_TAGS = Set.of(ENGLISH_TAG, SIMPLIFIED_CHINESE_TAG);

    /**
     * BUNDLE NAME.
     * <p>资源包名称。
     */
    private static final String BUNDLE_NAME = "gold.debug.windowstolinux.app.ui.i18n.messages.Messages";

    /**
     * Pattern recognizing pattern matching named localization placeholders.
     * <p>用于识别匹配具名本地化占位符的模式的匹配模式。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_.-]*)}");

    /**
     * Locale.
     * <p>区域。
     */
    private final Locale locale;

    /**
     * Bundle.
     * <p>资源包。
     */
    private final ResourceBundle bundle;

    /**
     * Validates and binds the inputs required by message catalog.
     * <p>校验并绑定消息目录所需输入。
     *
     * @param locale locale / 区域
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private MessageCatalog(Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
        this.bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /**
     * Builds message catalog from the supplied for language tag inputs.
     * <p>根据所提供对应语言标签输入构建消息目录。
     *
     * @param languageTag language tag / 语言标签
     * @return the operation result / 操作结果
     */
    public static MessageCatalog forLanguageTag(String languageTag) {
        String normalized = normalizeLanguageTag(languageTag);
        Locale locale = SIMPLIFIED_CHINESE_TAG.equals(normalized) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        return new MessageCatalog(locale);
    }

    /**
     * Normalizes language tag.
     * <p>规范化语言标签。
     *
     * @param languageTag language tag / 语言标签
     * @return the operation result / 操作结果
     */
    public static String normalizeLanguageTag(String languageTag) {
        Locale locale = Locale.forLanguageTag(languageTag == null ? "" : languageTag);
        if (SIMPLIFIED_CHINESE_TAG.equalsIgnoreCase(locale.toLanguageTag())) {
            return SIMPLIFIED_CHINESE_TAG;
        }
        return ENGLISH_TAG;
    }

    /**
     * Selects Simplified Chinese for Chinese system locales and English otherwise.
     * <p>中文系统区域选择简体中文，其他区域选择英文。
     *
     * @param systemLocale system locale / 系统区域
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String defaultLanguageTag(Locale systemLocale) {
        Objects.requireNonNull(systemLocale, "systemLocale");
        return systemLocale.getLanguage().equals(Locale.CHINESE.getLanguage()) ? SIMPLIFIED_CHINESE_TAG : ENGLISH_TAG;
    }

    /**
     * Returns SUPPORTED LANGUAGE TAGS.
     * <p>返回受支持语言标签集合。
     *
     * @return the operation result collection / 操作结果集合
     */
    public static Set<String> supportedLanguageTags() {
        return SUPPORTED_LANGUAGE_TAGS;
    }

    /**
     * Resolves a localized message and substitutes its named arguments.
     * <p>解析本地化消息并替换其具名参数。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the operation result / 操作结果
     */
    public String text(String key) {
        return text(new LocalizedMessage(key, Map.of()));
    }

    /**
     * Resolves a localized message and substitutes its named arguments.
     * <p>解析本地化消息并替换其具名参数。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @return the operation result / 操作结果
     */
    public String text(String key, Map<String, ?> arguments) {
        return text(LocalizedMessage.of(key, arguments));
    }

    /**
     * Resolves a localized message and substitutes its named arguments.
     * <p>解析本地化消息并替换其具名参数。
     *
     * @param message localized explanation / 本地化说明
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public String text(LocalizedMessage message) {
        Objects.requireNonNull(message, "message");
        String pattern = lookup(message.key());
        Matcher matcher = PLACEHOLDER.matcher(pattern);
        StringBuffer rendered = new StringBuffer();
        Set<String> missing = new LinkedHashSet<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = message.arguments().get(name);
            if (value == null) {
                missing.add(name);
                value = matcher.group(0);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("missing message arguments for " + message.key() + ": " + missing);
        }
        return rendered.toString();
    }

    /**
     * Returns locale.
     * <p>返回区域。
     *
     * @return the operation result / 操作结果
     */
    public Locale locale() {
        return locale;
    }

    /**
     * Returns the distinct named placeholders referenced by the localized message.
     * <p>返回本地化消息引用的去重具名占位符。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the operation result collection / 操作结果集合
     */
    public Set<String> placeholders(String key) {
        Matcher matcher = PLACEHOLDER.matcher(lookup(key));
        Set<String> result = new LinkedHashSet<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return Set.copyOf(result);
    }

    /**
     * Loads the localized message and rejects a missing message key.
     * <p>加载本地化消息，并拒绝缺失的消息键。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return the localized message and rejects a missing message key / 本地化消息，并拒绝缺失的消息键
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private String lookup(String key) {
        try {
            return bundle.getString(Objects.requireNonNull(key, "key"));
        } catch (MissingResourceException exception) {
            throw new IllegalArgumentException("missing message key: " + key, exception);
        }
    }
}
