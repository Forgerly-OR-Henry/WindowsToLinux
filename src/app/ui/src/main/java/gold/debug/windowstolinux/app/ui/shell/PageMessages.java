package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.message.LocalizedFailure;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import java.awt.Component;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Shared localization and diagnostic formatting for independent page controllers. / 独立页面控制器共享的本地化与诊断格式化。 */
public final class PageMessages {
    private final MessageCatalog messages;

    /** Creates page message support. / 创建页面消息支持。 */
    public PageMessages(MessageCatalog messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** Resolves one message key. / 解析一项消息键。 */
    public String text(String key) { return messages.text(key); }
    /** Resolves one parameterized message key. / 解析一项带参数消息键。 */
    public String text(String key, Map<String, ?> arguments) { return messages.text(key, arguments); }
    /** Returns the selected catalog. / 返回已选消息目录。 */
    public MessageCatalog catalog() { return messages; }

    /** Formats a safe user-facing exception while preserving raw diagnostics. / 格式化安全的用户异常并保留原始诊断。 */
    public String safe(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof LocalizedFailure failure) {
                return localized(failure.userMessage(), failure.diagnostic());
            }
            current = current.getCause();
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? text("diagnostic.unknown") : message;
    }

    /** Formats a localized message with optional raw diagnostic text. / 格式化本地化消息与可选原始诊断文本。 */
    public String localized(gold.debug.windowstolinux.shared.model.message.LocalizedMessage message, String diagnostic) {
        String headline = messages.text(message);
        return diagnostic == null || diagnostic.isBlank() ? headline : headline + "\n" + diagnostic;
    }

    /** Formats a lifecycle observation. / 格式化生命周期观测。 */
    public String lifecycle(LifecycleObservation observation) {
        if (observation == null) {
            return text("lifecycle.noObservation");
        }
        return text("lifecycle.observation", Map.of(
                "runtime", text("runtime.state." + observation.runtimeState().name().toLowerCase(Locale.ROOT)),
                "autostart", text("autostart.state." + observation.autostartState().name().toLowerCase(Locale.ROOT)),
                "ownership", text(observation.ownershipVerified() ? "ownership.verified" : "ownership.unverified"),
                "evidence", observation.evidence()));
    }

    /** Installs a localized enum renderer. / 安装本地化枚举渲染器。 */
    public <T extends Enum<T>> void localize(JComboBox<T> comboBox, String prefix) {
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                           boolean selected, boolean focus) {
                Object label = value instanceof Enum<?> item ? text(prefix + item.name().toLowerCase(Locale.ROOT)) : value;
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        });
    }
}
