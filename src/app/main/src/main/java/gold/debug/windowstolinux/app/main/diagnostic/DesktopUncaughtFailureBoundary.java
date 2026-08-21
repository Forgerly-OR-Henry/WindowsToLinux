package gold.debug.windowstolinux.app.main.diagnostic;

import gold.debug.windowstolinux.app.ui.diagnostic.DesktopFailurePresenter;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.function.IntConsumer;

/** Last-resort process boundary for uncaught desktop failures. / 桌面未捕获失败的最终进程边界。 */
public final class DesktopUncaughtFailureBoundary implements Thread.UncaughtExceptionHandler {
    private final FailureReportStore reports;
    private final DesktopFailurePresenter presenter;
    private final IntConsumer exitProcess;

    /** Creates the production uncaught-failure boundary. / 创建生产未捕获失败边界。 */
    public DesktopUncaughtFailureBoundary(FailureReportStore reports, MessageCatalog messages) {
        this(reports, messages, System::exit);
    }

    DesktopUncaughtFailureBoundary(FailureReportStore reports, MessageCatalog messages, IntConsumer exitProcess) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.presenter = new DesktopFailurePresenter(messages::text, reports);
        this.exitProcess = Objects.requireNonNull(exitProcess, "exitProcess");
    }

    /** Installs this boundary as the process default. / 将此边界安装为进程默认入口。 */
    public void install() { Thread.setDefaultUncaughtExceptionHandler(this); }

    /** Records all failures; fatal JVM/linkage errors then terminate the process. / 记录所有失败；JVM/链接致命错误随后终止进程。 */
    @Override
    public void uncaughtException(Thread thread, Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof LinkageError) {
            reports.record(failure);
            exitProcess.accept(70);
            return;
        }
        Runnable show = () -> {
            try {
                JOptionPane.showMessageDialog(null, presenter.present(failure),
                        "WindowsToLinux", JOptionPane.ERROR_MESSAGE);
            } catch (RuntimeException ignored) {
                // The last-resort boundary cannot safely recurse through another UI failure. / 最终边界不能因另一 UI 失败而递归。
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }
}
