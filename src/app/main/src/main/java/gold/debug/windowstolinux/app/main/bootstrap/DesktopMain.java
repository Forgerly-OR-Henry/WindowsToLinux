package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.main.runtime.RunModeDetector;
import gold.debug.windowstolinux.app.service.DesktopApplicationService;
import gold.debug.windowstolinux.app.ui.appearance.DesktopAppearance;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Production desktop bootstrap with the fixed data directory required by managed deployment.
 *
 * <p>使用受管部署要求固定数据目录的生产桌面引导程序。
 */
public final class DesktopMain {
    private DesktopMain() {
    }

    /**
     * Performs the {@code launch} operation.
     *
     * <p>执行 {@code launch} 操作。
     *
     * @param arguments the {@code arguments} value / {@code arguments} 值
     */
    public static void launch(String[] arguments) {
        try {
            var layout = RunModeDetector.resolve(DesktopMain.class);
            Files.createDirectories(layout.dataDirectory());
            if (!Files.isDirectory(layout.dataDirectory()) || !Files.isWritable(layout.dataDirectory())) {
                throw new IllegalStateException("fixed data directory is not writable: " + layout.dataDirectory());
            }
            DesktopPersistence database = DesktopPersistence.open(layout.dataDirectory());
            Runtime.getRuntime().addShutdownHook(new Thread(database::close, "windowstolinux-database-close"));
            DesktopAppearance appearance = DesktopAppearance.fromStoredValues(
                    database.preferences().find(DesktopPersistence.UI_LOCALE_SETTING).orElse(null),
                    database.preferences().find(DesktopPersistence.UI_THEME_SETTING).orElse(null),
                    Locale.getDefault());
            DesktopApplicationService service = new DesktopApplicationService(database,
                    layout.dataDirectory().resolve("work"), new SshdLinuxGateway());
            SwingUtilities.invokeLater(() -> new DesktopWindowController(database, service, appearance).showInitialWindow());
        } catch (Exception exception) {
            throw new IllegalStateException("failed to initialize the fixed data directory or desktop application",
                    exception);
        }
    }
}
