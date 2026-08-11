package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.app.db.DesktopDatabase;
import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.ui.appearance.DesktopAppearance;
import gold.debug.windowstolinux.app.ui.appearance.DesktopTheme;
import gold.debug.windowstolinux.app.ui.appearance.SystemThemePreference;
import gold.debug.windowstolinux.app.ui.appearance.ThemeMode;
import gold.debug.windowstolinux.app.ui.appearance.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.DesktopFrame;
import gold.debug.windowstolinux.app.ui.shell.DesktopViewState;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Rectangle;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one desktop window and replaces it safely when a locale or theme changes.
 *
 * <p>持有一个桌面窗口，并在区域设置或主题变化时安全替换它。
 */
final class DesktopWindowController {
    private final DesktopDatabase database;
    private final DesktopApplicationService service;
    private final Timer systemThemeTimer;
    private final AtomicBoolean checkingSystemTheme = new AtomicBoolean();

    private DesktopAppearance appearance;
    private ThemeMode effectiveTheme;
    private DesktopFrame frame;

    DesktopWindowController(DesktopDatabase database, DesktopApplicationService service, DesktopAppearance appearance) {
        this.database = database;
        this.service = service;
        this.appearance = appearance;
        this.effectiveTheme = SystemThemePreference.effectiveTheme(appearance.themeMode());
        this.systemThemeTimer = new Timer(5_000, event -> refreshSystemThemeIfChanged());
        this.systemThemeTimer.setRepeats(true);
    }

    void showInitialWindow() {
        DesktopTheme.install(effectiveTheme);
        showWindow(null, null);
        systemThemeTimer.start();
    }

    private void applyAppearance(DesktopFrame source, DesktopAppearance selected) {
        if (source != frame || selected.equals(appearance)) {
            return;
        }
        try {
            database.saveDesktopPreference(DesktopDatabase.UI_LOCALE_SETTING, selected.localeTag());
            database.saveDesktopPreference(DesktopDatabase.UI_THEME_SETTING, selected.themeMode().name());
        } catch (SQLException exception) {
            throw new IllegalStateException("desktop preference persistence failed", exception);
        }
        ThemeMode newEffectiveTheme = SystemThemePreference.effectiveTheme(selected.themeMode());
        DesktopViewState viewState = source.captureViewState();
        Rectangle bounds = source.getBounds();
        source.dispose();
        appearance = selected;
        effectiveTheme = newEffectiveTheme;
        DesktopTheme.apply(effectiveTheme);
        showWindow(bounds, viewState);
    }

    private void showWindow(Rectangle bounds, DesktopViewState viewState) {
        MessageCatalog catalog = MessageCatalog.forLanguageTag(appearance.localeTag());
        ThemePalette palette = effectiveTheme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
        frame = new DesktopFrame(service, catalog, appearance, palette, this::applyAppearance, viewState);
        if (bounds != null) {
            frame.setBounds(bounds);
        }
        frame.setVisible(true);
    }

    private void refreshSystemThemeIfChanged() {
        if (appearance.themeMode() != ThemeMode.SYSTEM || !checkingSystemTheme.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> SystemThemePreference.effectiveTheme(ThemeMode.SYSTEM))
                .thenAccept(theme -> SwingUtilities.invokeLater(() -> {
                    checkingSystemTheme.set(false);
                    if (frame != null && theme != effectiveTheme) {
                        DesktopViewState viewState = frame.captureViewState();
                        Rectangle bounds = frame.getBounds();
                        frame.dispose();
                        effectiveTheme = theme;
                        DesktopTheme.apply(theme);
                        showWindow(bounds, viewState);
                    }
                }));
    }
}
