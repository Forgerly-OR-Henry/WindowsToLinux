package gold.debug.windowstolinux.app.ui.i18n;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves application message keys and non-recursive named placeholders from a locale bundle.
 *
 * <p>从区域设置资源包解析应用消息键和非递归命名占位符。
 */
public final class MessageCatalog {
    /**
     * Exposes the {@code ENGLISH_TAG} constant.
     *
     * <p>公开 {@code ENGLISH_TAG} 常量。
     */
    public static final String ENGLISH_TAG = "en";
    /**
     * Exposes the {@code SIMPLIFIED_CHINESE_TAG} constant.
     *
     * <p>公开 {@code SIMPLIFIED_CHINESE_TAG} 常量。
     */
    public static final String SIMPLIFIED_CHINESE_TAG = "zh-CN";
    private static final Set<String> SUPPORTED_LANGUAGE_TAGS = Set.of(ENGLISH_TAG, SIMPLIFIED_CHINESE_TAG);
    private static final String BUNDLE_NAME = "gold.debug.windowstolinux.app.ui.i18n.messages.Messages";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_.-]*)}");

    private final Locale locale;
    private final ResourceBundle bundle;

    private MessageCatalog(Locale locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
        this.bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /**
     * Performs the {@code forLanguageTag} operation.
     *
     * <p>执行 {@code forLanguageTag} 操作。
     *
     * @param languageTag the {@code languageTag} value / {@code languageTag} 值
     * @return the operation result / 操作结果
     */
    public static MessageCatalog forLanguageTag(String languageTag) {
        String normalized = normalizeLanguageTag(languageTag);
        Locale locale = SIMPLIFIED_CHINESE_TAG.equals(normalized) ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        return new MessageCatalog(locale);
    }

    /**
     * Performs the {@code normalizeLanguageTag} operation.
     *
     * <p>执行 {@code normalizeLanguageTag} 操作。
     *
     * @param languageTag the {@code languageTag} value / {@code languageTag} 值
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
     * Performs the {@code defaultLanguageTag} operation.
     *
     * <p>执行 {@code defaultLanguageTag} 操作。
     *
     * @param systemLocale the {@code systemLocale} value / {@code systemLocale} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static String defaultLanguageTag(Locale systemLocale) {
        Objects.requireNonNull(systemLocale, "systemLocale");
        return systemLocale.getLanguage().equals(Locale.CHINESE.getLanguage())
                ? SIMPLIFIED_CHINESE_TAG : ENGLISH_TAG;
    }

    /**
     * Performs the {@code supportedLanguageTags} operation.
     *
     * <p>执行 {@code supportedLanguageTags} 操作。
     *
     * @return the operation result collection / 操作结果集合
     */
    public static Set<String> supportedLanguageTags() {
        return SUPPORTED_LANGUAGE_TAGS;
    }

    /**
     * Performs the {@code text} operation.
     *
     * <p>执行 {@code text} 操作。
     *
     * @param key the {@code key} value / {@code key} 值
     * @return the operation result / 操作结果
     */
    public String text(String key) {
        return text(new LocalizedMessage(key, Map.of()));
    }

    /**
     * Performs the {@code text} operation.
     *
     * <p>执行 {@code text} 操作。
     *
     * @param key the {@code key} value / {@code key} 值
     * @param arguments the {@code arguments} value / {@code arguments} 值
     * @return the operation result / 操作结果
     */
    public String text(String key, Map<String, ?> arguments) {
        return text(LocalizedMessage.of(key, arguments));
    }

    /**
     * Performs the {@code text} operation.
     *
     * <p>执行 {@code text} 操作。
     *
     * @param message the {@code message} value / {@code message} 值
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
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
     * Performs the {@code locale} operation.
     *
     * <p>执行 {@code locale} 操作。
     *
     * @return the operation result / 操作结果
     */
    public Locale locale() {
        return locale;
    }

    /**
     * Performs the {@code placeholders} operation.
     *
     * <p>执行 {@code placeholders} 操作。
     *
     * @param key the {@code key} value / {@code key} 值
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

    private String lookup(String key) {
        try {
            return bundle.getString(Objects.requireNonNull(key, "key"));
        } catch (MissingResourceException exception) {
            throw new IllegalArgumentException("missing message key: " + key, exception);
        }
    }
}
