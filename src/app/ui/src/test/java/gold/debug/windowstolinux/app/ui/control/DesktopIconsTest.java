package gold.debug.windowstolinux.app.ui.control;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;

import org.junit.jupiter.api.Test;

class DesktopIconsTest {
    @Test
    void categorizedResourcesAndIndependentLogoLoadFromClasspath() {
        for (String icon : new String[]{"lock/locked", "lock/unlocked", "navigation/settings",
            "navigation/panels-top-left", "navigation/panel-left-open", "navigation/panel-left-close",
            "navigation/chevron-down", "navigation/chevron-right", "server/server", "ai/bot", "mod/archive",
            "security/hand", "security/shield", "security/shield-alert", "file/folder-open", "file/git-branch",
            "action/plus", "action/refresh-cw", "action/search", "action/arrow-right", "action/grip-vertical", "action/rocket"}) {
            assertEquals(19, DesktopIcons.icon(icon, 19, () -> Color.WHITE).getIconWidth(), icon);
        }
        assertTrue(DesktopIcons.logo(36).getIconWidth() > 0);
        assertNull(DesktopIcons.class.getResource("icons/shield.svg"));
    }
}
