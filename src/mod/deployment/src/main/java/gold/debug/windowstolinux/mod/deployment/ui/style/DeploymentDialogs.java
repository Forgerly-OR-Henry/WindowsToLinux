package gold.debug.windowstolinux.mod.deployment.ui.style;

import java.awt.*;
import java.util.ResourceBundle;
import javax.swing.*;

/** Mod-owned window layout; the host still owns authorization and tasks. / Mod 所属窗口布局，授权和任务仍由宿主持有。 */
public final class DeploymentDialogs {
    /** Prevents instances of the Mod-local layout helper. / 禁止实例化 Mod 本地布局工具。 */
    private DeploymentDialogs() {
    }

    /** Creates an option dialog with a scrolling body and the original Swing decision buttons. / 创建内容滚动且保留 Swing 决定按钮的对话框。
     * @param owner actual owner / 实际所属者
     * @param title localized title / 本地化标题
     * @param body original controls / 原控件
     * @param options original decisions / 原决定
     * @param width logical preferred width / 逻辑首选宽
     * @param height logical preferred height / 逻辑首选高
     * @return owned undecided dialog / 所属未答对话框 */
    public static JDialog option(Component owner, String title, JComponent body, JOptionPane options, int width, int height) {
        options.setMessage(null);
        options.setMessageType(JOptionPane.PLAIN_MESSAGE);
        var dialog = options.createDialog(owner, title);
        mount(dialog, body, options, width, height);
        return dialog;
    }

    /** Creates an owned modeless window using the same spacing and work-area bound. / 使用相同留白和工作区边界创建所属非模态窗口。
     * @param owner original owner / 原所属者
     * @param title localized title / 本地化标题
     * @param body original content / 原内容
     * @param actions fixed actions or absence / 固定操作或空值
     * @param width logical preferred width / 逻辑首选宽
     * @param height logical preferred height / 逻辑首选高
     * @return mounted owned window / 已挂载所属窗口 */
    public static JDialog window(Component owner, String title, JComponent body, JComponent actions, int width, int height) {
        var dialog = new JDialog(owner instanceof Window window ? window : SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.MODELESS);
        mount(dialog, body, actions, width, height);
        return dialog;
    }

    /** Mounts retained content and decisions exactly once. / 恰好挂载一次保留内容及决定控件。
     * @param dialog owned window / 所属窗口
     * @param body original content / 原内容
     * @param actions original fixed actions / 原固定操作
     * @param width logical preferred width / 逻辑首选宽
     * @param height logical preferred height / 逻辑首选高 */
    private static void mount(JDialog dialog, JComponent body, JComponent actions, int width, int height) {
        var shell = new JPanel(new BorderLayout());
        shell.setName("deployment.dialog.shell");
        JComponent view = Boolean.TRUE.equals(body.getClientProperty("deployment.dialog.scrollManaged"))
            || body instanceof JScrollPane ? body : scroll(body);
        shell.add(view, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout());
        footer.setName("deployment.dialog.footer");
        if (body.getClientProperty("deployment.dialog.fixedActions") instanceof JComponent extra) {
            extra.getParent().remove(extra);
            footer.add(extra, BorderLayout.NORTH);
        }
        if (actions instanceof JOptionPane options) {
            options.setBorder(BorderFactory.createEmptyBorder());
            options.setOpaque(false);
            var decisions = new JPanel(new BorderLayout()) {
                /** Removes only the generic message-box minimum from the decision footer. / 仅移除决定底栏的通用消息框最小尺寸。
                 * @return natural decision size / 自然决定控件尺寸 */
                @Override
                public Dimension getPreferredSize() {
                    return options.getLayout().preferredLayoutSize(options);
                }
            };
            decisions.setOpaque(false);
            decisions.add(options);
            footer.add(decisions, BorderLayout.SOUTH);
        } else if (actions != null) footer.add(actions, BorderLayout.SOUTH);
        if (footer.getComponentCount() > 0) shell.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(shell);
        dialog.getRootPane().putClientProperty("deployment.dialog.preferred", new Dimension(width, height));
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(true);
        bindReveal(body);
        refreshFacts(shell);
        spacing(shell);
        fit(dialog, true);
        dialog.addPropertyChangeListener("graphicsConfiguration", event -> constrain(dialog, workArea(dialog)));
        dialog.addComponentListener(new java.awt.event.ComponentAdapter() {
            /** Keeps a resized decorated window inside its current monitor. / 将缩放后的完整窗口保持在当前屏幕内。
             * @param event actual resize / 实际尺寸变化 */
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                Rectangle area = workArea(dialog);
                if (!area.contains(dialog.getBounds())) constrain(dialog, area);
            }
        });
    }

    /** Refreshes the existing dialog without replacing its input documents or selection. / 刷新现有对话框，不替换输入文档或选择。
     * @param dialog current owned dialog / 当前所属对话框
     * @param messages current catalog / 当前文案 */
    public static void refresh(JDialog dialog, ResourceBundle messages) {
        SwingUtilities.updateComponentTreeUI(dialog);
        DeploymentPageStyle.refresh((JComponent) dialog.getContentPane(), messages);
        refreshFacts((Container) dialog.getContentPane());
        spacing((JComponent) dialog.getContentPane());
        fit(dialog, false);
    }

    /** Refreshes read-only facts and native decision layout, preserving editable documents. / 刷新只读事实及原生决定布局，保留可编辑文档。
     * @param container current owned subtree / 当前所属子树 */
    private static void refreshFacts(Container container) {
        for (var child : container.getComponents()) {
            if (child instanceof JTextArea text && text.getClientProperty("deployment.dialog.fact") instanceof java.util.function.Supplier<?> value) {
                String current = (String) value.get();
                if (!text.getText().equals(current)) text.setText(current);
            }
            if (child instanceof JOptionPane options) {
                options.setBorder(BorderFactory.createEmptyBorder());
                options.setOpaque(false);
                for (var part : options.getComponents()) {
                    if ("OptionPane.messageArea".equals(part.getName())) part.setVisible(false);
                    if (part instanceof JComponent component && "OptionPane.buttonArea".equals(part.getName()))
                        component.setBorder(BorderFactory.createEmptyBorder());
                }
            }
            if (child instanceof Container nested) {
                if (nested.getLayout() instanceof javax.swing.plaf.basic.BasicOptionPaneUI.ButtonAreaLayout buttons)
                    buttons.setPadding(DeploymentPageStyle.scale(8));
                refreshFacts(nested);
            }
        }
    }

    /** Reapplies logical spacing without accumulating borders. / 重设逻辑留白而不累计边距。
     * @param shell original dialog shell / 原对话框外壳 */
    private static void spacing(JComponent shell) {
        int padding = DeploymentPageStyle.scale(20), gap = DeploymentPageStyle.scale(12);
        shell.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
        shell.setOpaque(true);
        shell.setBackground(UIManager.getColor("Table.background"));
        if (shell.getLayout() instanceof BorderLayout layout) layout.setVgap(gap);
        for (var child : shell.getComponents()) {
            if (child instanceof JPanel footer && "deployment.dialog.footer".equals(footer.getName())) {
                footer.setOpaque(false);
                ((BorderLayout) footer.getLayout()).setVgap(gap);
                for (var action : footer.getComponents()) {
                    if (action instanceof JOptionPane pane) pane.setBorder(BorderFactory.createEmptyBorder());
                    if (action instanceof JPanel panel && panel.getLayout() instanceof FlowLayout flow) {
                        flow.setHgap(DeploymentPageStyle.scale(8));
                        flow.setVgap(DeploymentPageStyle.scale(8));
                    }
                }
            }
        }
    }

    /** Reveals the input through its parent rather than JTextField's horizontal-only override. / 通过父容器显示输入，避开 JTextField 仅水平滚动的重写。
     * @param control original focused or invalid input / 原聚焦或无效输入 */
    public static void reveal(JComponent control) {
        JComponent target = control;
        if (control.getParent() instanceof JViewport viewport && viewport.getParent() instanceof JScrollPane scroll)
            target = scroll;
        if (target.getParent() instanceof JComponent parent) parent.scrollRectToVisible(target.getBounds());
    }

    /** Installs one retained focus adapter per original input. / 每个原输入仅安装一个保留的焦点适配器。
     * @param body current original subtree / 当前原子树 */
    public static void bindReveal(Container body) {
        for (var child : body.getComponents()) {
            if (child instanceof JComponent control && (control instanceof javax.swing.text.JTextComponent
                || control instanceof JComboBox<?> || control instanceof AbstractButton)
                && !Boolean.TRUE.equals(control.getClientProperty("deployment.dialog.revealBound"))) {
                control.putClientProperty("deployment.dialog.revealBound", true);
                control.addFocusListener(new java.awt.event.FocusAdapter() {
                    /** Reveals actual keyboard focus without modifying the document. / 显示实际键盘焦点，不修改文档。
                     * @param event actual focus change / 实际焦点变化 */
                    @Override
                    public void focusGained(java.awt.event.FocusEvent event) {
                        reveal(control);
                    }
                });
            }
            if (child instanceof Container nested) bindReveal(nested);
        }
    }

    /** Measures the current dialog monitor, excluding taskbars and a twenty-pixel screen margin. / 测量当前对话框屏幕并扣除任务栏及二十像素屏幕边距。
     * @param dialog original window / 原窗口
     * @return usable screen rectangle / 可用屏幕矩形 */
    public static Rectangle workArea(JDialog dialog) {
        var configuration = dialog.getGraphicsConfiguration();
        if (configuration == null && dialog.getOwner() != null)
            configuration = dialog.getOwner().getGraphicsConfiguration();
        if (configuration == null)
            configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        var area = new Rectangle(configuration.getBounds());
        var insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        area.x += insets.left + 20;
        area.y += insets.top + 20;
        area.width = Math.max(1, area.width - insets.left - insets.right - 40);
        area.height = Math.max(1, area.height - insets.top - insets.bottom - 40);
        return area;
    }

    /** Scales the preferred window once before clamping its decorated bounds. / 窗口首选尺寸缩放一次后约束完整窗框边界。
     * @param dialog original window / 原窗口
     * @param center whether this is the first placement / 是否首次放置 */
    private static void fit(JDialog dialog, boolean center) {
        var logical = (Dimension) dialog.getRootPane().getClientProperty("deployment.dialog.preferred");
        var area = workArea(dialog);
        dialog.setSize(Math.min(DeploymentPageStyle.scale(logical.width), area.width),
            Math.min(DeploymentPageStyle.scale(logical.height), area.height));
        if (center) dialog.setLocationRelativeTo(dialog.getOwner());
        constrain(dialog, area);
        dialog.validate();
    }

    /** Constrains the whole decorated window to a measured usable area. / 将完整有窗框窗口限制在测得的可用区。
     * @param dialog original dialog / 原对话框
     * @param area measured usable area / 测得可用区 */
    public static void constrain(JDialog dialog, Rectangle area) {
        int width = Math.min(dialog.getWidth(), area.width), height = Math.min(dialog.getHeight(), area.height);
        dialog.setBounds(Math.max(area.x, Math.min(dialog.getX(), area.x + area.width - width)),
            Math.max(area.y, Math.min(dialog.getY(), area.y + area.height - height)), width, height);
        dialog.validate();
    }

    /** Creates a single width-following scroll boundary. / 创建单个随宽度变化的滚动边界。
     * @param body original content / 原内容
     * @return owned scroll surface / 所属滚动表面 */
    public static JScrollPane scroll(JComponent body) {
        var page = new Column(0);
        page.add(body);
        var scroll = new JScrollPane(page, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(DeploymentPageStyle.scale(20));
        scroll.getViewport().setBackground(UIManager.getColor("Table.background"));
        return scroll;
    }

    /** Creates naturally sized rows; multiline help never enlarges unrelated fields. / 创建自然高度行，多行帮助不会放大无关字段。
     * @return original form container / 原表单容器 */
    public static JPanel form() {
        return new Column(8);
    }

    /** Retains a localized fact without touching editable documents. / 保留可本地化的事实，不触碰可编辑文档。
     * @param value current safe fact / 当前安全事实
     * @return wrapping fact / 换行事实 */
    public static JTextArea fact(java.util.function.Supplier<String> value) {
        var text = text(value.get());
        text.putClientProperty("deployment.dialog.fact", value);
        return text;
    }

    /** Displays complete wrapping non-secret review facts. / 展示完整换行的非秘密审阅事实。
     * @param value original safe text / 原安全文字
     * @return selectable complete text / 可选择的完整文字 */
    public static JTextArea text(String value) {
        var text = new JTextArea(value);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder());
        text.setFont(UIManager.getFont("Label.font"));
        return text;
    }

    /** Measures each row independently at the viewport width. / 按视口宽度独立测量各行。
     */
    private static final class Column extends JPanel implements Scrollable {
        /** Unscaled row separation, reread on every layout. / 未缩放的行间隔，每次布局重新读取。 */
        private final int gap;

        /** Creates a natural-height content column. / 创建自然高度内容列。
         * @param gap logical row separation / 逻辑行间隔 */
        private Column(int gap) {
            super(null);
            this.gap = gap;
            setOpaque(false);
        }

        /** Measures wrapped content at its actual available width. / 按实际可用宽度测量换行内容。
         * @return complete natural extent / 完整自然尺寸 */
        @Override
        public Dimension getPreferredSize() {
            var insets = getInsets();
            int width = 0, height = 0, count = 0;
            int available = getWidth() - insets.left - insets.right;
            for (var child : getComponents())
                if (child.isVisible()) {
                    if (available > 0) {
                        child.setSize(available, Math.max(1, child.getHeight()));
                        if (child instanceof Container nested) nested.doLayout();
                    }
                    var size = child.getPreferredSize();
                    width = Math.max(width, size.width);
                    height += size.height;
                    count++;
                }
            return new Dimension(width + insets.left + insets.right,
                height + Math.max(0, count - 1) * DeploymentPageStyle.scale(gap) + insets.top + insets.bottom);
        }

        /** Preserves preferred row heights while following viewport width. / 保持各行首选高度并跟随视口宽度。 */
        @Override
        public void doLayout() {
            var insets = getInsets();
            int width = Math.max(0, getWidth() - insets.left - insets.right), y = insets.top;
            for (var child : getComponents())
                if (child.isVisible()) {
                    child.setSize(width, Math.max(1, child.getHeight()));
                    if (child instanceof Container nested) nested.doLayout();
                    int height = child.getPreferredSize().height;
                    child.setBounds(insets.left, y, width, height);
                    if (child instanceof Container nested) nested.doLayout();
                    y += height + DeploymentPageStyle.scale(gap);
                }
        }

        /** Returns the complete natural extent. / 返回完整自然尺寸。
         * @return preferred viewport size / 首选视口尺寸 */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        /** Uses the actual viewport width. / 使用实际视口宽度。
         * @return true / 真 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /** Retains natural height for vertical scrolling. / 保留自然高度供纵向滚动。
         * @return false / 假 */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        /** Uses one logical scroll step. / 使用一个逻辑滚动步长。
         * @param visible visible bounds / 可见边界
         * @param orientation scroll axis / 滚动轴
         * @param direction scroll direction / 滚动方向
         * @return scaled step / 缩放后步长 */
        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return DeploymentPageStyle.scale(20);
        }

        /** Retains a small overlap between scroll pages. / 滚动页之间保留少量重叠。
         * @param visible visible bounds / 可见边界
         * @param orientation scroll axis / 滚动轴
         * @param direction scroll direction / 滚动方向
         * @return page step / 翻页步长 */
        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(1, visible.height - DeploymentPageStyle.scale(20));
        }
    }
}
