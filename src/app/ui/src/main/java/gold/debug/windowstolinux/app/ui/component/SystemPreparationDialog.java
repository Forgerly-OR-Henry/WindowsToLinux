package gold.debug.windowstolinux.app.ui.component;

import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shares an explicit, default-declined system-change dialog across product entry points. / 在产品入口间共用默认拒绝的显式系统变更对话框。 */
public final class SystemPreparationDialog {
    private SystemPreparationDialog() { }

    public static boolean confirm(Component owner, PageMessagePresenter messages, Map<String, ?> details) {
        return show(owner, messages, messages.text("environment.system.title"), () -> content(messages, details));
    }

    /** Confirms package preparation before it can change the target. / 在软件包准备变更目标之前获得确认。 */
    public static boolean confirmEnvironment(Component owner, PageMessagePresenter messages, Map<String, ?> details) {
        return show(owner, messages, messages.text("environment.confirm.title"),
                () -> description(messages.text("environment.confirm", details)));
    }

    private static boolean show(Component owner, PageMessagePresenter messages, String title,
                                java.util.function.Supplier<JTextArea> content) {
        if (Thread.currentThread().isInterrupted()) return false;
        AtomicBoolean accepted = new AtomicBoolean();
        Runnable display = () -> {
            Object[] options = {messages.text("environment.system.accept"), messages.text("environment.system.cancel")};
            javax.swing.JScrollPane description = new javax.swing.JScrollPane(content.get());
            description.setBorder(null);
            accepted.set(JOptionPane.showOptionDialog(owner, description,
                    title, JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, options, options[1]) == 0);
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) display.run();
            else SwingUtilities.invokeAndWait(display);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.lang.reflect.InvocationTargetException failed) {
            return false;
        }
        return accepted.get();
    }

    static JTextArea content(PageMessagePresenter messages, Map<String, ?> details) {
        String action = Boolean.TRUE.equals(details.get("reboot"))
                ? messages.text("environment.system.action.reboot") : messages.text("environment.system.action.finish");
        return description(messages.text("environment.system.confirm", Map.of(
                "serverId", details.get("serverId"), "host", details.get("host"), "action", action,
                "security", messages.text("environment.system.security." + details.get("security")))));
    }

    private static JTextArea description(String message) {
        JTextArea text = new JTextArea(message, 12, 48);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setFont(javax.swing.UIManager.getFont("Label.font"));
        return text;
    }
}
