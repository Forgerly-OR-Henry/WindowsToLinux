package gold.debug.windowstolinux.app.ui.shell;

import java.awt.BorderLayout;
import java.util.Objects;

import javax.swing.*;

import gold.debug.windowstolinux.api.ui.UiEnvironment;
import gold.debug.windowstolinux.app.service.contract.security.SecurityApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.setting.SecuritySettingsPane;

/** Platform desktop hosting security controls and independently owned Mod views. / 承载安全控件及独立所属 Mod 视图的平台桌面。 */
public final class PlatformFrame extends JFrame {
    /** Platform privacy boundary including owned dialogs. / 包含从属对话框的平台隐私边界。 */
    private final DesktopLockController lock;

    /** Staged Mod page registry used by the actual startup coordinator. / 实际启动协调器使用的暂存 Mod 页面登记。 */
    private final ModPageRegistry pages;

    /** Safe localized diagnostic formatter. / 安全的本地化诊断格式化器。 */
    private final PageMessagePresenter messages;

    /** Prevents duplicate shutdown workers. / 防止重复启动关闭工作线程。 */
    private boolean closing;

    /**
     * Builds the platform shell on the event thread. / 在事件线程构造平台外壳。
     * @param security current platform security facade / 当前平台安全门面
     * @param catalog current language catalog / 当前语言目录
     * @param palette effective theme colors / 生效主题颜色
     * @param environment initial Mod page environment / 初始 Mod 页面环境
     * @param reports safe diagnostic storage / 安全诊断存储
     */
    public PlatformFrame(SecurityApplicationFacade security, MessageCatalog catalog, ThemePalette palette,
            UiEnvironment environment, FailureReportStore reports) {
        super("WindowsToLinux");
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Platform window construction requires the EDT");
        Objects.requireNonNull(security);
        messages = new PageMessagePresenter(catalog, reports);
        var components = new DesktopComponentFactory(palette);
        var extensions = new ExtensionPagePane(this::closePage, owner -> owner, () -> catalog.text("security.locked"));
        pages = new ModPageRegistry(extensions, environment);
        var settings = new SecuritySettingsPane(security, components, messages);
        var settingsSurface = new JPanel(new BorderLayout());
        settingsSurface.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        settingsSurface.add(settings, BorderLayout.NORTH);
        var settingsScroll = new JScrollPane(settingsSurface);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        var content = new JTabbedPane();
        content.setName("platform.pages");
        content.addTab(catalog.text("platform.pages"), extensions);
        content.addTab(catalog.text("security.settings"), settingsScroll);
        content.addTab(catalog.text("platform.modSettings"), extensions.settingsComponent());
        if (!security.securityState().configured())
            content.setSelectedComponent(settingsScroll);
        var sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        getContentPane().add(sidebar, BorderLayout.WEST);
        getContentPane().add(content, BorderLayout.CENTER);
        lock = DesktopLockController.attach(this, security, catalog, reports, sidebar, components, false,
                () -> content.setSelectedComponent(settingsScroll));
        lock.onLockChange(locked -> {
            try {
                pages.environment(new UiEnvironment(environment.locale(), environment.themeId(), locked));
            } catch (Exception failure) {
                reports.record(failure);
            }
        });
        setSize(1120, 760);
        setMinimumSize(new java.awt.Dimension(800, 560));
        setLocationRelativeTo(null);
    }

    /** Returns staged page ownership for Mod startup composition. / 返回用于 Mod 启动装配的暂存页面归属。
     * @return actual page registry / 实际页面登记 */
    public ModPageRegistry pages() {
        return pages;
    }

    /**
     * Registers actual resource shutdown, retaining the window after a failed close. / 登记实际资源关闭，关闭失败时保留窗口。
     * @param shutdown blocking platform cleanup invoked on a worker / 在工作线程调用的阻塞平台清理
     */
    public void onExit(AutoCloseable shutdown) {
        Objects.requireNonNull(shutdown);
        lock.onExit(() -> {
            if (closing)
                return;
            closing = true;
            new SwingWorker<Void, Void>() {
                /** Performs actual shutdown away from the event thread. / 在事件线程之外执行实际关闭。
                 * @return no result / 无返回值
                 * @throws Exception if owned resources remain / 仍有所属资源时 */
                @Override
                protected Void doInBackground() throws Exception {
                    shutdown.close();
                    return null;
                }

                /** Disposes the desktop only after confirmed shutdown. / 仅在确认关闭后销毁桌面。 */
                @Override
                protected void done() {
                    try {
                        get();
                        dispose();
                    } catch (Exception failure) {
                        closing = false;
                        JOptionPane.showMessageDialog(PlatformFrame.this, messages.safe(failure), "WindowsToLinux",
                                JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });
    }

    /**
     * Closes one author view through the tracked page registry. / 通过跟踪页面登记关闭一个作者视图。
     * @param key exact owner and page identity / 精确所有者及页面身份
     */
    private void closePage(ModPageRegistry.PageKey key) {
        try {
            pages.closeView(key);
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this, messages.safe(failure), "WindowsToLinux", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Releases privacy listeners and disposes the window after lifecycle cleanup. / 生命周期清理后释放隐私监听器并销毁窗口。 */
    @Override
    public void dispose() {
        lock.close();
        super.dispose();
    }
}
