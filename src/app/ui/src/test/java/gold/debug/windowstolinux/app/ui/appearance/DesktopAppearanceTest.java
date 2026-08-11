package gold.debug.windowstolinux.app.ui.appearance;

import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopAppearanceTest {
    @Test
    void usesTheSystemLanguageForNewInstalls() {
        assertEquals(MessageCatalog.SIMPLIFIED_CHINESE_TAG,
                DesktopAppearance.defaults(Locale.SIMPLIFIED_CHINESE).localeTag());
        assertEquals(MessageCatalog.ENGLISH_TAG, DesktopAppearance.defaults(Locale.ENGLISH).localeTag());
        assertEquals(MessageCatalog.ENGLISH_TAG, DesktopAppearance.defaults(Locale.GERMAN).localeTag());
    }

    @Test
    void savedLanguageAndThemeOverrideSystemDefaults() {
        DesktopAppearance english = DesktopAppearance.fromStoredValues(
                MessageCatalog.ENGLISH_TAG, ThemeMode.DARK.name(), Locale.SIMPLIFIED_CHINESE);
        assertEquals(MessageCatalog.ENGLISH_TAG, english.localeTag());
        assertEquals(ThemeMode.DARK, english.themeMode());

        DesktopAppearance chinese = DesktopAppearance.fromStoredValues(
                MessageCatalog.SIMPLIFIED_CHINESE_TAG, ThemeMode.LIGHT.name(), Locale.ENGLISH);
        assertEquals(MessageCatalog.SIMPLIFIED_CHINESE_TAG, chinese.localeTag());
        assertEquals(ThemeMode.LIGHT, chinese.themeMode());
    }
}
