package gold.debug.windowstolinux.app.ui.window;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** Transparent input boundary retaining host navigation. / 保留宿主导航的透明输入边界。 */
final class DesktopLockGate extends JPanel {
    /** Host used for original component hit testing. / 原组件命中检测所用宿主。 */
    private final JFrame frame;

    /** Creates a transparent input boundary for the owned desktop. / 为所属桌面创建透明输入边界。
     * @param frame owned desktop / 所属桌面
     */
    DesktopLockGate(JFrame frame) {
        this.frame = frame;
        setName("security.inputGate");
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
        });
        addMouseMotionListener(new MouseMotionAdapter() {
        });
        addMouseWheelListener(MouseWheelEvent::consume);
        new java.awt.dnd.DropTarget(this, java.awt.dnd.DnDConstants.ACTION_NONE, new java.awt.dnd.DropTargetAdapter() {
            /** Rejects drag admission at the privacy gate. / 在隐私门禁拒绝拖动准入。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void dragEnter(java.awt.dnd.DropTargetDragEvent event) {
                event.rejectDrag();
            }

            /** Rejects drops at the privacy gate. / 在隐私门禁拒绝放置。
             * @param event original event notification / 原事件通知
             */
            @Override
            public void drop(java.awt.dnd.DropTargetDropEvent event) {
                event.rejectDrop();
            }
        }, true);
    }

    /** Passes navigation hits through without forwarding synthetic events. / 直接放行导航命中，不转发合成事件。
     * @param x glass-pane horizontal coordinate / 玻璃层横坐标
     * @param y glass-pane vertical coordinate / 玻璃层纵坐标
     * @return whether privacy blocks this hit / 隐私是否阻止此命中 */
    @Override
    public boolean contains(int x, int y) {
        Point point = SwingUtilities.convertPoint(this, x, y, frame.getContentPane());
        Component hit = SwingUtilities.getDeepestComponentAt(frame.getContentPane(), point.x, point.y);
        if (navigation(hit)) return false;
        for (Component current = hit; current != null; current = current.getParent()) {
            if (current instanceof AbstractButton && !(current instanceof javax.swing.plaf.UIResource)) return true;
            if (current instanceof JTabbedPane tabs) {
                Point tabPoint = SwingUtilities.convertPoint(this, x, y, tabs);
                Component selected = tabs.getSelectedComponent();
                if (selected == null || selected.getWidth() <= 0 || selected.getHeight() <= 0) return true;
                Rectangle content = selected.getBounds();
                // Tab UI hit tests can reenter layout while a theme delegate is being replaced.
                // 标签 UI 命中检测可能在主题委托替换期间重入布局；此处只读取已有边界。
                return !switch (tabs.getTabPlacement()) {
                    case SwingConstants.TOP -> tabPoint.y < content.y;
                    case SwingConstants.BOTTOM -> tabPoint.y >= content.y + content.height;
                    case SwingConstants.LEFT -> tabPoint.x < content.x;
                    case SwingConstants.RIGHT -> tabPoint.x >= content.x + content.width;
                    default -> false;
                };
            }
        }
        return true;
    }

    /** Tests explicitly marked navigation controls and their popups. / 检查明确标记的导航控件及其弹层。
     * @param component original hit component / 原命中组件
     * @return whether the component belongs to host navigation / 组件是否属于宿主导航 */
    static boolean navigation(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof JComponent control
                && Boolean.TRUE.equals(control.getClientProperty("desktop.navigation"))) return true;
            if (current instanceof JPopupMenu popup) return navigation(popup.getInvoker());
        }
        return false;
    }

    /** Allows selection keys while blocking application shortcuts. / 允许选择按键，阻止应用快捷键。
     * @param event original keyboard event / 原键盘事件
     * @return whether privacy blocks the key / 隐私是否阻止此按键 */
    static boolean blocks(KeyEvent event) {
        Component source = event.getComponent();
        if (event.isAltDown() || event.isControlDown() || event.isMetaDown()) return true;
        boolean tab = source instanceof JTabbedPane;
        if (!tab && !navigation(source)) return true;
        return !java.util.Set.of(KeyEvent.VK_TAB, KeyEvent.VK_ENTER, KeyEvent.VK_SPACE,
            KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
            KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_ESCAPE).contains(event.getKeyCode());
    }
}
