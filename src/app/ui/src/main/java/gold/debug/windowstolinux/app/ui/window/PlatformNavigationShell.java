package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.DoubleConsumer;

import javax.swing.*;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import gold.debug.windowstolinux.app.ui.control.DesktopButtonStyle;
import gold.debug.windowstolinux.app.ui.control.DesktopNavigationButton;
import gold.debug.windowstolinux.app.ui.display.DesktopMotion;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

/** Persistent desktop navigation around existing page instances. / 围绕已有页面实例的常驻桌面导航。 */
final class PlatformNavigationShell extends JPanel implements AutoCloseable {
    /** Expanded sidebar width, independent of window resizing and UI zoom. / 不随窗口缩放及界面缩放变化的展开侧栏宽度。 */
    static final int SIDEBAR_WIDTH = 160;

    /** Reserved upper workspace height in application pixels. / 应用像素计量的工作区顶部预留高度。 */
    static final int HEADER_HEIGHT = 60;

    /** Original page selection and ownership container. / 原页面选择及归属容器。 */
    private final JTabbedPane pages;

    /** Live localized labels. / 实时本地化文字。 */
    private final MessageCatalog messages;

    /** Current palette. / 当前配色。 */
    private ThemePalette palette;

    /** Main sidebar. / 主侧栏。 */
    private final JPanel sidebar = new JPanel(new BorderLayout(0, 12));

    /** Fixed platform entries. / 固定平台入口。 */
    private final JPanel entries = column();

    /** Explicitly pinned author entries. / 明确固定的作者入口。 */
    private final JPanel pins = column();

    /** Settings and security controls. / 设置与安全控件。 */
    private final JPanel utilities = new NavigationGroup() {
        /** Applies the current width to late-mounted security controls. / 对后挂载安全控件应用当前宽度。 */
        @Override
        public void doLayout() {
            presentNavigation();
            super.doLayout();
        }
    };

    /** Settings categories. / 设置分类。 */
    private final JPanel categories = column();

    /** Category viewport. / 分类视口。 */
    private final JScrollPane categoryScroll = scroll(categories);

    /** Compact category selector for narrow windows. / 窄窗口的紧凑分类选择器。 */
    private final JComboBox<Entry> categoryPicker = new JComboBox<>();

    /** Available workspace width. / 可用工作区宽度。 */
    private final JPanel body = new JPanel(new BorderLayout());

    /** Product identity without a business-module dependency. / 不依赖业务模块的产品标识。 */
    private final DesktopBrandLabel brand;

    /** Logo navigation callback supplied by the owning window. / 所属窗口提供的 Logo 导航回调。 */
    private Runnable openHome = () -> {
    };

    /** Current page heading and explanation surface. / 当前页面标题及说明界面。 */
    private final PageHeading heading = new PageHeading();

    /** Host heading state independent of business page contents. / 独立于业务页面内容的宿主标题状态。 */
    private static final class PageHeading {
        /** Creates the heading controls before shell composition. / 外壳装配前创建标题控件。 */
        private PageHeading() {
        }

        /** Current page heading. / 当前页面标题。 */
        private final JLabel title = new JLabel();

        /** Page explanation. / 页面说明。 */
        private final JLabel description = new JLabel();

        /** Header surface. / 标题界面。 */
        private final JPanel header = new JPanel(new CardLayout());

    }

    /** Collapse action. / 折叠动作。 */
    private final DesktopNavigationButton collapse = new DesktopNavigationButton("");

    /** Settings entry remains at the bottom. / 设置入口保持在底部。 */
    private final DesktopNavigationButton settings = new DesktopNavigationButton("");

    /** Page presentation metadata only. / 仅保存页面展示元数据。 */
    private final List<Entry> registered = new ArrayList<>();

    /** Last confirmed platform entrance permutation. / 上次确认的平台入口排列。 */
    private List<String> navigationOrder = List.of();

    /** Current registered default entrance identities. / 当前已登记的默认入口身份。 */
    private volatile List<String> defaultIds = List.of();

    /** User's current session preference. / 用户的当前会话偏好。 */
    private boolean collapsed;

    /** Blocks category-model events during registration. / 登记期间阻止分类模型事件。 */
    private boolean updatingCategories;

    /** Last visited settings category. / 上次访问的设置分类。 */
    private Component lastSetting;

    /** Keeps the sorted first category as the default until settings are actually visited. / 实际访问设置前以排序首项作为默认分类。 */
    private boolean settingsVisited;

    /** Owns the sidebar spring and stops it on disposal. / 持有侧栏弹簧并在释放时停止。 */
    private final SidebarMotion motion = new SidebarMotion();

    /** Continuous sidebar motion state. / 连续侧栏运动状态。 */
    private static final class SidebarMotion {
        /** Creates the motion state before shell composition. / 外壳装配前创建运动状态。 */
        private SidebarMotion() {
        }

        /** Owned transition, stopped on disposal. / 销毁时停止的所属过渡动画。 */
        private DesktopMotion clock;

        /** Continuous width and velocity retained when reversing a transition. / 反转过渡时保留的连续宽度和速度。 */
        private double width, velocity, target;

    }

    /** Updates pinned-entry presentation. / 更新固定入口展示。 */
    private DoubleConsumer collapseObserver = ignored -> {
    };

    /** Builds the shell without creating or replacing business views. / 创建外壳，不创建或替换业务视图。
     * @param pages existing page container / 已有页面容器
     * @param messages current language / 当前语言
     * @param palette effective palette / 生效配色 */
    PlatformNavigationShell(JTabbedPane pages, MessageCatalog messages, ThemePalette palette) {
        super(new BorderLayout());
        this.pages = pages;
        this.messages = messages;
        this.palette = palette;
        utilities.setLayout(new BoxLayout(utilities, BoxLayout.Y_AXIS));
        utilities.setOpaque(false);
        brand = new DesktopBrandLabel(messages);
        motion.width = motion.target = SIDEBAR_WIDTH;
        setName("platform.shell");
        assembleSidebar();
        heading.title.setName("platform.pageTitle");
        heading.title.setFont(heading.title.getFont().deriveFont(Font.BOLD, UIScale.scale(19f)));
        heading.description.setFont(heading.description.getFont().deriveFont(UIScale.scale(12f)));
        heading.header.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 20));
        heading.header.setName("platform.header");
        heading.header.setPreferredSize(new Dimension(0, HEADER_HEIGHT));
        heading.description.setName("platform.pageDescription");
        var labels = new JPanel(new BorderLayout(20, 0));
        labels.setOpaque(false);
        labels.add(heading.title, BorderLayout.WEST);
        labels.add(heading.description, BorderLayout.CENTER);
        heading.header.add(centerHeading(labels), "platform");
        var workspace = new JPanel(new BorderLayout());
        workspace.add(heading.header, BorderLayout.NORTH);
        categoryPicker.setName("settings.category");
        categoryPicker.putClientProperty("desktop.navigation", true);
        categoryPicker.setRenderer(
            (list, value, index, selected, focused) -> new DefaultListCellRenderer().getListCellRendererComponent(
                list, value == null ? "" : messages.text(value.key), index, selected, focused));
        categoryPicker.addActionListener(event -> {
            if (!updatingCategories && categoryPicker.getSelectedItem() instanceof Entry entry
                && pages.getSelectedComponent() != entry.component)
                pages.setSelectedComponent(entry.component);
        });
        body.add(categoryPicker, BorderLayout.NORTH);
        body.addComponentListener(new java.awt.event.ComponentAdapter() {
            /** Adapts categories to actual content width. / 按实际内容宽度调整分类。
             * @param event size notification / 尺寸通知 */
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                adaptCategories();
            }
        });
        categoryScroll.setPreferredSize(new Dimension(UIScale.scale(150), 0));
        categoryScroll.setBorder(BorderFactory.createEmptyBorder(16, 10, 16, 8));
        body.add(categoryScroll, BorderLayout.WEST);
        body.add(pages, BorderLayout.CENTER);
        workspace.add(body, BorderLayout.CENTER);
        add(sidebar, BorderLayout.WEST);
        add(workspace, BorderLayout.CENTER);
        pages.addChangeListener(event -> refresh());
        refresh();
    }

    /** Mounts navigation, pins and security controls in their existing order. / 按既有顺序挂载导航、固定项及安全控件。 */
    private void assembleSidebar() {
        sidebar.setName("platform.sidebar");
        sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(18, 8, 12, 8));
        var top = column();
        brand.setName("navigation.logo");
        brand.setIcon(DesktopIcons.logo(36));
        brand.setHorizontalAlignment(SwingConstants.CENTER);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(54)));
        brand.setToolTipText(messages.text("home.open"));
        brand.getAccessibleContext().setAccessibleName(messages.text("home.open"));
        brand.addActionListener(event -> openHome.run());
        top.add(brand);
        configure(collapse, "navigation/panel-left-close");
        collapse.setName("navigation.collapse");
        collapse.addActionListener(event -> collapse(!collapsed, true));
        top.add(collapse);
        sidebar.add(top, BorderLayout.NORTH);
        var middle = column();
        middle.add(entries);
        middle.add(Box.createVerticalStrut(16));
        middle.add(pins);
        sidebar.add(scroll(middle), BorderLayout.CENTER);
        configure(settings, "navigation/settings");
        settings.setName("navigation.settings");
        settings.addActionListener(event -> {
            if (lastSetting != null)
                pages.setSelectedComponent(lastSetting);
        });
        utilities.add(settings);
        sidebar.add(utilities, BorderLayout.SOUTH);
    }

    /** Mounts the original root heading inside the fixed upper region. / 将原根标题挂载到固定顶部区域。
     * @param rootHeading original root title and close action / 原根标题及关闭动作 */
    void workspaceHeading(JComponent rootHeading) {
        heading.header.add(centerHeading(rootHeading), "workspace");
    }

    /** Centers one natural-height heading without stretching its buttons. / 将自然高度标题居中，不拉伸按钮。
     * @param heading original heading / 原标题
     * @return transparent heading holder / 透明标题容器 */
    private static JPanel centerHeading(JComponent heading) {
        var holder = new JPanel(new GridBagLayout());
        holder.setOpaque(false);
        var constraints = new GridBagConstraints();
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        holder.add(heading, constraints);
        return holder;
    }

    /** Returns the actual width travelled by the existing collapse motion. / 返回既有折叠动画的实际宽度行程。
     * @return expanded-to-collapsed distance / 展开至折叠距离 */
    static int sidebarTravel() {
        return SIDEBAR_WIDTH - UIScale.scale(64);
    }

    /** Creates a vertical, transparent group. / 创建透明纵向分组。
     * @return empty group / 空分组 */
    private static JPanel column() {
        var panel = new NavigationGroup();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    /** Creates a vertically scrolling navigation viewport. / 创建纵向滚动导航视口。
     * @param view existing group / 已有分组
     * @return borderless viewport / 无边框视口 */
    private static JScrollPane scroll(JComponent view) {
        var scroll = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.putClientProperty("desktop.navigation", true);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        return scroll;
    }

    /** Registers a present page and its navigation role. / 登记已有页面及其导航角色。
     * @param component existing mounted view / 已挂载视图
     * @param key localized title / 本地化标题键
     * @param icon bundled icon / 内置图标
     * @param order display order / 展示顺序
     * @param setting whether this is a settings category / 是否为设置分类 */
    void register(Component component, String key, String icon, int order, boolean setting) {
        var button = setting ? new JButton() : new DesktopNavigationButton("");
        configure(button, icon);
        button.setName("navigation." + key);
        button.addActionListener(event -> pages.setSelectedComponent(component));
        var entry = new Entry(component, key, order, setting, button);
        registered.add(entry);
        registered.sort(Comparator.comparingInt(Entry::order));
        defaultIds = registered.stream().filter(item -> !item.setting && !item.key.equals("shell.home")
            && !item.key.equals("shell.workspace")).map(Entry::key).toList();
        entries.removeAll();
        categories.removeAll();
        for (var item : registered)
            if (item.setting) categories.add(item.button);
        rebuildEntries();
        updatingCategories = true;
        try {
            categoryPicker.removeAllItems();
            for (var item : registered)
                if (item.setting)
                    categoryPicker.addItem(item);
        } finally {
            updatingCategories = false;
        }
        if (setting && !settingsVisited)
            lastSetting = registered.stream().filter(Entry::setting).findFirst().orElseThrow().component;
        refresh();
    }

    /** Returns code-defined defaults, excluding settings categories. / 返回代码定义的默认入口，排除设置分类。
     * @return stable identities / 稳定身份 */
    List<String> defaults() {
        return defaultIds;
    }

    /** Binds the home action without adding it to the reorderable sidebar. / 绑定首页动作，不加入可排序侧栏。
     * @param page permanent home surface / 常驻首页
     * @param action owning-window navigation / 所属窗口导航 */
    void home(Component page, Runnable action) {
        openHome = action;
        register(page, "shell.home", "navigation/panels-top-left", -10, false);
    }

    /** Returns the stable identity of the selected platform page. / 返回所选平台页面的稳定身份。
     * @return selected identity / 所选身份 */
    String selectedId() {
        return registered.stream().filter(entry -> entry.component == pages.getSelectedComponent()).map(Entry::key)
            .findFirst().orElse("shell.home");
    }

    /** Selects an existing platform identity without starting business work. / 选择已有平台身份，不启动业务任务。
     * @param id stable identity / 稳定身份
     * @return whether this page exists / 页面是否存在 */
    boolean select(String id) {
        var target = registered.stream().filter(entry -> entry.key.equals(id)).findFirst();
        target.ifPresent(entry -> pages.setSelectedComponent(entry.component));
        return target.isPresent();
    }

    /** Applies committed order and appends newly mounted entries. / 应用已提交顺序并追加新挂载入口。
     * @param order complete stored identities / 完整已存身份 */
    void order(List<String> order) {
        navigationOrder = List.copyOf(order);
        rebuildEntries();
        refresh();
    }

    /** Rebuilds registered platform buttons in confirmed order, appending new defaults. / 按已确认顺序重建平台按钮并追加新默认入口。 */
    private void rebuildEntries() {
        entries.removeAll();
        var ordered = new ArrayList<>(navigationOrder);
        for (String id : defaults()) if (!ordered.contains(id)) ordered.add(id);
        for (String id : ordered)
            registered.stream().filter(item -> !item.setting && !item.key.equals("shell.workspace") && item.key.equals(id)).findFirst()
                .ifPresent(item -> entries.add(item.button));
    }

    /** Returns the bottom security-control group. / 返回底部安全控件分组。
     * @return original group / 原分组 */
    JPanel utilities() {
        return utilities;
    }

    /** Mounts explicit pins and observes collapse without taking page ownership. / 挂载明确固定项并通知折叠，不接管页面归属。
     * @param view original pin view / 原固定视图
     * @param observer presentation callback / 展示回调 */
    void pins(JComponent view, DoubleConsumer observer) {
        pins.add(view);
        collapseObserver = observer;
        presentNavigation();
    }

    /** Styles one icon-led navigation action. / 设置图标导航动作的样式。
     * @param button existing action / 已有动作
     * @param icon bundled icon identity / 内置图标身份 */
    private void configure(JButton button, String icon) {
        button.putClientProperty("JButton.buttonType", null);
        button.putClientProperty("desktop.navigation", true);
        button.putClientProperty("JComponent.minimumWidth", 0);
        button.putClientProperty("html.disable", true);
        button.setIcon(DesktopIcons.icon(icon, 19, button::getForeground));
        button.setIconTextGap(10);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(38)));
        button.setMinimumSize(new Dimension(0, UIScale.scale(38)));
        button.setPreferredSize(new Dimension(UIScale.scale(140), UIScale.scale(38)));
        button.setMargin(new Insets(8, 10, 8, 10));
        button.setBorderPainted(false);
    }

    /** Refreshes labels and colors on the existing shell. / 在已有外壳刷新文字和配色。
     * @param palette effective theme / 生效主题 */
    void appearance(ThemePalette palette) {
        this.palette = palette;
        if (motion.clock != null) motion.clock.close();
        motion.width = motion.target = collapsed ? UIScale.scale(64) : SIDEBAR_WIDTH;
        motion.velocity = 0;
        sidebar.setPreferredSize(new Dimension((int) motion.target, 0));
        presentNavigation();
        brand.setIcon(DesktopIcons.logo(36));
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(54)));
        categoryScroll.setPreferredSize(new Dimension(UIScale.scale(150), 0));
        heading.title.setFont(heading.title.getFont().deriveFont(Font.BOLD, UIScale.scale(19f)));
        heading.description.setFont(heading.description.getFont().deriveFont(Font.PLAIN, UIScale.scale(12f)));
        refresh();
    }

    /** Applies the session collapse choice. / 应用会话折叠选择。
     * @param value collapsed state / 折叠状态
     * @param animate whether to animate visible windows / 是否为可见窗口播放动画 */
    void collapse(boolean value, boolean animate) {
        collapsed = value;
        refresh();
        motion.target = value ? UIScale.scale(64) : SIDEBAR_WIDTH;
        if (!animate || !isShowing() || !DesktopMotion.enabled()) {
            if (motion.clock != null) motion.clock.close();
            motion.width = motion.target;
            motion.velocity = 0;
            sidebar.setPreferredSize(new Dimension((int) motion.target, 0));
            presentNavigation();
            revalidate();
            sidebar.repaint();
            return;
        }
        if (motion.clock == null) motion.clock = new DesktopMotion(this::animateWidth);
        motion.clock.start();
    }

    /** Advances only width and paint, preserving navigation styles between frames. / 帧间仅推进宽度和绘制，保留导航样式。
     * @param seconds real frame interval / 实际帧间隔 */
    private void animateWidth(double seconds) {
        var next = DesktopMotion.step(motion.width, motion.velocity, motion.target, seconds / 1.2);
        motion.width = next.value();
        motion.velocity = next.velocity();
        if (Math.abs(motion.width - motion.target) < .1 && Math.abs(motion.velocity) < .5) {
            motion.width = motion.target;
            motion.velocity = 0;
            motion.clock.close();
        }
        sidebar.setPreferredSize(new Dimension((int) Math.round(motion.width), 0));
        presentNavigation();
        revalidate();
        sidebar.repaint();
    }

    /** Shares the width clock with fixed navigation icons, captions and footer motion. / 固定导航图标、文案及底部运动共用宽度时钟。 */
    private void presentNavigation() {
        double folded = (SIDEBAR_WIDTH - motion.width) / sidebarTravel();
        brand.presentation(folded);
        collapse.presentation(folded);
        settings.presentation(folded);
        for (var entry : registered)
            if (entry.button instanceof DesktopNavigationButton button) button.presentation(folded);
        collapseObserver.accept(folded);
        for (var component : utilities.getComponents())
            if (component instanceof DesktopLockStatus status) {
                status.alignWith(settings);
                status.presentation(folded);
            }
    }

    /** Adapts settings categories without recreating fonts, icons or button styles. / 调整设置分类，不重新创建字体、图标或按钮样式。 */
    private void adaptCategories() {
        var selected = registered.stream().filter(entry -> entry.component == pages.getSelectedComponent()).findFirst().orElse(null);
        boolean setting = selected != null && selected.setting;
        boolean compact = body.getWidth() > 0 && body.getWidth() < UIScale.scale(800);
        categoryScroll.setVisible(setting && !compact);
        categoryPicker.setVisible(setting && compact);
        if (setting && categoryPicker.getSelectedItem() != selected) categoryPicker.setSelectedItem(selected);
    }

    /** Updates navigation without changing page state. / 更新导航而不改变页面状态。 */
    private void refresh() {
        var selected = registered.stream().filter(entry -> entry.component == pages.getSelectedComponent()).findFirst()
            .orElse(null);
        boolean setting = selected != null && selected.setting;
        if (setting) {
            lastSetting = selected.component;
            settingsVisited = true;
        }
        adaptCategories();
        sidebar.setBackground(palette.sidebarBackground());
        sidebar.setBorder(BorderFactory.createEmptyBorder(16, 8, 12, 8));
        heading.header.setBackground(palette.cardBackground());
        setBackground(palette.pageBackground());
        heading.title.setForeground(palette.sidebarForeground());
        heading.description.setForeground(palette.subduedText());
        brand.setText(messages.text("shell.brand"));
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, UIScale.scale(12f)));
        brand.fitCaption(SIDEBAR_WIDTH - sidebar.getInsets().left - sidebar.getInsets().right);
        brand.setIconTextGap(UIScale.scale(6));
        brand.setForeground(palette.accent());
        brand.setToolTipText(messages.text("home.open"));
        brand.getAccessibleContext().setAccessibleName(messages.text("home.open"));
        heading.title.setText(selected == null ? messages.text("shell.workspace") : messages.text(selected.key));
        ((CardLayout) heading.header.getLayout()).show(heading.header,
            selected != null && selected.key.equals("shell.workspace") ? "workspace" : "platform");
        heading.description.setText(messages.text(selected != null && selected.key.equals("shell.home")
            ? "home.description" : setting ? "shell.settings.description" : "shell.workspace.description"));
        heading.description.setVisible(selected == null || !selected.key.equals("platform.models"));
        heading.description.setToolTipText(heading.description.getText());
        heading.title.setToolTipText(heading.description.getText());
        style(collapse, messages.text(collapsed ? "nav.expand" : "nav.collapse"), false);
        collapse.setIcon(
            DesktopIcons.icon(collapsed ? "navigation/panel-left-open" : "navigation/panel-left-close", 19, collapse::getForeground));
        style(settings, messages.text("nav.settings"), setting);
        for (var entry : registered)
            style(entry.button, messages.text(switch (entry.key) {
                case "platform.servers" -> "shell.servers";
                case "platform.models" -> "shell.ai";
                case "tasks.title" -> "shell.tasks";
                default -> entry.key;
            }), entry == selected);
        presentNavigation();
        revalidate();
        repaint();
    }

    /** Applies stateful navigation styling while retaining accessible labels. / 应用导航状态样式并保留无障碍标签。
     * @param button actual action / 实际动作
     * @param text localized label / 本地化标签
     * @param selected active location / 当前位置 */
    private void style(JButton button, String text, boolean selected) {
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(38)));
        button.setMinimumSize(new Dimension(0, UIScale.scale(38)));
        button.setPreferredSize(new Dimension(UIScale.scale(140), UIScale.scale(38)));
        button.setText(text);
        button.setMargin(new Insets(8, 10, 8, 10));
        button.setToolTipText(text);
        button.getAccessibleContext().setAccessibleName(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        boolean category = registered.stream().anyMatch(entry -> entry.setting && entry.button == button);
        java.awt.Color active = category ? palette.navigationActive() : palette.accent();
        button.setFont(button.getFont().deriveFont(Font.PLAIN, UIScale.scale(13f)));
        DesktopButtonStyle.navigation(button, selected ? active : palette.sidebarBackground(),
            selected && !category ? java.awt.Color.WHITE : palette.sidebarForeground(),
            selected ? active.darker() : palette.secondaryButtonBackground(),
            selected ? active.darker().darker() : palette.navigationActive());
    }

    /** Stops the owned animation. / 停止所属动画。 */
    @Override
    public void close() {
        if (motion.clock != null)
            motion.clock.close();
    }

    /** Local presentation metadata. / 本地展示元数据。
     * @param component existing view / 已有视图
     * @param key title key / 标题键
     * @param order navigation order / 导航顺序
     * @param setting settings category / 设置分类
     * @param button existing navigation action / 已有导航动作 */
    private record Entry(Component component, String key, int order, boolean setting, JButton button) {
    }

    /** Navigation always follows its viewport width, including during animation. / 导航始终随视口宽度变化，包括动画期间。 */
    private static class NavigationGroup extends JPanel implements Scrollable {
        /** Returns natural viewport dimensions. / 返回自然视口尺寸。
         * @return preferred dimensions / 首选尺寸 */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        /** Keeps buttons inside the sidebar. / 将按钮保持在侧栏内部。
         * @return always true / 始终为真 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /** Allows a long pin list to scroll. / 允许较长固定列表滚动。
         * @return false / 假 */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        /** Returns one scrolling step. / 返回一次滚动步长。
         * @param visible current viewport / 当前视口
         * @param orientation scroll axis / 滚动轴
         * @param direction scroll direction / 滚动方向
         * @return logical step / 逻辑步长 */
        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UIScale.scale(20);
        }

        /** Returns a page scrolling step. / 返回一次翻页步长。
         * @param visible current viewport / 当前视口
         * @param orientation scroll axis / 滚动轴
         * @param direction scroll direction / 滚动方向
         * @return viewport height / 视口高度 */
        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.height;
        }
    }

    /** Page selection container whose navigation is rendered by the sidebar. / 由侧栏呈现导航的页面选择容器。 */
    static final class PageDeck extends JTabbedPane {
        private java.util.function.BiConsumer<Component, Runnable> departure;

        private boolean leaving;

        void beforeLeave(java.util.function.BiConsumer<Component, Runnable> action) {
            departure = action;
        }

        @Override
        public void setSelectedIndex(int index) {
            if (departure == null || index == getSelectedIndex() || getSelectedComponent() == null) {
                super.setSelectedIndex(index);
                return;
            }
            if (leaving) return;
            var target = index < 0 ? null : getComponentAt(index);
            leaving = true;
            departure.accept(getSelectedComponent(), () -> {
                leaving = false;
                if (target != null && indexOfComponent(target) >= 0) super.setSelectedIndex(indexOfComponent(target));
            });
        }

        void cancelDeparture() {
            leaving = false;
        }

        /** Installs a page-only FlatLaf delegate after every theme update. / 每次主题更新安装仅呈现页面的 FlatLaf 委托。 */
        @Override
        public void updateUI() {
            setUI(new FlatTabbedPaneUI() {
                /** Removes the horizontal tab strip. / 移除水平标签栏。
                 * @param placement tab placement / 标签位置
                 * @param runs tab rows / 标签行数
                 * @param height tab height / 标签高度
                 * @return zero strip height / 零标签栏高度 */
                @Override
                protected int calculateTabAreaHeight(int placement, int runs, int height) {
                    return 0;
                }

                /** Omits tab painting owned by navigation. / 省略已由导航接管的标签绘制。
                 * @param graphics paint context / 绘制上下文
                 * @param placement placement / 位置
                 * @param selected selected index / 选中索引 */
                @Override
                protected void paintTabArea(Graphics graphics, int placement, int selected) {
                }
            });
            setBorder(BorderFactory.createEmptyBorder());
        }
    }
}
