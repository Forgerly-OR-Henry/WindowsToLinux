package gold.debug.windowstolinux.app.ui.component;

import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shares an explicit, default-declined system-change dialog across product entry points. / 在产品入口间共用默认拒绝的显式系统变更对话框。
 */
public final class SystemPreparationDialog {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SystemPreparationDialog() { }

    /**
     * Confirms system preparation dialog.
     * <p>确认系统准备对话框。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param messages localized message resolver / 本地化消息解析器
     * @param details details / 详情
     * @return true when confirms system preparation dialog, false otherwise / 确认系统准备对话框时为 true，否则为 false
     */
    public static boolean confirm(Component owner, PageMessagePresenter messages, Map<String, ?> details) {
        return show(owner, messages, messages.text("environment.system.title"), () -> content(messages, details));
    }

    /**
     * Confirms package preparation before it can change the target. / 在软件包准备变更目标之前获得确认。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param messages localized message resolver / 本地化消息解析器
     * @param details details / 详情
     * @return true when confirms package preparation before it can change the target, false otherwise / 在软件包准备变更目标之前获得确认时为 true，否则为 false
     */
    public static boolean confirmEnvironment(Component owner, PageMessagePresenter messages, Map<String, ?> details) {
        return show(owner, messages, messages.text("environment.confirm.title"),
                () -> description(messages.text("environment.confirm", details)));
    }

    /**
     * Shows the system-preparation consent dialog on the Swing event thread and returns the user's decision.
     * <p>在 Swing 事件线程显示系统准备同意对话框，并返回用户决定。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param messages localized message resolver / 本地化消息解析器
     * @param title title / 标题
     * @param content content / 内容
     * @return true when shows the system-preparation consent dialog on the Swing event thread and returns the user's decision, false otherwise / 在 Swing 事件线程显示系统准备同意对话框，并返回用户决定时为 true，否则为 false
     */
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

    /**
     * Formats system-preparation details and the required completion or reboot action.
     * <p>格式化系统准备详情及所需完成或重启动作。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @param details details / 详情
     * @return system-preparation details and the required completion or reboot action / 系统准备详情及所需完成或重启动作
     */
    static JTextArea content(PageMessagePresenter messages, Map<String, ?> details) {
        String action = Boolean.TRUE.equals(details.get("reboot"))
                ? messages.text("environment.system.action.reboot") : messages.text("environment.system.action.finish");
        return description(messages.text("environment.system.confirm", Map.of(
                "serverId", details.get("serverId"), "host", details.get("host"), "action", action,
                "security", messages.text("environment.system.security." + details.get("security")))));
    }

    /**
     * Creates a read-only wrapping text area styled as dialog explanatory content.
     * <p>创建只读自动换行文本区，并设置为对话框说明内容样式。
     *
     * @param message localized explanation / 本地化说明
     * @return a read-only wrapping text area styled as dialog explanatory content / 只读自动换行文本区，并设置为对话框说明内容样式
     */
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
