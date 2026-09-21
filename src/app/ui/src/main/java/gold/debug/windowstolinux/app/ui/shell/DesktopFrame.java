package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.LayoutManager;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Window shell for navigation, page assembly and lifecycle. / 负责导航、页面装配和生命周期的窗口外壳。
 */
public final class DesktopFrame extends JFrame {
    /**
     * PAGE DEPLOYMENT.
     * <p>页面部署。
     */
    private static final String PAGE_DEPLOYMENT = "deployment";
    /**
     * PAGE COMPONENTS.
     * <p>页面组件集合。
     */
    private static final String PAGE_COMPONENTS = "components";
    /**
     * PAGE APPLICATIONS.
     * <p>页面应用集合。
     */
    private static final String PAGE_APPLICATIONS = "applications";
    /**
     * PAGE BACKUP.
     * <p>页面备份。
     */
    private static final String PAGE_BACKUP = "backup";
    /**
     * PAGE SERVERS.
     * <p>页面服务器集合。
     */
    private static final String PAGE_SERVERS = "servers";
    /**
     * PAGE AI.
     * <p>页面AI。
     */
    private static final String PAGE_AI = "ai";
    /**
     * PAGE SETTINGS.
     * <p>页面设置。
     */
    private static final String PAGE_SETTINGS = "settings";

    /**
     * Localized message resolver.
     * <p>本地化消息解析器。
     */
    private final MessageCatalog messages;
    /**
     * Palette.
     * <p>配色。
     */
    private final ThemePalette palette;
    /**
     * Reviewed components in the application graph.
     * <p>应用图中的已审阅组件。
     */
    private final DesktopComponentFactory components;
    /**
     * Bound desktop page coordinator collaborator for page coordinator.
     * <p>处理页面协调器的Desktop页面协调器协作对象。
     */
    private final DesktopPageCoordinator pageCoordinator;
    /**
     * Page layout.
     * <p>页面布局。
     */
    private final CardLayout pageLayout = new CardLayout();
    /**
     * Swing control for pages.
     * <p>页面集合对应的 Swing 控件。
     */
    private final JPanel pages = new JPanel(pageLayout);
    /**
     * Navigation buttons.
     * <p>导航按钮集合。
     */
    private final Map<String, JButton> navigationButtons = new LinkedHashMap<>();
    /**
     * Swing control for page title.
     * <p>页面标题对应的 Swing 控件。
     */
    private final JLabel pageTitle = new JLabel();
    /**
     * Swing control for page description.
     * <p>页面说明对应的 Swing 控件。
     */
    private final JLabel pageDescription = new JLabel();
    /**
     * Swing control for header actions.
     * <p>头部动作集合对应的 Swing 控件。
     */
    private final JPanel headerActions = new JPanel(new BorderLayout());
    /**
     * Current page.
     * <p>当前页面。
     */
    private String currentPage = PAGE_DEPLOYMENT;
    /**
     * Swing control for sidebar.
     * <p>侧栏对应的 Swing 控件。
     */
    private JPanel sidebar;
    /**
     * Swing control for collapse.
     * <p>折叠对应的 Swing 控件。
     */
    private JButton collapse;
    /**
     * Swing control for product name.
     * <p>产品名称对应的 Swing 控件。
     */
    private JLabel productName;
    /**
     * Navigation collapsed.
     * <p>导航Collapsed。
     */
    private boolean navigationCollapsed;
    /**
     * Navigation expansion fraction from collapsed zero to expanded one.
     * <p>导航展开比例，零为折叠，一为展开。
     */
    private double navigationExpansion = 1;
    /**
     * Swing event-thread timer for navigation animation.
     * <p>导航动画使用的 Swing 事件线程定时器。
     */
    private Timer navigationAnimation;
    /**
     * Navigation change.
     * <p>导航变更。
     */
    private java.util.function.Consumer<Boolean> navigationChange = value -> { };
    /**
     * Advanced windows.
     * <p>高级Windows。
     */
    private gold.debug.windowstolinux.app.ui.component.AdvancedWindowHost advancedWindows;

    /**
     * Creates a desktop using default appearance. / 使用默认外观创建桌面。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param service application service used by the caller / 调用方使用的应用服务
     */
    public <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service) {
        this(service, MessageCatalog.forLanguageTag(DesktopDisplayConfiguration.defaults().localeTag()),
                DesktopDisplayConfiguration.defaults(), ThemePalette.light(), (source, selected) -> { }, null);
    }

    /**
     * Restores page state under the supplied language and theme. / 使用指定语言和主题恢复页面状态。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param messages localized message resolver / 本地化消息解析器
     * @param appearance appearance / 外观
     * @param palette palette / 配色
     * @param appearanceChangeListener appearance change listener / 外观变更监听器
     * @param viewState view state / 视图状态
     */
    public <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service, MessageCatalog messages,
                        DesktopDisplayConfiguration appearance, ThemePalette palette,
                        DesktopDisplayChangeHandler appearanceChangeListener,
                        DesktopViewState viewState) {
        this(service, messages, appearance, palette, appearanceChangeListener, viewState,
                FailureReportStore.disabled());
    }

    /**
     * Creates a desktop frame with safe diagnostic reporting. / 创建带安全诊断报告的桌面窗口。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param messages localized message resolver / 本地化消息解析器
     * @param appearance appearance / 外观
     * @param palette palette / 配色
     * @param appearanceChangeListener appearance change listener / 外观变更监听器
     * @param viewState view state / 视图状态
     * @param reports reports / 报告集合
     */
    public <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service, MessageCatalog messages,
                        DesktopDisplayConfiguration appearance, ThemePalette palette,
                        DesktopDisplayChangeHandler appearanceChangeListener,
                        DesktopViewState viewState, FailureReportStore reports) {
        this(service, messages, appearance, palette, appearanceChangeListener, viewState, reports, false);
    }

    /**
     * Enables UI previews only when authorized by the resolved launch mode. / 仅在已解析的启动模式允许时开启界面预览。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param messages localized message resolver / 本地化消息解析器
     * @param appearance appearance / 外观
     * @param palette palette / 配色
     * @param appearanceChangeListener appearance change listener / 外观变更监听器
     * @param viewState view state / 视图状态
     * @param reports reports / 报告集合
     * @param uiDebugEnabled ui debug enabled / 界面Debug启用
     */
    public <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service, MessageCatalog messages,
                        DesktopDisplayConfiguration appearance, ThemePalette palette,
                        DesktopDisplayChangeHandler appearanceChangeListener,
                        DesktopViewState viewState, FailureReportStore reports, boolean uiDebugEnabled) {
        super(messages.text("app.name"));
        this.messages = messages;
        this.palette = palette;
        this.components = new DesktopComponentFactory(palette);
        this.pageCoordinator = new DesktopPageCoordinator(
                this, service, messages, appearance, components, appearanceChangeListener, this::showPage, reports, uiDebugEnabled);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1060, 720));
        setSize(1180, 780);
        setLocationByPlatform(true);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(palette.pageBackground());
        root.add(navigationSidebar(), BorderLayout.WEST);
        JPanel workspace = new JPanel(new BorderLayout());
        workspace.add(applicationHeader(), BorderLayout.NORTH);
        workspace.add(pageDeck(), BorderLayout.CENTER);
        root.add(workspace, BorderLayout.CENTER);
        setContentPane(root);
        advancedWindows = new gold.debug.windowstolinux.app.ui.component.AdvancedWindowHost(this, root);
        pageCoordinator.bindInspectors(advancedWindows);
        if (viewState == null) {
            showPage(PAGE_DEPLOYMENT, "nav.deployment", "page.deployment.description");
        } else {
            pageCoordinator.restoreViewState(viewState);
            viewState.close();
        }
    }

    /**
     * Builds the application header with localized product and page context.
     * <p>构建包含本地化产品及页面上下文的应用页头。
     *
     * @return the application header with localized product and page context / 包含本地化产品及页面上下文的应用页头
     */
    private JComponent applicationHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(palette.cardBackground());
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, palette.cardBorder()),
                BorderFactory.createEmptyBorder(14, 22, 14, 22)
        ));

        JPanel heading = transparent(new BorderLayout(0, 4));
        pageTitle.setFont(pageTitle.getFont().deriveFont(Font.BOLD, 20f));
        pageTitle.setForeground(palette.sidebarForeground());
        pageDescription.setFont(pageDescription.getFont().deriveFont(12f));
        pageDescription.setForeground(palette.subduedText());
        heading.add(pageTitle, BorderLayout.NORTH);
        heading.add(pageDescription, BorderLayout.SOUTH);
        header.add(heading, BorderLayout.CENTER);
        JPanel status = transparent(new java.awt.GridBagLayout());
        status.add(badge(t("app.badge.managed")));
        headerActions.setOpaque(false); status.add(headerActions);
        header.add(status, BorderLayout.EAST);
        return header;
    }

    /**
     * Builds the branding area that participates in navigation expansion and collapse.
     * <p>构建参与导航展开及折叠的品牌区域。
     *
     * @return the branding area that participates in navigation expansion and collapse / 参与导航展开及折叠的品牌区域
     */
    private JComponent navigationBrand() {
        JLabel mark = new JLabel(t("app.mark")) {
            /**
             * Paints component.
             * <p>绘制组件。
             *
             * @param graphics graphics / 图形
             */
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(palette.accent());
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                } finally { g.dispose(); }
                super.paintComponent(graphics);
            }
        };
        JPanel brand = new JPanel(null) {
            /**
             * Positions the owned Swing components within the current available bounds.
             * <p>在当前可用边界内排列持有的 Swing 组件。
             */
            @Override public void doLayout() {
                int center = (int) Math.round(24 - 2 * navigationExpansion);
                mark.setBounds(center - 15, 3, 30, 30);
                productName.setBounds(center + 23, 3, productName.getPreferredSize().width, 30);
            }
        };
        brand.setOpaque(false);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.setPreferredSize(new Dimension(152, 52));
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        mark.setHorizontalAlignment(JLabel.CENTER);
        mark.setForeground(Color.WHITE);
        mark.setFont(mark.getFont().deriveFont(Font.BOLD, 15f));
        mark.setToolTipText(t("app.name"));
        mark.getAccessibleContext().setAccessibleName(t("app.name"));
        brand.add(mark);
        productName = fadingNavigationLabel(t("app.name"));
        productName.setFont(productName.getFont().deriveFont(Font.BOLD, 12f));
        productName.setForeground(palette.sidebarForeground());
        brand.add(productName);
        return brand;
    }

    /**
     * Builds page navigation buttons and the sidebar collapse control.
     * <p>构建页面导航按钮及侧栏折叠控件。
     *
     * @return page navigation buttons and the sidebar collapse control / 页面导航按钮及侧栏折叠控件
     */
    private JComponent navigationSidebar() {
        sidebar = new JPanel();
        sidebar.setBackground(palette.sidebarBackground());
        sidebar.setBorder(BorderFactory.createEmptyBorder(14, 8, 12, 8));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(168, 0));
        sidebar.add(navigationBrand());
        collapse = new NavigationButton("");
        collapse.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        collapse.setForeground(palette.sidebarForeground());
        collapse.addActionListener(event -> {
            changeNavigationCollapsed(!navigationCollapsed, true);
            navigationChange.accept(navigationCollapsed);
        });
        sidebar.add(collapse);
        JPanel divider = components.transparent(new BorderLayout());
        divider.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(16, 12, 16, 12),
                BorderFactory.createMatteBorder(1, 0, 0, 0, palette.inputBorder())));
        divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 33));
        divider.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(divider);
        for (String page : java.util.List.of(PAGE_DEPLOYMENT, PAGE_SERVERS, PAGE_APPLICATIONS, PAGE_BACKUP, PAGE_AI)) {
            sidebar.add(navigationButton(page, "nav." + page, "page." + page + ".description"));
            sidebar.add(Box.createVerticalStrut(6));
        }
        sidebar.add(Box.createVerticalGlue());
        sidebar.add(navigationButton(PAGE_SETTINGS, "nav.settings", "page.settings.description"));
        setNavigationCollapsed(false);
        return sidebar;
    }

    /**
     * Applies the navigation layout without rebuilding active pages. / 调整导航布局且不重建活动页面。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setNavigationCollapsed(boolean value) {
        changeNavigationCollapsed(value, false);
    }

    /**
     * Updates navigation collapse state and accessibility text, optionally animating the width change.
     * <p>更新导航折叠状态及无障碍文本，可选地为宽度变化添加动画。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param animate animate / 动画
     */
    private void changeNavigationCollapsed(boolean value, boolean animate) {
        if (navigationAnimation != null) navigationAnimation.stop();
        navigationCollapsed = value;
        String label = t(value ? "nav.expand" : "nav.collapse");
        collapse.setText(label);
        collapse.setToolTipText(label);
        collapse.getAccessibleContext().setAccessibleName(label);
        collapse.setIcon(gold.debug.windowstolinux.app.ui.component.DesktopIcons.icon(
                value ? "panel-left-open" : "panel-left-close", 20, collapse::getForeground));
        double target = value ? 0 : 1;
        if (!animate || !isShowing()
                || Boolean.FALSE.equals(java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation"))
                || "false".equals(System.getProperty("flatlaf.animation"))) {
            layoutNavigation(target);
            return;
        }
        double from = navigationExpansion;
        long started = System.nanoTime();
        navigationAnimation = new Timer(15, event -> {
            double elapsed = Math.min(1, (System.nanoTime() - started) / 220_000_000.0);
            double eased = elapsed * elapsed * (3 - 2 * elapsed);
            layoutNavigation(from + (target - from) * eased);
            if (elapsed >= 1) ((Timer) event.getSource()).stop();
        });
        navigationAnimation.start();
    }

    /**
     * Lays out navigation.
     * <p>布局导航。
     *
     * @param expansion expansion / 展开
     */
    private void layoutNavigation(double expansion) {
        navigationExpansion = expansion;
        sidebar.setPreferredSize(new Dimension((int) Math.round(64 + 104 * expansion), 0));
        sidebar.revalidate();
        if (isDisplayable()) validate();
        sidebar.repaint();
    }

    /**
     * Returns navigation text opacity.
     * <p>返回导航文本Opacity。
     *
     * @return navigation text opacity / 导航文本Opacity
     */
    private float navigationTextOpacity() {
        return (float) Math.max(0, Math.min(1, (navigationExpansion - 0.65) / 0.35));
    }

    /**
     * Builds J label from the supplied fading navigation label inputs.
     * <p>根据所提供渐隐导航标签输入构建J标签。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return j label from the supplied fading navigation label inputs / 根据所提供渐隐导航标签输入构建J标签
     */
    private JLabel fadingNavigationLabel(String text) {
        return new JLabel(text) {
            /**
             * Paints component.
             * <p>绘制组件。
             *
             * @param graphics graphics / 图形
             */
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setComposite(AlphaComposite.SrcOver.derive(navigationTextOpacity()));
                    super.paintComponent(g);
                } finally { g.dispose(); }
            }
        };
    }

    /**
     * Stops navigation animation before disposing the Swing frame.
     * <p>销毁 Swing 窗口前停止导航动画。
     */
    @Override public void dispose() {
        if (navigationAnimation != null) navigationAnimation.stop();
        super.dispose();
    }

    /**
     * Reports the current navigation preference. / 返回当前导航偏好。
     *
     * @return true when reports the current navigation preference, false otherwise / 返回当前导航偏好时为 true，否则为 false
     */
    public boolean navigationCollapsed() { return navigationCollapsed; }
    /**
     * Installs preference persistence owned by startup. / 注入由启动层负责的偏好保存。
     *
     * @param listener listener / 监听器
     */
    public void onNavigationChange(java.util.function.Consumer<Boolean> listener) { navigationChange = listener; }
    /**
     * Captures the primary window bounds for appearance changes. / 捕获外观变更所需主窗口尺寸。
     *
     * @return constructed or resolved rectangle / 构造或解析得到的Rectangle
     */
    public java.awt.Rectangle workspaceWindowBounds() { return advancedWindows.workspaceBounds(); }
    /**
     * Restores normal dimensions independently of maximized state. / 独立于最大化状态恢复普通尺寸。
     *
     * @param bounds bounds / 边界集合
     */
    public void restoreWorkspaceWindowBounds(java.awt.Rectangle bounds) { advancedWindows.restoreWorkspaceBounds(bounds); }

    /**
     * Updates visible.
     * <p>更新可见。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    @Override public void setVisible(boolean value) {
        super.setVisible(value);
        if (value && advancedWindows != null) advancedWindows.activate(pageCoordinator.inspector(currentPage));
    }

    /**
     * Creates a page navigation button with localized labels, accessibility metadata and the page icon.
     * <p>创建页面导航按钮，包含本地化标签、无障碍元数据及页面图标。
     *
     * @param page page / 页面
     * @param labelKey label key / 标签键
     * @param descriptionKey description key / 说明键
     * @return a page navigation button with localized labels, accessibility metadata and the page icon / 页面导航按钮，包含本地化标签、无障碍元数据及页面图标
     */
    private JButton navigationButton(String page, String labelKey, String descriptionKey) {
        JButton button = new NavigationButton(t(labelKey));
        button.setToolTipText(t(labelKey));
        button.getAccessibleContext().setAccessibleName(t(labelKey));
        String icon = switch (page) {
            case PAGE_DEPLOYMENT -> "rocket";
            case PAGE_SERVERS -> "server";
            case PAGE_APPLICATIONS -> "panels-top-left";
            case PAGE_BACKUP -> "archive";
            case PAGE_AI -> "bot";
            default -> "settings";
        };
        button.setIcon(gold.debug.windowstolinux.app.ui.component.DesktopIcons.icon(icon, 20, button::getForeground));
        button.setForeground(palette.sidebarForeground());
        button.setBackground(palette.sidebarBackground());
        button.addActionListener(event -> showPage(page, labelKey, descriptionKey));
        navigationButtons.put(page, button);
        return button;
    }

    /**
     * Registers the owned page panels in the frame's card layout.
     * <p>在窗口卡片布局中登记持有的页面面板。
     *
     * @return constructed or resolved J component / 构造或解析得到的J组件
     */
    private JComponent pageDeck() {
        pages.setBackground(palette.pageBackground());
        pages.add(pageCoordinator.deploymentPanel(), PAGE_DEPLOYMENT);
        pages.add(pageCoordinator.multiComponentPanel(), PAGE_COMPONENTS);
        pages.add(pageCoordinator.managedApplicationsPanel(), PAGE_APPLICATIONS);
        pages.add(pageCoordinator.backupPanel(), PAGE_BACKUP);
        pages.add(pageCoordinator.serverPanel(), PAGE_SERVERS);
        pages.add(pageCoordinator.aiPanel(), PAGE_AI);
        pages.add(pageCoordinator.settingsPanel(), PAGE_SETTINGS);

        JPanel deck = new JPanel(new BorderLayout());
        deck.setBackground(palette.pageBackground());
        deck.setBorder(BorderFactory.createEmptyBorder(16, 22, 20, 22));
        deck.add(pages, BorderLayout.CENTER);
        return deck;
    }

    /**
     * Switches the visible page and updates navigation, heading and advanced-inspector controls.
     * <p>切换可见页面，并更新导航、标题及高级检查面板控件。
     *
     * @param page page / 页面
     * @param titleKey title key / 标题键
     * @param descriptionKey description key / 说明键
     */
    private void showPage(String page, String titleKey, String descriptionKey) {
        pageLayout.show(pages, page);
        currentPage = page;
        pageCoordinator.currentPage(page);
        pageCoordinator.showInspectorControls(page, headerActions);
        if (advancedWindows != null && isVisible()) advancedWindows.activate(pageCoordinator.inspector(page));
        pageTitle.setText(t(titleKey));
        pageDescription.setText(t(descriptionKey));
        navigationButtons.forEach((key, button) -> {
            boolean selected = key.equals(page);
            button.setSelected(selected);
            button.setBackground(selected ? palette.navigationActive() : palette.sidebarBackground());
            button.setForeground(selected ? palette.accentDark() : palette.sidebarForeground());
            button.setFont(button.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN));
        });
    }

    /**
     * Keeps icon geometry stable while the sidebar and labels transition. / 侧栏与文字过渡时保持图标位置稳定。
     */
    private final class NavigationButton extends JButton {
        /**
         * Binds the supplied dependencies and state for navigation button.
         * <p>为导航按钮绑定传入的依赖及状态。
         *
         * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
         */
        NavigationButton(String text) {
            super(text);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setPreferredSize(new Dimension(140, 42));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        /**
         * Paints the themed navigation button, including selection and expansion-dependent appearance.
         * <p>绘制主题化导航按钮，包含选择及随展开程度变化的外观。
         *
         * @param graphics graphics / 图形
         */
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Color surface = isSelected() ? palette.navigationActive() : palette.sidebarBackground();
                if (getModel().isPressed()) surface = palette.inputBorder();
                else if (!isSelected() && getModel().isRollover()) surface = palette.secondaryButtonBackground();
                g.setColor(surface);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                if (hasFocus()) {
                    g.setColor(palette.accent());
                    g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
                }
                if (getIcon() != null) {
                    int centered = (48 - getIcon().getIconWidth()) / 2;
                    int x = (int) Math.round(centered + (12 - centered) * navigationExpansion);
                    getIcon().paintIcon(this, g, x, (getHeight() - getIcon().getIconHeight()) / 2);
                    g.setComposite(AlphaComposite.SrcOver.derive(navigationTextOpacity()));
                    g.setColor(getForeground());
                    g.setFont(getFont());
                    var metrics = g.getFontMetrics();
                    g.drawString(getText(), x + getIcon().getIconWidth() + 10,
                            (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
                }
            } finally { g.dispose(); }
        }
    }


    /**
     * Captures page state before rebuilding the window. / 在重建窗口前捕获页面状态。
     *
     * @return constructed or resolved desktop view state / 构造或解析得到的Desktop视图状态
     */
    public DesktopViewState captureViewState() {
        return pageCoordinator.captureViewState();
    }

    /**
     * Creates a transparent panel through the current theme's component factory.
     * <p>通过当前主题组件工厂创建透明面板。
     *
     * @param layout layout / 布局
     * @return a transparent panel through the current theme's component factory / 通过当前主题组件工厂创建透明面板
     */
    private JPanel transparent(LayoutManager layout) {
        return components.transparent(layout);
    }

    /**
     * Creates a text badge through the current theme's component factory.
     * <p>通过当前主题组件工厂创建文本标记。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return a text badge through the current theme's component factory / 通过当前主题组件工厂创建文本标记
     */
    private JLabel badge(String text) {
        return components.badge(text);
    }

    /**
     * Looks up a localized frame message by key.
     * <p>按键查找本地化窗口消息。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return t text / t文本
     */
    private String t(String key) {
        return messages.text(key);
    }
}
