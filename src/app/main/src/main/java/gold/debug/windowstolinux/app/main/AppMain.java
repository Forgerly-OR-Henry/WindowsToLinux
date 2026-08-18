package gold.debug.windowstolinux.app.main;

import gold.debug.windowstolinux.app.main.startup.DesktopMain;

/**
 * WindowsToLinux desktop application entrypoint.
 *
 * <p>WindowsToLinux 桌面应用入口。
 */
public final class AppMain {
    private AppMain() {
    }

    /**
     * Starts the WindowsToLinux application.
     *
     * <p>启动 WindowsToLinux 应用。
     *
     * @param arguments the {@code arguments} value / {@code arguments} 值
     */
    public static void main(String[] arguments) {
        DesktopMain.launch(arguments);
    }
}
