package gold.debug.windowstolinux.app.ui.i18n;

import gold.debug.windowstolinux.app.ui.diagnostic.DesktopFailurePresenter;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import java.awt.Component;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Shared localization and diagnostic formatting for independent page controllers. / 独立页面控制器共享的本地化与诊断格式化。 */
public final class PageMessagePresenter {
    private final MessageCatalog messages;
    private final DesktopFailurePresenter failures;

    /** Creates page message support. / 创建页面消息支持。 */
    public PageMessagePresenter(MessageCatalog messages) {
        this(messages, FailureReportStore.disabled());
    }

    /** Creates page message support backed by safe local diagnostics. / 创建由安全本地诊断支持的页面消息支持。 */
    public PageMessagePresenter(MessageCatalog messages, FailureReportStore reports) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.failures = new DesktopFailurePresenter(messages::text, reports);
    }

    /** Resolves one message key. / 解析一项消息键。 */
    public String text(String key) { return messages.text(key); }
    /** Resolves one parameterized message key. / 解析一项带参数消息键。 */
    public String text(String key, Map<String, ?> arguments) { return messages.text(key, arguments); }
    /** Returns the selected catalog. / 返回已选消息目录。 */
    public MessageCatalog catalog() { return messages; }

    /** Formats a safe user-facing exception and records bounded diagnostics. / 格式化安全用户异常并记录有界诊断。 */
    public String safe(Exception exception) {
        String diagnostic = failures.present(exception);
        Throwable root = exception;
        while (root.getCause() != null) root = root.getCause();
        if (root instanceof gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort.DatabaseFailure failure)
            return text("db.failure." + failure.reason().name().toLowerCase(Locale.ROOT));
        return diagnostic;
    }

    /** Localizes display choices while preserving the submitted protocol value. */
    public String inputChoice(gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField field, String value) {
        if (value.isBlank()) return field.labelKey().equals("db.field.initialize") ? text("db.initialize.none") : value;
        return switch (field.labelKey()) {
            case "auto.field.type" -> text("project.type."+value.toLowerCase(Locale.ROOT));
            case "db.field.engine" -> text("db.engine."+value.toLowerCase(Locale.ROOT));
            case "field.healthMode" -> text("health.mode." + value.toLowerCase(Locale.ROOT));
            case "field.containerEngine" -> text("container.engine." + value.toLowerCase(Locale.ROOT));
            case "field.databaseReviewMode" -> text("database.review.mode." + value.toLowerCase(Locale.ROOT));
            default -> value;
        };
    }

    /** Formats a localized message with optional raw diagnostic text. / 格式化本地化消息与可选原始诊断文本。 */
    public String localized(gold.debug.windowstolinux.shared.model.message.LocalizedMessage message, String diagnostic) {
        String headline = messages.text(message);
        return diagnostic == null || diagnostic.isBlank() ? headline : headline + "\n" + diagnostic;
    }

    /** Opens the diagnostic directory when supported. / 平台支持时打开诊断目录。 */
    public boolean openDiagnosticsDirectory() { return failures.openDiagnosticsDirectory(); }

    /** Returns the copyable diagnostic directory path. / 返回可复制的诊断目录路径。 */
    public String diagnosticsPath() { return failures.diagnosticsPath(); }

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
            /** Performs the {@code getListCellRendererComponent} operation. / 执行 {@code getListCellRendererComponent} 操作。 */
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                           boolean selected, boolean focus) {
                Object label = value instanceof Enum<?> item ? text(prefix + item.name().toLowerCase(Locale.ROOT)) : value;
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        });
    }
}
