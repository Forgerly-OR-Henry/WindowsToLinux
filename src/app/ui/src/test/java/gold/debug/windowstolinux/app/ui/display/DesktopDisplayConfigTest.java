package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;

import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import org.junit.jupiter.api.Test;

class DesktopDisplayConfigTest {
    @Test
    void restoresSupportedZoomAndDefaultsMissingPreferencesToNormalSize() {
        for (int scale : new int[]{100, 150, 200}) {
            var saved = DesktopDisplayConfig.fromStoredValues("zh-CN", "DARK", Integer.toString(scale), Locale.ENGLISH);
            assertEquals(scale, saved.scalePercent());
        }
        assertEquals(100, DesktopDisplayConfig.fromStoredValues("en", "LIGHT", Locale.ENGLISH).scalePercent());
        assertEquals(100, DesktopDisplayConfig.defaults(Locale.ENGLISH).scalePercent());
    }

    @Test
    void rejectsInvalidStoredZoomRatherThanSilentlyChangingIt() {
        for (String scale : new String[]{"125", "0", "-100", "invalid"})
            assertThrows(IllegalArgumentException.class,
                () -> DesktopDisplayConfig.fromStoredValues("en", "LIGHT", scale, Locale.ENGLISH));
    }

    @Test
    void startupRestorationIsOptInAndRejectsMalformedSavedSwitches() {
        org.junit.jupiter.api.Assertions.assertFalse(DesktopDisplayConfig.defaults().restoreLastPage());
        org.junit.jupiter.api.Assertions.assertTrue(DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "150", "true", Locale.ENGLISH).restoreLastPage());
        org.junit.jupiter.api.Assertions.assertFalse(DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "150", "false", Locale.ENGLISH).restoreLastPage());
        assertThrows(IllegalArgumentException.class,
            () -> DesktopDisplayConfig.fromStoredValues("en", "LIGHT", "100", "enabled", Locale.ENGLISH));
    }

    @Test
    void usesTheSystemLanguageForNewInstalls() {
        assertEquals(MessageCatalog.SIMPLIFIED_CHINESE_TAG,
            DesktopDisplayConfig.defaults(Locale.SIMPLIFIED_CHINESE).localeTag());
        assertEquals(MessageCatalog.ENGLISH_TAG, DesktopDisplayConfig.defaults(Locale.ENGLISH).localeTag());
        assertEquals(MessageCatalog.ENGLISH_TAG, DesktopDisplayConfig.defaults(Locale.GERMAN).localeTag());
    }

    @Test
    void savedLanguageAndThemeOverrideSystemDefaults() {
        DesktopDisplayConfig english = DesktopDisplayConfig.fromStoredValues(MessageCatalog.ENGLISH_TAG,
            ThemeMode.DARK.name(), Locale.SIMPLIFIED_CHINESE);
        assertEquals(MessageCatalog.ENGLISH_TAG, english.localeTag());
        assertEquals(ThemeMode.DARK, english.themeMode());

        DesktopDisplayConfig chinese = DesktopDisplayConfig
            .fromStoredValues(MessageCatalog.SIMPLIFIED_CHINESE_TAG, ThemeMode.LIGHT.name(), Locale.ENGLISH);
        assertEquals(MessageCatalog.SIMPLIFIED_CHINESE_TAG, chinese.localeTag());
        assertEquals(ThemeMode.LIGHT, chinese.themeMode());
    }
}
