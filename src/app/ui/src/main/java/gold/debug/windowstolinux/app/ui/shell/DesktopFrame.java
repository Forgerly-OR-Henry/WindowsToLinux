package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.shell.DesktopDisplayChangeHandler;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.LayoutManager;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Window shell responsible only for navigation, page assembly and frame lifecycle.
 *
 * <p>只负责导航、页面装配和窗口生命周期的窗口外壳。
 */
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
    private String currentPage = PAGE_DEPLOYMENT;
    private JPanel sidebar;
    private JButton collapse;
    private JLabel navigationLabel;
    private boolean navigationCollapsed;
    private java.util.function.Consumer<Boolean> navigationChange = value -> { };
    private gold.debug.windowstolinux.app.ui.component.AdvancedWindowHost advancedWindows;

    /**
     * Creates a {@code DesktopFrame} instance.
     *
     * <p>创建 {@code DesktopFrame} 实例。
     *
     * @param service the {@code service} value / {@code service} 值
     */
    public <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service) {
        this(service, DesktopDisplayConfiguration.defaults(), FailureReportStore.disabled());
    }

    private <T extends AutomaticDeploymentApplicationFacade & ManagedApplicationFacade & BackupApplicationFacade> DesktopFrame(T service, DesktopDisplayConfiguration appearance,
                         FailureReportStore reports) {
        this(service, MessageCatalog.forLanguageTag(appearance.localeTag()),
                appearance, ThemePalette.light(), (source, selected) -> { }, null, reports);
    }

    /**
     * Creates a {@code DesktopFrame} instance.
     *
     * <p>创建 {@code DesktopFrame} 实例。
     *
     * @param service the {@code service} value / {@code service} 值
     * @param messages the {@code messages} value / {@code messages} 值
     * @param appearance the {@code appearance} value / {@code appearance} 值
     * @param palette the {@code palette} value / {@code palette} 值
     * @param appearanceChangeListener the {@code appearanceChangeListener} value / {@code appearanceChangeListener} 值
     * @param viewState the {@code viewState} value / {@code viewState} 值
     */
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
        super(messages.text("app.name"));
        this.messages = messages;
        this.palette = palette;
        this.components = new DesktopComponentFactory(palette);
        this.pageCoordinator = new DesktopPageCoordinator(
                this, service, messages, appearance, components, appearanceChangeListener, this::showPage, reports);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1060, 720));
        setSize(1180, 780);
        setLocationByPlatform(true);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(palette.pageBackground());
        root.add(applicationHeader(), BorderLayout.NORTH);
        root.add(navigationSidebar(), BorderLayout.WEST);
        root.add(pageDeck(), BorderLayout.CENTER);
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

        JPanel brand = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JLabel mark = new JLabel(messages.text("app.mark"));
        mark.setOpaque(true);
        mark.setHorizontalAlignment(JLabel.CENTER);
        mark.setPreferredSize(new Dimension(30, 30));
        mark.setBackground(palette.accent());
        mark.setForeground(Color.WHITE);
        mark.setFont(mark.getFont().deriveFont(Font.BOLD, 15f));
        brand.add(mark);
        JLabel product = new JLabel(messages.text("app.name"));
        product.setFont(product.getFont().deriveFont(Font.BOLD, 17f));
        brand.add(product);
        brand.add(badge(t("app.badge.managed")));
        header.add(brand, BorderLayout.WEST);

        JPanel heading = transparent(new BorderLayout(0, 2));
        pageTitle.setFont(pageTitle.getFont().deriveFont(Font.BOLD, 18f));
        pageDescription.setForeground(palette.subduedText());
        heading.add(pageTitle, BorderLayout.NORTH);
        heading.add(pageDescription, BorderLayout.SOUTH);
        header.add(heading, BorderLayout.EAST);
        return header;
    }

    private JComponent navigationSidebar() {
        sidebar = new JPanel();
        sidebar.setBackground(palette.sidebarBackground());
        sidebar.setBorder(BorderFactory.createEmptyBorder(14, 8, 12, 8));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(168, 0));
        collapse = new JButton();
        collapse.setAlignmentX(Component.LEFT_ALIGNMENT);
        collapse.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        collapse.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        collapse.addActionListener(event -> { setNavigationCollapsed(!navigationCollapsed); navigationChange.accept(navigationCollapsed); });
        sidebar.add(collapse);
        navigationLabel = new JLabel(t("nav.workspace"));
        navigationLabel.setForeground(palette.subduedText());
        navigationLabel.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 0));
        navigationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(navigationLabel);
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
        navigationCollapsed = value;
        sidebar.setPreferredSize(new Dimension(value ? 64 : 168, 0));
        navigationLabel.setVisible(!value);
        navigationButtons.forEach((key, button) -> {
            button.setText(value ? "" : t("nav." + key));
            button.setHorizontalAlignment(value ? JButton.CENTER : JButton.LEFT);
            button.setIconTextGap(10);
        });
        String label = t(value ? "nav.expand" : "nav.collapse");
        collapse.setText(value ? "" : label);
        collapse.setToolTipText(label);
        collapse.getAccessibleContext().setAccessibleName(label);
        collapse.setIcon(gold.debug.windowstolinux.app.ui.component.DesktopIcons.icon(
                value ? "panel-left-open" : "panel-left-close", 20, collapse::getForeground));
        sidebar.revalidate(); sidebar.repaint();
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
        JButton button = new JButton(t(labelKey));
        button.setHorizontalAlignment(JButton.LEFT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        button.setPreferredSize(new Dimension(140, 42));
        button.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
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
        button.setContentAreaFilled(false);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
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
        deck.setBorder(BorderFactory.createEmptyBorder(20, 22, 22, 22));
        deck.add(pages, BorderLayout.CENTER);
        return deck;
    }

    private void showPage(String page, String titleKey, String descriptionKey) {
        pageLayout.show(pages, page);
        currentPage = page;
        pageCoordinator.currentPage(page);
        if (advancedWindows != null && isVisible()) advancedWindows.activate(pageCoordinator.inspector(page));
        pageTitle.setText(t(titleKey));
        pageDescription.setText(t(descriptionKey));
        navigationButtons.forEach((key, button) -> {
            boolean selected = key.equals(page);
            button.setContentAreaFilled(selected);
            button.setOpaque(selected);
            button.setBackground(selected ? palette.navigationActive() : palette.sidebarBackground());
            button.setForeground(selected ? Color.WHITE : palette.sidebarForeground());
        });
    }


    /**
     * Performs the {@code captureViewState} operation.
     *
     * <p>执行 {@code captureViewState} 操作。
     *
     * @return the operation result / 操作结果
     */
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
