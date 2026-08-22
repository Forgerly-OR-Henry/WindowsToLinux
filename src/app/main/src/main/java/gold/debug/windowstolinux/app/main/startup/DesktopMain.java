package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.main.diagnostic.DesktopFailureReportStore;
import gold.debug.windowstolinux.app.main.diagnostic.DesktopStartupException;
import gold.debug.windowstolinux.app.main.diagnostic.DesktopSystemFailureType;
import gold.debug.windowstolinux.app.main.diagnostic.DesktopUncaughtFailureBoundary;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver;
import gold.debug.windowstolinux.app.main.runtime.DesktopStorageLayout;
import gold.debug.windowstolinux.app.service.DesktopApplicationFacade;
import gold.debug.windowstolinux.app.ui.diagnostic.DesktopFailurePresenter;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** Production desktop bootstrap with fixed paths and structured startup boundaries. / 具备固定路径和结构化启动边界的生产桌面引导程序。 */
public final class DesktopMain {
    private static final long MINIMUM_FREE_BYTES = 1024L * 1024L;

    private DesktopMain() {
    }

    /** Starts the desktop and stops safely when a mandatory startup stage fails. / 启动桌面；必要启动阶段失败时安全停止。 */
    public static void launch(String[] arguments) {
        MessageCatalog initialMessages = MessageCatalog.forLanguageTag(Locale.getDefault().toLanguageTag());
        RunModeResolver.RuntimeLayout layout;
        DesktopStorageLayout dataLayout;
        try {
            layout = RunModeResolver.resolve(DesktopMain.class);
            dataLayout = DesktopStorageLayout.from(layout.dataDirectory());
        } catch (RuntimeException failure) {
            showStartupFailure(FailureReportStore.disabled(), initialMessages,
                    DesktopStartupException.create(DesktopSystemFailureType.STARTUP_LAYOUT_INVALID,
                            "The application runtime layout could not be resolved safely", failure));
            return;
        }

        DesktopFailureReportStore reports = new DesktopFailureReportStore(dataLayout.root());
        try {
            verifyDataDirectory(dataLayout);
        } catch (Exception failure) {
            showStartupFailure(reports, initialMessages,
                    DesktopStartupException.create(DesktopSystemFailureType.DATA_DIRECTORY_UNAVAILABLE,
                            "The fixed application data directory is unavailable or lacks safe capacity", failure));
            return;
        }

        DesktopPersistence database;
        try {
            database = DesktopPersistence.open(dataLayout.root());
        } catch (Exception failure) {
            showStartupFailure(reports, initialMessages,
                    DesktopStartupException.create(DesktopSystemFailureType.DATABASE_INITIALIZATION_FAILED,
                            "Desktop persistence failed its migration or integrity startup checks", failure));
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                database.close();
            } catch (RuntimeException failure) {
                reports.record(DesktopStartupException.create(DesktopSystemFailureType.SHUTDOWN_FAILED,
                        "Desktop persistence did not close cleanly", failure));
            }
        }, "windowstolinux-database-close"));

        try {
            DesktopDisplayConfiguration appearance = DesktopDisplayConfiguration.fromStoredValues(
                    database.preferences().find(DesktopPersistence.UI_LOCALE_SETTING).orElse(null),
                    database.preferences().find(DesktopPersistence.UI_THEME_SETTING).orElse(null),
                    Locale.getDefault());
            MessageCatalog messages = MessageCatalog.forLanguageTag(appearance.localeTag());
            new DesktopUncaughtFailureBoundary(reports, messages).install();
            DesktopApplicationFacade service = new DesktopApplicationFacade(database,
                    dataLayout.workDirectory(), new SshdLinuxGateway());
            SwingUtilities.invokeLater(() -> {
                try {
                    new DesktopWindowController(database, service, appearance, reports).showInitialWindow();
                } catch (RuntimeException failure) {
                    throw DesktopStartupException.create(DesktopSystemFailureType.UI_INITIALIZATION_FAILED,
                            "The desktop user interface could not be initialized", failure);
                }
            });
        } catch (Exception failure) {
            showStartupFailure(reports, initialMessages,
                    DesktopStartupException.create(DesktopSystemFailureType.UI_INITIALIZATION_FAILED,
                            "Desktop services or user interface initialization failed", failure));
        }
    }

    private static void verifyDataDirectory(DesktopStorageLayout layout) throws java.io.IOException {
        layout.initializeDirectories();
        Path normalized = layout.root();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(normalized)
                || Files.getFileStore(normalized).getUsableSpace() < MINIMUM_FREE_BYTES) {
            throw new java.io.IOException("fixed data directory did not pass writable-directory checks");
        }
    }

    private static void showStartupFailure(
            FailureReportStore reports, MessageCatalog messages, DesktopStartupException failure) {
        String text = new DesktopFailurePresenter(messages::text, reports).present(failure);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, text, "WindowsToLinux", JOptionPane.ERROR_MESSAGE);
        } else {
            System.err.println(text);
        }
    }
}
