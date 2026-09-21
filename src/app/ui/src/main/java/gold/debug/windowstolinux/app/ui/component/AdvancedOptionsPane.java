package gold.debug.windowstolinux.app.ui.component;

import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * A page-local, initially closed inspector preserving the original input controls. / 页面专属且初始折叠的检查面板，保留原有输入控件。
 */
public final class AdvancedOptionsPane extends JPanel {
    /**
     * Allowed or requested input field definitions.
     * <p>允许或请求的输入字段定义。
     */
    private final InspectorFieldsPane fields = new InspectorFieldsPane();
    /**
     * Swing control for drawer.
     * <p>抽屉面板对应的 Swing 控件。
     */
    private final JScrollPane drawer;
    /**
     * Swing control for toggle.
     * <p>切换对应的 Swing 控件。
     */
    private final JButton toggle;
    /**
     * Swing control for toolbar.
     * <p>工具栏对应的 Swing 控件。
     */
    private final JPanel toolbar;
    /**
     * Expanded.
     * <p>已展开。
     */
    private boolean expanded;
    /**
     * Window controller.
     * <p>窗口控制器。
     */
    private AdvancedWindowHost windowController;
    /**
     * Swing control for modified.
     * <p>已修改对应的 Swing 控件。
     */
    private final JLabel modified = new JLabel();
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;
    /**
     * Changes.
     * <p>变更集合。
     */
    private final List<BooleanSupplier> changes = new ArrayList<>();
    /**
     * Disabled inputs.
     * <p>已禁用输入集合。
     */
    private final java.util.Map<Component, Boolean> disabledInputs = new java.util.IdentityHashMap<>();

    /**
     * Wraps a primary page without hiding its operation log behind an overlay. / 包装主页面，不使用覆盖层遮挡其操作日志。
     *
     * @param primary primary / 主
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param messages localized message resolver / 本地化消息解析器
     */
    public AdvancedOptionsPane(JComponent primary, DesktopComponentFactory components, PageMessagePresenter messages) {
        super(new BorderLayout(0, 8));
        this.messages = messages;
        setOpaque(false);
        fields.setOpaque(false);
        fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
        fields.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        drawer = new JScrollPane(fields);
        drawer.setPreferredSize(new Dimension(AdvancedWindowHost.WIDTH, 0));
        drawer.setMinimumSize(new Dimension(240, 0));
        drawer.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        drawer.getVerticalScrollBar().setUnitIncrement(18);
        drawer.setVisible(false);
        toggle = components.secondaryButton(messages.text("advanced.show"));
        toggle.addActionListener(event -> setExpanded(!expanded()));
        toolbar = components.transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        toolbar.add(modified);
        toolbar.add(toggle);
        toolbar.add(help(messages.text("advanced.help")));
        add(toolbar, BorderLayout.NORTH);
        add(primary, BorderLayout.CENTER);
    }

    /**
     * Adds one labeled input with an accessible, explanatory question-mark button. / 添加一个带标签的输入及支持无障碍访问的说明问号按钮。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param input source content consumed by this operation / 当前操作消费的源内容
     */
    public void field(String key, JComponent input) {
        JTextArea label = new JTextArea(messages.text(key));
        label.setEditable(false);
        label.setFocusable(false);
        label.setOpaque(false);
        label.setBorder(null);
        label.setMargin(new Insets(0, 0, 0, 0));
        label.setFont(UIManager.getFont("Label.font"));
        label.setForeground(UIManager.getColor("Label.foreground"));
        label.setLineWrap(true);
        label.setWrapStyleWord(true);
        input.getAccessibleContext().setAccessibleName(messages.text(key));
        boolean switchField = input instanceof ToggleSwitch;
        if (switchField) ((ToggleSwitch) input).setText(null);
        JButton help = help(messages.text("help." + key));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        controls.setOpaque(false);
        if (switchField) { controls.add(input); controls.add(Box.createHorizontalStrut(6)); }
        controls.add(help);
        JPanel row = new JPanel(new BorderLayout(6, 5)) {
            /**
             * Returns preferred size.
             * <p>返回首选大小。
             *
             * @return preferred size / 首选大小
             */
            @Override public Dimension getPreferredSize() {
                int width = fields.contentWidth() - controls.getPreferredSize().width - 6;
                label.setSize(Math.max(1, width), Short.MAX_VALUE);
                return super.getPreferredSize();
            }
            /**
             * Returns maximum size.
             * <p>返回最大大小。
             *
             * @return maximum size / 最大大小
             */
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
            /**
             * Returns minimum size.
             * <p>返回最小大小。
             *
             * @return minimum size / 最小大小
             */
            @Override public Dimension getMinimumSize() {
                return new Dimension(0, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel heading = new JPanel(new BorderLayout(6, 0));
        heading.setOpaque(false);
        JPanel helpColumn = new JPanel(switchField ? new GridBagLayout() : new BorderLayout());
        helpColumn.setOpaque(false);
        if (switchField) helpColumn.add(controls);
        else helpColumn.add(controls, BorderLayout.NORTH);
        if (switchField) {
            JPanel textColumn = new JPanel(new GridBagLayout());
            textColumn.setOpaque(false);
            GridBagConstraints placement = new GridBagConstraints();
            placement.weightx = 1; placement.fill = GridBagConstraints.HORIZONTAL;
            textColumn.add(label, placement);
            heading.add(textColumn, BorderLayout.CENTER);
        } else heading.add(label, BorderLayout.CENTER);
        heading.add(helpColumn, BorderLayout.EAST);
        row.add(heading, BorderLayout.NORTH);
        if (!switchField) row.add(input, BorderLayout.CENTER);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        fields.add(row);
        observe(input);
    }

    /**
     * Adds a secondary operation or informational card. / 添加次要操作或信息卡片。
     *
     * @param option option / 选项
     */
    public void addOption(JComponent option) {
        option.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.add(option);
        fields.add(Box.createVerticalStrut(10));
    }

    /**
     * Returns whether this page's inspector is expanded. / 返回当前页面检查面板是否展开。
     *
     * @return true when returns whether this page's inspector is expanded, false otherwise / 返回当前页面检查面板是否展开时为 true，否则为 false
     */
    public boolean expanded() { return expanded; }

    /**
     * Binds the inspector to its window host. / 将检查面板绑定到窗口宿主。
     *
     * @param controller controller / 控制器
     */
    public void bind(AdvancedWindowHost controller) { windowController = controller; }
    /**
     * Moves the existing controls into the desktop header; standalone dialogs keep their local toolbar. / 将原控件移入桌面顶部栏，独立弹窗仍保留本地工具栏。
     *
     * @return constructed or resolved J component / 构造或解析得到的J组件
     */
    public JComponent headerControls() { remove(toolbar); return toolbar; }
    /**
     * Returns drawer.
     * <p>返回抽屉面板。
     *
     * @return drawer / 抽屉面板
     */
    JComponent drawer() { return drawer; }
    /**
     * Returns inspector title.
     * <p>返回检查器标题。
     *
     * @return inspector title / 检查器标题
     */
    String inspectorTitle() { return messages.text("advanced.show"); }

    /**
     * Freezes operation inputs while retaining each control's prior enabled state and readable output. / 冻结操作输入，同时保留各控件原有启用状态和可读输出。
     *
     * @param busy whether a page action is in progress and conflicting controls must remain disabled / 页面动作是否正在进行且冲突控件须保持禁用
     */
    public void setBusy(boolean busy) {
        if (busy) {
            if (disabledInputs.isEmpty()) { disableInputs(this); disableInputs(drawer); }
        } else {
            disabledInputs.forEach(Component::setEnabled);
            disabledInputs.clear();
        }
    }

    /**
     * Disables reviewed non-secret deployment input fields.
     * <p>禁用已审阅的非秘密部署输入字段。
     *
     * @param parent parent / 父级
     */
    private void disableInputs(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child == toggle || child instanceof JButton button
                    && "help".equals(button.getClientProperty("JButton.buttonType"))) continue;
            if (child instanceof AbstractButton || child instanceof JComboBox<?>
                    || child instanceof JTextComponent text && text.isEditable()) {
                disabledInputs.put(child, child.isEnabled());
                child.setEnabled(false);
            } else if (child instanceof Container container) disableInputs(container);
        }
    }

    /**
     * Restores visibility without changing any input values. / 恢复可见性，不改变输入值。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    public void setExpanded(boolean value) {
        expanded = value;
        toggle.setText(messages.text(value ? "advanced.hide" : "advanced.show"));
        refreshChanges();
        if (windowController == null) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            if (owner != null) {
                windowController = new AdvancedWindowHost(owner, this);
                windowController.activate(this);
            }
        } else {
            windowController.update(this);
        }
    }

    /**
     * Creates a native FlatLaf circular help control with hover and keyboard access. / 创建支持悬停和键盘访问的 FlatLaf 原生圆形帮助控件。
     *
     * @param description description / 说明
     * @return a native FlatLaf circular help control with hover and keyboard access / 支持悬停和键盘访问的 FlatLaf 原生圆形帮助控件
     */
    public static JButton help(String description) {
        JButton button = new JButton();
        button.putClientProperty("JButton.buttonType", "help");
        String escaped = description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        button.setToolTipText("<html><body style='width:280px'>" + escaped + "</body></html>");
        button.getAccessibleContext().setAccessibleName(description);
        button.getAccessibleContext().setAccessibleDescription(description);
        button.addActionListener(event -> JOptionPane.showMessageDialog(button, description));
        button.addFocusListener(new FocusAdapter() {
            /**
             * Updates the associated control when it receives keyboard focus.
             * <p>在关联控件获得键盘焦点时更新控件。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            @Override public void focusGained(FocusEvent event) {
                Action action = button.getActionMap().get("postTip");
                if (action != null) action.actionPerformed(new java.awt.event.ActionEvent(button, 0, "postTip"));
            }
        });
        return button;
    }

    /**
     * Attaches change listeners that keep advanced-field modification and validation indicators current.
     * <p>绑定变更监听器，以及时更新高级字段修改及校验指示。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     */
    private void observe(JComponent input) {
        if (input instanceof JPasswordField password) {
            changes.add(() -> password.getDocument().getLength() > 0);
            password.getDocument().addDocumentListener(new DocumentListener() {
                /**
                 * Inserts update.
                 * <p>插入更新。
                 *
                 * @param event state or UI event being processed / 正在处理的状态或 UI 事件
                 */
                @Override public void insertUpdate(DocumentEvent event) { refreshChanges(); }
                /**
                 * Removes update.
                 * <p>移除更新。
                 *
                 * @param event state or UI event being processed / 正在处理的状态或 UI 事件
                 */
                @Override public void removeUpdate(DocumentEvent event) { refreshChanges(); }
                /**
                 * Responds to a document change by updating the associated form state.
                 * <p>响应文档变更并更新关联表单状态。
                 *
                 * @param event state or UI event being processed / 正在处理的状态或 UI 事件
                 */
                @Override public void changedUpdate(DocumentEvent event) { refreshChanges(); }
            });
            return;
        }
        if (input instanceof JTextComponent text) {
            if (!text.isEditable()) return;
            String initial = text.getText();
            changes.add(() -> !initial.equals(text.getText()));
            text.getDocument().addDocumentListener(new DocumentListener() {
                /**
                 * Inserts update.
                 * <p>插入更新。
                 *
                 * @param event state or UI event being processed / 正在处理的状态或 UI 事件
                 */
                @Override public void insertUpdate(DocumentEvent event) { refreshChanges(); }
                /**
                 * Removes update.
                 * <p>移除更新。
                 *
                 * @param event state or UI event being processed / 正在处理的状态或 UI 事件
                 */
                @Override public void removeUpdate(DocumentEvent event) { refreshChanges(); }
                /**
                 * Responds to a document change by updating the associated form state.
                 * <p>响应文档变更并更新关联表单状态。
                 *
                 * @param event state or UI event being processed / 正在处理的状态或 UI 事件
                 */
                @Override public void changedUpdate(DocumentEvent event) { refreshChanges(); }
            });
        } else if (input instanceof JComboBox<?> combo) {
            Object initial = combo.getSelectedItem();
            changes.add(() -> !Objects.equals(initial, combo.getSelectedItem()));
            combo.addActionListener(event -> refreshChanges());
        } else if (input instanceof AbstractButton button) {
            boolean initial = button.isSelected();
            changes.add(() -> initial != button.isSelected());
            button.addItemListener(event -> refreshChanges());
        } else {
            for (Component child : input.getComponents()) if (child instanceof JComponent nested) observe(nested);
        }
    }

    /**
     * Refreshes changes.
     * <p>刷新变更集合。
     */
    private void refreshChanges() {
        modified.setText(changes.stream().anyMatch(BooleanSupplier::getAsBoolean)
                ? messages.text("advanced.modified") : "");
    }

    /**
     * Renders inspector-provided advanced fields using the existing form state.
     * <p>使用既有表单状态呈现检查器提供的高级字段。
     */
    private static final class InspectorFieldsPane extends JPanel implements Scrollable {
        /**
         * Last measured panel width in pixels, or minus one before measurement.
         * <p>上次测量的面板宽度，单位为像素；测量前为负一。
         */
        private int measuredWidth = -1;

        /**
         * Returns content width.
         * <p>返回内容宽度。
         *
         * @return content width / 内容宽度
         */
        int contentWidth() {
            int width = getParent() instanceof JViewport viewport ? viewport.getExtentSize().width : getWidth();
            if (width <= 0) width = AdvancedWindowHost.WIDTH;
            return Math.max(1, width - getInsets().left - getInsets().right);
        }

        /**
         * Returns preferred size.
         * <p>返回首选大小。
         *
         * @return preferred size / 首选大小
         */
        @Override public Dimension getPreferredSize() {
            int width = contentWidth();
            if (measuredWidth != width) {
                measuredWidth = width;
                ((BoxLayout) getLayout()).invalidateLayout(this);
            }
            return super.getPreferredSize();
        }

        /**
         * Returns preferred scrollable viewport size.
         * <p>返回首选可滚动视口大小。
         *
         * @return preferred scrollable viewport size / 首选可滚动视口大小
         */
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        /**
         * Returns scrollable unit increment.
         * <p>返回可滚动单元增量。
         *
         * @param visible visible / 可见
         * @param orientation orientation / 方向
         * @param direction direction / 方向
         * @return scrollable unit increment / 可滚动单元增量
         */
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        /**
         * Returns scrollable block increment.
         * <p>返回可滚动块增量。
         *
         * @param visible visible / 可见
         * @param orientation orientation / 方向
         * @param direction direction / 方向
         * @return scrollable block increment / 可滚动块增量
         */
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 18); }
        /**
         * Returns true.
         * <p>返回真。
         *
         * @return true when returns true, false otherwise / 返回真时为 true，否则为 false
         */
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        /**
         * Returns false.
         * <p>返回假。
         *
         * @return true when returns false, false otherwise / 返回假时为 true，否则为 false
         */
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
