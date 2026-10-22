package gold.debug.windowstolinux.app.ui.display;

import java.awt.Component;
import java.beans.PropertyChangeListener;
import javax.swing.*;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatListUI;

/** Decorates standard object lists while preserving models and author renderers. / 装饰标准对象列表，保留模型与作者渲染器。 */
public final class DesktopListUi extends FlatListUI {
    /** Scale under which the original fixed row height was set. / 原固定行高设定时的缩放比例。 */
    private float installedScale;

    /** Creates the host list delegate. / 创建宿主列表绘制器。
     * @param component original list / 原列表
     * @return owned UI delegate / 所属绘制器 */
    public static ComponentUI createUI(JComponent component) {
        return new DesktopListUi();
    }

    /** Restores logical row geometry before decorating original content. / 装饰原内容前恢复逻辑行尺寸。
     * @param component original list / 原列表 */
    @Override
    public void installUI(JComponent component) {
        super.installUI(component);
        installedScale = com.formdev.flatlaf.util.UIScale.getUserScaleFactor();
        if (list.getClientProperty("desktop.inventory.logicalHeight") instanceof Number height)
            list.setFixedCellHeight(Math.round(height.floatValue() * installedScale));
        decorate();
    }

    /** Releases only this delegate's renderer wrapper. / 仅释放当前绘制器的渲染包装。 */
    @Override
    protected void uninstallDefaults() {
        if (list.getFixedCellHeight() > 0 && installedScale > 0)
            list.putClientProperty("desktop.inventory.logicalHeight", list.getFixedCellHeight() / installedScale);
        if (list.getCellRenderer() instanceof Renderer renderer) list.setCellRenderer(renderer.original);
        super.uninstallDefaults();
    }

    /** Preserves FlatLaf property handling and wraps replacement author renderers. / 保留FlatLaf属性处理并包装替换的作者渲染器。
     * @return original property handler with decoration / 带装饰的原属性处理器 */
    @Override
    protected PropertyChangeListener createPropertyChangeListener() {
        var original = super.createPropertyChangeListener();
        return event -> {
            original.propertyChange(event);
            if (event.getPropertyName().equals("cellRenderer")) decorate();
        };
    }

    /** Adds exactly one presentation wrapper. / 添加恰好一层展示包装。 */
    private void decorate() {
        if (list.getCellRenderer() != null && !(list.getCellRenderer() instanceof Renderer))
            list.setCellRenderer(new Renderer(list.getCellRenderer()));
    }

    /** Returns the author renderer without the UI-owned wrapper. / 返回不含UI包装的作者渲染器。
     * @param <T> original value type / 原值类型
     * @param list original list / 原列表
     * @return original renderer / 原渲染器 */
    @SuppressWarnings("unchecked")
    public static <T> ListCellRenderer<? super T> originalRenderer(JList<T> list) {
        return list.getCellRenderer() instanceof Renderer renderer
            ? (ListCellRenderer<? super T>) renderer.original : list.getCellRenderer();
    }

    /** Ordinary popup and system chooser lists keep their native presentation. / 普通弹层与系统选择器保留原展示。
     * @param original author renderer / 作者渲染器 */
    private record Renderer(ListCellRenderer<Object> original) implements ListCellRenderer<Object> {
        /** Decorates an object row without changing its content. / 装饰对象行而不改变内容。
         * @param list original list / 原列表
         * @param value original item / 原条目
         * @param index original index / 原索引
         * @param selected original selection / 原选择
         * @param focused original focus / 原焦点
         * @return author content on its shared surface / 共用表面上的作者内容 */
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            var content = original.getListCellRendererComponent(list, value, index, selected, focused);
            if (index < 0 || "ComboBox.list".equals(list.getName()) || Boolean.TRUE.equals(list.getClientProperty("List.isFileList"))
                || SwingUtilities.getAncestorOfClass(JFileChooser.class, list) != null)
                return content;
            return DesktopListCard.row(content, selected, null);
        }
    }
}
