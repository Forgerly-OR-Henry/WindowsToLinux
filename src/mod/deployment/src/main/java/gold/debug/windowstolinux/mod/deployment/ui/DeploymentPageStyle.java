package gold.debug.windowstolinux.mod.deployment.ui;

import java.awt.*;
import java.util.ResourceBundle;

import javax.swing.*;

/** Mod-local presentation using Swing theme values, without host implementation access. / 仅使用 Swing 主题值的 Mod 本地展示，不访问宿主实现。 */
final class DeploymentPageStyle {
    /** Prevents helper instances. / 禁止工具实例。 */
    private DeploymentPageStyle() {
    }

    /** Keeps complete forms reachable in small windows without replacing their controls. / 不替换控件，确保小窗口仍可访问完整表单。
     * @param root existing BorderLayout page / 已有 BorderLayout 页面 */
    static void scrollPage(JPanel root) {
        var content = new ViewportPage();
        var layout = (BorderLayout) root.getLayout();
        for (var child : root.getComponents()) {
            Object constraint = layout.getConstraints(child);
            root.remove(child);
            content.add(child, constraint);
        }
        content.setBorder(root.getBorder());
        root.setBorder(BorderFactory.createEmptyBorder());
        var scroll = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        root.add(scroll);
    }

    /** Builds a card with a localized heading and existing content. / 用本地化标题和已有内容构建卡片。
     * @param key title key / 标题键
     * @param content original controls / 原控件
     * @return owned visual wrapper / 所属展示容器 */
    static JPanel card(String key, JComponent content) {
        var card = new Card(new BorderLayout(0, 12));
        if (key != null) {
            var heading = new JLabel();
            heading.putClientProperty("deployment.style.title", key);
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() * 14f / 13f));
            card.add(heading, BorderLayout.NORTH);
        }
        card.add(content, BorderLayout.CENTER);
        card.setVisible(content.isVisible());
        content.addComponentListener(new java.awt.event.ComponentAdapter() {
            /** Hides the heading together with conditional content. / 将标题与条件内容一同隐藏。
             * @param event visibility change / 可见性变化 */
            @Override
            public void componentHidden(java.awt.event.ComponentEvent event) {
                card.setVisible(false);
            }

            /** Restores the original card when content is selected. / 内容被选择时恢复原卡片。
             * @param event visibility change / 可见性变化 */
            @Override
            public void componentShown(java.awt.event.ComponentEvent event) {
                card.setVisible(true);
            }
        });
        return card;
    }

    /** Styles an original page and its controls. / 设置原页面及控件样式。
     * @param root original page / 原页面
     * @param messages current owned catalog / 当前所属语言目录 */
    static void refresh(JComponent root, ResourceBundle messages) {
        root.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (root instanceof JLabel label && label.getClientProperty("deployment.style.title") instanceof String key)
            label.setText(messages.getString(key));
        if (root instanceof JButton button) {
            button.putClientProperty("JButton.buttonType", null);
            button.setMargin(new Insets(7, 13, 7, 13));
            if (Boolean.TRUE.equals(button.getClientProperty("deployment.style.primary"))) {
                button.setBackground(UIManager.getColor("Component.focusColor"));
                button.setForeground(Color.WHITE);
            }
        }
        if (root instanceof JPanel panel && !(panel instanceof Card)) {
            panel.setOpaque(false);
            if (panel.getLayout() instanceof FlowLayout flow && !(flow instanceof Actions))
                panel.setLayout(new Actions(flow));
        }
        if (root instanceof JScrollPane scroll) {
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(18);
        }
        for (var child : root.getComponents())
            if (child instanceof JComponent component)
                refresh(component, messages);
        root.revalidate();
        root.repaint();
    }

    /** Creates the two responsive primary selection cards. / 创建两个响应式主要选择卡片。
     * @param source original source card / 原来源卡片
     * @param server original target card / 原目标卡片
     * @return responsive selection region / 响应式选择区 */
    static JPanel selections(JComponent source, JComponent server) {
        var panel = new JPanel(new GridLayout(1, 2, 16, 12)) {
            /** Lays out the same cards according to available width. / 根据可用宽度排列同一卡片。 */
            @Override
            public void doLayout() {
                var grid = (GridLayout) getLayout();
                grid.setColumns(getWidth() < 620 ? 1 : 2);
                grid.setRows(getWidth() < 620 ? 2 : 1);
                super.doLayout();
            }

            /** Measures the responsive arrangement before parent layout. / 在父布局前测量响应式排列。
             * @return actual preferred size / 实际首选尺寸 */
            @Override
            public Dimension getPreferredSize() {
                int width = getParent() == null ? 900 : getParent().getWidth();
                boolean narrow = width > 0 && width < 620;
                var a = source.getPreferredSize();
                var b = server.getPreferredSize();
                return new Dimension(narrow ? Math.max(a.width, b.width) : a.width + b.width + 16,
                    narrow ? 2 * Math.max(a.height, b.height) + 12 : Math.max(a.height, b.height));
            }
        };
        panel.setOpaque(false);
        panel.add(source);
        panel.add(server);
        return panel;
    }

    /** Adds a labeled field to a simple vertical form. / 为简单纵向表单添加带标签字段。
     * @param form existing form / 已有表单
     * @param label original label / 原标签
     * @param input original input / 原输入 */
    static void field(JPanel form, JLabel label, JComponent input) {
        var row = new JPanel(new BorderLayout(0, 4));
        row.setOpaque(false);
        label.setLabelFor(input);
        row.add(label, BorderLayout.NORTH);
        row.add(input, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        form.add(row);
    }

    /** Returns a transparent vertical form. / 返回透明纵向表单。
     * @return new form / 新表单 */
    static JPanel form() {
        var form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        return form;
    }

    /** Mod-owned modeless advanced view retaining original input components. / 保留原输入控件的 Mod 所属非模态高级视图。 */
    static final class Advanced implements AutoCloseable {
        /** Actual page used to resolve window ownership. / 用于解析窗口归属的实际页面。 */
        private final JComponent root;

        /** Original advanced controls. / 原高级控件。 */
        private final JPanel content;

        /** Explicit advanced entry. / 明确的高级入口。 */
        private final JButton button = new JButton();

        /** Lazily created owned window. / 惰性创建的从属窗口。 */
        private JDialog window;

        /** Current localized title. / 当前本地化标题。 */
        private String title;

        /** Retains fields without opening a window. / 保留字段而不打开窗口。
         * @param root owning page / 所属页面
         * @param content original advanced fields / 原高级字段 */
        Advanced(JComponent root, JPanel content) {
            this.root = root;
            this.content = content;
            content.setName("deployment.advanced.content");
            button.setName("deployment.advanced");
            button.addActionListener(event -> open());
        }

        /** Returns the explicit entry. / 返回明确入口。
         * @return original button / 原按钮 */
        JButton button() {
            return button;
        }

        /** Opens a window without resizing the main workspace. / 打开窗口而不调整主工作区尺寸。 */
        private void open() {
            if (!button.isEnabled())
                return;
            if (window == null || !window.isDisplayable()) {
                window = new JDialog(SwingUtilities.getWindowAncestor(root), title, Dialog.ModalityType.MODELESS);
                window.setName("deployment.advanced.window");
                window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
                var body = new ViewportPage();
                body.add(content, BorderLayout.NORTH);
                var scroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                scroll.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
                scroll.getVerticalScrollBar().setUnitIncrement(18);
                window.add(scroll);
                var screen = window.getGraphicsConfiguration().getBounds();
                window.setSize(Math.min(600, screen.width - 40), Math.min(680, screen.height - 80));
                window.setLocationRelativeTo(root);
            }
            window.setVisible(true);
            window.toFront();
        }

        /** Refreshes existing fields and hides advanced content across lock boundaries. / 刷新原字段并在锁定边界隐藏高级内容。
         * @param messages owned catalog / 所属语言目录
         * @param locked actual lock state / 实际锁状态 */
        void environment(ResourceBundle messages, boolean locked) {
            title = messages.getString("style.advanced");
            button.setText(title);
            button.setEnabled(!locked);
            if (window != null) {
                window.setTitle(title);
                SwingUtilities.updateComponentTreeUI(window);
                if (locked)
                    window.setVisible(false);
            }
            refresh(content, messages);
        }

        /** Disposes only owned visual resources. / 仅销毁所属视觉资源。 */
        @Override
        public void close() {
            if (window != null)
                window.dispose();
        }
    }

    /** A page that follows width and scrolls when its full content is taller. / 随宽度变化并在完整内容较高时滚动的页面。 */
    private static final class ViewportPage extends JPanel implements Scrollable {
        /** Creates an ordinary width-following content area. / 创建普通随宽度变化的内容区。 */
        private ViewportPage() {
            super(new BorderLayout(8, 12));
            setOpaque(false);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                /** Remeasures stacked cards after viewport changes. / 视口改变后重新测量纵排卡片。
                 * @param event size change / 尺寸变化 */
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    revalidate();
                }
            });
        }

        /** Returns preferred viewport dimensions. / 返回首选视口尺寸。
         * @return preferred size / 首选尺寸 */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        /** Keeps forms within the viewport. / 将表单保持在视口内。
         * @return true / 真 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /** Expands only when the whole form fits. / 仅当完整表单可容纳时扩展。
         * @return whether height fits / 高度是否可容纳 */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getPreferredSize().height <= getParent().getHeight();
        }

        /** Returns a small scroll step. / 返回较小滚动步长。
         * @param visible viewport / 视口
         * @param axis axis / 轴
         * @param direction direction / 方向
         * @return scroll step / 滚动步长 */
        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int axis, int direction) {
            return 20;
        }

        /** Returns one viewport scroll step. / 返回一视口滚动步长。
         * @param visible viewport / 视口
         * @param axis axis / 轴
         * @param direction direction / 方向
         * @return viewport height / 视口高度 */
        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int axis, int direction) {
            return visible.height;
        }
    }

    /** Mod-local wrapping actions measured before parent layout. / 父布局前测量的 Mod 本地换行操作区。 */
    private static final class Actions extends FlowLayout {
        /** Last measured wrapped height. / 上次测量的换行高度。 */
        private int measuredHeight = -1;

        /** Preserves action alignment. / 保留操作对齐。
         * @param original original flow / 原流式布局 */
        private Actions(FlowLayout original) {
            super(original.getAlignment(), original.getHgap(), original.getVgap());
        }

        /** Refreshes parent height when row count changes. / 行数改变时刷新父容器高度。
         * @param target existing actions / 已有操作区 */
        @Override
        public void layoutContainer(Container target) {
            super.layoutContainer(target);
            int height = preferredLayoutSize(target).height;
            if (height != measuredHeight) {
                measuredHeight = height;
                if (target.getParent() instanceof JComponent parent)
                    EventQueue.invokeLater(parent::revalidate);
            }
        }

        /** Includes every wrapped row. / 包含每个换行。
         * @param target existing controls / 已有控件
         * @return measured size / 测量尺寸 */
        @Override
        public Dimension preferredLayoutSize(Container target) {
            if (target.getWidth() <= 0)
                return super.preferredLayoutSize(target);
            var insets = target.getInsets();
            int limit = Math.max(1, target.getWidth() - insets.left - insets.right - 2 * getHgap());
            int width = 0, height = 0, row = 0;
            for (var child : target.getComponents()) {
                if (!child.isVisible())
                    continue;
                var size = child.getPreferredSize();
                if (width > 0 && width + getHgap() + size.width > limit) {
                    height += row + getVgap();
                    width = 0;
                    row = 0;
                }
                width += (width == 0 ? 0 : getHgap()) + size.width;
                row = Math.max(row, size.height);
            }
            return new Dimension(target.getWidth(), height + row + insets.top + insets.bottom + 2 * getVgap());
        }
    }

    /** Rounded surface resolving live host Swing colors at paint time. / 绘制时解析实时宿主 Swing 配色的圆角界面。 */
    private static final class Card extends JPanel {
        /** Creates a padded presentation surface. / 创建带内边距的展示界面。
         * @param layout content layout / 内容布局 */
        private Card(LayoutManager layout) {
            super(layout);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        }

        /** Prevents vertical forms from stretching input cards. / 阻止纵向表单拉伸输入卡片。
         * @return natural height with flexible width / 自然高度与弹性宽度 */
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        /** Paints the surface without altering child state. / 绘制界面而不改变子控件状态。
         * @param graphics current context / 当前上下文 */
        @Override
        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                String color = Boolean.TRUE.equals(getClientProperty("deployment.style.inventory"))
                    ? Boolean.TRUE.equals(getClientProperty("deployment.style.selected")) ? "List.selectionBackground" : "Panel.background"
                    : "List.background";
                g.setColor(UIManager.getColor(color));
                g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }
}
