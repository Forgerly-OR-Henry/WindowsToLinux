package gold.debug.windowstolinux.app.ui.server;

import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import java.awt.Component;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Presents host trust on the EDT while connection work remains in the background. / 在事件线程确认主机信任，连接仍在后台执行。 */
public final class ServerTrustPrompt {
    private ServerTrustPrompt() { }

    /** Rejects trust if the user cancels or confirmation is interrupted. / 用户取消或确认中断时拒绝信任。 */
    public static boolean confirm(Component owner, PageMessagePresenter messages, String fingerprint) {
        AtomicBoolean accepted = new AtomicBoolean();
        Runnable prompt = () -> accepted.set(JOptionPane.showConfirmDialog(owner,
                messages.text("fingerprint.confirm", Map.of("fingerprint", fingerprint)),
                messages.text("fingerprint.confirm.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
        try { if (SwingUtilities.isEventDispatchThread()) prompt.run(); else SwingUtilities.invokeAndWait(prompt); }
        catch (Exception failure) { return false; }
        return accepted.get();
    }
}
