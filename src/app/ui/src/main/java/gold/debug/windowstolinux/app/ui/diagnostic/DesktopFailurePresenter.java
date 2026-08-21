package gold.debug.windowstolinux.app.ui.diagnostic;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Converts task failures into safe localized UI text and report references. / 将任务失败转换为安全的本地化 UI 文本和报告引用。 */
public final class DesktopFailurePresenter {
    private static final java.util.regex.Pattern SENSITIVE_ASSIGNMENT = java.util.regex.Pattern.compile(
            "(?i)(password|passphrase|private[-_ ]?key|api[-_ ]?key|secret|token)\\s*[:=]\\s*[^\\s,;]+");
    private static final java.util.regex.Pattern BEARER = java.util.regex.Pattern.compile(
            "(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private final Function<LocalizedMessage, String> messages;
    private final FailureReportStore reports;

    /** Creates the desktop failure presenter. / 创建桌面失败呈现器。 */
    public DesktopFailurePresenter(Function<LocalizedMessage, String> messages, FailureReportStore reports) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    /** Presents a failure without exposing an unknown exception message. / 呈现失败且不暴露未知异常消息。 */
    public String present(Throwable throwable) {
        Throwable root = unwrap(Objects.requireNonNull(throwable, "throwable"));
        Optional<FailureReportRecord> recorded = reports.record(root);
        FailureDescriptor descriptor = recorded.map(FailureReportRecord::failure)
                .orElseGet(() -> findStructured(root).orElse(null));
        if (descriptor == null) {
            return text("diagnostic.unknown");
        }
        String report = recorded.flatMap(FailureReportRecord::reportPath)
                .or(() -> recorded.map(FailureReportRecord::diagnosticsDirectory))
                .map(Path::toString)
                .orElseGet(() -> reports.diagnosticsDirectory().map(Path::toString)
                        .orElse(text("failure.report.unavailable")));
        return text("failure.presentation", Map.of(
                "message", messages.apply(descriptor.userMessage()),
                "code", descriptor.code(),
                "operationId", descriptor.operationIdentity().toString(),
                "summary", redact(descriptor.diagnostic()),
                "recovery", text("failure.recovery."
                        + descriptor.recoveryDisposition().name().toLowerCase(Locale.ROOT)),
                "report", report));
    }

    /** Opens the diagnostic directory when the platform supports it. / 平台支持时打开诊断目录。 */
    public boolean openDiagnosticsDirectory() {
        Optional<Path> directory = reports.diagnosticsDirectory();
        if (directory.isEmpty() || !Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return false;
        }
        try {
            Desktop.getDesktop().open(directory.orElseThrow().toFile());
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /** Returns a copyable diagnostic directory path. / 返回可复制的诊断目录路径。 */
    public String diagnosticsPath() {
        return reports.diagnosticsDirectory().map(Path::toString)
                .orElse(text("failure.report.unavailable"));
    }

    private static Optional<FailureDescriptor> findStructured(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof FailureCarrier carrier) {
                return Optional.of(carrier.failure());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String redact(String diagnostic) {
        return BEARER.matcher(SENSITIVE_ASSIGNMENT.matcher(diagnostic).replaceAll("$1=[REDACTED]"))
                .replaceAll("Bearer [REDACTED]");
    }

    private String text(String key) {
        return messages.apply(LocalizedMessage.of(key));
    }

    private String text(String key, Map<String, ?> arguments) {
        return messages.apply(LocalizedMessage.of(key, arguments));
    }
}
