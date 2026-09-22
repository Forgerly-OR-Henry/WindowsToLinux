package gold.debug.windowstolinux.app.main.diagnostic;

import java.util.Objects;
import java.util.function.IntConsumer;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.ui.diagnostic.DesktopFailurePresenter;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

/**
 * Last-resort process boundary for uncaught desktop failures. / 桌面未捕获失败的最终进程边界。
 */
public final class DesktopUncaughtFailureBoundary implements Thread.UncaughtExceptionHandler {
    /**
     * Bound failure report store collaborator for reports.
     * <p>处理报告集合的失败报告存储协作对象。
     */
    private final FailureReportStore reports;

    /**
     * Bound desktop failure presenter collaborator for presenter.
     * <p>处理展示器的Desktop失败展示器协作对象。
     */
    private final DesktopFailurePresenter presenter;

    /**
     * Exit process.
     * <p>退出进程。
     */
    private final IntConsumer exitProcess;

    /**
     * Creates the production uncaught-failure boundary. / 创建生产未捕获失败边界。
     *
     * @param reports reports / 报告集合
     * @param messages localized message resolver / 本地化消息解析器
     */
    public DesktopUncaughtFailureBoundary(FailureReportStore reports, MessageCatalog messages) {
        this(reports, messages, System::exit);
    }

    /**
     * Validates and binds the inputs required by desktop uncaught failure boundary.
     * <p>校验并绑定DesktopUncaught失败边界所需输入。
     *
     * @param reports reports / 报告集合
     * @param messages localized message resolver / 本地化消息解析器
     * @param exitProcess exit process / 退出进程
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    DesktopUncaughtFailureBoundary(FailureReportStore reports, MessageCatalog messages, IntConsumer exitProcess) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.presenter = new DesktopFailurePresenter(messages::text, reports);
        this.exitProcess = Objects.requireNonNull(exitProcess, "exitProcess");
    }

    /**
     * Installs this boundary as the process default. / 将此边界安装为进程默认入口。
     */
    public void install() {
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    /**
     * Records all failures; fatal JVM/linkage errors then terminate the process. / 记录所有失败；JVM/链接致命错误随后终止进程。
     *
     * @param thread thread / 线程
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override
    public void uncaughtException(Thread thread, Throwable failure) {
        if (failure instanceof VirtualMachineError || failure instanceof LinkageError) {
            reports.record(failure);
            exitProcess.accept(70);
            return;
        }
        Runnable show = () -> {
            try {
                JOptionPane.showMessageDialog(null, presenter.present(failure), "WindowsToLinux",
                        JOptionPane.ERROR_MESSAGE);
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
