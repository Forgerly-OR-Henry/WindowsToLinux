package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;

class DesktopDisplayConfigurationTest {
    @Test
    void usesTheSystemLanguageForNewInstalls() {
        assertEquals(MessageCatalog.SIMPLIFIED_CHINESE_TAG,
                DesktopDisplayConfiguration.defaults(Locale.SIMPLIFIED_CHINESE).localeTag());
        assertEquals(MessageCatalog.ENGLISH_TAG, DesktopDisplayConfiguration.defaults(Locale.ENGLISH).localeTag());
        assertEquals(MessageCatalog.ENGLISH_TAG, DesktopDisplayConfiguration.defaults(Locale.GERMAN).localeTag());
    }

    @Test
    void savedLanguageAndThemeOverrideSystemDefaults() {
        DesktopDisplayConfiguration english = DesktopDisplayConfiguration.fromStoredValues(MessageCatalog.ENGLISH_TAG,
                ThemeMode.DARK.name(), Locale.SIMPLIFIED_CHINESE);
        assertEquals(MessageCatalog.ENGLISH_TAG, english.localeTag());
        assertEquals(ThemeMode.DARK, english.themeMode());

        DesktopDisplayConfiguration chinese = DesktopDisplayConfiguration
                .fromStoredValues(MessageCatalog.SIMPLIFIED_CHINESE_TAG, ThemeMode.LIGHT.name(), Locale.ENGLISH);
        assertEquals(MessageCatalog.SIMPLIFIED_CHINESE_TAG, chinese.localeTag());
        assertEquals(ThemeMode.LIGHT, chinese.themeMode());
    }
}
