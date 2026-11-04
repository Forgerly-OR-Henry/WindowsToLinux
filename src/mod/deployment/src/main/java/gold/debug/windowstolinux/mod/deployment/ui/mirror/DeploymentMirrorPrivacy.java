package gold.debug.windowstolinux.mod.deployment.ui.mirror;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.ResourceBundle;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentDialogs;

/** Explicit consent for region detection, independent of deployment authorization. */
public final class DeploymentMirrorPrivacy {
    private DeploymentMirrorPrivacy() { }

    public static boolean confirm(Component owner, ResourceBundle messages) {
        if (GraphicsEnvironment.isHeadless()) return false;
        if (!SwingUtilities.isEventDispatchThread()) {
            var question = new FutureTask<>(() -> confirm(owner, messages));
            try {
                SwingUtilities.invokeAndWait(question);
                return question.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception unavailable) {
                throw new IllegalStateException("Mirror privacy dialog failed", unavailable);
            }
        }
        String enable = messages.getString("privacy.enable");
        var options = new JOptionPane(null, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION,
            null, new Object[]{enable, messages.getString("privacy.cancel")}, messages.getString("privacy.cancel"));
        var text = new JTextArea(messages.getString("privacy.text"));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        var dialog = DeploymentDialogs.option(owner, messages.getString("privacy.title"), text, options, 580, 300);
        try {
            dialog.setLocationRelativeTo(owner);
            dialog.setVisible(true);
            return enable.equals(options.getValue());
        } finally {
            dialog.dispose();
        }
    }
}
