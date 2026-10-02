package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.*;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;

/** Persistent desktop navigation around existing page instances. / 围绕已有页面实例的常驻桌面导航。 */
final class PlatformNavigationShell extends JPanel implements AutoCloseable {
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
    private final JPanel utilities = column();

    /** Settings categories. / 设置分类。 */
    private final JPanel categories = column();

    /** Category viewport. / 分类视口。 */
    private final JScrollPane categoryScroll = scroll(categories);

    /** Compact category selector for narrow windows. / 窄窗口的紧凑分类选择器。 */
    private final JComboBox<Entry> categoryPicker = new JComboBox<>();

    /** Available workspace width. / 可用工作区宽度。 */
    private final JPanel body = new JPanel(new BorderLayout());

    /** Product identity without a business-module dependency. / 不依赖业务模块的产品标识。 */
    private final JLabel brand = new JLabel();

    /** Current page heading. / 当前页面标题。 */
    private final JLabel title = new JLabel();

    /** Page explanation. / 页面说明。 */
    private final JLabel description = new JLabel();

    /** Header surface. / 标题界面。 */
    private final JPanel header = new JPanel(new BorderLayout(0, 5));

    /** Collapse action. / 折叠动作。 */
    private final JButton collapse = new JButton();

    /** Settings entry remains at the bottom. / 设置入口保持在底部。 */
    private final JButton settings = new JButton();

    /** Page presentation metadata only. / 仅保存页面展示元数据。 */
    private final List<Entry> registered = new ArrayList<>();

    /** User's current session preference. / 用户的当前会话偏好。 */
    private boolean collapsed;

    /** Blocks category-model events during registration. / 登记期间阻止分类模型事件。 */
    private boolean updatingCategories;

    /** Last visited settings category. / 上次访问的设置分类。 */
    private Component lastSetting;

    /** Owned transition, stopped on disposal. / 销毁时停止的所属过渡动画。 */
    private Timer animation;

    /** Updates pinned-entry presentation. / 更新固定入口展示。 */
    private Consumer<Boolean> collapseObserver = ignored -> {
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
        setName("platform.shell");
        sidebar.setName("platform.sidebar");
        sidebar.setPreferredSize(new Dimension(UIScale.scale(168), 0));
        sidebar.setBorder(BorderFactory.createEmptyBorder(18, 8, 12, 8));
        var top = column();
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, UIScale.scale(12f)));
        brand.setBorder(BorderFactory.createEmptyBorder(4, 7, 18, 0));
        top.add(brand);
        configure(collapse, "panel-left-close");
        collapse.setName("navigation.collapse");
        collapse.addActionListener(event -> collapse(!collapsed, true));
        top.add(collapse);
        sidebar.add(top, BorderLayout.NORTH);
        var middle = column();
        middle.add(entries);
        middle.add(Box.createVerticalStrut(16));
        middle.add(pins);
        sidebar.add(scroll(middle), BorderLayout.CENTER);
        configure(settings, "settings");
        settings.setName("navigation.settings");
        settings.addActionListener(event -> {
            if (lastSetting != null)
                pages.setSelectedComponent(lastSetting);
        });
        utilities.add(settings);
        sidebar.add(utilities, BorderLayout.SOUTH);
        title.setName("platform.pageTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, UIScale.scale(21f)));
        description.setFont(description.getFont().deriveFont(UIScale.scale(12f)));
        header.setBorder(BorderFactory.createEmptyBorder(18, 22, 17, 22));
        header.add(title, BorderLayout.NORTH);
        header.add(description, BorderLayout.SOUTH);
        var workspace = new JPanel(new BorderLayout());
        workspace.add(header, BorderLayout.NORTH);
        categoryPicker.setName("settings.category");
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
                refresh();
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
        var button = new JButton();
        configure(button, icon);
        button.setName("navigation." + key);
        button.addActionListener(event -> pages.setSelectedComponent(component));
        var entry = new Entry(component, key, order, setting, button);
        registered.add(entry);
        registered.sort(Comparator.comparingInt(Entry::order));
        entries.removeAll();
        categories.removeAll();
        for (var item : registered)
            (item.setting ? categories : entries).add(item.button);
        updatingCategories = true;
        try {
            categoryPicker.removeAllItems();
            for (var item : registered)
                if (item.setting)
                    categoryPicker.addItem(item);
        } finally {
            updatingCategories = false;
        }
        if (setting && lastSetting == null)
            lastSetting = component;
        refresh();
    }

    /** Returns the bottom security-control group. / 返回底部安全控件分组。
     * @return original group / 原分组 */
    JPanel utilities() {
        return utilities;
    }

    /** Mounts explicit pins and observes collapse without taking page ownership. / 挂载明确固定项并通知折叠，不接管页面归属。
     * @param view original pin view / 原固定视图
     * @param observer presentation callback / 展示回调 */
    void pins(JComponent view, Consumer<Boolean> observer) {
        pins.add(view);
        collapseObserver = observer;
        observer.accept(collapsed);
    }

    /** Styles one icon-led navigation action. / 设置图标导航动作的样式。
     * @param button existing action / 已有动作
     * @param icon bundled icon identity / 内置图标身份 */
    private void configure(JButton button, String icon) {
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.putClientProperty("JComponent.minimumWidth", 0);
        button.putClientProperty("html.disable", true);
        button.setIcon(DesktopIcons.icon(icon, 19, button::getForeground));
        button.setIconTextGap(10);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(43)));
        button.setMinimumSize(new Dimension(0, UIScale.scale(43)));
        button.setPreferredSize(new Dimension(UIScale.scale(140), UIScale.scale(43)));
        button.setMargin(new Insets(8, 10, 8, 10));
        button.setBorderPainted(false);
    }

    /** Refreshes labels and colors on the existing shell. / 在已有外壳刷新文字和配色。
     * @param palette effective theme / 生效主题 */
    void appearance(ThemePalette palette) {
        this.palette = palette;
        refresh();
    }

    /** Applies the session collapse choice. / 应用会话折叠选择。
     * @param value collapsed state / 折叠状态
     * @param animate whether to animate visible windows / 是否为可见窗口播放动画 */
    void collapse(boolean value, boolean animate) {
        collapsed = value;
        if (animation != null)
            animation.stop();
        collapseObserver.accept(value);
        refresh();
        int target = UIScale.scale(value ? 64 : 168);
        if (!animate || !isShowing() || "false".equals(System.getProperty("flatlaf.animation"))
            || Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation"))) {
            sidebar.setPreferredSize(new Dimension(target, 0));
            revalidate();
            return;
        }
        int start = sidebar.getWidth();
        long began = System.nanoTime();
        animation = new Timer(15, event -> {
            double elapsed = Math.min(1, (System.nanoTime() - began) / 180_000_000.0);
            double fraction = 1 - Math.pow(1 - elapsed, 3);
            sidebar.setPreferredSize(new Dimension((int) Math.round(start + (target - start) * fraction), 0));
            revalidate();
            if (elapsed >= 1)
                ((Timer) event.getSource()).stop();
        });
        animation.start();
    }

    /** Updates navigation without changing page state. / 更新导航而不改变页面状态。 */
    private void refresh() {
        var selected = registered.stream().filter(entry -> entry.component == pages.getSelectedComponent()).findFirst()
            .orElse(null);
        boolean setting = selected != null && selected.setting;
        if (setting)
            lastSetting = selected.component;
        boolean compact = body.getWidth() > 0 && body.getWidth() < UIScale.scale(800);
        categoryScroll.setVisible(setting && !compact);
        categoryPicker.setVisible(setting && compact);
        if (setting && categoryPicker.getSelectedItem() != selected)
            categoryPicker.setSelectedItem(selected);
        sidebar.setBackground(palette.sidebarBackground());
        header.setBackground(palette.cardBackground());
        setBackground(palette.pageBackground());
        title.setForeground(palette.sidebarForeground());
        description.setForeground(palette.subduedText());
        brand.setText(messages.text(collapsed ? "shell.brand.short" : "shell.brand"));
        brand.setForeground(palette.accent());
        title.setText(selected == null ? messages.text("shell.workspace") : messages.text(selected.key));
        description.setText(messages.text(setting ? "shell.settings.description" : "shell.workspace.description"));
        style(collapse, messages.text(collapsed ? "nav.expand" : "nav.collapse"), false, collapsed);
        collapse.setIcon(
            DesktopIcons.icon(collapsed ? "panel-left-open" : "panel-left-close", 19, collapse::getForeground));
        style(settings, messages.text("nav.settings"), setting, collapsed);
        for (var entry : registered)
            style(entry.button, messages.text(switch (entry.key) {
                case "platform.servers" -> "shell.servers";
                case "platform.models" -> "shell.ai";
                case "tasks.title" -> "shell.tasks";
                default -> entry.key;
            }), entry == selected, !entry.setting && collapsed);
        for (var component : utilities.getComponents())
            if (component instanceof JButton button && button != settings) {
                if (button.getIcon() == null)
                    configure(button, "shield");
                style(button, messages.text("shell.lock"), false, collapsed);
                button.setToolTipText(messages.text("security.lock"));
            }
        revalidate();
        repaint();
    }

    /** Applies stateful navigation styling while retaining accessible labels. / 应用导航状态样式并保留无障碍标签。
     * @param button actual action / 实际动作
     * @param text localized label / 本地化标签
     * @param selected active location / 当前位置
     * @param iconOnly collapsed presentation / 折叠展示 */
    private void style(JButton button, String text, boolean selected, boolean iconOnly) {
        button.setText(iconOnly ? "" : text);
        button.setMargin(new Insets(8, iconOnly ? 3 : 10, 8, iconOnly ? 3 : 10));
        button.setToolTipText(text);
        button.getAccessibleContext().setAccessibleName(text);
        button.setHorizontalAlignment(iconOnly ? SwingConstants.CENTER : SwingConstants.LEFT);
        button.setBackground(selected ? palette.navigationActive() : palette.sidebarBackground());
        button.setForeground(selected ? palette.accent() : palette.sidebarForeground());
        button.setFont(button.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN));
    }

    /** Stops the owned animation. / 停止所属动画。 */
    @Override
    public void close() {
        if (animation != null)
            animation.stop();
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
    private static final class NavigationGroup extends JPanel implements Scrollable {
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
