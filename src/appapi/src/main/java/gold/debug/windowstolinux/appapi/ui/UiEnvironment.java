package gold.debug.windowstolinux.appapi.ui;

import java.util.Locale;

/**
 * Current host language, theme and lock state. / 当前宿主语言、主题及锁定状态。
 * @param locale current application language / 当前应用语言
 * @param themeId current application theme identity / 当前应用主题身份
 * @param locked whether the platform is currently locked / 平台当前是否已锁定
 */
public record UiEnvironment(Locale locale, String themeId, boolean locked) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param locale current application language / 当前应用语言
     * @param themeId current application theme identity / 当前应用主题身份
     * @param locked whether the platform is currently locked / 平台当前是否已锁定
     */
    public UiEnvironment {
        java.util.Objects.requireNonNull(locale, "locale");
        java.util.Objects.requireNonNull(themeId, "themeId");
    }
}
