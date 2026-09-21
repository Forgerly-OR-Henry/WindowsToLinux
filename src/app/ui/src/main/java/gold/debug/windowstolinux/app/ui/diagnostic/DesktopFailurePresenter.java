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

/**
 * Converts task failures into safe localized UI text and report references. / 将任务失败转换为安全的本地化 UI 文本和报告引用。
 */
public final class DesktopFailurePresenter {
    /**
     * Pattern matching secret-like assignments for redaction.
     * <p>匹配疑似秘密赋值以供脱敏的模式。
     */
    private static final java.util.regex.Pattern SENSITIVE_ASSIGNMENT = java.util.regex.Pattern.compile(
            "(?i)(password|passphrase|private[-_ ]?key|api[-_ ]?key|secret|token)\\s*[:=]\\s*[^\\s,;]+");
    /**
     * Pattern matching bearer authorization material for redaction.
     * <p>匹配 bearer 授权素材以供脱敏的模式。
     */
    private static final java.util.regex.Pattern BEARER = java.util.regex.Pattern.compile(
            "(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    /**
     * Localized message resolver.
     * <p>本地化消息解析器。
     */
    private final Function<LocalizedMessage, String> messages;
    /**
     * Bound failure report store collaborator for reports.
     * <p>处理报告集合的失败报告存储协作对象。
     */
    private final FailureReportStore reports;

    /**
     * Creates the desktop failure presenter. / 创建桌面失败呈现器。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @param reports reports / 报告集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopFailurePresenter(Function<LocalizedMessage, String> messages, FailureReportStore reports) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    /**
     * Formats a classified failure through the existing redaction, localization and bounded report path. Unknown exception messages are not used as user-facing diagnostics.
     * <p>通过既有脱敏、本地化及有界报告路径格式化分类失败。不将未知异常消息用作用户可见诊断。
     *
     * @param throwable throwable / 异常原因
     * @return a classified failure through the existing redaction, localization and bounded report path / 通过既有脱敏、本地化及有界报告路径格式化分类失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Formats a classified failure through the existing redaction, localization and bounded report path. Unknown exception messages are not used as user-facing diagnostics.
     * <p>通过既有脱敏、本地化及有界报告路径格式化分类失败。不将未知异常消息用作用户可见诊断。
     *
     * @param descriptor descriptor / 描述符
     * @return a classified failure through the existing redaction, localization and bounded report path / 通过既有脱敏、本地化及有界报告路径格式化分类失败
     */
    public String present(FailureDescriptor descriptor) {
        return present(new SnapshotFailureException(descriptor));
    }

    /**
     * Adapts a structured snapshot to the existing safe diagnostic report interface.
     * <p>将结构化快照适配到既有安全诊断报告接口。
     */
    private static final class SnapshotFailureException extends RuntimeException implements FailureCarrier {
        /**
         * Structured failure occurrence retained for safe reporting.
         * <p>保留用于安全报告的结构化失败实例。
         */
        private final FailureDescriptor failure;
        /**
         * Validates and binds the inputs required by snapshot failure exception.
         * <p>校验并绑定快照失败异常所需输入。
         *
         * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        private SnapshotFailureException(FailureDescriptor failure) {
            super("Structured recovery snapshot"); this.failure = Objects.requireNonNull(failure, "failure");
        }
        /**
         * Returns structured failure occurrence retained for safe reporting.
         * <p>返回保留用于安全报告的结构化失败实例。
         *
         * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
         */
        @Override public FailureDescriptor failure() { return failure; }
    }

    /**
     * Opens the diagnostic directory when the platform supports it. / 平台支持时打开诊断目录。
     *
     * @return true when opens the diagnostic directory when the platform supports it, false otherwise / 平台支持时打开诊断目录时为 true，否则为 false
     */
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

    /**
     * Returns a copyable diagnostic directory path. / 返回可复制的诊断目录路径。
     *
     * @return a copyable diagnostic directory path / 可复制的诊断目录路径
     */
    public String diagnosticsPath() {
        return reports.diagnosticsDirectory().map(Path::toString)
                .orElse(text("failure.report.unavailable"));
    }

    /**
     * Finds structured.
     * <p>查找结构化。
     *
     * @param throwable throwable / 异常原因
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
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

    /**
     * Unwraps completion and execution wrappers while preserving the underlying failure.
     * <p>解开完成及执行异常包装，并保留底层失败。
     *
     * @param throwable throwable / 异常原因
     * @return constructed or resolved throwable / 构造或解析得到的异常原因
     */
    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Redacts sensitive content from desktop failure presenter.
     * <p>脱敏Desktop失败展示器。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return redact text / 脱敏文本
     */
    private static String redact(String diagnostic) {
        return BEARER.matcher(SENSITIVE_ASSIGNMENT.matcher(diagnostic).replaceAll("$1=[REDACTED]"))
                .replaceAll("Bearer [REDACTED]");
    }

    /**
     * Resolves a localized message and substitutes its named arguments.
     * <p>解析本地化消息并替换其具名参数。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return a localized message and substitutes its named arguments / 本地化消息并替换其具名参数
     */
    private String text(String key) {
        return messages.apply(LocalizedMessage.of(key));
    }

    /**
     * Resolves a localized message and substitutes its named arguments.
     * <p>解析本地化消息并替换其具名参数。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @return a localized message and substitutes its named arguments / 本地化消息并替换其具名参数
     */
    private String text(String key, Map<String, ?> arguments) {
        return messages.apply(LocalizedMessage.of(key, arguments));
    }
}
