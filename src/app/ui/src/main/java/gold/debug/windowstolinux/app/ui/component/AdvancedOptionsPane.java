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

/** A page-local, initially closed inspector preserving the original input controls. / 页面专属且初始折叠的检查面板，保留原有输入控件。 */
public final class AdvancedOptionsPane extends JPanel {
    private final InspectorFieldsPane fields = new InspectorFieldsPane();
    private final JScrollPane drawer;
    private final JButton toggle;
    private final JPanel toolbar;
    private boolean expanded;
    private AdvancedWindowHost windowController;
    private final JLabel modified = new JLabel();
    private final PageMessagePresenter messages;
    private final List<BooleanSupplier> changes = new ArrayList<>();
    private final java.util.Map<Component, Boolean> disabledInputs = new java.util.IdentityHashMap<>();

    /** Wraps a primary page without hiding its operation log behind an overlay. / 包装主页面，不使用覆盖层遮挡其操作日志。 */
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

    /** Adds one labeled input with an accessible, explanatory question-mark button. / 添加一个带标签的输入及支持无障碍访问的说明问号按钮。 */
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
            @Override public Dimension getPreferredSize() {
                int width = fields.contentWidth() - controls.getPreferredSize().width - 6;
                label.setSize(Math.max(1, width), Short.MAX_VALUE);
                return super.getPreferredSize();
            }
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
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

    /** Adds a secondary operation or informational card. / 添加次要操作或信息卡片。 */
    public void addOption(JComponent option) {
        option.setAlignmentX(Component.LEFT_ALIGNMENT);
        fields.add(option);
        fields.add(Box.createVerticalStrut(10));
    }

    /** Returns whether this page's inspector is expanded. / 返回当前页面检查面板是否展开。 */
    public boolean expanded() { return expanded; }

    /** Binds the inspector to its window host. / 将检查面板绑定到窗口宿主。 */
    public void bind(AdvancedWindowHost controller) { windowController = controller; }
    /** Moves the existing controls into the desktop header; standalone dialogs keep their local toolbar. / 将原控件移入桌面顶部栏，独立弹窗仍保留本地工具栏。 */
    public JComponent headerControls() { remove(toolbar); return toolbar; }
    JComponent drawer() { return drawer; }
    String inspectorTitle() { return messages.text("advanced.show"); }

    /** Freezes operation inputs while retaining each control's prior enabled state and readable output. / 冻结操作输入，同时保留各控件原有启用状态和可读输出。 */
    public void setBusy(boolean busy) {
        if (busy) {
            if (disabledInputs.isEmpty()) { disableInputs(this); disableInputs(drawer); }
        } else {
            disabledInputs.forEach(Component::setEnabled);
            disabledInputs.clear();
        }
    }

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

    /** Restores visibility without changing any input values. / 恢复可见性，不改变输入值。 */
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

    /** Creates a native FlatLaf circular help control with hover and keyboard access. / 创建支持悬停和键盘访问的 FlatLaf 原生圆形帮助控件。 */
    public static JButton help(String description) {
        JButton button = new JButton();
        button.putClientProperty("JButton.buttonType", "help");
        String escaped = description.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        button.setToolTipText("<html><body style='width:280px'>" + escaped + "</body></html>");
        button.getAccessibleContext().setAccessibleName(description);
        button.getAccessibleContext().setAccessibleDescription(description);
        button.addActionListener(event -> JOptionPane.showMessageDialog(button, description));
        button.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) {
                Action action = button.getActionMap().get("postTip");
                if (action != null) action.actionPerformed(new java.awt.event.ActionEvent(button, 0, "postTip"));
            }
        });
        return button;
    }

    private void observe(JComponent input) {
        if (input instanceof JPasswordField password) {
            changes.add(() -> password.getDocument().getLength() > 0);
            password.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { refreshChanges(); }
                @Override public void removeUpdate(DocumentEvent event) { refreshChanges(); }
                @Override public void changedUpdate(DocumentEvent event) { refreshChanges(); }
            });
            return;
        }
        if (input instanceof JTextComponent text) {
            if (!text.isEditable()) return;
            String initial = text.getText();
            changes.add(() -> !initial.equals(text.getText()));
            text.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { refreshChanges(); }
                @Override public void removeUpdate(DocumentEvent event) { refreshChanges(); }
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

    private void refreshChanges() {
        modified.setText(changes.stream().anyMatch(BooleanSupplier::getAsBoolean)
                ? messages.text("advanced.modified") : "");
    }

    private static final class InspectorFieldsPane extends JPanel implements Scrollable {
        private int measuredWidth = -1;

        int contentWidth() {
            int width = getParent() instanceof JViewport viewport ? viewport.getExtentSize().width : getWidth();
            if (width <= 0) width = AdvancedWindowHost.WIDTH;
            return Math.max(1, width - getInsets().left - getInsets().right);
        }

        @Override public Dimension getPreferredSize() {
            int width = contentWidth();
            if (measuredWidth != width) {
                measuredWidth = width;
                ((BoxLayout) getLayout()).invalidateLayout(this);
            }
            return super.getPreferredSize();
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 18); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
