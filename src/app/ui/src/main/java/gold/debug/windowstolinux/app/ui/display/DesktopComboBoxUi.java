package gold.debug.windowstolinux.app.ui.display;

import java.awt.Dimension;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatComboBoxUI;

/** Keeps each displayed dropdown's width stable while preserving FlatLaf input and popup rendering. / 固定各下拉框显示后的宽度，保留 FlatLaf 输入及展开列表绘制。 */
public final class DesktopComboBoxUi extends FlatComboBoxUI {
    /** Width measured from the initial options, renderer and current scale. / 按初始选项、渲染器及当前缩放测量的宽度。 */
    private int fixedWidth = -1;

    /** Creates an independent sizing delegate for each dropdown. / 为每个下拉框创建独立的尺寸绘制器。 */
    private DesktopComboBoxUi() {
    }

    /** Installs the shared dropdown behavior without sharing component sizes. / 安装统一下拉框行为，不共享控件尺寸。
     * @param component dropdown receiving this delegate / 使用当前绘制器的下拉框
     * @return a new delegate / 新绘制器
     */
    public static ComponentUI createUI(JComponent component) {
        return new DesktopComboBoxUi();
    }

    /** Retains the initial field width after realization; the expanded list still measures all current options. / 控件创建窗口资源后保留初始宽度，展开列表仍测量全部当前选项。
     * @param component dropdown being measured / 正在测量的下拉框
     * @return stable width with the native height / 固定宽度及原生高度
     */
    @Override
    public Dimension getMinimumSize(JComponent component) {
        Dimension size = super.getMinimumSize(component);
        if (component.isDisplayable()) {
            if (fixedWidth < 0)
                fixedWidth = size.width;
            return new Dimension(fixedWidth, size.height);
        }
        return size;
    }
}
