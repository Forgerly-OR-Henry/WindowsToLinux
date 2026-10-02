package gold.debug.windowstolinux.app.ui.error;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import gold.debug.windowstolinux.app.ui.language.LocalizedMessage;
import gold.debug.windowstolinux.appapi.task.Problem;

/**
 * Converts task failures into safe localized UI text and report references. / 将任务失败转换为安全的本地化 UI 文本和报告引用。
 */
public final class DesktopFailurePresenter {
    /**
     * Pattern matching secret-like assignments for redaction.
     * <p>匹配疑似秘密赋值以供脱敏的模式。
     */
    private static final java.util.regex.Pattern SENSITIVE_ASSIGNMENT = java.util.regex.Pattern
        .compile("(?i)(password|passphrase|private[-_ ]?key|api[-_ ]?key|secret|token)\\s*[:=]\\s*[^\\s,;]+");

    /**
     * Pattern matching bearer authorization material for redaction.
     * <p>匹配 bearer 授权素材以供脱敏的模式。
     */
    private static final java.util.regex.Pattern BEARER = java.util.regex.Pattern
        .compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");

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
        Problem descriptor = recorded.map(FailureReportRecord::failure)
            .orElseGet(() -> findStructured(root).orElse(null));
        return render(descriptor, recorded);
    }

    /** Displays an existing public snapshot without another report write. / 展示既有公开快照且不再次写入报告。
     * @param problem original safe platform diagnostic / 原安全平台诊断
     * @return localized explanation, event and original identity / 本地化说明、事件及原身份 */
    public String present(gold.debug.windowstolinux.appapi.task.Problem problem) {
        return redact(explanation(problem)) + "\n[" + redact(problem.eventCode()) + "]"
            + problem.diagnosticId().map(id -> "\n" + id).orElse("");
    }

    /** Reports the original exception while supplying already classified startup evidence. / 报告原始异常并提供已分类启动依据。
     * @param throwable original exception preserving its causes / 保留原因链的原异常
     * @param problem original classified evidence / 原分类依据
     * @return localized diagnostic and retained report reference / 本地化诊断及保留报告引用
     * @throws NullPointerException if an input is missing / 输入缺失时 */
    public String present(Throwable throwable, Problem problem) {
        Objects.requireNonNull(problem, "problem");
        return render(problem, reports.record(Objects.requireNonNull(throwable, "throwable")));
    }

    /** Renders concrete platform recovery metadata without replacing its diagnostic identity. / 展示具体平台恢复元数据且不替换其诊断身份。
     * @param problem original classified evidence, or null / 原分类依据或null
     * @param recorded best-effort report reference / 尽力保存的报告引用
     * @return redacted localized presentation / 脱敏本地化展示 */
    private String render(Problem problem, Optional<FailureReportRecord> recorded) {
        if (problem == null)
            return text("diagnostic.unknown");
        String report = recorded.flatMap(FailureReportRecord::reportPath)
            .or(() -> recorded.map(FailureReportRecord::diagnosticsDirectory)).map(Path::toString)
            .orElseGet(() -> reports.diagnosticsDirectory().map(Path::toString)
                .orElse(text("failure.report.unavailable")));
        String disposition = problem.context().getOrDefault("recoveryDisposition", "NOT_ATTEMPTED");
        return text("failure.presentation",
            Map.of("message", redact(explanation(problem)), "code", redact(problem.eventCode()), "operationId",
                problem.diagnosticId().map(Object::toString).orElse(""), "summary", redact(problem.message()),
                "recovery", text("failure.recovery." + disposition.toLowerCase(Locale.ROOT)), "report",
                report));
    }

    /** Resolves registered metadata or a public event translation without requiring author-defined events in the host catalog. / 解析登记元数据或公开事件翻译，不要求宿主目录包含作者自定义事件。
     * @param problem original safe diagnostic / 原安全诊断
     * @return localized explanation or original safe message / 本地化说明或原安全消息 */
    private String explanation(Problem problem) {
        var arguments = new java.util.LinkedHashMap<>(problem.context());
        problem.context().forEach((key, value) -> {
            if (key.startsWith("argument."))
                arguments.put(key.substring("argument.".length()), value);
        });
        String key = problem.context().getOrDefault("messageKey", "problem." + problem.eventCode());
        try {
            String localized = text(key, arguments);
            return localized.equals(key) ? problem.message() : localized;
        } catch (IllegalArgumentException untranslated) {
            return problem.message();
        }
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
        return reports.diagnosticsDirectory().map(Path::toString).orElse(text("failure.report.unavailable"));
    }

    /**
     * Finds structured.
     * <p>查找结构化。
     *
     * @param throwable throwable / 异常原因
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Problem> findStructured(Throwable throwable) {
        Throwable current = throwable;
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && visited.size() < 16 && visited.add(current)) {
            if (current instanceof gold.debug.windowstolinux.appapi.task.ApiException failure)
                return Optional.of(failure.problem());
            if (current instanceof gold.debug.windowstolinux.app.service.error.ApplicationServiceException failure)
                return Optional.of(failure.problem());
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
            || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
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
