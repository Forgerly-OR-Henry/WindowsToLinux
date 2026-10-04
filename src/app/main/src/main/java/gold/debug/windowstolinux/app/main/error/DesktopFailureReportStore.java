package gold.debug.windowstolinux.app.main.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.log.EmergencyLogs;
import gold.debug.windowstolinux.app.db.log.LogRecordCodec;
import gold.debug.windowstolinux.app.db.log.LogStore;
import gold.debug.windowstolinux.app.service.log.PlatformLogService;
import gold.debug.windowstolinux.app.ui.error.FailureReportRecord;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.log.LogStorage;
import gold.debug.windowstolinux.appapi.task.result.Problem;

/**
 * Routes bounded host failures through the existing database or startup emergency queue. / 将有界宿主失败送入既有数据库或启动应急队列。
 */
public final class DesktopFailureReportStore implements FailureReportStore {
    /**
     * MAX REPORT BYTES.
     * <p>最大报告字节。
     */
    static final int MAX_REPORT_BYTES = LogRecordCodec.MAX_BYTES;

    /**
     * MAX REPORTS.
     * <p>最大报告集合。
     */
    static final int MAX_REPORTS = 100;

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

    /** Shared fixed-root startup emergency store. / 共用固定根启动应急存储。 */
    private final EmergencyLogs emergency;

    /** Exact producer version, unavailable before identity can be read. / 精确生产方版本，身份不能读取时明确不可用。 */
    private String version;

    /** Live host log service after database initialization. / 数据库初始化后的宿主日志服务。 */
    private PlatformLogService logs;

    /** Binds an already confirmed data root without creating a fallback directory. / 绑定已确认数据根，不创建备用目录。
     * @param dataDirectory fixed data root / 固定数据根 */
    public DesktopFailureReportStore(Path dataDirectory) {
        this(dataDirectory, "unavailable");
    }

    /** Binds startup diagnostics to the original data root and build identity. / 将启动诊断绑定原数据根和构建身份。
     * @param dataDirectory fixed data root / 固定数据根
     * @param version actual version or explicit unavailable identity / 实际版本或明确不可用身份 */
    public DesktopFailureReportStore(Path dataDirectory, String version) {
        this(dataDirectory, version, 100);
    }

    /** Captures configured emergency retention. / 捕获配置的应急保留数量。
     * @param dataDirectory selected root / 数据根
     * @param version producer identity / 生产者身份
     * @param retention records per category / 每类数量 */
    public DesktopFailureReportStore(Path dataDirectory, String version, int retention) {
        directory = dataDirectory.toAbsolutePath().normalize().resolve("log").resolve("emergency");
        emergency = new EmergencyLogs(dataDirectory, retention);
        this.version = java.util.Objects.requireNonNull(version);
    }

    /** Switches subsequent failures to the initialized host logger without copying old reports. / 将随后失败切换到已初始化宿主日志器，不复制旧报告。
     * @param logs existing host log service / 既有宿主日志服务
     * @param version confirmed producer version / 已确认生产方版本 */
    public synchronized void attach(PlatformLogService logs, String version) {
        this.logs = java.util.Objects.requireNonNull(logs);
        this.version = java.util.Objects.requireNonNull(version);
    }

    /** Preserves original structured evidence and returns only confirmed file references. / 保留原结构化证据，仅返回已确认文件引用。
     * @param throwable original failure / 原失败
     * @return original classification and observed report reference / 原分类及已观察报告引用 */
    @Override
    public synchronized Optional<FailureReportRecord> record(Throwable throwable) {
        if (throwable == null)
            return Optional.empty();
        Problem descriptor = structured(throwable).orElseGet(() -> unknownDescriptor(throwable));
        return write(descriptor, throwable).or(() -> Optional.of(new FailureReportRecord(descriptor, directory, Optional.empty())));
    }

    /** Saves bounded callback evidence and returns only an actually persisted identifier. / 保存有界回调依据，仅返回实际持久化编号。
     * @param throwable original exception / 原异常
     * @param problem fixed public host diagnosis / 固定公开宿主诊断
     * @return saved identifier, empty when all storage fails / 已保存编号，全部存储失败时为空 */
    public synchronized Optional<java.util.UUID> recordDiagnostic(Throwable throwable, Problem problem) {
        var descriptor = new Problem(problem.code(), problem.eventCode(), problem.message(), problem.context(),
            Optional.of(java.util.UUID.randomUUID()));
        return write(descriptor, throwable).flatMap(record -> record.failure().diagnosticId());
    }

    /** Writes through the existing durable and emergency channels. / 经既有持久及应急通道写入。
     * @param descriptor safe public identity / 安全公开身份
     * @param throwable original bounded stack evidence / 原有界堆栈依据
     * @return receipt only after actual storage / 仅实际存储后返回凭据 */
    private Optional<FailureReportRecord> write(Problem descriptor, Throwable throwable) {
        Optional<Path> report = Optional.empty();
        try {
            var target = LogStore.startupTarget();
            var id = descriptor.diagnosticId().orElseGet(java.util.UUID::randomUUID);
            var event = new LogEvent(LogLevel.ERROR, descriptor.eventCode(), descriptor.message(),
                Optional.of(new String(boundedReport(descriptor, throwable), StandardCharsets.UTF_8)),
                Optional.empty(), Optional.of(id), Optional.empty());
            var record = LogRecordCodec.createSummary(target, version, event, id, System.currentTimeMillis(), null);
            if (logs != null && logs.writeStartup(record).storage() != LogStorage.NOT_SAVED)
                return Optional.of(new FailureReportRecord(descriptor, directory, report));
            synchronized (LogStore.class) {
                emergency.append(target, record);
            }
            report = Optional.of(directory.resolve(target.key() + ".log"));
        } catch (IOException | RuntimeException ignored) {
            System.err.println("LOG_STARTUP_WRITE_FAILED: original diagnostic could not be saved");
            return Optional.empty();
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
        var evidence = new java.util.ArrayList<Throwable>();
        pending.add(throwable);
        int causeIndex = 0;
        while (!pending.isEmpty() && causeIndex < 16) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current))
                continue;
            evidence.add(current);
            append(report, "exceptionClass[" + causeIndex + "]", current.getClass().getName());
            int evidenceIndex = causeIndex;
            directProblem(current)
                .ifPresent(problem -> appendProblem(report, "problem[" + evidenceIndex + "].", problem));
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
        for (int evidenceIndex = 0; evidenceIndex < evidence.size(); evidenceIndex++) {
            StackTraceElement[] frames = evidence.get(evidenceIndex).getStackTrace();
            for (int index = 0; index < frames.length && index < 256; index++) {
                StackTraceElement frame = frames[index];
                append(report, "frame[" + evidenceIndex + "][" + index + "]", frame.getClassName() + "."
                    + frame.getMethodName() + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")");
            }
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
        if (throwable instanceof gold.debug.windowstolinux.appapi.task.result.ApiException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.service.error.ApplicationServiceException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.db.error.DesktopStorageException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.windows.update.model.DesktopUpdateException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.windows.uninstall.model.DesktopUninstallException failure)
            return Optional.of(failure.problem());
        if (throwable instanceof gold.debug.windowstolinux.app.windows.handoff.DesktopHandoffException failure)
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
