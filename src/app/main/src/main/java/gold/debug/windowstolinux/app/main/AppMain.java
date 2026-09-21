package gold.debug.windowstolinux.app.main;

import gold.debug.windowstolinux.app.main.startup.DesktopMain;

/**
 * WindowsToLinux desktop application entrypoint.
 *
 *  <p>WindowsToLinux 桌面应用入口。
 */
public final class AppMain {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private AppMain() {
    }

    /**
     * Starts the WindowsToLinux application.
     *
     *  <p>启动 WindowsToLinux 应用。
     *
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     */
    public static void main(String[] arguments) {
        DesktopMain.launch(arguments);
    }
}
