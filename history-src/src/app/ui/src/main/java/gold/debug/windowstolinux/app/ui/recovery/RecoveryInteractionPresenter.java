package gold.debug.windowstolinux.app.ui.recovery;

import java.awt.Component;
import java.util.concurrent.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.*;
import gold.debug.windowstolinux.app.service.contract.DesktopRecoveryInteraction;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

/**
 * Shared Swing handoff for server checks and environment preparation. / 服务器检查和环境准备共用的 Swing 交接。
 *
 * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
 * @param ai the supplied ai application facade / 所提供的AI应用门面
 * @param components reviewed components in the application graph / 应用图中的已审阅组件
 * @param messages localized message resolver / 本地化消息解析器
 */
public record RecoveryInteractionPresenter(Component owner, AiApplicationFacade ai, DesktopComponentFactory components,
        PageMessagePresenter messages) implements DesktopRecoveryInteraction {
    /**
     * Tests the offer ssh recovery predicate against the supplied evidence.
     * <p>根据所提供证据检查offerSSH恢复条件。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return true when offer ssh recovery predicate against the supplied evidence, false otherwise / 根据所提供证据检查offerSSH恢复条件时为 true，否则为 false
     */
    @Override
    public boolean offerSshRecovery(ServerProfile server) {
        if (ai == null)
            return false;
        return onEdt(() -> JOptionPane.showConfirmDialog(owner, messages.text("recovery.offer"),
                messages.text("recovery.title"), JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION);
    }

    /**
     * Displays ssh recovery.
     * <p>展示SSH恢复。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     */
    @Override
    public void showSshRecovery(ServerProfile server, SshRecoverySession session) {
        onEdt(() -> new SshRecoveryDialog(owner, server, session, ai, components, messages));
    }

    /**
     * Schedules Swing state changes on the event dispatch thread.
     * <p>在 Swing 事件分派线程调度状态变更。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static <T> T onEdt(Callable<T> action) {
        try {
            if (SwingUtilities.isEventDispatchThread())
                return action.call();
            var task = new FutureTask<>(action);
            SwingUtilities.invokeLater(task);
            return task.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (Exception failure) {
            throw new IllegalStateException("recovery-ui-unavailable");
        }
    }
}
