package gold.debug.windowstolinux.mod.files.ui;

import java.awt.*;
import javax.swing.*;

/** Author-owned modal layout using the host's Swing defaults. / 使用宿主 Swing 默认值的作者所属模态布局。 */
final class FilesDialogLayout {
    /** Prevents instances of this layout helper. / 禁止实例化此布局工具。 */
    private FilesDialogLayout() {
    }

    /** Reads the host UIScale value without requiring a host implementation dependency. / 无宿主实现依赖地读取 UIScale 值。
     * @param value logical length / 逻辑长度
     * @return once-scaled screen length / 一次缩放后的屏幕长度 */
    static int scale(int value) {
        Object factor = UIManager.get("laf.scaleFactor");
        return Math.round(value * (factor instanceof Number number ? number.floatValue() : 1f));
    }

    /** Keeps controls at their natural height in a vertically scrollable form. / 在可纵向滚动表单中保留控件自然高度。
     * @param fields original label and input pairs / 原标签和输入对
     * @return naturally sized form / 自然尺寸表单 */
    static JPanel fields(JComponent... fields) {
        var panel = new JPanel(new GridBagLayout());
        panel.putClientProperty("files.dialog.fields", true);
        for (int index = 0; index < fields.length; index++) {
            var constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = index;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.NORTHWEST;
            constraints.insets.top = index == 0 ? 0 : scale(index % 2 == 0 ? 12 : 8);
            panel.add(fields[index], constraints);
        }
        return panel;
    }

    /** Mounts fixed actions beneath a scrollable body. / 在可滚动主体下方固定操作区。
     * @param dialog original modal window / 原模态窗口
     * @param body original business controls / 原业务控件
     * @param ok original accept action / 原接受操作
     * @param cancel original cancel action / 原取消操作 */
    static void install(JDialog dialog, JComponent body, JButton ok, JButton cancel) {
        var buttons = new JPanel(new GridBagLayout());
        buttons.setName("files.dialog.actions");
        var action = new GridBagConstraints();
        action.weightx = 1;
        action.anchor = GridBagConstraints.EAST;
        buttons.add(ok, action);
        action.weightx = 0;
        action.insets.left = scale(8);
        buttons.add(cancel, action);
        var scroll = body instanceof JScrollPane existing ? existing
            : new JScrollPane(new ViewportBody(body), ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setName("files.dialog.body");
        if (body.getClientProperty("files.dialog.listScope") instanceof JTextArea scope)
            scroll.putClientProperty("files.dialog.listScope", scope);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        var content = new JPanel(new BorderLayout(0, scale(12)));
        content.putClientProperty("files.dialog.sectionGap", 12);
        content.setName("files.dialog.content");
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.addPropertyChangeListener("graphicsConfiguration", event -> {
            if (dialog.isDisplayable()) fit(dialog, dialog.getOwner(), workArea(dialog));
        });
        bindReveal(body);
        refresh(dialog);
    }

    /** Recomputes from logical baselines without replacing input documents or models. / 从逻辑基准重算而不替换输入文档或模型。
     * @param dialog original visible or opening window / 原已显示或正在打开的窗口 */
    static void refresh(JDialog dialog) {
        style(dialog.getContentPane());
        var content = (JPanel) dialog.getContentPane();
        int padding = scale(20);
        content.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
        ((BorderLayout) content.getLayout()).setVgap(scale(12));
        dialog.pack();
        fit(dialog, dialog.getOwner(), workArea(dialog));
    }

    /** Restyles existing surfaces and recomputes logical viewport baselines. / 重设既有表面并重算逻辑视口基准。
     * @param container original component subtree / 原控件子树 */
    private static void style(Container container) {
        if (container instanceof JComponent component) {
            Color surface = UIManager.getColor("Table.background");
            if (component instanceof JPanel || component instanceof JViewport || component instanceof JCheckBox)
                component.setBackground(surface);
            if (component instanceof JScrollPane scroll) {
                scroll.setBackground(surface);
                scroll.setBorder(BorderFactory.createEmptyBorder());
            }
            if (component instanceof JTextArea text && !text.isEditable()) {
                text.setFont(UIManager.getFont("Label.font"));
                text.setBackground(surface);
                text.setBorder(BorderFactory.createEmptyBorder());
            }
            if (component instanceof JPanel panel && panel.getLayout() instanceof BorderLayout layout)
                layout.setVgap(scale(component.getClientProperty("files.dialog.sectionGap") instanceof Integer gap ? gap : 8));
            if (component instanceof JPanel panel && Boolean.TRUE.equals(panel.getClientProperty("files.dialog.links"))) {
                var layout = (GridBagLayout) panel.getLayout();
                for (Component field : panel.getComponents()) {
                    var constraints = layout.getConstraints(field);
                    constraints.insets.bottom = scale(8);
                    constraints.insets.left = constraints.gridx == 1 ? scale(8) : 0;
                    layout.setConstraints(field, constraints);
                }
            }
            if (component instanceof JPanel panel && Boolean.TRUE.equals(panel.getClientProperty("files.dialog.fields"))) {
                var layout = (GridBagLayout) panel.getLayout();
                for (Component field : panel.getComponents()) {
                    var constraints = layout.getConstraints(field);
                    constraints.insets.top = constraints.gridy == 0 ? 0 : scale(constraints.gridy % 2 == 0 ? 12 : 8);
                    layout.setConstraints(field, constraints);
                }
            }
            if (component instanceof JPanel panel && "files.dialog.actions".equals(panel.getName())) {
                var layout = (GridBagLayout) panel.getLayout();
                var constraints = layout.getConstraints(panel.getComponent(1));
                constraints.insets.left = scale(8);
                layout.setConstraints(panel.getComponent(1), constraints);
            }
        }
        for (Component child : container.getComponents())
            if (child instanceof Container nested) style(nested);
        if (container instanceof JScrollPane scroll && scroll.getClientProperty("files.dialog.listScope") instanceof JTextArea scope) {
            Dimension prose = scope.getPreferredSize();
            scroll.setPreferredSize(new Dimension(Math.max(scale(360), prose.width), prose.height + scale(12) + scale(150)));
        }
    }

    /** Binds focus visibility once while retaining original documents and models. / 仅绑定一次焦点可见性并保留原文档和模型。
     * @param container original body subtree / 原主体子树 */
    private static void bindReveal(Container container) {
        if (container instanceof JComponent component
            && (component instanceof javax.swing.text.JTextComponent || component instanceof JComboBox<?> || component instanceof JCheckBox)
            && !Boolean.TRUE.equals(component.getClientProperty("files.dialog.revealBound"))) {
            component.putClientProperty("files.dialog.revealBound", true);
            component.addFocusListener(new java.awt.event.FocusAdapter() {
                /** Reveals the control through its parent rather than its internal text scroller. / 经父容器显露控件，避免被文本内部滚动吞掉。
                 * @param event original focus event / 原焦点事件 */
                @Override
                public void focusGained(java.awt.event.FocusEvent event) {
                    JComponent target = component.getClientProperty("files.dialog.revealTarget") instanceof JComponent row ? row : component;
                    if (target.getParent() instanceof JComponent parent)
                        parent.scrollRectToVisible(target.getBounds());
                }
            });
        }
        for (Component child : container.getComponents())
            if (child instanceof Container nested) bindReveal(nested);
    }

    /** Wraps prose to the viewport while retaining vertical scrolling for large forms. / 说明按视口换行，大表单保留纵向滚动。 */
    private static final class ViewportBody extends JPanel implements Scrollable {
        /** Retains one naturally sized content tree. / 保留一棵自然尺寸内容树。
         * @param body original business body / 原业务主体 */
        ViewportBody(JComponent body) {
            super(new BorderLayout());
            add(body, BorderLayout.NORTH);
        }

        /** Reports the natural viewport size. / 返回自然视口尺寸。
         * @return preferred viewport extent / 首选视口范围 */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        /** Scrolls by a scaled text-sized step. / 按缩放后的文本步长滚动。
         * @param visible current visible bounds / 当前可见范围
         * @param orientation scroll axis / 滚动轴
         * @param direction scroll direction / 滚动方向
         * @return unit step / 单步长度 */
        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return scale(16);
        }

        /** Leaves one unit of context when paging. / 翻页时保留一步上下文。
         * @param visible current visible bounds / 当前可见范围
         * @param orientation scroll axis / 滚动轴
         * @param direction scroll direction / 滚动方向
         * @return page step / 翻页步长 */
        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(scale(16), visible.height - scale(16));
        }

        /** Wraps prose to the actual viewport width. / 使说明按实际视口宽度换行。
         * @return whether width follows the viewport / 宽度是否跟随视口 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        /** Retains natural content height when prose wraps in a narrow viewport. / 说明在窄视口换行时保留自然内容高度。
         * @return false to preserve vertical scrolling / 返回假以保留纵向滚动 */
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Uses the current window's monitor and excludes task bars and docks. / 使用当前窗口显示器并扣除任务栏和停靠栏。
     * @param window actual dialog or its owner before initialization / 实际对话框或初始化前的所属窗口
     * @return current monitor work area / 当前显示器工作区 */
    static Rectangle workArea(Window window) {
        GraphicsConfiguration graphics = window.getGraphicsConfiguration();
        if (graphics == null && window.getOwner() != null) graphics = window.getOwner().getGraphicsConfiguration();
        if (graphics == null)
            graphics = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle bounds = new Rectangle(graphics.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(graphics);
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        return bounds;
    }

    /** Constrains the complete decorated window, including its native title bar. / 约束含原生标题栏的完整窗口。
     * @param dialog actual dialog / 实际对话框
     * @param owner optional centering anchor / 可选居中参照窗口
     * @param workArea current monitor work area / 当前显示器工作区 */
    static void fit(JDialog dialog, Window owner, Rectangle workArea) {
        int margin = 20;
        var available = new Rectangle(workArea);
        available.grow(-margin, -margin);
        int width = Math.max(1, Math.min(dialog.getWidth(), available.width));
        int height = Math.max(1, Math.min(dialog.getHeight(), available.height));
        dialog.setSize(width, height);
        Rectangle anchor = owner == null ? available : owner.getBounds();
        int x = Math.max(available.x, Math.min(anchor.x + (anchor.width - width) / 2, available.x + available.width - width));
        int y = Math.max(available.y, Math.min(anchor.y + (anchor.height - height) / 2, available.y + available.height - height));
        dialog.setLocation(x, y);
        dialog.validate();
    }
}
