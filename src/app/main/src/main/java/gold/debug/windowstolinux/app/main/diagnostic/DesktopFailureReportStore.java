package gold.debug.windowstolinux.app.main.diagnostic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportRecord;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.appapi.task.Problem;

/**
 * Writes bounded, rotated, secret-free UTF-8 failure reports under the fixed data directory. / 在固定数据目录下写入有界、轮转且无秘密的 UTF-8 失败报告。
 */
public final class DesktopFailureReportStore implements FailureReportStore {
    /**
     * MAX REPORT BYTES.
     * <p>最大报告字节。
     */
    static final int MAX_REPORT_BYTES = 256 * 1024;

    /**
     * MAX REPORTS.
     * <p>最大报告集合。
     */
    static final int MAX_REPORTS = 50;

    /**
     * FILE TIME.
     * <p>文件时间。
     */
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /**
     * Pattern recognizing pattern matching secret-like assignments for redaction.
     * <p>用于识别匹配疑似秘密赋值以供脱敏的模式的匹配模式。
     */
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern
            .compile("(?i)(password|passphrase|private[-_ ]?key|api[-_ ]?key|secret|token)\\s*[:=]\\s*[^\\s,;]+");

    /**
     * Pattern recognizing pattern matching bearer authorization material for redaction.
     * <p>用于识别匹配 bearer 授权素材以供脱敏的模式的匹配模式。
     */
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");

    /**
     * Directory within the caller's controlled storage boundary.
     * <p>调用方受控存储边界内的目录。
     */
    private final Path directory;

    /**
     * Creates the report store and performs startup rotation best-effort. / 创建报告存储并在启动时尽力轮转。
     *
     * @param dataDirectory data directory / 数据目录
     */
    public DesktopFailureReportStore(Path dataDirectory) {
        directory = dataDirectory.toAbsolutePath().normalize().resolve("error-logs");
        try {
            Files.createDirectories(directory);
            rotate();
        } catch (IOException ignored) {
            // Reporting must never recursively report its own write failure. / 报告功能绝不能递归报告自身写入失败。
        }
    }

    /**
     * Records one report best-effort and never throws. / 尽力记录一份报告且绝不抛出异常。
     *
     * @param throwable throwable / 异常原因
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    @Override
    public synchronized Optional<FailureReportRecord> record(Throwable throwable) {
        if (throwable == null) {
            return Optional.empty();
        }
        Problem descriptor = structured(throwable).orElseGet(() -> unknownDescriptor(throwable));
        Optional<Path> report = Optional.empty();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            rotate();
            byte[] content = boundedReport(descriptor, throwable);
            temporary = Files.createTempFile(directory, ".failure-", ".tmp");
            Files.write(temporary, content);
            Path target = directory.resolve(FILE_TIME.format(Instant.now()) + "-"
                    + descriptor.diagnosticId().orElseGet(java.util.UUID::randomUUID) + ".txt");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.deleteIfExists(temporary);
                temporary = null;
                throw exception;
            }
            temporary = null;
            rotate();
            report = Optional.of(target);
        } catch (IOException | RuntimeException ignored) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException alsoIgnored) {
                    // No recursive report attempt. / 不递归尝试报告。
                }
            }
        }
        return Optional.of(new FailureReportRecord(descriptor, directory, report));
    }

    /**
     * Returns diagnostics directory.
     * <p>返回诊断目录。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    @Override
    public Optional<Path> diagnosticsDirectory() {
        return Optional.of(directory);
    }

    /**
     * Removes the oldest diagnostic text reports beyond the retention limit.
     * <p>删除超出保留上限的最旧诊断文本报告。
     *
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private void rotate() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> reports;
        try (var files = Files.list(directory)) {
            reports = files.filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        int excess = reports.size() - MAX_REPORTS;
        for (int index = 0; index < excess; index++) {
            Files.deleteIfExists(reports.get(index));
        }
    }

    /**
     * Builds a size-bounded diagnostic report from classified and redacted failure evidence.
     * <p>根据分类且脱敏的失败证据构建大小有界的诊断报告。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param throwable throwable / 异常原因
     * @return a size-bounded diagnostic report from classified and redacted failure evidence / 根据分类且脱敏的失败证据构建大小有界的诊断报告
     */
    private static byte[] boundedReport(Problem failure, Throwable throwable) {
        StringBuilder report = new StringBuilder(4096);
        append(report, "timestamp", Instant.now().toString());
        appendProblem(report, "", failure);
        var pending = new java.util.ArrayDeque<Throwable>();
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        pending.add(throwable);
        int causeIndex = 0;
        while (!pending.isEmpty() && causeIndex < 16) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current))
                continue;
            append(report, "exceptionClass[" + causeIndex + "]", current.getClass().getName());
            int evidenceIndex = causeIndex;
            directProblem(current)
                    .ifPresent(problem -> appendProblem(report, "problem[" + evidenceIndex + "].", problem));
            StackTraceElement[] frames = current.getStackTrace();
            for (int index = 0; index < frames.length && index < 256; index++) {
                StackTraceElement frame = frames[index];
                append(report, "frame[" + causeIndex + "][" + index + "]", frame.getClassName() + "."
                        + frame.getMethodName() + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")");
            }
            if (current.getCause() != null)
                pending.addLast(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) {
                if (pending.size() >= 16)
                    break;
                append(report, "suppressedBy[" + causeIndex + "]", suppressed.getClass().getName());
                pending.addLast(suppressed);
            }
            causeIndex++;
        }
        return truncateUtf8(report.toString());
    }

    /**
     * Appends desktop failure report store.
     * <p>追加Desktop失败报告存储。
     *
     * @param report report / 报告
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void append(StringBuilder report, String key, String value) {
        report.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    /**
     * Truncates report text to the UTF-8 byte bound while appending the fixed truncation marker.
     * <p>将报告文本截断到 UTF-8 字节边界，并追加固定截断标记。
     *
     * @param content content / 内容
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    private static byte[] truncateUtf8(String content) {
        byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= MAX_REPORT_BYTES) {
            return encoded;
        }
        String marker = "\nreportTruncated=true\n";
        int low = 0;
        int high = content.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int bytes = (content.substring(0, middle) + marker).getBytes(StandardCharsets.UTF_8).length;
            if (bytes <= MAX_REPORT_BYTES) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return (content.substring(0, low) + marker).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Redacts sensitive content from desktop failure report store.
     * <p>脱敏Desktop失败报告存储。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return redact text / 脱敏文本
     */
    private static String redact(String diagnostic) {
        return BEARER.matcher(SENSITIVE_ASSIGNMENT.matcher(diagnostic).replaceAll("$1=[REDACTED]"))
                .replaceAll("Bearer [REDACTED]");
    }

    /**
     * Finds admitted public evidence in a bounded causal chain without arbitrary exception messages.
     * <p>在有界原因链查找已准入公开证据，不暴露任意异常消息。
     *
     * @param throwable throwable / 异常原因
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    private static Optional<Problem> structured(Throwable throwable) {
        Throwable current = throwable;
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && visited.size() < 16 && visited.add(current)) {
            var admitted = directProblem(current);
            if (admitted.isPresent())
                return admitted;
            current = current.getCause();
        }
        return Optional.empty();
    }

    /**
     * Builds the structured failure descriptor for unknown descriptor.
     * <p>为未知描述符构建结构化失败描述。
     *
     * @param throwable throwable / 异常原因
     * @return the structured failure descriptor for unknown descriptor / 为未知描述符构建结构化失败描述
     */
    private static Problem unknownDescriptor(Throwable throwable) {
        DesktopSystemFailureType type = throwable instanceof VirtualMachineError
                ? DesktopSystemFailureType.RESOURCE_EXHAUSTED
                : throwable instanceof LinkageError
                        ? DesktopSystemFailureType.UI_INITIALIZATION_FAILED
                        : DesktopSystemFailureType.UNKNOWN_RUNTIME_FAILURE;
        String diagnostic = type == DesktopSystemFailureType.RESOURCE_EXHAUSTED
                ? "The JVM reported a fatal resource failure and cannot safely continue"
                : type == DesktopSystemFailureType.UI_INITIALIZATION_FAILED
                        ? "A required runtime linkage could not be initialized"
                        : "An unexpected desktop runtime failure was captured without exposing its message";
        return type.problem(java.util.UUID.randomUUID(), diagnostic);
    }

    /** Projects only concrete platform exception contracts. / 仅投影具体平台异常契约。
     * @param throwable original exception / 原始异常
     * @return original public evidence when supported / 受支持时返回原公开证据 */
    private static Optional<Problem> directProblem(Throwable throwable) {
        if (throwable instanceof DesktopStartupException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.appapi.task.ApiException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.service.contract.result.ApplicationServiceException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.db.failure.DesktopPersistenceException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.windows.update.DesktopUpdateException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.windows.uninstall.DesktopUninstallException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.windows.workspace.WindowsWorkspaceException failure)
            return Optional.of(failure.problem());
        return Optional.empty();
    }

    /** Writes redacted classification and context with the original diagnostic UUID. / 写入脱敏分类及上下文并保留原诊断UUID。
     * @param report bounded report builder / 有界报告构建器
     * @param prefix evidence location / 证据位置
     * @param problem original safe diagnostic / 原安全诊断 */
    private static void appendProblem(StringBuilder report, String prefix, Problem problem) {
        append(report, prefix + "code", problem.eventCode());
        append(report, prefix + "category", problem.code().name());
        append(report, prefix + "operationId", problem.diagnosticId().map(Object::toString).orElse(""));
        append(report, prefix + "diagnostic", redact(problem.message()));
        problem.context().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(
                entry -> append(report, prefix + "context." + redact(entry.getKey()), redact(entry.getValue())));
    }
}
