package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.SystemThemeResolver;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
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
    private final DesktopPersistence database;
    private final DesktopApplicationFacade service;
    private final Timer systemThemeTimer;
    private final AtomicBoolean checkingSystemTheme = new AtomicBoolean();

    private DesktopDisplayConfiguration appearance;
    private ThemeMode effectiveTheme;
    private DesktopFrame frame;

    DesktopWindowController(DesktopPersistence database, DesktopApplicationFacade service, DesktopDisplayConfiguration appearance) {
        this.database = database;
        this.service = service;
        this.appearance = appearance;
        this.effectiveTheme = SystemThemeResolver.effectiveTheme(appearance.themeMode());
        this.systemThemeTimer = new Timer(5_000, event -> refreshSystemThemeIfChanged());
        this.systemThemeTimer.setRepeats(true);
    }

    void showInitialWindow() {
        DesktopThemeService.install(effectiveTheme);
        showWindow(null, null);
        systemThemeTimer.start();
    }

    private void applyAppearance(DesktopFrame source, DesktopDisplayConfiguration selected) {
        if (source != frame || selected.equals(appearance)) {
            return;
        }
        try {
            database.preferences().save(DesktopPersistence.UI_LOCALE_SETTING, selected.localeTag());
            database.preferences().save(DesktopPersistence.UI_THEME_SETTING, selected.themeMode().name());
        } catch (SQLException exception) {
            throw new IllegalStateException("desktop preference persistence failed", exception);
        }
        ThemeMode newEffectiveTheme = SystemThemeResolver.effectiveTheme(selected.themeMode());
        DesktopViewState viewState = source.captureViewState();
        Rectangle bounds = source.getBounds();
        source.dispose();
        appearance = selected;
        effectiveTheme = newEffectiveTheme;
        DesktopThemeService.apply(effectiveTheme);
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
        CompletableFuture.supplyAsync(() -> SystemThemeResolver.effectiveTheme(ThemeMode.SYSTEM))
                .thenAccept(theme -> SwingUtilities.invokeLater(() -> {
                    checkingSystemTheme.set(false);
                    if (frame != null && theme != effectiveTheme) {
                        DesktopViewState viewState = frame.captureViewState();
                        Rectangle bounds = frame.getBounds();
                        frame.dispose();
                        effectiveTheme = theme;
                        DesktopThemeService.apply(theme);
                        showWindow(bounds, viewState);
                    }
                }));
    }
}
