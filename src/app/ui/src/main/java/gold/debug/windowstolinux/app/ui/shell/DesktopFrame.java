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

/** Window shell for navigation, page assembly and lifecycle. / 负责导航、页面装配和生命周期的窗口外壳。 */
public final class DesktopFrame extends JFrame {
    private static final String PAGE_DEPLOYMENT = "deployment";
    private static final String PAGE_COMPONENTS = "components";
    private static final String PAGE_APPLICATIONS = "applications";
    private static final String PAGE_BACKUP = "backup";
    private static final String PAGE_SERVERS = "servers";
    private static final String PAGE_AI = "ai";
    private static final String PAGE_SETTINGS = "settings";

    private final MessageCatalog messages;
    private final ThemePalette palette;
    private final DesktopComponentFactory components;
    private final DesktopPageCoordinator pageCoordinator;
    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pages = new JPanel(pageLayout);
    private final Map<String, JButton> navigationButtons = new LinkedHashMap<>();
    private final JLabel pageTitle = new JLabel();
    private final JLabel pageDescription = new JLabel();
    private final JPanel headerActions = new JPanel(new BorderLayout());
    private String currentPage = PAGE_DEPLOYMENT;
    private JPanel sidebar;
    private JButton collapse;
    private JLabel productName;
    private boolean navigationCollapsed;
    private double navigationExpansion = 1;
    private Timer navigationAnimation;
    private java.util.function.Consumer<Boolean> navigationChange = value -> { };
    private gold.debug.windowstolinux.app.ui.component.AdvancedWindowHost advancedWindows;

    /** Creates a desktop using default appearance. / 使用默认外观创建桌面。 */
    public <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service) {
        this(service, MessageCatalog.forLanguageTag(DesktopDisplayConfiguration.defaults().localeTag()),
                DesktopDisplayConfiguration.defaults(), ThemePalette.light(), (source, selected) -> { }, null);
    }

    /** Restores page state under the supplied language and theme. / 使用指定语言和主题恢复页面状态。 */
    public <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service, MessageCatalog messages,
                        DesktopDisplayConfiguration appearance, ThemePalette palette,
                        DesktopDisplayChangeHandler appearanceChangeListener,
                        DesktopViewState viewState) {
        this(service, messages, appearance, palette, appearanceChangeListener, viewState,
                FailureReportStore.disabled());
    }

    /** Creates a desktop frame with safe diagnostic reporting. / 创建带安全诊断报告的桌面窗口。 */
    public <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service, MessageCatalog messages,
                        DesktopDisplayConfiguration appearance, ThemePalette palette,
                        DesktopDisplayChangeHandler appearanceChangeListener,
                        DesktopViewState viewState, FailureReportStore reports) {
        this(service, messages, appearance, palette, appearanceChangeListener, viewState, reports, false);
    }

    /** Enables UI previews only when authorized by the resolved launch mode. / 仅在已解析的启动模式允许时开启界面预览。 */
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

    private JComponent navigationBrand() {
        JLabel mark = new JLabel(t("app.mark")) {
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

    private JComponent navigationSidebar() {
        sidebar = new JPanel();
        sidebar.setBackground(palette.sidebarBackground());
        sidebar.setBorder(BorderFactory.createEmptyBorder(14, 8, 12, 8));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(168, 0));
        sidebar.add(navigationBrand());
        collapse = new NavigationButton("");
        collapse.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        collapse.setForeground(palette.subduedText());
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

    /** Applies the navigation layout without rebuilding active pages. / 调整导航布局且不重建活动页面。 */
    public void setNavigationCollapsed(boolean value) {
        changeNavigationCollapsed(value, false);
    }

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

    private void layoutNavigation(double expansion) {
        navigationExpansion = expansion;
        sidebar.setPreferredSize(new Dimension((int) Math.round(64 + 104 * expansion), 0));
        sidebar.revalidate();
        if (isDisplayable()) validate();
        sidebar.repaint();
    }

    private float navigationTextOpacity() {
        return (float) Math.max(0, Math.min(1, (navigationExpansion - 0.65) / 0.35));
    }

    private JLabel fadingNavigationLabel(String text) {
        return new JLabel(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setComposite(AlphaComposite.SrcOver.derive(navigationTextOpacity()));
                    super.paintComponent(g);
                } finally { g.dispose(); }
            }
        };
    }

    @Override public void dispose() {
        if (navigationAnimation != null) navigationAnimation.stop();
        super.dispose();
    }

    /** Reports the current navigation preference. / 返回当前导航偏好。 */
    public boolean navigationCollapsed() { return navigationCollapsed; }
    /** Installs preference persistence owned by startup. / 注入由启动层负责的偏好保存。 */
    public void onNavigationChange(java.util.function.Consumer<Boolean> listener) { navigationChange = listener; }
    /** Captures the primary window bounds for appearance changes. / 捕获外观变更所需主窗口尺寸。 */
    public java.awt.Rectangle workspaceWindowBounds() { return advancedWindows.workspaceBounds(); }
    /** Restores normal dimensions independently of maximized state. / 独立于最大化状态恢复普通尺寸。 */
    public void restoreWorkspaceWindowBounds(java.awt.Rectangle bounds) { advancedWindows.restoreWorkspaceBounds(bounds); }

    @Override public void setVisible(boolean value) {
        super.setVisible(value);
        if (value && advancedWindows != null) advancedWindows.activate(pageCoordinator.inspector(currentPage));
    }

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

    /** Keeps icon geometry stable while the sidebar and labels transition. / 侧栏与文字过渡时保持图标位置稳定。 */
    private final class NavigationButton extends JButton {
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


    /** Captures page state before rebuilding the window. / 在重建窗口前捕获页面状态。 */
    public DesktopViewState captureViewState() {
        return pageCoordinator.captureViewState();
    }

    private JPanel transparent(LayoutManager layout) {
        return components.transparent(layout);
    }

    private JLabel badge(String text) {
        return components.badge(text);
    }

    private String t(String key) {
        return messages.text(key);
    }
}
