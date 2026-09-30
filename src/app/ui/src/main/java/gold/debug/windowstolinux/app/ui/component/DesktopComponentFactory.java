package gold.debug.windowstolinux.app.ui.component;

import java.awt.BorderLayout;
import java.awt.Color;
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
import javax.swing.JTextArea;

import com.formdev.flatlaf.FlatClientProperties;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;

/**
 * Creates Swing controls using the selected desktop theme and shared visual conventions.
 * <p>按所选桌面主题及共享视觉约定创建 Swing 控件。
 */
public final class DesktopComponentFactory {
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
    public DesktopComponentFactory(ThemePalette palette) {
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

    /** Builds a heading whose visible labels retain declared locale keys. / 创建可见标签保留声明语言键的标题。
     * @param messages current message presenter / 当前消息展示器
     * @param title title key / 标题键
     * @param description explanation key / 说明键
     * @return original heading component / 原标题组件 */
    public JComponent sectionHeading(gold.debug.windowstolinux.app.ui.diagnostic.PageMessagePresenter messages,
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
            gold.debug.windowstolinux.app.ui.diagnostic.PageMessagePresenter messages, String key, Component component) {
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
        JPanel panel = new JPanel(new BorderLayout(0, 16));
        panel.setBackground(palette.pageBackground());
        return DesktopThemeBindings.role(panel, "page");
    }

    /**
     * Creates a nonopaque panel using the supplied layout manager.
     * <p>使用所提供布局管理器创建非不透明面板。
     *
     * @param layout layout / 布局
     * @return the operation result / 操作结果
     */
    public JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
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
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11f));
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
        JPanel card = new RoundedCard(layout, palette.cardBackground(), palette.cardBorder());
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        return DesktopThemeBindings.role(card, "card");
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
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setForeground(palette.sidebarForeground());
        DesktopThemeBindings.role(label, "title");
        JLabel detail = new WrappingLabel(description, 420);
        detail.setForeground(palette.subduedText());
        DesktopThemeBindings.role(detail, "detail");
        detail.setFont(detail.getFont().deriveFont(12f));
        heading.add(label, BorderLayout.NORTH);
        heading.add(detail, BorderLayout.SOUTH);
        return heading;
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
        number.setFont(number.getFont().deriveFont(Font.BOLD, 12f));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        DesktopThemeBindings.role(heading, "title");
        JLabel detail = new JLabel("<html><body style='width: 170px'>" + description + "</body></html>");
        detail.setForeground(palette.subduedText());
        DesktopThemeBindings.role(detail, "detail");
        detail.setFont(detail.getFont().deriveFont(12f));
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
        button.setBackground(palette.accent());
        button.setForeground(Color.WHITE);
        decorate(button);
        return DesktopThemeBindings.role(button, "primary");
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
        button.setBackground(palette.secondaryButtonBackground());
        button.setForeground(palette.secondaryButtonForeground());
        decorate(button);
        return DesktopThemeBindings.role(button, "secondary");
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
        scrollPane.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, palette.cardBorder()));
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
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        JTextArea detail = new JTextArea(message.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", ""));
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setOpaque(false);
        detail.setRows(5);
        detail.setColumns(16);
        detail.setForeground(palette.subduedText());
        DesktopThemeBindings.role(detail, "detail");
        detail.setFont(detail.getFont().deriveFont(13f));
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
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }

    /**
     * Applies shared borderless surfaces while retaining focus and hover feedback.
     * <p>应用共享的无描边表面，同时保留焦点与悬停反馈。
     *
     * @param button button / 按钮
     */
    private void decorate(JButton button) {
        button.setFocusPainted(true);
        button.setOpaque(false);
        button.putClientProperty(FlatClientProperties.STYLE,
                java.util.Map.of("arc", 12, "borderWidth", 0, "focusWidth", 1));
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
            super.setText("<html><body style='width: " + com.formdev.flatlaf.util.UIScale.scale(wrapUnits)
                    + "px'>" + escaped + "</body></html>");
            getAccessibleContext().setAccessibleName(text);
        }

        /** Reapplies the original plain caption after a theme or scale change. / 主题或缩放变化后重新应用原纯文本说明。 */
        @Override
        public void updateUI() {
            super.updateUI();
            if (wrapUnits > 0) setText(plainText);
        }
    }
}
