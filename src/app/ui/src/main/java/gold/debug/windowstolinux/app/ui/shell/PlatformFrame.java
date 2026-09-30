package gold.debug.windowstolinux.app.ui.shell;

import java.awt.BorderLayout;
import java.util.Objects;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.security.SecurityApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.diagnostic.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.setting.SecuritySettingsPane;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;

/** Platform desktop hosting security controls and independently owned Mod views. / 承载安全控件及独立所属 Mod 视图的平台桌面。 */
public final class PlatformFrame extends JFrame {
    /** Platform privacy boundary including owned dialogs. / 包含从属对话框的平台隐私边界。 */
    private final DesktopLockController lock;

    /** Staged Mod page registry used by the actual startup coordinator. / 实际启动协调器使用的暂存 Mod 页面登记。 */
    private final ModPageRegistry pages;

    /** Safe localized diagnostic formatter. / 安全的本地化诊断格式化器。 */
    private final PageMessagePresenter messages;

    /** Permanent platform and extension navigation. / 常驻平台及扩展导航。 */
    private final JTabbedPane content = new JTabbedPane();

    /** Current theme used by platform configuration dialogs. / 平台配置窗口使用的当前主题。 */
    private final Appearance appearance;

    /** Current non-secret security status. / 当前不含秘密的安全状态。 */
    private final SecurityApplicationFacade security;

    /** Installed permanent server page, if composition has completed. / 已安装常驻服务器页面，装配完成后存在。 */
    private gold.debug.windowstolinux.app.ui.server.PlatformServersPane servers;

    /** Permanent model inventory and capability checks. / 常驻模型清单及能力检查。 */
    private gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane models;

    /** Permanent connection and transfer threshold settings. / 常驻连接及传输阈值设置。 */
    private gold.debug.windowstolinux.app.ui.setting.TransferSettingsPane transfers;

    /** Permanent policy preference independent of AI review. / 独立于 AI 审批的常驻策略偏好。 */
    private gold.debug.windowstolinux.app.ui.setting.AuthorizationSettingsPane authorization;

    /** Permanent diagnostic management page. / 常驻诊断管理页面。 */
    private gold.debug.windowstolinux.app.ui.setting.LogSettingsPane logs;

    /** Permanent original-task inspection and recovery. / 常驻原任务检查及恢复。 */
    private gold.debug.windowstolinux.app.ui.recovery.TaskRecoveryPane tasks;

    /** Permanent installed Mod choices and explicit data maintenance. / 常驻已安装 Mod 选择及明确数据维护。 */
    private gold.debug.windowstolinux.app.ui.mod.PlatformModsPane mods;

    /** Original-package view, absent in CLASS mode. / CLASS 模式下不存在的原包视图。 */
    private gold.debug.windowstolinux.app.ui.mod.ModOriginalsPane originals;

    /** Permanent installed and original package tabs. / 常驻已安装及原包标签页。 */
    private final JTabbedPane modManagement = new JTabbedPane();

    /** Whether source-mode composition has been supplied. / 是否已提供来源模式装配。 */
    private boolean originalsConfigured;

    /** Actual opened business and settings surfaces. / 实际已打开的业务及设置界面。 */
    private final ExtensionPagePane extensions;

    /** Explicit user pins on the permanent navigation. / 常驻导航上的明确用户固定项。 */
    private gold.debug.windowstolinux.app.ui.mod.ModNavigationPane navigation;

    /** Security controls and user-pinned navigation. / 安全控件及用户固定导航。 */
    private final JPanel sidebar = new JPanel();

    /** Visible non-recursive diagnostic persistence feedback. / 可见非递归诊断持久化反馈。 */
    private final JLabel logIssue = new JLabel();

    /** Non-secret startup counts independent of the selected page. / 独立于所选页面的不含秘密启动数量。 */
    private final JLabel modStatus = new JLabel();

    /** Safe failure viewer, unavailable while the desktop is locked. / 安全失败查看器，桌面锁定时不可用。 */
    private final JButton modIssues = new JButton();

    /** Original bounded startup evidence for this boot. / 本次启动的原有界启动证据。 */
    private java.util.List<gold.debug.windowstolinux.appapi.task.Problem> modProblems = java.util.List.of();

    /** Actual feedback subscription owned by this window. / 此窗口持有的实际反馈订阅。 */
    private AutoCloseable logSubscription;

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
        this.security = Objects.requireNonNull(security);
        messages = new PageMessagePresenter(catalog, reports);
        appearance = new Appearance(palette, environment);
        extensions = new ExtensionPagePane(this::closePage, owner -> owner, () -> catalog.text("security.locked"),
                this::selectModSurface);
        pages = new ModPageRegistry(extensions, environment);
        var settings = new SecuritySettingsPane(security, appearance.components, messages);
        var settingsSurface = new JPanel(new BorderLayout());
        settingsSurface.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        settingsSurface.add(settings, BorderLayout.NORTH);
        var settingsScroll = new JScrollPane(settingsSurface);
        settingsScroll.setBorder(BorderFactory.createEmptyBorder());
        content.setName("platform.pages");
        content.addTab(catalog.text("platform.pages"), messages.bind(extensions, "platform.pages"));
        content.addTab(catalog.text("security.settings"), messages.bind(settingsScroll, "security.settings"));
        content.addTab(catalog.text("platform.modSettings"), messages.bind(extensions.settingsComponent(), "platform.modSettings"));
        if (!security.securityState().configured())
            content.setSelectedComponent(settingsScroll);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        getContentPane().add(sidebar, BorderLayout.WEST);
        getContentPane().add(content, BorderLayout.CENTER);
        logIssue.setName("platform.logIssue");
        modStatus.setName("platform.modStatus");
        modIssues.setName("platform.modProblems");
        messages.bind(modIssues, "platform.modProblems");
        modIssues.setVisible(false);
        modIssues.addActionListener(event -> showModProblems());
        var footer = new JPanel(new java.awt.GridLayout(0, 1));
        var modLine = new JPanel(new BorderLayout());
        modLine.add(modStatus, BorderLayout.CENTER);
        modLine.add(modIssues, BorderLayout.EAST);
        footer.add(logIssue);
        footer.add(modLine);
        getContentPane().add(footer, BorderLayout.SOUTH);
        lock = DesktopLockController.attach(this, security, catalog, reports, sidebar, appearance.components, false,
                () -> content.setSelectedComponent(settingsScroll));
        lock.onLockChange(locked -> {
            modIssues.setEnabled(!locked);
            if (servers != null)
                servers.setLocked(locked);
            if (models != null)
                models.setLocked(locked);
            if (transfers != null)
                transfers.setLocked(locked);
            if (authorization != null)
                authorization.setLocked(locked);
            if (logs != null)
                logs.setLocked(locked);
            if (tasks != null)
                tasks.setLocked(locked);
            if (navigation != null)
                navigation.setLocked(locked);
            if (mods != null)
                mods.setLocked(locked);
            if (originals != null)
                originals.setLocked(locked);
            if (appearance.pane != null)
                appearance.pane.setLocked(locked);
            try {
                appearance.environment = new UiEnvironment(catalog.locale(), appearance.environment.themeId(), locked);
                pages.environment(appearance.environment);
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

    /** Mounts actual Mod management after the original startup graph has settled. / 原启动图稳定后挂载实际 Mod 管理。
     * @param service original serialized intent service / 原串行意图服务
     * @param actions original graph maintenance / 原图维护
     * @throws gold.debug.windowstolinux.appapi.task.ApiException if publication observation is unavailable / 发布观察不可用时 */
    public void installMods(gold.debug.windowstolinux.app.service.mod.PlatformModIntents service,
            gold.debug.windowstolinux.app.ui.mod.PlatformModsPane.SchemaActions actions)
            throws gold.debug.windowstolinux.appapi.task.ApiException {
        if (!SwingUtilities.isEventDispatchThread() || mods != null)
            throw new IllegalStateException("Mod management must be installed once on the EDT");
        navigation = new gold.debug.windowstolinux.app.ui.mod.ModNavigationPane(service, pages, appearance.components, messages);
        sidebar.add(navigation);
        mods = new gold.debug.windowstolinux.app.ui.mod.PlatformModsPane(service, actions, appearance.components, messages, () -> {
            if (logs != null)
                content.setSelectedComponent(logs);
        }, navigation);
        modManagement.setName("mods.management");
        modManagement.addTab(messages.text("mods.installed"), messages.bind(mods, "mods.installed"));
        content.addTab("Mod", modManagement);
        navigation.setLocked(!security.securityState().unlocked());
        mods.setLocked(!security.securityState().unlocked());
    }

    /** Mounts originals only for an actual installation layout, otherwise explains CLASS source use. / 仅为实际安装布局挂载原包，否则说明 CLASS 源码来源。
     * @param service actual installation owner, absent in CLASS / 实际安装所有者，CLASS 时缺省 */
    public void installOriginalPackages(
            java.util.Optional<gold.debug.windowstolinux.app.service.mod.PlatformModInstallation> service) {
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
            modManagement.addTab(messages.text("originals.title"), messages.bind(notice, "originals.title"));
            return;
        }
        originals = new gold.debug.windowstolinux.app.ui.mod.ModOriginalsPane(service.orElseThrow(), appearance.components,
                messages, mods::refresh, () -> {
                    if (logs != null)
                        content.setSelectedComponent(logs);
                });
        mods.installMaintenance(service.orElseThrow(), originals::refresh);
        modManagement.addTab(messages.text("originals.title"), messages.bind(originals, "originals.title"));
        javax.swing.event.ChangeListener refresh = event -> {
            if (content.getSelectedComponent() == modManagement && modManagement.getSelectedComponent() == originals)
                originals.refresh();
        };
        content.addChangeListener(refresh);
        modManagement.addChangeListener(refresh);
        originals.setLocked(!security.securityState().unlocked());
    }

    /** Focuses the existing outer surface after an explicit page open, including author requests. / 明确打开页面后聚焦已有外层界面，包括作者请求。
     * @param settings whether the page belongs to settings / 页面是否属于设置 */
    private void selectModSurface(boolean settings) {
        content.setSelectedComponent(settings ? extensions.settingsComponent() : extensions);
    }

    /** Shows actual startup counts while retaining safe failure details behind the lock boundary. / 展示实际启动数量，将安全失败详情保留在锁定边界之后。
     * @param running actually running Mod count / 实际运行 Mod 数量
     * @param problems original safe startup issues / 原安全启动问题 */
    public void showModStartup(int running, java.util.List<gold.debug.windowstolinux.appapi.task.Problem> problems) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Mod startup feedback requires the EDT");
        modProblems = java.util.List.copyOf(problems);
        gold.debug.windowstolinux.app.ui.i18n.LocaleBinding.bind(modStatus,
                gold.debug.windowstolinux.app.ui.i18n.LocalizedMessage.of("platform.modSummary",
                        java.util.Map.of("running", running, "issues", problems.size())), messages.catalog());
        modIssues.setVisible(!problems.isEmpty());
        modIssues.setEnabled(security.securityState().unlocked());
    }

    /** Presents only safe host-formatted failures, bounded before opening the owned dialog. / 打开从属窗口前仅呈现有界的宿主安全格式化失败。
     */
    private void showModProblems() {
        if (!security.securityState().unlocked())
            return;
        var text = new StringBuilder();
        for (var problem : modProblems) {
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
        JOptionPane.showMessageDialog(this, new JScrollPane(details), messages.text("platform.modProblems"),
                JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Mounts the permanent server settings independently of Mod pages. / 独立于 Mod 页面挂载常驻服务器设置。
     * @param service actual host-owned settings and SSH checks / 实际宿主服务器设置及 SSH 检查
     */
    public void installServers(gold.debug.windowstolinux.app.service.server.PlatformServerService service) {
        if (!SwingUtilities.isEventDispatchThread() || servers != null)
            throw new IllegalStateException("Server page must be installed once on the EDT");
        servers = new gold.debug.windowstolinux.app.ui.server.PlatformServersPane(this, service, appearance.components, messages);
        content.insertTab(messages.text("platform.servers"), null, messages.bind(servers, "platform.servers"), null, 0);
        servers.setLocked(!security.securityState().unlocked());
    }

    /** Installs the independent rescue entry on the permanent server page. / 在常驻服务器页面安装独立救援入口。
     * @param service actual original-task rescue coordinator / 实际原任务救援协调器 */
    public void installRecovery(gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService service) {
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
        models = new gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane(this, service, appearance.components, messages);
        content.insertTab(messages.text("platform.models"), null, messages.bind(models, "platform.models"), null, servers == null ? 0 : 1);
        models.setLocked(!security.securityState().unlocked());
    }

    /** Mounts independent platform connection and no-progress preferences. / 挂载独立平台连接及无进展偏好。
     * @param service shared persisted threshold owner / 共用持久阈值所有者 */
    public void installTransfers(gold.debug.windowstolinux.app.service.linux.TransferSettingsService service) {
        if (!SwingUtilities.isEventDispatchThread() || transfers != null)
            throw new IllegalStateException("Transfer settings must be installed once on the EDT");
        transfers = new gold.debug.windowstolinux.app.ui.setting.TransferSettingsPane(service, appearance.components, messages);
        content.addTab(messages.text("transfer.settings.title"), messages.bind(transfers, "transfer.settings.title"));
        transfers.setLocked(!security.securityState().unlocked());
    }

    /** Mounts platform authorization preferences independently of business AI modes. / 独立于业务 AI 模式挂载平台授权偏好。
     * @param service actual preference and scope owner / 实际偏好及作用域所有者 */
    public void installAuthorization(gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService service) {
        if (!SwingUtilities.isEventDispatchThread() || authorization != null)
            throw new IllegalStateException("Authorization settings must be installed once on the EDT");
        authorization = new gold.debug.windowstolinux.app.ui.setting.AuthorizationSettingsPane(service, appearance.components,
                messages);
        content.addTab(messages.text("authorization.title"), messages.bind(authorization, "authorization.title"));
        authorization.setLocked(!security.securityState().unlocked());
    }

    /** Mounts actual log management and storage-failure feedback. / 挂载实际日志管理及存储失败反馈。
     * @param service actual category and storage owner / 实际类别及存储所有者 */
    public void installLogs(gold.debug.windowstolinux.app.service.log.PlatformLogService service) {
        if (!SwingUtilities.isEventDispatchThread() || logs != null)
            throw new IllegalStateException("Log settings must be installed once on the EDT");
        logs = new gold.debug.windowstolinux.app.ui.setting.LogSettingsPane(service, appearance.components, messages);
        content.addTab(messages.text("logs.title"), messages.bind(logs, "logs.title"));
        logSubscription = service.onIssue(problem -> SwingUtilities.invokeLater(() -> showLogIssue(problem)));
        service.issue().ifPresent(this::showLogIssue);
        logs.setLocked(!security.securityState().unlocked());
    }

    /** Mounts retained tasks through a narrow host contract, reusing the existing log history page. / 通过窄宿主契约挂载保留任务，复用已有日志历史页。
     * @param service original task-management service / 原任务管理服务 */
    public void installTasks(gold.debug.windowstolinux.app.service.contract.spi.TaskManagement service) {
        if (!SwingUtilities.isEventDispatchThread() || tasks != null || logs == null)
            throw new IllegalStateException("Task management must be installed once after logs on the EDT");
        tasks = new gold.debug.windowstolinux.app.ui.recovery.TaskRecoveryPane(service, appearance.components, messages,
                () -> content.setSelectedComponent(logs));
        content.addTab(messages.text("tasks.title"), messages.bind(tasks, "tasks.title"));
        content.addChangeListener(event -> {
            if (content.getSelectedComponent() == tasks)
                tasks.refresh();
        });
        tasks.setLocked(!security.securityState().unlocked());
    }

    /** Displays safe localized feedback outside the selected page. / 在所选页面之外显示安全本地化反馈。
     * @param problem structured storage issue / 结构化存储问题 */
    private void showLogIssue(gold.debug.windowstolinux.appapi.task.Problem problem) {
        String key = switch (problem.eventCode()) {
            case "LOG_DATABASE_UNCONFIRMED" -> "logs.emergencyNotice";
            case "LOG_REPLAY_PENDING" -> "logs.pendingNotice";
            default -> "logs.failureNotice";
        };
        messages.bind(logIssue, key);
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
        if (appearance.pane != null)
            appearance.pane.close();
        if (servers != null)
            servers.close();
        if (models != null)
            models.close();
        if (transfers != null)
            transfers.close();
        if (authorization != null)
            authorization.close();
        if (logs != null)
            logs.close();
        if (tasks != null)
            tasks.close();
        if (mods != null)
            mods.close();
        if (originals != null)
            originals.close();
        if (navigation != null)
            navigation.close();
        if (logSubscription != null)
            try {
                logSubscription.close();
            } catch (Exception failure) {
                logIssue.setText(messages.text("logs.failed"));
            }
        lock.close();
        super.dispose();
    }

    /** Mounts explicit persisted display choices without rebuilding any platform or Mod state. / 挂载明确的持久显示选择，不重建任何平台或 Mod 状态。
     * @param initial confirmed display preferences / 已确认显示偏好
     * @param preferences original secure persistence operation / 原安全持久化操作 */
    public void installAppearance(gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration initial,
            gold.debug.windowstolinux.app.ui.setting.PlatformAppearancePane.Preferences preferences) {
        appearance.install(initial, preferences);
    }

    /** Owns display composition independently of platform task and lifecycle state. / 独立于平台任务和生命周期状态持有显示装配。 */
    private final class Appearance {
        /** Current control factory shared with existing pages. / 与已有页面共享的当前控件工厂。 */
        private final DesktopComponentFactory components;
        /** Current author-page locale, effective theme and lock state. / 当前作者页面区域、生效主题及锁定状态。 */
        private UiEnvironment environment;
        /** Explicit preference editor, installed once. / 安装一次的明确偏好编辑器。 */
        private gold.debug.windowstolinux.app.ui.setting.PlatformAppearancePane pane;

        /** Binds initial display without changing the runtime. / 绑定初始显示，不改变运行器。
         * @param palette initial colors / 初始颜色
         * @param environment original author environment / 原作者环境 */
        private Appearance(ThemePalette palette, UiEnvironment environment) {
            components = new DesktopComponentFactory(palette);
            this.environment = environment;
        }

        /** Installs the preference editor once on the EDT. / 在事件线程安装一次偏好编辑器。
         * @param initial confirmed preference / 已确认偏好
         * @param preferences original persistence / 原持久化 */
        private void install(gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration initial,
                gold.debug.windowstolinux.app.ui.setting.PlatformAppearancePane.Preferences preferences) {
            if (!SwingUtilities.isEventDispatchThread() || pane != null)
                throw new IllegalStateException("Appearance must be installed once on the EDT");
            pane = new gold.debug.windowstolinux.app.ui.setting.PlatformAppearancePane(initial, preferences,
                    this::apply, components, messages);
            content.addTab(messages.text("settings.appearance.title"), messages.bind(pane, "settings.appearance.title"));
            pane.setLocked(!security.securityState().unlocked());
        }

        /** Updates existing widgets and notifies existing author pages of a confirmed preference. / 更新已有控件，并通知已有作者页面已确认偏好。
         * @param requested successfully persisted display choice / 成功持久化的显示选择 */
        private void apply(gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration requested) {
            messages.catalog().language(requested.localeTag());
            var effective = gold.debug.windowstolinux.app.ui.display.SystemThemeResolver.effectiveTheme(requested.themeMode());
            components.palette(effective == gold.debug.windowstolinux.app.ui.display.ThemeMode.DARK
                    ? ThemePalette.dark() : ThemePalette.light());
            gold.debug.windowstolinux.app.ui.display.DesktopThemeService.apply(effective);
            components.refreshTheme(PlatformFrame.this);
            gold.debug.windowstolinux.app.ui.i18n.LocaleBinding.refresh(PlatformFrame.this, messages.catalog());
            if (models != null)
                models.localize();
            if (tasks != null)
                tasks.localize();
            for (var owned : getOwnedWindows()) {
                components.refreshTheme(owned);
                gold.debug.windowstolinux.app.ui.i18n.LocaleBinding.refresh(owned, messages.catalog());
            }
            environment = new UiEnvironment(messages.catalog().locale(), effective.name(), !security.securityState().unlocked());
            try {
                pages.environment(environment);
            } catch (Exception failure) {
                JOptionPane.showMessageDialog(PlatformFrame.this, messages.safe(failure), "WindowsToLinux", JOptionPane.ERROR_MESSAGE);
            }
            revalidate();
            repaint();
        }
    }
}
