package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver.RunMode;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.SystemThemeResolver;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.DesktopFrame;
import gold.debug.windowstolinux.app.ui.shell.DesktopViewState;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Rectangle;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns one desktop window and replaces it safely when a locale or theme changes.
 *
 *  <p>持有一个桌面窗口，并在区域设置或主题变化时安全替换它。
 */
final class DesktopWindowController {
    /**
     * Reviewed database identity or database operation boundary.
     * <p>已审阅数据库身份或数据库操作边界。
     */
    private final DesktopPersistence database;
    /**
     * Bound desktop application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的Desktop应用门面协作对象。
     */
    private final DesktopApplicationFacade service;
    /**
     * Swing event-thread timer for system theme timer.
     * <p>系统主题定时器使用的 Swing 事件线程定时器。
     */
    private final Timer systemThemeTimer;
    /**
     * Checking system theme.
     * <p>检查中系统主题。
     */
    private final AtomicBoolean checkingSystemTheme = new AtomicBoolean();
    /**
     * Bound failure report store collaborator for reports.
     * <p>处理报告集合的失败报告存储协作对象。
     */
    private final FailureReportStore reports;
    /**
     * Ui debug enabled.
     * <p>界面Debug启用。
     */
    private final boolean uiDebugEnabled;

    /**
     * Appearance.
     * <p>外观。
     */
    private DesktopDisplayConfiguration appearance;
    /**
     * Effective theme.
     * <p>生效主题。
     */
    private ThemeMode effectiveTheme;
    /**
     * Frame.
     * <p>框架。
     */
    private DesktopFrame frame;
    /**
     * Window state.
     * <p>窗口状态。
     */
    private int windowState = java.awt.Frame.NORMAL;

    /**
     * Binds the supplied dependencies and state for desktop window controller.
     * <p>为Desktop窗口控制器绑定传入的依赖及状态。
     *
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param appearance appearance / 外观
     * @param reports reports / 报告集合
     * @param mode selected operating or storage mode / 所选运行或存储模式
     */
    DesktopWindowController(DesktopPersistence database, DesktopApplicationFacade service,
                            DesktopDisplayConfiguration appearance, FailureReportStore reports, RunMode mode) {
        this.database = database;
        this.service = service;
        this.appearance = appearance;
        this.reports = reports;
        this.uiDebugEnabled = mode == RunMode.RUN_CLASS;
        this.effectiveTheme = SystemThemeResolver.effectiveTheme(appearance.themeMode());
        this.systemThemeTimer = new Timer(5_000, event -> refreshSystemThemeIfChanged());
        this.systemThemeTimer.setRepeats(true);
    }

    /**
     * Displays initial window.
     * <p>展示初始窗口。
     */
    void showInitialWindow() {
        DesktopThemeService.install(effectiveTheme);
        showWindow(null, null);
        systemThemeTimer.start();
    }

    /**
     * Persists the selected appearance and recreates the current frame while preserving its page state.
     * <p>持久化所选外观，并在保留页面状态的同时重建当前窗口。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param selected explicitly selected item or state / 显式选择的项目或状态
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
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
        Rectangle bounds = source.workspaceWindowBounds();
        windowState = source.getExtendedState();
        source.dispose();
        appearance = selected;
        effectiveTheme = newEffectiveTheme;
        DesktopThemeService.apply(effectiveTheme);
        showWindow(bounds, viewState);
    }

    /**
     * Displays window.
     * <p>展示窗口。
     *
     * @param bounds bounds / 边界集合
     * @param viewState view state / 视图状态
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private void showWindow(Rectangle bounds, DesktopViewState viewState) {
        MessageCatalog catalog = MessageCatalog.forLanguageTag(appearance.localeTag());
        ThemePalette palette = effectiveTheme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
        frame = new DesktopFrame(service, catalog, appearance, palette, this::applyAppearance, viewState, reports, uiDebugEnabled);
        if (bounds != null) {
            frame.restoreWorkspaceWindowBounds(bounds);
        }
        try {
            frame.setNavigationCollapsed(Boolean.parseBoolean(database.preferences().find("ui.navigationCollapsed").orElse("false")));
        } catch (SQLException failure) { throw new IllegalStateException("Navigation preference could not be read", failure); }
        frame.onNavigationChange(collapsed -> {
            try { database.preferences().save("ui.navigationCollapsed", Boolean.toString(collapsed)); }
            catch (SQLException failure) { throw new IllegalStateException("Navigation preference could not be saved", failure); }
        });
        frame.setExtendedState(windowState);
        frame.setVisible(true);
    }

    /**
     * Refreshes system theme if changed.
     * <p>刷新系统主题If已变化。
     */
    private void refreshSystemThemeIfChanged() {
        if (gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor.hasActiveTasks()
                || appearance.themeMode() != ThemeMode.SYSTEM || !checkingSystemTheme.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(() -> SystemThemeResolver.effectiveTheme(ThemeMode.SYSTEM))
                .thenAccept(theme -> SwingUtilities.invokeLater(() -> {
                    checkingSystemTheme.set(false);
                    if (frame != null && theme != effectiveTheme) {
                        DesktopViewState viewState = frame.captureViewState();
                        Rectangle bounds = frame.workspaceWindowBounds();
                        windowState = frame.getExtendedState();
                        frame.dispose();
                        effectiveTheme = theme;
                        DesktopThemeService.apply(theme);
                        showWindow(bounds, viewState);
                    }
                }));
    }
}
