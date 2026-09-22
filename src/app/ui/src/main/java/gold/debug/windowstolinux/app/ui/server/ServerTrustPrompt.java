package gold.debug.windowstolinux.app.ui.server;

import java.awt.Component;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

/**
 * Presents host trust on the EDT while connection work remains in the background. / 在事件线程确认主机信任，连接仍在后台执行。
 */
public final class ServerTrustPrompt {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private ServerTrustPrompt() {
    }

    /**
     * Rejects trust if the user cancels or confirmation is interrupted. / 用户取消或确认中断时拒绝信任。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param messages localized message resolver / 本地化消息解析器
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return true when rejects trust if the user cancels or confirmation is interrupted, false otherwise / 用户取消或确认中断时拒绝信任时为 true，否则为 false
     */
    public static boolean confirm(Component owner, PageMessagePresenter messages, String fingerprint) {
        AtomicBoolean accepted = new AtomicBoolean();
        Runnable prompt = () -> accepted.set(JOptionPane.showConfirmDialog(owner,
                messages.text("fingerprint.confirm", Map.of("fingerprint", fingerprint)),
                messages.text("fingerprint.confirm.title"), JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
        try {
            if (SwingUtilities.isEventDispatchThread())
                prompt.run();
            else
                SwingUtilities.invokeAndWait(prompt);
        } catch (Exception failure) {
            return false;
        }
        return accepted.get();
    }
}
