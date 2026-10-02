package gold.debug.windowstolinux.app.ui.control;

import java.awt.*;

/** Measures wrapped action rows using the available width. / 按可用宽度测量换行操作区。 */
public final class WrappingFlowLayout extends FlowLayout {
    /** Last measured row height. / 上次测量的行高度。 */
    private int measuredHeight = -1;

    /** Preserves the original alignment and spacing. / 保留原对齐及间距。
     * @param original original flow / 原流式布局 */
    public WrappingFlowLayout(FlowLayout original) {
        super(original.getAlignment(), original.getHgap(), original.getVgap());
    }

    /** Requests parent measurement after wrapping changes on resize. / 缩放改变换行后请求父容器重新测量。
     * @param target existing actions / 已有操作区 */
    @Override
    public void layoutContainer(Container target) {
        super.layoutContainer(target);
        int height = preferredLayoutSize(target).height;
        if (height != measuredHeight) {
            measuredHeight = height;
            if (target.getParent() instanceof javax.swing.JComponent parent)
                EventQueue.invokeLater(parent::revalidate);
        }
    }

    /** Measures all visible rows instead of reporting a single row. / 测量所有可见行，不只报告单行。
     * @param target existing action panel / 已有操作面板
     * @return complete action region / 完整操作区域 */
    @Override
    public Dimension preferredLayoutSize(Container target) {
        int available = target.getWidth();
        if (available <= 0)
            return super.preferredLayoutSize(target);
        Insets insets = target.getInsets();
        int limit = Math.max(1, available - insets.left - insets.right - getHgap() * 2);
        int width = 0, height = 0, row = 0;
        for (Component child : target.getComponents()) {
            if (!child.isVisible())
                continue;
            Dimension size = child.getPreferredSize();
            if (width > 0 && width + getHgap() + size.width > limit) {
                height += row + getVgap();
                width = 0;
                row = 0;
            }
            width += (width == 0 ? 0 : getHgap()) + size.width;
            row = Math.max(row, size.height);
        }
        return new Dimension(available, height + row + insets.top + insets.bottom + getVgap() * 2);
    }
}
