package gold.debug.windowstolinux.app.ui.appearance;

import gold.debug.windowstolinux.app.ui.shell.DesktopFrame;

/**
 * Saves a selected appearance and replaces the active desktop window on the event thread.
 *
 * <p>保存选定外观，并在事件线程上替换活动桌面窗口。
 */
@FunctionalInterface
public interface DesktopAppearanceChangeListener {
    /**
     * Performs the {@code apply} operation.
     *
     * <p>执行 {@code apply} 操作。
     *
     * @param source the {@code source} value / {@code source} 值
     * @param appearance the {@code appearance} value / {@code appearance} 值
     */
    void apply(DesktopFrame source, DesktopAppearance appearance);
}
