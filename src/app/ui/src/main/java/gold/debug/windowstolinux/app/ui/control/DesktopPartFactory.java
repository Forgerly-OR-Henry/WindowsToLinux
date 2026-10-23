package gold.debug.windowstolinux.app.ui.control;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import gold.debug.windowstolinux.app.ui.display.ThemePalette;

/**
 * Creates Swing controls using the selected desktop theme and shared visual conventions.
 * <p>按所选桌面主题及共享视觉约定创建 Swing 控件。
 */
public final class DesktopPartFactory {
    /**
     * Palette.
     * <p>配色。
     */
    private ThemePalette palette;

    /**
     * Validates and binds the inputs required by desktop component factory.
     * <p>校验并绑定Desktop组件工厂所需输入。
     *
     * @param palette palette / 配色
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopPartFactory(ThemePalette palette) {
        this.palette = Objects.requireNonNull(palette, "palette");
    }

    /** Updates colors used for subsequently created controls. / 更新后续创建控件所用颜色。
     * @param palette effective theme colors / 生效主题颜色 */
    public void palette(ThemePalette palette) {
        this.palette = Objects.requireNonNull(palette);
    }

    /** Applies declared palette roles to an existing component tree. / 向已有组件树应用声明的配色角色。
     * @param root original component tree / 原组件树 */
    public void refreshTheme(Component root) {
        DesktopThemeBindings.refresh(root, palette);
    }

    /** Wraps the original list content in the shared rounded card surface. / 使用共用圆角卡片包装原清单内容。
     * @param <T> original row identity / 原行身份
     * @param list original list / 原清单
     * @param ordered whether the list offers ordering / 清单是否支持排序 */
    public <T> void cardList(javax.swing.JList<T> list, boolean ordered) {
        list.putClientProperty(gold.debug.windowstolinux.app.ui.display.DesktopListCard.DRAGGABLE, ordered);
        list.repaint();
    }

    /** Declares card presentation and handle capability without binding persistence. / 声明卡片及手柄能力，不绑定持久化。 */
    public void cardList(javax.swing.JTable table, boolean ordered) {
        table.putClientProperty("desktop.inventory.cards", true);
        table.putClientProperty(gold.debug.windowstolinux.app.ui.display.DesktopListCard.DRAGGABLE, ordered);
        gold.debug.windowstolinux.app.ui.display.DesktopTableUi.decorate(table);
        table.repaint();
    }

    /** Binds a table's immutable identity order without enabling its handle. / 绑定表格不可变身份顺序，不自动开启手柄。 */
    public <T> DesktopCardTableOrder<T> cardOrder(javax.swing.JTable table,
                                                  java.util.function.Supplier<java.util.List<T>> currentOrder,
                                                  java.util.function.Consumer<java.util.List<T>> reordered,
                                                  java.util.function.BooleanSupplier admitted) {
        return new DesktopCardTableOrder<>(table, currentOrder, reordered, admitted);
    }

    /** Builds a heading whose visible labels retain declared locale keys. / 创建可见标签保留声明语言键的标题。
     * @param messages current message presenter / 当前消息展示器
     * @param title title key / 标题键
     * @param description explanation key / 说明键
     * @return original heading component / 原标题组件 */
    public JComponent sectionHeading(gold.debug.windowstolinux.app.ui.error.PageMessagePresenter messages,
                                     String title, String description) {
        var heading = sectionHeading(messages.text(title), messages.text(description));
        var labels = heading.getComponents();
        messages.bind((JLabel) labels[0], title);
        messages.bind((JLabel) labels[1], description);
        return heading;
    }

    /** Adds a form label with explicit locale identity. / 添加具备明确语言身份的表单标签。
     * @param panel original form / 原表单
     * @param row grid row / 网格行
     * @param column grid column / 网格列
     * @param messages current presenter / 当前展示器
     * @param key label key / 标签键
     * @param component original input, kept unbound / 保持未绑定的原输入 */
    public void addField(JPanel panel, int row, int column,
                         gold.debug.windowstolinux.app.ui.error.PageMessagePresenter messages, String key,
                         Component component) {
        int previous = panel.getComponentCount();
        addField(panel, row, column, messages.text(key), component);
        messages.bind((JLabel) panel.getComponent(previous), key);
    }

    /**
     * Returns page panel.
     * <p>返回页面面板。
     *
     * @return the operation result / 操作结果
     */
    public JPanel pagePanel() {
        JPanel panel = new ScrollablePage();
        panel.setBackground(palette.pageBackground());
        return DesktopThemeBindings.role(panel, "page");
    }

    /** Applies the shared background to an existing page without replacing controls. / 为已有页面应用统一背景，不替换控件。
     * @param page existing page / 已有页面 */
    public void stylePage(JComponent page) {
        DesktopThemeBindings.role(page, "page");
        page.setBackground(palette.pageBackground());
    }

    /** Keeps search surfaces distinct in light mode and follows live theme changes. / 浅色搜索框突出显示，并跟随实时主题更新。
     * @param field existing search input / 已有搜索输入框 */
    public void styleSearchField(JTextField field) {
        field.putClientProperty("FlatLaf.style", "background: $TextField.searchBackground; focusedBackground: $TextField.searchBackground");
    }

    /** Keeps complete page controls reachable when the viewport is shorter than their natural height. / 视口较矮时仍可访问全部页面控件。
     * @param root original BorderLayout page / 原 BorderLayout 页面 */
    public void scrollPage(JPanel root) {
        var layout = (BorderLayout) root.getLayout();
        var content = pagePanel();
        content.setLayout(new BorderLayout(layout.getHgap(), layout.getVgap()));
        for (var child : root.getComponents()) {
            Object constraint = layout.getConstraints(child);
            root.remove(child);
            content.add(child, constraint);
        }
        content.setBorder(root.getBorder());
        root.setBorder(BorderFactory.createEmptyBorder());
        var scroll = new JScrollPane(content, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(com.formdev.flatlaf.util.UIScale.scale(20));
        root.add(scroll, BorderLayout.CENTER);
    }

    /** Renders one inventory item with a stable title, summary and status. / 以稳定标题、摘要与状态呈现一个清单项。
     * @param title item label / 项目标签
     * @param detail non-secret summary / 非秘密摘要
     * @param status observed state / 观测状态
     * @param selected current selection / 当前选择
     * @return presentation-only card / 仅展示的卡片 */
    public JComponent inventoryCard(String title, String detail, String status, boolean selected) {
        JPanel card = transparent(new BorderLayout(10, 6));
        var name = new JLabel(title);
        name.putClientProperty("html.disable", true);
        DesktopThemeBindings.font(name, name.getFont().deriveFont(Font.BOLD, 14f));
        name.setForeground(javax.swing.UIManager.getColor("List.foreground"));
        var summary = new JLabel(detail);
        summary.putClientProperty("html.disable", true);
        summary.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        card.add(name, BorderLayout.NORTH);
        card.add(summary, BorderLayout.CENTER);
        if (!status.isBlank()) {
            var indicator = badge(status);
            indicator.setBackground(javax.swing.UIManager.getColor("Button.background"));
            indicator.setForeground(javax.swing.UIManager.getColor("Component.focusColor"));
            card.add(indicator, BorderLayout.EAST);
        }
        return gold.debug.windowstolinux.app.ui.display.DesktopListCard.row(card, selected, false);
    }

    /**
     * Creates a nonopaque panel using the supplied layout manager.
     * <p>使用所提供布局管理器创建非不透明面板。
     *
     * @param layout layout / 布局
     * @return the operation result / 操作结果
     */
    public JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout instanceof java.awt.FlowLayout flow ? new WrappingFlowLayout(flow) : layout);
        panel.setOpaque(false);
        return panel;
    }

    /**
     * Creates a themed rounded badge with the supplied text.
     * <p>使用所提供文本创建主题化圆角标记。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return the operation result / 操作结果
     */
    public JLabel badge(String text) {
        JLabel badge = new JLabel(text) {
            /**
             * Paints component.
             * <p>绘制组件。
             *
             * @param graphics graphics / 图形
             */
            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                var g = (java.awt.Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(getBackground());
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                } finally {
                    g.dispose();
                }
                super.paintComponent(graphics);
            }
        };
        badge.setBackground(palette.badgeBackground());
        badge.setForeground(palette.accentDark());
        DesktopThemeBindings.font(badge, badge.getFont().deriveFont(Font.BOLD, 11f));
        badge.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        return DesktopThemeBindings.role(badge, "badge");
    }

    /**
     * Creates a themed rounded content card with standard inner padding.
     * <p>创建具有标准内边距的主题化圆角内容卡片。
     *
     * @param layout layout / 布局
     * @return the operation result / 操作结果
     */
    public JPanel card(LayoutManager layout) {
        JPanel card = new RoundedCard(layout, palette.cardBackground());
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        return DesktopThemeBindings.role(card, "card");
    }

    /** Separates related detail rows within one surface. / 在同一内容块内分隔关联的详情行。
     * @return theme-aware subtle line / 随主题更新的浅分隔线 */
    public JSeparator divider() {
        var divider = new JSeparator();
        divider.setForeground(palette.cardBorder());
        return DesktopThemeBindings.role(divider, "divider");
    }

    /**
     * Combines a styled section title with its explanatory text.
     * <p>组合带样式的分区标题及其说明文本。
     *
     * @param title title / 标题
     * @param description description / 说明
     * @return the operation result / 操作结果
     */
    public JComponent sectionHeading(String title, String description) {
        JPanel heading = transparent(new BorderLayout(0, 3));
        JLabel label = new JLabel(title);
        DesktopThemeBindings.font(label, label.getFont().deriveFont(Font.BOLD, 15f));
        label.setForeground(palette.sidebarForeground());
        DesktopThemeBindings.role(label, "title");
        JLabel detail = description(description);
        heading.add(label, BorderLayout.NORTH);
        heading.add(detail, BorderLayout.SOUTH);
        return heading;
    }

    /** Creates a localized explanation that follows the available page width. / 创建随页面可用宽度换行的本地化说明。
     * @param text plain explanation / 纯文本说明
     * @return themed wrapping label / 主题换行标签 */
    public JLabel description(String text) {
        JLabel detail = new WrappingLabel(text, 420);
        detail.setForeground(palette.subduedText());
        DesktopThemeBindings.role(detail, "detail");
        DesktopThemeBindings.font(detail, detail.getFont().deriveFont(12f));
        return detail;
    }

    /**
     * Builds a numbered step card containing its explanation and action button.
     * <p>构建包含说明及动作按钮的编号步骤卡片。
     *
     * @param index index / 索引
     * @param title title / 标题
     * @param description description / 说明
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return the operation result / 操作结果
     */
    public JPanel stepCard(String index, String title, String description, JButton action) {
        JPanel card = card(new BorderLayout(0, 12));
        JPanel copy = transparent(new BorderLayout(0, 4));
        JLabel number = new JLabel(index);
        number.setForeground(palette.accent());
        DesktopThemeBindings.role(number, "number");
        DesktopThemeBindings.font(number, number.getFont().deriveFont(Font.BOLD, 12f));
        JLabel heading = new JLabel(title);
        DesktopThemeBindings.font(heading, heading.getFont().deriveFont(Font.BOLD, 15f));
        DesktopThemeBindings.role(heading, "title");
        JLabel detail = new JLabel("<html><body style='width: 170px'>" + description + "</body></html>");
        detail.setForeground(palette.subduedText());
        DesktopThemeBindings.role(detail, "detail");
        DesktopThemeBindings.font(detail, detail.getFont().deriveFont(12f));
        copy.add(number, BorderLayout.NORTH);
        copy.add(heading, BorderLayout.CENTER);
        copy.add(detail, BorderLayout.SOUTH);
        card.add(copy, BorderLayout.CENTER);
        card.add(action, BorderLayout.SOUTH);
        return card;
    }

    /**
     * Creates an accent-colored primary action button with shared interaction styling.
     * <p>创建使用强调色及共享交互样式的主动作按钮。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return the operation result / 操作结果
     */
    public JButton primaryButton(String text) {
        JButton button = new JButton(text);
        styleButton(button, true);
        return button;
    }

    /**
     * Creates a secondary action button using the current palette.
     * <p>使用当前配色创建次级动作按钮。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return the operation result / 操作结果
     */
    public JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        styleSecondary(button);
        return button;
    }

    /** Creates a sidebar action whose icon stays fixed during collapse. / 创建折叠期间图标固定的侧栏动作。
     * @param text complete navigation caption / 完整导航文案
     * @return original navigation action / 原导航动作 */
    public DesktopNavigationButton navigationButton(String text) {
        var button = new DesktopNavigationButton(text);
        styleSecondary(button);
        return button;
    }

    /** Reuses secondary surfaces for regular and navigation actions. / 为普通及导航动作复用次级底色。
     * @param button original action / 原动作 */
    private void styleSecondary(JButton button) {
        styleButton(button, false);
    }

    /** Updates an existing action's emphasis while retaining its geometry and listeners. / 更新现有动作的强调色，保留尺寸及监听器。
     * @param button original action / 原动作
     * @param primary whether the action is emphasized / 是否强调动作 */
    public void styleButton(JButton button, boolean primary) {
        DesktopThemeBindings.button(button, palette, primary);
        DesktopThemeBindings.role(button, primary ? "primary" : "secondary");
    }

    /**
     * Adds an accessible label and input pair to the form's grid row.
     * <p>向表单网格行添加具备无障碍关联的标签及输入控件。
     *
     * @param panel panel / 面板
     * @param row row / 数据行
     * @param column column / 列
     * @param label label / 标签
     * @param component component / 组件
     */
    public void addField(JPanel panel, int row, int column, String label, Component component) {
        int leftColumn = column * 2;
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = leftColumn;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(0, 0, 10, 8);
        JLabel labelComponent = new WrappingLabel(label, 130);
        labelComponent.setForeground(palette.subduedText());
        labelComponent.setLabelFor(component);
        DesktopThemeBindings.role(labelComponent, "detail");
        panel.add(labelComponent, labelConstraints);
        GridBagConstraints inputConstraints = new GridBagConstraints();
        inputConstraints.gridx = leftColumn + 1;
        inputConstraints.gridy = row;
        inputConstraints.weightx = 1;
        inputConstraints.fill = GridBagConstraints.HORIZONTAL;
        inputConstraints.insets = new Insets(0, 0, 10, column == 0 ? 22 : 0);
        panel.add(component, inputConstraints);
    }

    /**
     * Wraps a text output area in a themed scrollable card with a heading.
     * <p>将文本输出区包装到具有标题的主题化可滚动卡片。
     *
     * @param title title / 标题
     * @param description description / 说明
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return the operation result / 操作结果
     */
    public JPanel outputCard(String title, String description, JTextArea output) {
        JPanel card = card(new BorderLayout(0, 12));
        card.add(sectionHeading(title, description), BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(output);
        output.setBackground(palette.cardBackground());
        output.setForeground(palette.sidebarForeground());
        DesktopThemeBindings.role(output, "output");
        scrollPane.getViewport().setBackground(palette.cardBackground());
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        DesktopThemeBindings.role(scrollPane, "scroll");
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        card.add(scrollPane, BorderLayout.CENTER);
        return card;
    }

    /**
     * Builds a read-only information card from a title and normalized message text.
     * <p>根据标题及规范化消息文本构建只读信息卡片。
     *
     * @param title title / 标题
     * @param message localized explanation / 本地化说明
     * @return the operation result / 操作结果
     */
    public JPanel informationCard(String title, String message) {
        JPanel card = card(new BorderLayout(0, 10));
        JLabel heading = new JLabel(title);
        DesktopThemeBindings.role(heading, "title");
        DesktopThemeBindings.font(heading, heading.getFont().deriveFont(Font.BOLD, 15f));
        JTextArea detail = new JTextArea(message.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", ""));
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setOpaque(false);
        detail.setRows(5);
        detail.setColumns(16);
        detail.setForeground(palette.subduedText());
        DesktopThemeBindings.role(detail, "detail");
        DesktopThemeBindings.font(detail, detail.getFont().deriveFont(13f));
        card.add(heading, BorderLayout.NORTH);
        card.add(detail, BorderLayout.CENTER);
        return card;
    }

    /**
     * Returns output area.
     * <p>返回输出区域。
     *
     * @return the operation result / 操作结果
     */
    public static JTextArea outputArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setMargin(new Insets(12, 12, 12, 12));
        Font font = javax.swing.UIManager.getFont("defaultFont");
        if (font == null) font = javax.swing.UIManager.getFont("Label.font");
        DesktopThemeBindings.font(area, font.deriveFont(Font.PLAIN, 12f));
        return area;
    }

    /** Wraps owned nonsecret labels while retaining their plain accessible name and explicit locale binding. / 对所属非秘密标签换行，同时保留纯文本无障碍名称及明确语言绑定。 */
    private static final class WrappingLabel extends JLabel {
        /** Logical column width before the current Swing scale. / 当前Swing缩放前的逻辑列宽。 */
        private final int wrapUnits;

        /** Original localized plain text, never input contents. / 原本地化纯文本，绝不为输入内容。 */
        private String plainText;

        /** Creates the actual label at a bounded scaled width. / 在有界缩放宽度创建实际标签。
         * @param text original plain caption / 原纯文本说明
         * @param wrapUnits logical wrapping width / 逻辑换行宽度 */
        private WrappingLabel(String text, int wrapUnits) {
            this.wrapUnits = wrapUnits;
            setText(text);
        }

        /** Rebuilds only display markup for an explicit localized caption. / 仅为明确本地化说明重建显示标记。
         * @param text original plain caption / 原纯文本说明 */
        @Override
        public void setText(String text) {
            plainText = text;
            if (wrapUnits == 0 || text == null) {
                super.setText(text);
                return;
            }
            String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            int width = com.formdev.flatlaf.util.UIScale.scale(wrapUnits);
            if (getWidth() > 0)
                width = Math.min(width, getWidth());
            // Swing HTML scales CSS px; unitless widths match component bounds. / Swing HTML 会缩放 CSS px，无单位宽度才与控件边界一致。
            super.setText("<html><body style='width: " + width + "'>" + escaped + "</body></html>");
            getAccessibleContext().setAccessibleName(text);
        }

        /** Reapplies the original plain caption after a theme or scale change. / 主题或缩放变化后重新应用原纯文本说明。 */
        @Override
        public void updateUI() {
            super.updateUI();
            if (wrapUnits > 0)
                setText(plainText);
        }

        /** Reflows the existing caption when the available width changes. / 可用宽度变化时重新排布原说明。
         * @param x horizontal position / 横坐标
         * @param y vertical position / 纵坐标
         * @param width available width / 可用宽度
         * @param height allocated height / 分配高度 */
        @Override
        public void setBounds(int x, int y, int width, int height) {
            boolean changed = getWidth() != width;
            super.setBounds(x, y, width, height);
            if (changed && wrapUnits > 0)
                setText(plainText);
        }
    }

    /** Width-following settings and dialog content with vertical scrolling. / 随宽度变化并纵向滚动的设置与弹窗内容。 */
    private static final class ScrollablePage extends JPanel implements javax.swing.Scrollable {
        /** Creates the shared page layout. / 创建共用页面布局。 */
        private ScrollablePage() {
            super(new BorderLayout(0, 16));
        }

        /** Returns natural viewport dimensions. / 返回自然视口尺寸。
         * @return preferred size / 首选尺寸 */
        @Override
        public java.awt.Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        /** Fits forms within their viewport. / 将表单保持在视口内。
         * @return true / 真 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /** Fills short pages and scrolls taller ones. / 填充短页面并滚动较高页面。
         * @return whether content fits / 内容是否可容纳 */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getPreferredSize().height <= getParent().getHeight();
        }

        /** Returns a scaled scrolling increment. / 返回按缩放计算的滚动步长。
         * @param visible viewport / 视口
         * @param axis orientation / 方向
         * @param direction scroll direction / 滚动方向
         * @return step / 步长 */
        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int axis, int direction) {
            return com.formdev.flatlaf.util.UIScale.scale(20);
        }

        /** Returns one viewport step. / 返回一视口步长。
         * @param visible viewport / 视口
         * @param axis orientation / 方向
         * @param direction scroll direction / 滚动方向
         * @return viewport height / 视口高度 */
        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int axis, int direction) {
            return visible.height;
        }
    }
}
