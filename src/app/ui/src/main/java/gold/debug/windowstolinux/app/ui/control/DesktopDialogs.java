package gold.debug.windowstolinux.app.ui.control;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.util.UIScale;

/** Shared layout for explicitly managed platform dialogs. / 显式管理的平台弹窗共用布局。 */
public final class DesktopDialogs {
    /** Marks only explicitly opted-in platform windows. / 仅标记明确接入的平台窗口。 */
    private static final String MANAGED = "desktop.dialog.managed";

    /** Retains unscaled preferred dimensions across appearance refreshes. / 外观刷新间保留未缩放首选尺寸。 */
    private static final String SIZE = "desktop.dialog.logicalSize";

    /** Identifies the fixed action region without inspecting business listeners. / 标识固定操作区，不检查业务监听器。 */
    private static final String ACTIONS = "desktop.dialog.actions";

    /** Prevents instances of the shared layout contract. / 禁止创建共用布局契约的实例。 */
    private DesktopDialogs() {
    }

    /** Creates the original option pane with bounded content and retained result semantics. / 使用有界内容创建原选项窗，保留返回语义。
     * @param pane original choices and default / 原选项及默认值
     * @param owner actual parent / 实际父组件
     * @param title localized caption / 本地化标题
     * @return owned dialog / 所属弹窗 */
    public static JDialog create(JOptionPane pane, Component owner, String title) {
        Object message = pane.getMessage();
        JComponent content = message(message);
        if (!(content instanceof JScrollPane) && !containsScroll(content)) content = scroll(content);
        pane.setMessage(content);
        pane.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(20), UIScale.scale(20),
            UIScale.scale(20), UIScale.scale(20)));
        var dialog = pane.createDialog(owner, title);
        dialog.setResizable(true);
        manage(dialog, null);
        optionLayout(pane);
        fit(dialog);
        return dialog;
    }

    /** Installs a scrolling body with a fixed action row. / 安装滚动正文与固定操作区。
     * @param dialog owned window / 所属窗口
     * @param content original body / 原正文
     * @param actions original actions, or null / 原操作区，可空
     * @param width preferred logical width / 首选逻辑宽度
     * @param height preferred logical height, zero for natural height / 首选逻辑高度，零表示自然高度 */
    public static void install(JDialog dialog, JComponent content, JComponent actions, int width, int height) {
        var body = new JPanel(new BorderLayout(UIScale.scale(12), UIScale.scale(12)));
        body.setName("desktop.dialog.body");
        surface(body);
        body.add(content instanceof JScrollPane ? content : scroll(content), BorderLayout.CENTER);
        if (actions != null) {
            actions.setOpaque(false);
            actions.putClientProperty(ACTIONS, true);
            actionLayout(actions);
            body.add(actions, BorderLayout.SOUTH);
        }
        dialog.setContentPane(body);
        manage(dialog, new Dimension(width, height));
        refresh(dialog);
    }

    /** Bounds an existing authored dialog without replacing its controls. / 限制已有弹窗尺寸，不替换控件。
     * @param dialog owned window / 所属窗口
     * @param width preferred logical width / 首选逻辑宽度
     * @param height preferred logical height / 首选逻辑高度 */
    public static void size(JDialog dialog, int width, int height) {
        manage(dialog, new Dimension(width, height));
        refresh(dialog);
    }

    /** Re-packs expanded content within the original owner's screen. / 重新测量展开内容，并限制在原所有者屏幕内。
     * @param dialog original window / 原窗口 */
    public static void fit(JDialog dialog) {
        dialog.pack();
        constrain(dialog, workArea(dialog), dialog.getSize());
    }

    /** Uses the window's monitor rather than the primary monitor. / 使用窗口所属显示器而非主显示器。
     * @param window original owned window / 原所属窗口
     * @return usable screen rectangle with a small safety margin / 带小留白的可用屏幕矩形 */
    public static Rectangle workArea(Window window) {
        GraphicsConfiguration configuration = window.getGraphicsConfiguration();
        if (configuration == null && window.getOwner() != null)
            configuration = window.getOwner().getGraphicsConfiguration();
        if (configuration == null)
            configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int margin = 20;
        bounds.x += insets.left + margin;
        bounds.y += insets.top + margin;
        bounds.width = Math.max(1, bounds.width - insets.left - insets.right - margin * 2);
        bounds.height = Math.max(1, bounds.height - insets.top - insets.bottom - margin * 2);
        return bounds;
    }

    /** Applies the same geometry to a smaller supplied work area. / 对较小的给定工作区应用相同几何规则。
     * @param dialog actual dialog / 实际弹窗
     * @param available already inset work area / 已扣留白的工作区
     * @param preferred already scaled size / 已缩放首选尺寸 */
    public static void constrain(JDialog dialog, Rectangle available, Dimension preferred) {
        int width = Math.min(Math.max(1, preferred.width), available.width);
        int height = Math.min(Math.max(1, preferred.height), available.height);
        Dimension minimum = dialog.getMinimumSize();
        if (minimum.width > width || minimum.height > height)
            dialog.setMinimumSize(new Dimension(Math.min(minimum.width, width), Math.min(minimum.height, height)));
        dialog.setSize(width, height);
        if (!dialog.isShowing()) dialog.setLocationRelativeTo(dialog.getOwner());
        int x = Math.max(available.x, Math.min(dialog.getX(), available.x + available.width - width));
        int y = Math.max(available.y, Math.min(dialog.getY(), available.y + available.height - height));
        dialog.setLocation(x, y);
        dialog.validate();
    }

    /** Wraps original controls once and tracks the viewport width. / 只包装一次原控件，并跟随视口宽度。
     * @param content retained controls / 保留的控件
     * @return width-following scroller / 随宽度变化的滚动区 */
    public static JScrollPane scroll(JComponent content) {
        if (content instanceof JScrollPane scroll) return scroll;
        revealOnFocus(content);
        var scroll = new JScrollPane(new Viewport(content), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(20));
        scroll.setMinimumSize(new Dimension());
        surface(scroll.getViewport());
        return scroll;
    }

    /** Reveals naturally sized controls when keyboard focus enters a scrolled body. / 键盘焦点进入滚动正文时显露自然高度控件。
     * @param component retained body or descendant / 保留的正文或后代 */
    private static void revealOnFocus(Component component) {
        if (component instanceof JComponent control && control.isFocusable())
            control.addFocusListener(new java.awt.event.FocusAdapter() {
                /** Scrolls the parent rather than a text field's internal horizontal viewport. / 滚动父容器，避免只移动文本框的内部水平视口。
                 * @param event original focus event / 原焦点事件 */
                @Override
                public void focusGained(java.awt.event.FocusEvent event) {
                    if (control.getParent() instanceof JComponent parent)
                        parent.scrollRectToVisible(control.getBounds());
                }
            });
        if (component instanceof Container container)
            for (Component child : container.getComponents()) revealOnFocus(child);
    }

    /** Arranges retained controls at natural height with a shared field gap. / 按共用字段间距保留控件自然高度。
     * @param rows original controls / 原控件
     * @return vertically flowing form / 纵向流动表单 */
    public static JPanel fields(JComponent... rows) {
        var panel = new JPanel(new java.awt.GridBagLayout());
        for (int index = 0; index < rows.length; index++) {
            var constraints = new java.awt.GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = index;
            constraints.weightx = 1;
            constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
            constraints.anchor = java.awt.GridBagConstraints.NORTHWEST;
            constraints.insets = new Insets(index == 0 ? 0 : UIScale.scale(8), 0, 0, 0);
            panel.add(rows[index], constraints);
        }
        return panel;
    }

    /** Creates copyable wrapping facts measured against the current viewport width. / 按当前视口宽度测量可复制的换行事实。
     * @param value complete original facts / 完整原事实
     * @return naturally sized read-only text / 自然高度的只读文本 */
    public static JTextArea prose(String value) {
        var text = new JTextArea(value) {
            /** Measures wrapped lines before ancestors allocate their new bounds. / 在祖先分配新边界前测量换行内容。
             * @return current-width natural text size / 当前宽度下的自然文本尺寸 */
            @Override
            public Dimension getPreferredSize() {
                int width = proseWidth(this);
                if (width <= 0) return super.getPreferredSize();
                Insets insets = getInsets();
                var view = getUI().getRootView(this);
                view.setSize(Math.max(1, width - insets.left - insets.right), Float.MAX_VALUE);
                int height = (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + insets.top + insets.bottom;
                return new Dimension(width, height);
            }

            /** Permits narrow text while retaining every wrapped line. / 允许文本变窄，同时保留全部换行高度。
             * @return zero minimum width and natural height / 零最小宽度及自然高度 */
            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, getPreferredSize().height);
            }
        };
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        return text;
    }

    /** Subtracts authored side regions from the current viewport rather than stale child bounds. / 从当前视口扣除作者侧区，不依赖旧子组件边界。
     * @param text original nested facts / 原嵌套事实
     * @return available width, or absence before mounting / 可用宽度，挂载前无值 */
    private static int proseWidth(JTextArea text) {
        var viewport = (javax.swing.JViewport) SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, text);
        if (viewport == null || viewport.getExtentSize().width <= 0) return -1;
        int width = viewport.getExtentSize().width;
        for (Container parent = text.getParent(); parent != null && parent != viewport; parent = parent.getParent()) {
            Insets insets = parent.getInsets();
            width -= insets.left + insets.right;
            if (parent.getLayout() instanceof BorderLayout layout)
                for (String side : new String[]{BorderLayout.WEST, BorderLayout.EAST}) {
                    Component sibling = layout.getLayoutComponent(parent, side);
                    if (sibling != null && sibling.isVisible() && !SwingUtilities.isDescendingFrom(text, sibling))
                        width -= sibling.getPreferredSize().width + layout.getHgap();
                }
        }
        return Math.max(1, width);
    }

    /** Shows a localized informational message. / 显示本地化普通消息。
     * @param owner actual parent / 实际父组件
     * @param message retained message content / 保留的消息内容 */
    public static void showMessageDialog(Component owner, Object message) {
        showMessageDialog(owner, message, UIManager.getString("OptionPane.messageDialogTitle"), JOptionPane.INFORMATION_MESSAGE);
    }

    /** Shows a bounded message with the original severity. / 按原严重程度显示有界消息。
     * @param owner actual parent / 实际父组件
     * @param message retained message content / 保留的消息内容
     * @param title localized caption / 本地化标题
     * @param type original severity / 原严重程度 */
    public static void showMessageDialog(Component owner, Object message, String title, int type) {
        show(new JOptionPane(message, type, JOptionPane.DEFAULT_OPTION), owner, title);
    }

    /** Shows the original confirmation choices. / 显示原确认选项。
     * @param owner actual parent / 实际父组件
     * @param message retained review / 保留的审阅内容
     * @param title localized caption / 本地化标题
     * @param choices original choice set / 原选项集合
     * @return original choice or closed option / 原选择或关闭值 */
    public static int showConfirmDialog(Component owner, Object message, String title, int choices) {
        return showConfirmDialog(owner, message, title, choices, JOptionPane.QUESTION_MESSAGE);
    }

    /** Shows the original confirmation choices and severity. / 显示原确认选项及严重程度。
     * @param owner actual parent / 实际父组件
     * @param message retained review / 保留的审阅内容
     * @param title localized caption / 本地化标题
     * @param choices original choice set / 原选项集合
     * @param type original severity / 原严重程度
     * @return original choice or closed option / 原选择或关闭值 */
    public static int showConfirmDialog(Component owner, Object message, String title, int choices, int type) {
        Object result = show(new JOptionPane(message, type, choices), owner, title);
        return result instanceof Integer value ? value : JOptionPane.CLOSED_OPTION;
    }

    /** Keeps exact custom option objects and the initial choice. / 保留精确自定义选项对象及初始选择。
     * @param owner actual parent / 实际父组件
     * @param message retained review / 保留的审阅内容
     * @param title localized caption / 本地化标题
     * @param choices original choice set / 原选项集合
     * @param type original severity / 原严重程度
     * @param icon original optional icon / 原可选图标
     * @param options exact custom choices / 精确自定义选项
     * @param initial original initial choice / 原初始选择
     * @return original choice index or closed option / 原选择索引或关闭值 */
    public static int showOptionDialog(Component owner, Object message, String title, int choices, int type,
                                       javax.swing.Icon icon, Object[] options, Object initial) {
        Object result = show(new JOptionPane(message, type, choices, icon, options, initial), owner, title);
        if (result == null || result == JOptionPane.UNINITIALIZED_VALUE) return JOptionPane.CLOSED_OPTION;
        if (options == null) return result instanceof Integer value ? value : JOptionPane.CLOSED_OPTION;
        for (int index = 0; index < options.length; index++)
            if (java.util.Objects.equals(options[index], result)) return index;
        return JOptionPane.CLOSED_OPTION;
    }

    /** Owns the modal window until it is disposed, including dismissal. / 持有模态窗口直至销毁，包括关闭场景。
     * @param pane original choices / 原选项
     * @param owner actual parent / 实际父组件
     * @param title localized caption / 本地化标题
     * @return original option value / 原选项值 */
    private static Object show(JOptionPane pane, Component owner, String title) {
        var dialog = create(pane, owner, title);
        try {
            pane.selectInitialValue();
            dialog.setVisible(true);
            return pane.getValue();
        } finally {
            dialog.dispose();
        }
    }

    /** Subscribes once to geometry changes without a global window hook. / 只订阅一次几何变化，不安装全局窗口钩子。
     * @param dialog opted-in window / 接入的窗口
     * @param logicalSize unscaled preferred size or absence / 未缩放首选尺寸或空值 */
    private static void manage(JDialog dialog, Dimension logicalSize) {
        dialog.getRootPane().putClientProperty(SIZE, logicalSize);
        if (Boolean.TRUE.equals(dialog.getRootPane().getClientProperty(MANAGED))) return;
        dialog.getRootPane().putClientProperty(MANAGED, true);
        dialog.getRootPane().addPropertyChangeListener("UI", event -> SwingUtilities.invokeLater(() -> {
            if (dialog.isDisplayable()) refresh(dialog);
        }));
        dialog.addPropertyChangeListener("graphicsConfiguration", event -> {
            if (dialog.isDisplayable()) constrain(dialog, workArea(dialog), dialog.getSize());
        });
    }

    /** Recomputes geometry from logical baselines while retaining authored controls. / 从逻辑基准重算几何并保留作者控件。
     * @param dialog opted-in window / 接入的窗口 */
    private static void refresh(JDialog dialog) {
        if ("desktop.dialog.body".equals(dialog.getContentPane().getName())) {
            var body = (JComponent) dialog.getContentPane();
            surface(body);
            body.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(20), UIScale.scale(20),
                UIScale.scale(20), UIScale.scale(20)));
            ((BorderLayout) body.getLayout()).setVgap(UIScale.scale(12));
            for (Component child : body.getComponents())
                if (child instanceof JComponent component && Boolean.TRUE.equals(component.getClientProperty(ACTIONS)))
                    actionLayout(component);
        }
        optionActions(dialog);
        dialog.pack();
        Dimension preferred = dialog.getSize();
        if (dialog.getRootPane().getClientProperty(SIZE) instanceof Dimension logical)
            preferred = new Dimension(logical.width <= 0 ? preferred.width : UIScale.scale(logical.width),
                logical.height <= 0 ? preferred.height : UIScale.scale(logical.height));
        constrain(dialog, workArea(dialog), preferred);
    }

    /** Restyles Swing-created decisions after a delegate refresh. / 绘制器刷新后重设 Swing 决定区样式。
     * @param container retained option tree / 保留的选项树 */
    private static void optionActions(Container container) {
        if (container instanceof JOptionPane pane) {
            pane.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(20), UIScale.scale(20),
                UIScale.scale(20), UIScale.scale(20)));
            optionLayout(pane);
        }
        if ("OptionPane.buttonArea".equals(container.getName())) actionLayout(container);
        for (Component child : container.getComponents())
            if (child instanceof Container nested) optionActions(nested);
    }

    /** Keeps Swing's original decision buttons outside its message viewport. / 将 Swing 原决定按钮保留在正文视口外。
     * @param pane original option pane / 原选项面板 */
    private static void optionLayout(JOptionPane pane) {
        Component footer = null;
        for (Component child : pane.getComponents())
            if ("OptionPane.buttonArea".equals(child.getName())) footer = child;
        if (footer != null && !(pane.getLayout() instanceof BorderLayout)) {
            var content = new JPanel(new BorderLayout());
            for (Component child : pane.getComponents())
                if (child != footer) content.add(child, BorderLayout.CENTER);
            pane.setLayout(new BorderLayout(0, UIScale.scale(12)));
            pane.add(content, BorderLayout.CENTER);
            pane.add(footer, BorderLayout.SOUTH);
        }
        surface(pane);
    }

    /** Applies the dialog content surface without recoloring input or button states. / 应用弹窗内容底色，不重设输入或按钮状态色。
     * @param component platform-owned content / 平台所属内容 */
    private static void surface(Component component) {
        if (component instanceof JPanel || component instanceof javax.swing.JViewport || component instanceof JOptionPane)
            component.setBackground(UIManager.getColor("Table.background"));
        if (component instanceof Container container)
            for (Component child : container.getComponents()) surface(child);
    }

    /** Allows translated decision labels to wrap as complete buttons. / 允许翻译后的决定按钮整体换行。
     * @param actions retained action region / 保留的操作区 */
    private static void actionLayout(Container actions) {
        if (actions.getLayout() instanceof FlowLayout || "OptionPane.buttonArea".equals(actions.getName()))
            actions.setLayout(new WrappingFlowLayout(new FlowLayout(FlowLayout.TRAILING, UIScale.scale(8), UIScale.scale(4))));
    }

    /** Recognizes an authored viewport before adding a scroll boundary. / 添加滚动边界前识别作者已有视口。
     * @param component original body / 原正文
     * @return whether the body already owns scrolling / 正文是否已有滚动职责 */
    private static boolean containsScroll(Component component) {
        if (component instanceof JScrollPane) return true;
        if (component instanceof Container container)
            for (Component child : container.getComponents())
                if (containsScroll(child)) return true;
        return false;
    }

    /** Converts only message presentation, preserving supplied component identities. / 仅转换消息展示，保留传入组件身份。
     * @param message original Swing message / 原 Swing 消息
     * @return literal text or original controls / 字面文本或原控件 */
    private static JComponent message(Object message) {
        if (message instanceof JComponent component) return component;
        if (message instanceof Object[] parts) {
            return fields(java.util.Arrays.stream(parts).map(DesktopDialogs::message).toArray(JComponent[]::new));
        }
        var text = new JTextArea(message == null ? "" : message.toString(), 0, 48);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(UIManager.getFont("Label.font"));
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder());
        return text;
    }

    /** Width-following body; original input documents remain attached. / 随宽度变化的正文，保留原输入文档。 */
    private static final class Viewport extends JPanel implements Scrollable {
        /** Retains the form at its natural height. / 将表单保留在自然高度。
         * @param content original form / 原表单 */
        private Viewport(JComponent content) {
            super(new BorderLayout());
            add(content, BorderLayout.NORTH);
        }

        /** Bounds initial packing without limiting the actual scroll extent. / 限制初始测量，不限制真实滚动范围。
         * @return preferred viewport dimensions / 首选视口尺寸 */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            Dimension size = getPreferredSize();
            return new Dimension(Math.min(size.width, UIScale.scale(760)), Math.min(size.height, UIScale.scale(480)));
        }

        /** Reflows the original form to the available width. / 按可用宽度重排原表单。
         * @return true / 真 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /** Keeps tall forms scrollable rather than compressing their rows. / 保持长表单可滚动，不压缩字段行。
         * @return false / 假 */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        /** Uses a logical line step. / 使用逻辑行步长。
         * @param visible current viewport / 当前视口
         * @param orientation requested axis / 请求方向
         * @param direction requested direction / 请求方向符号
         * @return scaled line step / 缩放后的行步长 */
        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UIScale.scale(20);
        }

        /** Leaves one line of context during page scrolling. / 翻页时保留一行上下文。
         * @param visible current viewport / 当前视口
         * @param orientation requested axis / 请求方向
         * @param direction requested direction / 请求方向符号
         * @return viewport step / 视口步长 */
        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(UIScale.scale(20), visible.height - UIScale.scale(20));
        }
    }
}
