package gold.debug.windowstolinux.app.ui.display;

import java.util.Locale;
import java.util.Objects;

import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

/**
 * Persistable display preferences selected in the desktop settings page.
 *
 *  <p>在桌面设置页面选择的可持久化显示偏好。
 *
 * @param localeTag locale tag / 区域标签
 * @param themeMode theme mode / 主题模式
 * @param scalePercent application zoom relative to system scaling / 相对于系统缩放的应用缩放百分比
 * @param restoreLastPage whether startup restores the previous page / 启动是否恢复上次页面
 */
public record DesktopDisplayConfig(String localeTag, ThemeMode themeMode, int scalePercent, boolean restoreLastPage) {
    /**
     * Validates and binds the inputs required by desktop display configuration.
     * <p>校验并绑定Desktop显示配置所需输入。
     *
     * @param localeTag locale tag / 区域标签
     * @param themeMode theme mode / 主题模式
     * @param scalePercent supported application zoom / 支持的应用缩放百分比
     * @param restoreLastPage explicit startup restoration / 明确的启动恢复选择
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopDisplayConfig {
        localeTag = Objects.requireNonNull(localeTag, "localeTag");
        themeMode = Objects.requireNonNull(themeMode, "themeMode");
        if (scalePercent != 100 && scalePercent != 150 && scalePercent != 200)
            throw new IllegalArgumentException("invalid ui_scale preference");
    }

    /** Uses normal application zoom for language and theme choices. / 语言和主题选择默认使用正常应用缩放。
     * @param localeTag locale tag / 区域标签
     * @param themeMode theme mode / 主题模式 */
    public DesktopDisplayConfig(String localeTag, ThemeMode themeMode) {
        this(localeTag, themeMode, 100, false);
    }

    /** Defaults startup to the home page. / 默认从首页启动。
     * @param localeTag locale tag / 区域标签
     * @param themeMode selected theme / 所选主题
     * @param scalePercent selected zoom / 所选缩放 */
    public DesktopDisplayConfig(String localeTag, ThemeMode themeMode, int scalePercent) {
        this(localeTag, themeMode, scalePercent, false);
    }

    /**
     * Returns defaults.
     * <p>返回默认集合。
     *
     * @return the operation result / 操作结果
     */
    public static DesktopDisplayConfig defaults() {
        return defaults(Locale.getDefault());
    }

    /**
     * Builds desktop display configuration from the supplied defaults inputs.
     * <p>根据所提供默认集合输入构建Desktop显示配置。
     *
     * @param systemLocale system locale / 系统区域
     * @return the operation result / 操作结果
     */
    public static DesktopDisplayConfig defaults(Locale systemLocale) {
        return new DesktopDisplayConfig(MessageCatalog.defaultLanguageTag(systemLocale), ThemeMode.SYSTEM);
    }

    /**
     * Creates a value through {@code fromStoredValues}.
     *
     *  <p>通过 {@code fromStoredValues} 创建值。
     *
     * @param storedLocaleTag stored locale tag / 已存储区域标签
     * @param storedThemeMode stored theme mode / 已存储主题模式
     * @param systemLocale system locale / 系统区域
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static DesktopDisplayConfig fromStoredValues(String storedLocaleTag, String storedThemeMode,
                                                        Locale systemLocale) {
        return fromStoredValues(storedLocaleTag, storedThemeMode, null, systemLocale);
    }

    /** Restores all display choices, defaulting missing zoom to 100%. / 恢复全部显示选择，未保存缩放时默认 100%。
     * @param storedLocaleTag stored locale tag / 已存区域标签
     * @param storedThemeMode stored theme / 已存主题
     * @param storedScalePercent stored application zoom / 已存应用缩放
     * @param systemLocale system locale / 系统区域
     * @return validated display preferences / 已校验显示偏好 */
    public static DesktopDisplayConfig fromStoredValues(String storedLocaleTag, String storedThemeMode,
                                                        String storedScalePercent, Locale systemLocale) {
        return fromStoredValues(storedLocaleTag, storedThemeMode, storedScalePercent, null, systemLocale);
    }

    /** Restores display and explicit startup choices. / 恢复显示及明确启动选择。
     * @param storedLocaleTag stored language / 已存语言
     * @param storedThemeMode stored theme / 已存主题
     * @param storedScalePercent stored zoom / 已存缩放
     * @param storedRestoreLastPage stored restoration switch / 已存恢复开关
     * @param systemLocale system language / 系统语言
     * @return validated preferences / 已校验偏好 */
    public static DesktopDisplayConfig fromStoredValues(String storedLocaleTag, String storedThemeMode,
                                                        String storedScalePercent, String storedRestoreLastPage,
                                                        Locale systemLocale) {
        Objects.requireNonNull(systemLocale, "systemLocale");
        if (storedLocaleTag != null && !java.util.Set.of("zh-CN", "en-US", "en").contains(storedLocaleTag))
            throw new IllegalArgumentException("invalid ui_locale preference");
        String localeTag = storedLocaleTag == null
            ? MessageCatalog.defaultLanguageTag(systemLocale)
            : MessageCatalog.normalizeLanguageTag(storedLocaleTag);
        int scalePercent = storedScalePercent == null ? 100 : Integer.parseInt(storedScalePercent);
        if (storedRestoreLastPage != null && !storedRestoreLastPage.equals("true") && !storedRestoreLastPage.equals("false"))
            throw new IllegalArgumentException("invalid ui_restore_last_page preference");
        return new DesktopDisplayConfig(localeTag, ThemeMode.fromStoredValue(storedThemeMode), scalePercent,
            Boolean.parseBoolean(storedRestoreLastPage));
    }
}
