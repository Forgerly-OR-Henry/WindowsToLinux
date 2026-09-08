package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

class BackupPageStateTest {
    @Test void appearanceRebuildRetainsEnteredPasswordsAndReleasesStateCopies() throws Exception {
        var state = new BackupPageState("demo", "target", "backup.wtl", "destination.wtl", "retained", null, 1,
                "fixture-backup".toCharArray(), "fixture-master".toCharArray());
        SwingUtilities.invokeAndWait(() -> {
            var page = new BackupPage(null, null, new DesktopComponentFactory(ThemePalette.light()),
                    new PageMessagePresenter(MessageCatalog.forLanguageTag("en-US")));
            page.restoreState(state);
            try (var restored = page.captureState()) {
                assertArrayEquals(state.backupPassword(), restored.backupPassword());
                assertArrayEquals(state.masterPassword(), restored.masterPassword());
                assertEquals(state.task(), restored.task()); assertEquals(state.output(), restored.output());
            }
        });
        state.close();
        assertArrayEquals(new char[14], state.backupPassword()); assertArrayEquals(new char[14], state.masterPassword());
    }
}
