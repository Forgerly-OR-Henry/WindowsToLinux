package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;

/**
 * Saves a selected appearance and replaces the active desktop window on the event thread.
 *
 *  <p>保存选定外观，并在事件线程上替换活动桌面窗口。
 */
@FunctionalInterface
public interface DesktopDisplayChangeHandler {
    /**
     * Applies desktop display change handler.
     * <p>应用Desktop显示变更Handler。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param appearance appearance / 外观
     */
    void apply(DesktopFrame source, DesktopDisplayConfiguration appearance);
}
