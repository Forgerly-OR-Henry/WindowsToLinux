package gold.debug.windowstolinux.app.ui.window;

import gold.debug.windowstolinux.app.ui.control.DesktopDialogs;

import java.awt.BorderLayout;
import java.util.Objects;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.security.SecurityAccess;
import gold.debug.windowstolinux.app.service.setting.StartupPageLocation;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ExtensionPagePane;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.setting.SecuritySettingsPane;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;

/** Platform desktop hosting security controls and independently owned Mod views. / 承载安全控件及独立所属 Mod 视图的平台桌面。 */
public final class PlatformFrame extends JFrame {
    /** Frozen configuration used for initial UI and capability composition. / 初始界面及能力装配使用的冻结配置。 */
    private final gold.debug.windowstolinux.app.config.AppConfig config;

    /** Platform privacy boundary including owned dialogs. / 包含从属对话框的平台隐私边界。 */
    private final DesktopLockController lock;

    /** Staged Mod page registry used by the actual startup coordinator. / 实际启动协调器使用的暂存 Mod 页面登记。 */
    private final ModPageRegistry pages;

    /** Safe localized diagnostic formatter. / 安全的本地化诊断格式化器。 */
    private final PageMessagePresenter messages;

    /** Permanent platform and extension navigation. / 常驻平台及扩展导航。 */
    private final JTabbedPane content = new PlatformNavigationShell.PageDeck();

    /** Persistent visual shell around original pages. / 围绕原页面的常驻视觉外壳。 */
    private final PlatformNavigationShell shell;

    /** Permanent public home surface and optional startup-only Mod target. / 常驻公开首页及可选的仅启动 Mod 目标。 */
    private final DesktopHomePane home = new DesktopHomePane();

    /** Startup-only non-secret target retained until root publication. / 保留至根发布的仅启动非秘密目标。 */
    private StartupPageLocation pendingStartupPage;

    /** Non-secret identity writer run before normal resource shutdown. / 正常资源关闭前执行的非秘密身份写入器。 */
    private PageMemory rememberPage = location -> {
    };

    /** Current theme used by platform configuration dialogs. / 平台配置窗口使用的当前主题。 */
    private final Appearance appearance;

    /** Current non-secret security status. / 当前不含秘密的安全状态。 */
    private final SecurityAccess security;

    /** Installed permanent server page, if composition has completed. / 已安装常驻服务器页面，装配完成后存在。 */
    private gold.debug.windowstolinux.app.ui.server.PlatformServersPane servers;

    /** Permanent model inventory and capability checks. / 常驻模型清单及能力检查。 */
    private gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane models;

    /** Owns mounted preference views with one window lifecycle. / 以同一窗口生命周期持有已挂载偏好视图。 */
    private final SettingsViews settingsViews = new SettingsViews();

    /** Preference surfaces grouped by their privacy and detach lifecycle. / 按隐私及分离生命周期组织的偏好界面。 */
    private final class SettingsViews {
        /** Binds the same outer window. / 绑定同一外围窗口。 */
        private SettingsViews() {
        }

        /** Permanent connection and transfer threshold settings. / 常驻连接及传输阈值设置。 */
        private gold.debug.windowstolinux.app.ui.setting.TransferSettingsPane transfers;

        /** Optional file explanation settings. / 可选文件说明设置。 */
        private gold.debug.windowstolinux.app.ui.setting.FileSettingsPane fileSettings;

        /** Permanent diagnostic management page. / 常驻诊断管理页面。 */
        private gold.debug.windowstolinux.app.ui.setting.LogSettingsPane logs;

        /** Applies the same privacy state to every preference view. / 将同一隐私状态应用到所有偏好视图。
         * @param locked current privacy state / 当前隐私状态 */
        private void locked(boolean locked) {
            if (transfers != null) transfers.setLocked(locked);
            if (fileSettings != null) fileSettings.setLocked(locked);
            if (logs != null) logs.setLocked(locked);
        }

        /** Detaches every mounted settings view without cancelling accepted writes. / 分离所有已挂载设置视图而不取消已接纳写入。 */
        private void close() {
            if (transfers != null) transfers.close();
            if (fileSettings != null) fileSettings.close();
            if (logs != null) logs.close();
        }
    }

    /** Permanent original-task inspection and recovery. / 常驻原任务检查及恢复。 */
    private gold.debug.windowstolinux.app.ui.task.TaskRecoveryPane tasks;

    /** Permanent installed Mod choices and explicit data maintenance. / 常驻已安装 Mod 选择及明确数据维护。 */
    private gold.debug.windowstolinux.app.ui.mod.PlatformModsPane mods;

    /** Original-package view, absent in CLASS mode. / CLASS 模式下不存在的原包视图。 */
    private gold.debug.windowstolinux.app.ui.mod.ModOriginalsPane originals;

    /** Owned explicit Mod store view after management composition. / 管理装配后所属的明确 Mod 商店界面。 */
    private gold.debug.windowstolinux.app.ui.mod.ModStorePane store;

    /** Permanent installed and original package tabs. / 常驻已安装及原包标签页。 */
    private final JTabbedPane modManagement = new PlatformNavigationShell.PageDeck();

    private JComponent originalsPage;

    private AutoCloseable shutdown;

    private RestartActions restart;

    private boolean runtimeClosed;

    private Runnable restartExit = () -> System.exit(0);

    private JDialog restartProgress;

    /** Whether source-mode composition has been supplied. / 是否已提供来源模式装配。 */
    private boolean originalsConfigured;

    /** Actual opened business and settings surfaces. / 实际已打开的业务及设置界面。 */
    private final ExtensionPagePane extensions;

    /** Explicit user pins on the permanent navigation. / 常驻导航上的明确用户固定项。 */
    private gold.debug.windowstolinux.app.ui.mod.ModNavigationPane navigation;

    /** Startup feedback and its owned diagnostic subscription. / 启动反馈及其所属诊断订阅。 */
    private final Feedback feedback = new Feedback();

    /** Feedback state with the same window lifetime. / 与窗口同寿命的反馈状态。 */
    private static final class Feedback {
        /** Creates detached feedback controls before window composition. / 窗口装配前创建未挂载反馈控件。 */
        private Feedback() {
        }

        /** Visible non-recursive diagnostic persistence feedback. / 可见非递归诊断持久化反馈。 */
        private final JLabel logIssue = new JLabel();

        /** Non-secret startup counts independent of the selected page. / 独立于所选页面的不含秘密启动数量。 */
        private final JLabel modStatus = new JLabel();

        /** Safe failure viewer, unavailable while the desktop is locked. / 安全失败查看器，桌面锁定时不可用。 */
        private final JButton modIssues = new JButton();

        /** Navigation into the original log history page. / 导航到原日志历史页面。 */
        private final JButton openLogs = new JButton();

        /** Single-line status surface with an invariant application-pixel height. / 应用像素高度恒定的单行状态界面。 */
        private final JPanel bar = new JPanel(new BorderLayout(12, 0));

        /** Original bounded startup evidence for this boot. / 本次启动的原有界启动证据。 */
        private java.util.List<gold.debug.windowstolinux.appapi.task.result.Problem> modProblems = java.util.List.of();

        /** Actual feedback subscription owned by this window. / 此窗口持有的实际反馈订阅。 */
        private AutoCloseable logSubscription;

        /** Refreshes compact status controls without increasing the reserved height. / 刷新紧凑状态控件，不增加预留高度。
         * @param palette effective colors / 生效配色 */
        private void appearance(ThemePalette palette) {
            bar.setBackground(palette.sidebarBackground());
            bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, palette.cardBorder()),
                BorderFactory.createEmptyBorder(0, 12, 0, 8)));
            var font = UIManager.getFont("Label.font");
            for (var control : new JComponent[]{logIssue, modStatus, modIssues, openLogs}) {
                control.setForeground(palette.sidebarForeground());
                control.setFont(font.deriveFont(Math.min(18f, font.getSize2D())));
            }
        }

    }

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
    public PlatformFrame(SecurityAccess security, MessageCatalog catalog, ThemePalette palette,
                         UiEnvironment environment, FailureReportStore reports) {
        this(security, catalog, palette, environment, reports, gold.debug.windowstolinux.app.config.AppConfig.defaultsConfig());
    }

    /** Creates the configured shell without starting Mod code. / 创建配置外壳，不启动Mod代码。
     * @param security actual privacy boundary / 实际隐私边界
     * @param catalog language catalog / 语言目录
     * @param palette effective colors / 生效配色
     * @param environment UI environment / 界面环境
     * @param reports failure reports / 失败报告
     * @param config startup snapshot / 启动快照 */
    public PlatformFrame(SecurityAccess security, MessageCatalog catalog, ThemePalette palette,
                         UiEnvironment environment, FailureReportStore reports, gold.debug.windowstolinux.app.config.AppConfig config) {
        super("WindowsToLinux");
        this.config = Objects.requireNonNull(config);
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Platform window construction requires the EDT");
        this.security = Objects.requireNonNull(security);
        messages = new PageMessagePresenter(catalog, reports);
        localizeSwing();
        appearance = new Appearance(palette, environment);
        shell = new PlatformNavigationShell(content, catalog, palette);
        extensions = new ExtensionPagePane(this::closePage, owner -> owner, () -> catalog.text("security.locked"),
            this::selectModSurface);
        shell.workspaceHeading(extensions.headingComponent());
        extensions.platformNavigation(target -> shell.select(switch (target) {
            case SERVERS -> "platform.servers";
            case AI_MODELS -> "platform.models";
            case TASKS -> "tasks.title";
        }));
        pages = new ModPageRegistry(extensions, environment);
        try {
            pages.extensions().diagnostics(problem -> feedback.logIssue.setText(messages.safe(problem)));
        } catch (gold.debug.windowstolinux.appapi.task.result.ApiException failure) {
            throw new IllegalStateException("Extension feedback could not be composed", failure);
        }
        var settings = new SecuritySettingsPane(security, appearance.components, messages);
        var settingsSurface = appearance.components.pagePanel();
        settingsSurface.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        appearance.components.stylePage(settingsSurface);
        var securityCard = appearance.components.card(new BorderLayout());
        securityCard.add(settings);
        settingsSurface.add(securityCard, BorderLayout.NORTH);
        appearance.debug = new Debug(settingsSurface);
        var settingsScroll = new JScrollPane(settingsSurface);
        settingsScroll.setName("platform.security");
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        content.setName("platform.pages");
        content.addTab(catalog.text("shell.home"), messages.bind(home, "shell.home"));
        content.addTab(catalog.text("platform.pages"), messages.bind(extensions, "platform.pages"));
        content.addTab(catalog.text("security.settings"), messages.bind(settingsScroll, "security.settings"));
        content.addTab(catalog.text("platform.modSettings"),
            messages.bind(extensions.settingsComponent(), "platform.modSettings"));
        shell.home(home, this::showHome);
        installWorkspaceNavigation();
        shell.register(extensions, "shell.workspace", "navigation/panels-top-left", 0, false);
        shell.register(settingsScroll, "security.settings", "security/shield", 20, true);
        shell.register(extensions.settingsComponent(), "platform.modSettings", "navigation/panels-top-left", 60, true);
        getContentPane().add(shell, BorderLayout.CENTER);
        installFooter(palette);
        lock = DesktopLockController.attach(this, security, catalog, reports, shell.utilities(), appearance.components,
            false, () -> content.setSelectedComponent(settingsScroll));
        lock.onLockChange(locked -> {
            feedback.modIssues.setEnabled(!locked);
            appearance.debug.locked(locked);
            if (servers != null)
                servers.setLocked(locked);
            if (models != null)
                models.setLocked(locked);
            settingsViews.locked(locked);
            if (tasks != null)
                tasks.setLocked(locked);
            if (navigation != null)
                navigation.setLocked(locked);
            if (mods != null)
                mods.setLocked(locked);
            if (originals != null)
                originals.setLocked(locked);
            if (store != null) store.setLocked(locked);
            if (appearance.pane != null)
                appearance.pane.setLocked(locked);
            if (appearance.navigationOrder != null) appearance.navigationOrder.setLocked(locked);
            try {
                appearance.environment = new UiEnvironment(catalog.locale(), appearance.environment.themeId(), locked);
                pages.environment(appearance.environment);
            } catch (Exception failure) {
                reports.record(failure);
            }
        });
        shell.appearance(palette);
        shell.collapse(config.ui().navigationCollapsed(), false);
        DesktopWindowGeometry.install(this);
        ((PlatformNavigationShell.PageDeck) content).beforeLeave((current, next) ->
            gold.debug.windowstolinux.app.ui.control.CardEditGate.save(current).whenComplete((ok, failure) -> {
                if (failure == null && Boolean.TRUE.equals(ok)) next.run();
                else {
                    ((PlatformNavigationShell.PageDeck) content).cancelDeparture();
                    if (failure != null) DesktopDialogs.showMessageDialog(this, messages.safe(new Exception(failure)));
                }
            }));
        lock.beforeExit(() -> {
            if (!security.securityState().unlocked()
                && gold.debug.windowstolinux.app.ui.control.CardEditGate.dirty(content)) {
                lock.requireUnlockForDrafts();
                return java.util.concurrent.CompletableFuture.completedFuture(false);
            }
            return gold.debug.windowstolinux.app.ui.control.CardEditGate.save(content);
        });
        setLocationRelativeTo(null);
    }

    /** Binds root selection and startup restoration within the existing shell. / 在已有外壳绑定根选择及启动恢复。 */
    private void installWorkspaceNavigation() {
        extensions.closeLabel(messages.text("workspace.close"));
        extensions.onSelection(key -> {
            if (navigation != null) navigation.selected(content.getSelectedComponent() == extensions ? key : null);
            if (key == null && content.getSelectedComponent() == extensions) showHome();
        });
        content.addChangeListener(event -> {
            if (content.getSelectedComponent() != modManagement) {
                if (mods != null) mods.leaveDetails();
                if (store != null) store.leaveDetails();
            }
            if (pendingStartupPage != null && content.getSelectedComponent() != home) pendingStartupPage = null;
            if (navigation != null) navigation.selected(content.getSelectedComponent() == extensions
                ? extensions.selectedPage(false).orElse(null) : null);
        });
    }

    /** Mounts startup and diagnostic feedback independently of the page shell. / 独立于页面外壳挂载启动及诊断反馈。
     * @param palette effective colors / 生效配色 */
    private void installFooter(ThemePalette palette) {
        feedback.logIssue.setName("platform.logIssue");
        feedback.modStatus.setName("platform.modStatus");
        feedback.modIssues.setName("platform.modProblems");
        messages.bind(feedback.modIssues, "platform.modProblems");
        feedback.modIssues.setVisible(false);
        feedback.modIssues.addActionListener(event -> showModProblems());
        feedback.openLogs.setName("platform.viewLogs");
        messages.bind(feedback.openLogs, "platform.viewLogs");
        feedback.openLogs.putClientProperty("desktop.navigation", true);
        feedback.openLogs.setEnabled(false);
        feedback.openLogs.addActionListener(event -> content.setSelectedComponent(settingsViews.logs));
        messages.bind(feedback.logIssue, "platform.ready");
        for (var label : new JLabel[]{feedback.logIssue, feedback.modStatus}) {
            label.putClientProperty("html.disable", true);
            label.setMinimumSize(new java.awt.Dimension(0, 0));
            label.setToolTipText(label.getText());
            label.addPropertyChangeListener("text", event -> label.setToolTipText(label.getText()));
        }
        var actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(feedback.modStatus);
        actions.add(Box.createHorizontalStrut(12));
        actions.add(feedback.modIssues);
        actions.add(feedback.openLogs);
        for (var button : new JButton[]{feedback.modIssues, feedback.openLogs}) {
            button.setBorderPainted(false);
            button.setContentAreaFilled(false);
            button.setMargin(new java.awt.Insets(0, 8, 0, 8));
            button.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 26));
        }
        feedback.bar.setName("platform.statusBar");
        feedback.bar.setPreferredSize(new java.awt.Dimension(0, 30));
        feedback.bar.add(feedback.logIssue, BorderLayout.CENTER);
        feedback.bar.add(actions, BorderLayout.EAST);
        feedback.appearance(palette);
        getContentPane().add(feedback.bar, BorderLayout.SOUTH);
    }

    /** Sets standard dialog labels from the chosen application language. / 按所选应用语言设置标准弹窗按钮。 */
    private void localizeSwing() {
        JComponent.setDefaultLocale(messages.catalog().locale());
        UIManager.put("OptionPane.okButtonText", messages.text("button.ok"));
        UIManager.put("OptionPane.cancelButtonText", messages.text("button.cancel"));
        UIManager.put("OptionPane.yesButtonText", messages.text("value.yes"));
        UIManager.put("OptionPane.noButtonText", messages.text("value.no"));
    }

    /** Returns staged page ownership for Mod startup composition. / 返回用于 Mod 启动装配的暂存页面归属。
     * @return actual page registry / 实际页面登记 */
    public ModPageRegistry pages() {
        return pages;
    }

    /** Mounts actual Mod management after the original startup graph has settled. / 原启动图稳定后挂载实际 Mod 管理。
     * @param service original serialized intent service / 原串行意图服务
     * @param actions original graph maintenance / 原图维护
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if publication observation is unavailable / 发布观察不可用时 */
    public void installMods(gold.debug.windowstolinux.app.service.mod.PlatformModIntents service,
                            gold.debug.windowstolinux.app.ui.mod.PlatformModsPane.SchemaActions actions)
        throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        if (!SwingUtilities.isEventDispatchThread() || mods != null)
            throw new IllegalStateException("Mod management must be installed once on the EDT");
        navigation = new gold.debug.windowstolinux.app.ui.mod.ModNavigationPane(service, pages, appearance.components,
            messages);
        shell.pins(navigation, navigation::presentation);
        mods = new gold.debug.windowstolinux.app.ui.mod.PlatformModsPane(service, actions, appearance.components,
            messages, () -> {
            if (settingsViews.logs != null)
                content.setSelectedComponent(settingsViews.logs);
        }, navigation);
        modManagement.setName("mods.management");
        ((PlatformNavigationShell.PageDeck) modManagement).beforeLeave((current, next) -> {
            if (current == mods) mods.leaveDetails();
            if (current == store && store != null) store.leaveDetails();
            next.run();
        });
        modManagement.addTab(messages.text("mods.installed"), messages.bind(mods, "mods.installed"));
        content.addTab("Mod", modManagement);
        shell.register(modManagement, "shell.mods", "mod/puzzle", 30, false);
        navigation.setLocked(!security.securityState().unlocked());
        mods.setLocked(!security.securityState().unlocked());
        appearance.modOrderService = service;
        appearance.installModOrder();
    }

    /** Mounts originals only for an actual installation layout, otherwise explains CLASS source use. / 仅为实际安装布局挂载原包，否则说明 CLASS 源码来源。
     * @param service actual installation owner, absent in CLASS / 实际安装所有者，CLASS 时缺省 */
    public void installOriginalPackages(
        java.util.Optional<gold.debug.windowstolinux.app.service.mod.install.PlatformModInstallation> service) {
        if (!SwingUtilities.isEventDispatchThread() || mods == null || originalsConfigured)
            throw new IllegalStateException("Original management must be composed once after installed management");
        originalsConfigured = true;
        if (service.isEmpty()) {
            var notice = new JTextArea(messages.text("originals.classMode"));
            notice.setName("originals.classMode");
            notice.setEditable(false);
            notice.setLineWrap(true);
            notice.setWrapStyleWord(true);
            notice.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            var sourceNotice = appearance.components.card(new BorderLayout());
            sourceNotice.setName("mods.originals_class_mode");
            sourceNotice.add(messages.bind(notice, "originals.classMode"));
            originalsPage = originalSurface(sourceNotice);
            modManagement.addTab(messages.text("originals.title"), messages.bind(originalsPage, "originals.title"));
            return;
        }
        originals = new gold.debug.windowstolinux.app.ui.mod.ModOriginalsPane(service.orElseThrow(),
            appearance.components, messages, mods::refresh, () -> {
            if (settingsViews.logs != null)
                content.setSelectedComponent(settingsViews.logs);
        });
        mods.installMaintenance(service.orElseThrow(), originals::refresh);
        originalsPage = originalSurface(originals);
        modManagement.addTab(messages.text("originals.title"), messages.bind(originalsPage, "originals.title"));
        javax.swing.event.ChangeListener refresh = event -> {
            if (content.getSelectedComponent() == modManagement && modManagement.getSelectedComponent() == originalsPage)
                originals.refresh();
        };
        content.addChangeListener(refresh);
        modManagement.addChangeListener(refresh);
        originals.setLocked(!security.securityState().unlocked());
    }

    /** Mounts the explicit store only after Mod management exists. / Mod 管理具备后才挂载明确商店。
     * @param service configured store adapter / 配置商店适配器 */
    public void installStore(gold.debug.windowstolinux.app.service.mod.store.ModStoreService service) {
        if (!SwingUtilities.isEventDispatchThread() || mods == null || store != null)
            throw new IllegalStateException("Store composition requires Mod management");
        store = new gold.debug.windowstolinux.app.ui.mod.ModStorePane(service, appearance.components, messages,
            () -> {
                mods.refresh();
                if (originals != null) originals.refresh();
            });
        modManagement.addTab(messages.text("store.title"), messages.bind(store, "store.title"));
        mods.workspaceActions(() -> modManagement.setSelectedComponent(store), () -> {
            if (originalsPage != null) modManagement.setSelectedComponent(originalsPage);
        }, owner -> {
            modManagement.setSelectedComponent(store);
            store.selectOwner(owner);
        }, this::restartApplication);
        store.workspaceActions(() -> modManagement.setSelectedComponent(mods));
        store.setLocked(!security.securityState().unlocked());
    }

    private JComponent originalSurface(JComponent body) {
        var result = appearance.components.transparent(new BorderLayout(0, 8));
        result.setName(body.getName());
        body.setName(body.getName() + ".content");
        var back = messages.bind(appearance.components.secondaryButton(""), "modui.backInstalled");
        back.setName("modui.backInstalled");
        back.addActionListener(event -> modManagement.setSelectedComponent(mods));
        var header = appearance.components.transparent(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
        header.add(back);
        result.add(header, BorderLayout.NORTH);
        result.add(body, BorderLayout.CENTER);
        return result;
    }

    /** Focuses the existing outer surface after an explicit page open, including author requests. / 明确打开页面后聚焦已有外层界面，包括作者请求。
     * @param settings whether the page belongs to settings / 页面是否属于设置 */
    private void selectModSurface(boolean settings) {
        content.setSelectedComponent(settings ? extensions.settingsComponent() : extensions);
    }

    /** Shows actual startup counts while retaining safe failure details behind the lock boundary. / 展示实际启动数量，将安全失败详情保留在锁定边界之后。
     * @param running actually running Mod count / 实际运行 Mod 数量
     * @param problems original safe startup issues / 原安全启动问题 */
    public void showModStartup(int running, java.util.List<gold.debug.windowstolinux.appapi.task.result.Problem> problems) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Mod startup feedback requires the EDT");
        feedback.modProblems = java.util.List.copyOf(problems);
        gold.debug.windowstolinux.app.ui.language.LocaleBinding.bind(feedback.modStatus,
            gold.debug.windowstolinux.app.ui.language.LocalizedMessage.of("platform.modSummary",
                java.util.Map.of("running", running, "issues", problems.size())),
            messages.catalog());
        feedback.modIssues.setVisible(!problems.isEmpty());
        feedback.modIssues.setEnabled(security.securityState().unlocked());
    }

    /** Presents only safe host-formatted failures, bounded before opening the owned dialog. / 打开从属窗口前仅呈现有界的宿主安全格式化失败。
     */
    private void showModProblems() {
        if (!security.securityState().unlocked())
            return;
        var text = new StringBuilder();
        for (var problem : feedback.modProblems) {
            String owner = problem.context().getOrDefault("ownerId", "");
            text.append(owner).append(owner.isEmpty() ? "" : ": ").append(messages.safe(problem)).append('\n');
            if (text.length() > 32768) {
                text.setLength(32768);
                break;
            }
        }
        var details = new JTextArea(text.toString(), 16, 76);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        DesktopDialogs.showMessageDialog(this, new JScrollPane(details), messages.text("platform.modProblems"),
            JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Mounts the permanent server settings independently of Mod pages. / 独立于 Mod 页面挂载常驻服务器设置。
     * @param service actual host-owned settings and SSH checks / 实际宿主服务器设置及 SSH 检查
     */
    public void installServers(gold.debug.windowstolinux.app.service.server.PlatformServerService service) {
        if (!SwingUtilities.isEventDispatchThread() || servers != null)
            throw new IllegalStateException("Server page must be installed once on the EDT");
        servers = new gold.debug.windowstolinux.app.ui.server.PlatformServersPane(this, service, appearance.components,
            messages);
        content.insertTab(messages.text("platform.servers"), null, messages.bind(servers, "platform.servers"), null, 0);
        shell.register(servers, "platform.servers", "server/server", 10, false);
        servers.installExtensions(pages.extensions().platform());
        servers.setLocked(!security.securityState().unlocked());
    }

    /** Installs the independent rescue entry on the permanent server page. / 在常驻服务器页面安装独立救援入口。
     * @param service actual original-task rescue coordinator / 实际原任务救援协调器 */
    public void installRecovery(gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery service) {
        if (!SwingUtilities.isEventDispatchThread() || servers == null)
            throw new IllegalStateException("Rescue requires the installed server page on the EDT");
        servers.installRecovery(service, () -> {
            if (models != null)
                content.setSelectedComponent(models);
        });
    }

    /** Mounts platform model settings independently of business extensions. / 独立于业务扩展挂载平台模型设置。
     * @param service real model configuration owner / 真实模型配置所有者 */
    public void installModels(gold.debug.windowstolinux.app.service.ai.PlatformModelService service) {
        if (!SwingUtilities.isEventDispatchThread() || models != null)
            throw new IllegalStateException("Model page must be installed once on the EDT");
        models = new gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane(this, service, appearance.components,
            messages);
        content.insertTab(messages.text("platform.models"), null, messages.bind(models, "platform.models"), null,
            servers == null ? 0 : 1);
        shell.register(models, "platform.models", "ai/bot", 20, false);
        models.setLocked(!security.securityState().unlocked());
    }

    /** Mounts independent platform connection and no-progress preferences. / 挂载独立平台连接及无进展偏好。
     * @param service shared persisted threshold owner / 共用持久阈值所有者 */
    public void installTransfers(gold.debug.windowstolinux.app.service.setting.TransferSettingsService service) {
        if (!SwingUtilities.isEventDispatchThread() || settingsViews.transfers != null)
            throw new IllegalStateException("Transfer settings must be installed once on the EDT");
        settingsViews.transfers = new gold.debug.windowstolinux.app.ui.setting.TransferSettingsPane(service, appearance.components,
            messages);
        content.addTab(messages.text("transfer.settings.title"), messages.bind(settingsViews.transfers, "transfer.settings.title"));
        shell.register(settingsViews.transfers, "transfer.settings.title", "action/arrow-right", 30, true);
        settingsViews.transfers.setLocked(!security.securityState().unlocked());
    }

    /** Mounts the persistent optional deletion explanation preference. / 挂载持久可选删除说明偏好。
     * @param service actual preference owner / 实际偏好所有者 */
    public void installFileSettings(gold.debug.windowstolinux.app.service.setting.FileSettingsService service) {
        if (!SwingUtilities.isEventDispatchThread() || settingsViews.fileSettings != null)
            throw new IllegalStateException("File settings must be installed once on EDT");
        settingsViews.fileSettings = new gold.debug.windowstolinux.app.ui.setting.FileSettingsPane(service, appearance.components, messages);
        content.addTab(messages.text("file.settings.title"), messages.bind(settingsViews.fileSettings, "file.settings.title"));
        shell.register(settingsViews.fileSettings, "file.settings.title", "file/folder-open", 35, true);
        settingsViews.fileSettings.setLocked(!security.securityState().unlocked());
    }

    /** Mounts actual log management and storage-failure feedback. / 挂载实际日志管理及存储失败反馈。
     * @param service actual category and storage owner / 实际类别及存储所有者 */
    public void installLogs(gold.debug.windowstolinux.app.service.log.PlatformLogService service) {
        if (!SwingUtilities.isEventDispatchThread() || settingsViews.logs != null)
            throw new IllegalStateException("Log settings must be installed once on the EDT");
        settingsViews.logs = new gold.debug.windowstolinux.app.ui.setting.LogSettingsPane(service, appearance.components, messages);
        content.addTab(messages.text("logs.title"), messages.bind(settingsViews.logs, "logs.title"));
        shell.register(settingsViews.logs, "logs.title", "file/folder-open", 50, true);
        feedback.logSubscription = service.onIssue(problem -> SwingUtilities.invokeLater(() -> showLogIssue(problem)));
        service.issue().ifPresent(this::showLogIssue);
        settingsViews.logs.setLocked(!security.securityState().unlocked());
        feedback.openLogs.setEnabled(true);
    }

    /** Mounts retained tasks through a narrow host contract, reusing the existing log history page. / 通过窄宿主契约挂载保留任务，复用已有日志历史页。
     * @param service original task-management service / 原任务管理服务 */
    public void installTasks(gold.debug.windowstolinux.app.service.task.TaskManagement service) {
        if (!SwingUtilities.isEventDispatchThread() || tasks != null || settingsViews.logs == null)
            throw new IllegalStateException("Task management must be installed once after logs on the EDT");
        tasks = new gold.debug.windowstolinux.app.ui.task.TaskRecoveryPane(service, appearance.components, messages,
            () -> content.setSelectedComponent(settingsViews.logs));
        content.addTab(messages.text("tasks.title"), messages.bind(tasks, "tasks.title"));
        shell.register(tasks, "tasks.title", "navigation/panels-top-left", 40, false);
        content.addChangeListener(event -> {
            if (content.getSelectedComponent() == tasks)
                tasks.refresh();
        });
        tasks.setLocked(!security.securityState().unlocked());
    }

    /** Displays safe localized feedback outside the selected page. / 在所选页面之外显示安全本地化反馈。
     * @param problem structured storage issue / 结构化存储问题 */
    private void showLogIssue(gold.debug.windowstolinux.appapi.task.result.Problem problem) {
        String key = switch (problem.eventCode()) {
            case "LOG_DATABASE_UNCONFIRMED" -> "logs.emergencyNotice";
            case "LOG_REPLAY_PENDING" -> "logs.pendingNotice";
            default -> "logs.failureNotice";
        };
        messages.bind(feedback.logIssue, key);
    }

    /**
     * Registers actual resource shutdown, retaining the window after a failed close. / 登记实际资源关闭，关闭失败时保留窗口。
     * @param shutdown blocking platform cleanup invoked on a worker / 在工作线程调用的阻塞平台清理
     */
    public void onExit(AutoCloseable shutdown) {
        Objects.requireNonNull(shutdown);
        this.shutdown = shutdown;
        lock.onExit(() -> {
            if (closing)
                return;
            closing = true;
            StartupPageLocation location;
            try {
                location = currentPage();
            } catch (RuntimeException failure) {
                closing = false;
                DesktopDialogs.showMessageDialog(PlatformFrame.this, messages.safe(new Exception(failure)), "WindowsToLinux", JOptionPane.ERROR_MESSAGE);
                return;
            }
            new SwingWorker<Void, Void>() {
                /** Performs actual shutdown away from the event thread. / 在事件线程之外执行实际关闭。
                 * @return no result / 无返回值
                 * @throws Exception if owned resources remain / 仍有所属资源时 */
                @Override
                protected Void doInBackground() throws Exception {
                    rememberPage.save(location);
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
                        DesktopDialogs.showMessageDialog(PlatformFrame.this, messages.safe(new Exception(failure)), "WindowsToLinux",
                            JOptionPane.ERROR_MESSAGE);
                    }
                }
            }.execute();
        });
    }

    /** Internal lifecycle adapter, without exposing process control to Mods. / 不向Mod暴露进程控制的内部生命周期适配。 */
    public interface RestartActions extends AutoCloseable {
        void prepare() throws Exception;

        void start() throws Exception;

        RestartStatus status() throws Exception;

        boolean retryable();

        void close() throws Exception;
    }

    public enum RestartStatus {WAITING, READY, FAILED}

    public void onRestart(RestartActions actions) {
        if (restart != null) throw new IllegalStateException("Restart must be composed once");
        restart = Objects.requireNonNull(actions);
    }

    /** Saves already-reviewed lifecycle state and starts only after confirmed shutdown. / 仅在确认关闭后启动新实例。 */
    public void restartApplication() {
        if (closing || restart == null || shutdown == null || !security.securityState().unlocked()) return;
        closing = true;
        gold.debug.windowstolinux.app.ui.control.CardEditGate.save(this).whenComplete((saved, failure) ->
            SwingUtilities.invokeLater(() -> {
                if (failure != null || !Boolean.TRUE.equals(saved)) {
                    closing = false;
                    return;
                }
                var location = currentPage();
                showRestartProgress();
                new SwingWorker<Void, Void>() {
                    protected Void doInBackground() throws Exception {
                        restart.prepare();
                        rememberPage.save(location);
                        shutdown.close();
                        runtimeClosed = true;
                        restart.start();
                        while (true) {
                            var status = restart.status();
                            if (status == RestartStatus.READY) return null;
                            if (status == RestartStatus.FAILED)
                                throw new IllegalStateException(messages.text("modui.restart.failed"));
                            Thread.sleep(250);
                        }
                    }

                    protected void done() {
                        hideRestartProgress();
                        try {
                            get();
                            restart.close();
                            dispose();
                            restartExit.run();
                        } catch (Exception problem) {
                            if (!runtimeClosed) {
                                try {
                                    restart.close();
                                } catch (Exception cleanup) {
                                    problem.addSuppressed(cleanup);
                                }
                                closing = false;
                                DesktopDialogs.showMessageDialog(PlatformFrame.this, messages.safe(problem),
                                    messages.text("modui.restart.title"), JOptionPane.ERROR_MESSAGE);
                            } else restartFailure(problem);
                        }
                    }
                }.execute();
            }));
    }

    private void restartFailure(Exception problem) {
        var retry = messages.text("modui.restart.retry");
        var exit = messages.text("modui.restart.exit");
        Object[] options = restart.retryable() ? new Object[]{retry, exit} : new Object[]{exit};
        int answer = DesktopDialogs.showOptionDialog(this, messages.safe(problem), messages.text("modui.restart.title"),
            JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[0]);
        if (restart.retryable() && answer == 0) {
            showRestartProgress();
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() throws Exception {
                    restart.prepare();
                    restart.start();
                    while (true) {
                        var state = restart.status();
                        if (state == RestartStatus.READY) return null;
                        if (state == RestartStatus.FAILED)
                            throw new IllegalStateException(messages.text("modui.restart.failed"));
                        Thread.sleep(250);
                    }
                }

                protected void done() {
                    hideRestartProgress();
                    try {
                        get();
                        restart.close();
                        dispose();
                        restartExit.run();
                    } catch (Exception failure) {
                        restartFailure(failure);
                    }
                }
            }.execute();
        } else {
            try {
                restart.close();
            } catch (Exception failure) {
                reportsRecord(failure);
            }
            dispose();
            restartExit.run();
        }
    }

    private void reportsRecord(Exception failure) {
        feedback.logIssue.setText(messages.safe(failure));
    }

    private void showRestartProgress() {
        restartProgress = new JDialog(this, messages.text("modui.restart.title"), java.awt.Dialog.ModalityType.DOCUMENT_MODAL);
        restartProgress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        var body = appearance.components.transparent(new BorderLayout(12, 12));
        body.add(messages.bind(new JLabel(), "modui.restart.waiting"), BorderLayout.NORTH);
        var progress = new JProgressBar();
        progress.setIndeterminate(true);
        body.add(progress, BorderLayout.CENTER);
        DesktopDialogs.install(restartProgress, body, null, 420, 0);
        var dialog = restartProgress;
        SwingUtilities.invokeLater(() -> {
            if (restartProgress == dialog) dialog.setVisible(true);
        });
    }

    private void hideRestartProgress() {
        if (restartProgress != null) restartProgress.dispose();
        restartProgress = null;
    }

    /** Installs the normal-close page writer without reading preferences on the EDT. / 安装正常关闭页面写入器，不在 EDT 读取偏好。
     * @param memory original preference write / 原偏好写入 */
    public void rememberPage(PageMemory memory) {
        rememberPage = Objects.requireNonNull(memory);
    }

    /** Opens the permanent home and cancels a pending startup restoration. / 打开常驻首页，并取消待执行启动恢复。 */
    public void showHome() {
        pendingStartupPage = null;
        content.setSelectedComponent(home);
    }

    /** Captures stable navigation only, without view drafts or credentials. / 仅捕获稳定导航，不含页面草稿或凭据。
     * @return current non-secret location / 当前非秘密位置 */
    public StartupPageLocation currentPage() {
        String id = shell.selectedId();
        if (id.equals("shell.workspace") || id.equals("platform.modSettings")) {
            var selected = extensions.selectedPage(id.equals("platform.modSettings"));
            if (selected.isPresent()) {
                var key = selected.orElseThrow();
                try {
                    return new StartupPageLocation(id, key.ownerId(), key.pageId(), id.equals("shell.workspace") ? pages.navigationId(key) : "");
                } catch (ApiException failure) {
                    throw new IllegalStateException("Cannot capture internal navigation", failure);
                }
            }
        }
        String detail = id.equals("shell.mods") && modManagement.getSelectedComponent() != null
            ? Objects.toString(modManagement.getSelectedComponent().getName(), "") : "";
        return new StartupPageLocation(id, "", "", detail);
    }

    /** Restores present platform pages now and defers Mod pages until publication completes. / 立即恢复已有平台页面，Mod 页面等待发布完成。
     * @param location retained stable target / 保留稳定目标 */
    public void resumePage(StartupPageLocation location) {
        showHome();
        if (location.platformId().equals("shell.workspace") && location.ownerId().isEmpty()) return;
        if (!location.ownerId().isEmpty()) {
            pendingStartupPage = location;
            return;
        }
        if (!shell.select(location.platformId())) {
            pendingStartupPage = location;
            return;
        }
        if (location.platformId().equals("shell.mods") && !location.detailId().isEmpty()) {
            for (int index = 0; index < modManagement.getTabCount(); index++) {
                var page = modManagement.getComponentAt(index);
                if (location.detailId().equals(page.getName())) {
                    modManagement.setSelectedComponent(page);
                    return;
                }
            }
            showHome();
        }
    }

    /** Resolves the one startup Mod target after startup composition, respecting newer navigation. / 启动装配后解析一次 Mod 目标，并遵守较新的导航选择。 */
    public void finishStartupNavigation() {
        StartupPageLocation target = pendingStartupPage;
        pendingStartupPage = null;
        if (target == null || content.getSelectedComponent() != home) return;
        if (target.ownerId().isEmpty()) {
            resumePage(target);
            pendingStartupPage = null;
            return;
        }
        try {
            boolean settings = target.platformId().equals("platform.modSettings");
            var published = pages.contributions().stream().filter(page -> page.key().ownerId().equals(target.ownerId())
                    && page.spec().settingsPage() == settings && (!settings || page.key().pageId().equals(target.pageId())))
                .findFirst();
            if (published.isPresent()) {
                var key = published.orElseThrow().key();
                String detail = target.detailId().isEmpty() && !target.pageId().equals(key.pageId()) ? target.pageId() : target.detailId();
                pages.navigateView(key, settings ? null : detail);
            }
        } catch (Exception failure) {
            showHome();
            gold.debug.windowstolinux.app.ui.language.LocaleBinding.unbind(feedback.logIssue);
            feedback.logIssue.setText(messages.safe(new Exception(failure)));
        }
    }

    /** Narrow non-secret close preference operation. / 窄非秘密关闭偏好操作。 */
    @FunctionalInterface
    public interface PageMemory {
        /** Saves the captured target before runtime cleanup. / 运行时清理前保存捕获目标。
         * @param location captured target / 捕获目标
         * @throws Exception if persistence fails / 持久化失败时 */
        void save(StartupPageLocation location) throws Exception;
    }

    /**
     * Closes one author view through the tracked page registry. / 通过跟踪页面登记关闭一个作者视图。
     * @param key exact owner and page identity / 精确所有者及页面身份
     */
    private void closePage(ModPageRegistry.PageKey key) {
        if (!security.securityState().unlocked()) return;
        Object[] options = {messages.text("workspace.close"), messages.text("button.cancel")};
        int choice = DesktopDialogs.showOptionDialog(this, messages.text("workspace.close.confirm"),
            messages.text("workspace.close"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, options, options[1]);
        if (choice != 0) return;
        try {
            pages.closeView(key);
        } catch (Exception failure) {
            DesktopDialogs.showMessageDialog(this, messages.safe(new Exception(failure)), "WindowsToLinux", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Releases privacy listeners and disposes the window after lifecycle cleanup. / 生命周期清理后释放隐私监听器并销毁窗口。 */
    @Override
    public void dispose() {
        appearance.debug.close();
        if (appearance.navigationOrder != null) appearance.navigationOrder.close();
        if (appearance.orderSubscription != null) try {
            appearance.orderSubscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Navigation observer cleanup failed", failure);
        }
        if (appearance.pane != null)
            appearance.pane.close();
        if (servers != null)
            servers.close();
        if (models != null)
            models.close();
        settingsViews.close();
        if (tasks != null)
            tasks.close();
        if (mods != null)
            mods.close();
        if (originals != null)
            originals.close();
        if (store != null) store.close();
        if (navigation != null)
            navigation.close();
        if (feedback.logSubscription != null)
            try {
                feedback.logSubscription.close();
            } catch (Exception failure) {
                feedback.logIssue.setText(messages.text("logs.failed"));
            }
        lock.close();
        shell.close();
        super.dispose();
    }

    /** Mounts explicit persisted display choices without rebuilding any platform or Mod state. / 挂载明确的持久显示选择，不重建任何平台或 Mod 状态。
     * @param initial confirmed display preferences / 已确认显示偏好
     * @param preferences original secure persistence operation / 原安全持久化操作 */
    public void installAppearance(gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig initial,
                                  gold.debug.windowstolinux.app.ui.setting.PlatformAppearancePane.Preferences preferences) {
        appearance.install(initial, preferences);
    }

    /** Installs ordering within appearance after platform entries exist. / 平台入口具备后在外观内安装排序。
     * @param service original preference service / 原偏好服务 */
    public void installNavigationOrder(gold.debug.windowstolinux.app.service.setting.NavigationOrderService service) {
        if (!SwingUtilities.isEventDispatchThread() || appearance.pane == null || appearance.navigationOrder != null)
            throw new IllegalStateException("Navigation order must be installed once after appearance");
        appearance.navigationOrder = new gold.debug.windowstolinux.app.ui.setting.NavigationOrderPane(appearance.components, messages);
        appearance.navigationOrder.section("navigation.platform", () -> service.load(shell.defaults()),
            saved -> saved.stream().filter(shell.defaults()::contains).toList(),
            visible -> shell.defaults(), id -> messages.text(id),
            (expected, requested) -> service.save(shell.defaults(), expected, requested), shell::order);
        var ordering = new JScrollPane(appearance.navigationOrder);
        ordering.setBorder(BorderFactory.createEmptyBorder());
        appearance.pane.ordering(ordering);
        content.addChangeListener(event -> {
            if (content.getSelectedComponent() == appearance.pane) appearance.navigationOrder.refresh();
        });
        appearance.navigationOrder.setLocked(!security.securityState().unlocked());
        appearance.installModOrder();
    }

    /** Installs the advanced preview entry only for a resolved CLASS layout. / 仅为已解析CLASS布局安装高级预览入口。
     * @param classMode actual resolved executable mode / 实际已解析可执行模式
     * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if host installation is duplicated / 宿主重复安装时 */
    public void installUiDebug(boolean classMode) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
        appearance.debug.install(classMode);
    }

    /** Owns the isolated advanced entry and one gallery without retaining runtime services. / 不保留运行时服务地持有隔离高级入口及单个目录。 */
    private final class Debug {
        /** Original settings placement. / 原设置挂载位置。 */
        private final JPanel settings;

        /** Explicit advanced entry, absent outside CLASS. / 明确高级入口；CLASS外不存在。 */
        private JButton button;

        /** Original single gallery and its owned dialogs. / 原单个目录及从属对话框。 */
        private gold.debug.windowstolinux.app.ui.debug.UiDebugDialog gallery;

        /** Prevents mode replacement after composition. / 装配后阻止替换模式。 */
        private boolean installed;

        /** Retains only the local settings surface. / 仅保留本地设置界面。
         * @param settings original local surface / 原本地界面 */
        private Debug(JPanel settings) {
            this.settings = settings;
        }

        /** Installs one CLASS-only collapsed advanced section. / 安装一个仅CLASS的折叠高级分区。
         * @param classMode resolved actual mode / 已解析实际模式
         * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if duplicate / 重复时 */
        private void install(boolean classMode) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
            if (!SwingUtilities.isEventDispatchThread() || installed)
                throw new IllegalStateException("Debug composition must happen once on the EDT");
            installed = true;
            if (!classMode)
                return;
            pages.installPreviews(
                new gold.debug.windowstolinux.app.ui.debug.UiDebugPreviewHost(pages, () -> gallery));
            button = messages.bind(appearance.components.secondaryButton(""), "settings.debug.open");
            button.setName("settings.uiDebug");
            button.addActionListener(event -> open());
            var details = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
            details.add(button);
            details.add(messages.bind(new JLabel(), "settings.debug.hint"));
            details.setVisible(config.ui().advancedExpanded());
            var toggle = messages.bind(new JToggleButton(), config.ui().advancedExpanded() ? "advanced.hide" : "advanced.show");
            toggle.setSelected(config.ui().advancedExpanded());
            toggle.setName("settings.advanced");
            toggle.addActionListener(event -> {
                details.setVisible(toggle.isSelected());
                messages.bind(toggle, toggle.isSelected() ? "advanced.hide" : "advanced.show");
                settings.revalidate();
            });
            var advanced = new JPanel(new BorderLayout(8, 8));
            advanced.add(toggle, BorderLayout.NORTH);
            advanced.add(details, BorderLayout.CENTER);
            settings.add(advanced, BorderLayout.SOUTH);
            locked(!security.securityState().unlocked());
        }

        /** Opens or focuses the same unlocked gallery. / 打开或聚焦同一已解锁目录。 */
        private void open() {
            if (closing || button == null || !button.isEnabled() || !security.securityState().unlocked())
                return;
            try {
                if (gallery == null || !gallery.isDisplayable())
                    gallery = new gold.debug.windowstolinux.app.ui.debug.UiDebugDialog(PlatformFrame.this, pages,
                        appearance.components, messages);
                if (gallery.getRootPane().getClientProperty("debug.cleanupProblem") != null)
                    return;
                gallery.navigation(links());
                gallery.setVisible(true);
                gallery.toFront();
            } catch (gold.debug.windowstolinux.appapi.task.result.ApiException failure) {
                getRootPane().putClientProperty("debug.problem", failure.problem());
            }
        }

        /** Lists actual platform tabs and already committed author pages without opening them. / 不打开页面地列出真实平台标签及已提交作者页面。
         * @return current workspace selections / 当前工作区选择
         * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if metadata is unavailable / 元数据不可用时 */
        private java.util.List<gold.debug.windowstolinux.app.ui.debug.UiDebugDialog.PageLink> links()
            throws gold.debug.windowstolinux.appapi.task.result.ApiException {
            var result = new java.util.ArrayList<gold.debug.windowstolinux.app.ui.debug.UiDebugDialog.PageLink>();
            for (int index = 0; index < content.getTabCount(); index++) {
                int selected = index;
                result.add(new gold.debug.windowstolinux.app.ui.debug.UiDebugDialog.PageLink("platform-" + index,
                    content.getTitleAt(index), () -> content.setSelectedIndex(selected)));
            }
            for (var contribution : pages.contributions())
                result.add(new gold.debug.windowstolinux.app.ui.debug.UiDebugDialog.PageLink(
                    contribution.key().ownerId() + "." + contribution.key().pageId(),
                    contribution.spec().defaultTitle(), () -> {
                    try {
                        pages.openView(contribution.key());
                    } catch (gold.debug.windowstolinux.appapi.task.result.ApiException failure) {
                        getRootPane().putClientProperty("debug.problem", failure.problem());
                    }
                }));
            return java.util.List.copyOf(result);
        }

        /** Hides and closes previews before allowing locked display. / 允许锁定显示前隐藏并关闭预览。
         * @param locked current privacy state / 当前隐私状态 */
        private void locked(boolean locked) {
            if (button != null)
                button.setEnabled(!locked && !closing);
            if (locked && gallery != null) {
                gallery.setVisible(false);
                gallery.dispose();
            }
        }

        /** Releases the original gallery during actual window teardown. / 实际窗口销毁时释放原目录。 */
        private void close() {
            if (gallery != null)
                gallery.dispose();
        }

        /** Refreshes one existing catalog and its nested previews. / 刷新一个既有目录及嵌套预览。 */
        private void localize() {
            if (gallery != null && gallery.isDisplayable()) {
                gallery.localize();
                try {
                    gallery.navigation(links());
                } catch (gold.debug.windowstolinux.appapi.task.result.ApiException failure) {
                    getRootPane().putClientProperty("debug.problem", failure.problem());
                }
            }
        }
    }

    /** Owns display composition independently of platform task and lifecycle state. / 独立于平台任务和生命周期状态持有显示装配。 */
    private final class Appearance {
        /** Appearance-owned platform and Mod entrance order editor. / 外观所属的平台及 Mod 入口顺序编辑器。 */
        private gold.debug.windowstolinux.app.ui.setting.NavigationOrderPane navigationOrder;

        /** Owned subscription refreshing Mod order after publication changes. / 发布变化后刷新 Mod 顺序的所属订阅。 */
        private AutoCloseable orderSubscription;

        /** Original transactional Mod navigation service. / 原事务 Mod 导航服务。 */
        private gold.debug.windowstolinux.app.service.mod.PlatformModIntents modOrderService;

        /** Mounts pins once regardless of appearance/Mod composition order. / 不论外观及 Mod 装配顺序，仅挂载一次固定项排序。 */
        private void installModOrder() {
            if (navigationOrder == null || modOrderService == null || orderSubscription != null) return;
            navigationOrder.section("navigation.mods", navigation::savedWorkspaces, navigation::visiblePins,
                visible -> visible.stream().sorted(java.util.Comparator.comparing(
                        gold.debug.windowstolinux.app.service.mod.PlatformModIntents.PagePin::ownerId)
                    .thenComparing(gold.debug.windowstolinux.app.service.mod.PlatformModIntents.PagePin::pageId)).toList(),
                navigation::pinTitle, modOrderService::reorderPins, navigation::confirmedOrder);
            orderSubscription = navigation.onChange(navigationOrder::refresh);
            navigationOrder.refresh();
        }

        /** Display-only gallery sharing this appearance lifetime. / 共用此外观寿命的仅显示目录。 */
        private Debug debug;

        /** Current control factory shared with existing pages. / 与已有页面共享的当前控件工厂。 */
        private final DesktopPartFactory components;

        /** Current author-page locale, effective theme and lock state. / 当前作者页面区域、生效主题及锁定状态。 */
        private UiEnvironment environment;

        /** Explicit preference editor, installed once. / 安装一次的明确偏好编辑器。 */
        private gold.debug.windowstolinux.app.ui.setting.PlatformAppearancePane pane;

        /** Binds initial display without changing the runtime. / 绑定初始显示，不改变运行器。
         * @param palette initial colors / 初始颜色
         * @param environment original author environment / 原作者环境 */
        private Appearance(ThemePalette palette, UiEnvironment environment) {
            components = new DesktopPartFactory(palette);
            this.environment = environment;
        }

        /** Installs the preference editor once on the EDT. / 在事件线程安装一次偏好编辑器。
         * @param initial confirmed preference / 已确认偏好
         * @param preferences original persistence / 原持久化 */
        private void install(gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig initial,
                             gold.debug.windowstolinux.app.ui.setting.PlatformAppearancePane.Preferences preferences) {
            if (!SwingUtilities.isEventDispatchThread() || pane != null)
                throw new IllegalStateException("Appearance must be installed once on the EDT");
            pane = new gold.debug.windowstolinux.app.ui.setting.PlatformAppearancePane(initial, preferences,
                this::apply, components, messages);
            content.addTab(messages.text("settings.appearance.title"),
                messages.bind(pane, "settings.appearance.title"));
            shell.register(pane, "settings.appearance.title", "navigation/settings", 10, true);
            pane.setLocked(!security.securityState().unlocked());
        }

        /** Updates existing widgets and notifies existing author pages of a confirmed preference. / 更新已有控件，并通知已有作者页面已确认偏好。
         * @param requested successfully persisted display choice / 成功持久化的显示选择 */
        private void apply(gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig requested) {
            messages.catalog().language(requested.localeTag());
            localizeSwing();
            var effective = gold.debug.windowstolinux.app.ui.display.SystemThemeResolver
                .effectiveTheme(requested.themeMode());
            components.palette(effective == gold.debug.windowstolinux.app.ui.display.ThemeMode.DARK
                ? ThemePalette.dark()
                : ThemePalette.light());
            gold.debug.windowstolinux.app.ui.display.DesktopThemeService.apply(effective, requested.scalePercent());
            components.refreshTheme(PlatformFrame.this);
            gold.debug.windowstolinux.app.ui.language.LocaleBinding.refresh(PlatformFrame.this, messages.catalog());
            shell.appearance(effective == gold.debug.windowstolinux.app.ui.display.ThemeMode.DARK
                ? ThemePalette.dark()
                : ThemePalette.light());
            feedback.appearance(effective == gold.debug.windowstolinux.app.ui.display.ThemeMode.DARK
                ? ThemePalette.dark() : ThemePalette.light());
            debug.localize();
            if (models != null)
                models.localize();
            if (servers != null)
                servers.localize();
            if (tasks != null)
                tasks.localize();
            for (var owned : getOwnedWindows()) {
                components.refreshTheme(owned);
                gold.debug.windowstolinux.app.ui.language.LocaleBinding.refresh(owned, messages.catalog());
            }
            environment = new UiEnvironment(messages.catalog().locale(), effective.name(),
                !security.securityState().unlocked());
            extensions.closeLabel(messages.text("workspace.close"));
            try {
                pages.environment(environment);
            } catch (Exception failure) {
                DesktopDialogs.showMessageDialog(PlatformFrame.this, messages.safe(new Exception(failure)), "WindowsToLinux",
                    JOptionPane.ERROR_MESSAGE);
            }
            revalidate();
            repaint();
        }
    }
}
