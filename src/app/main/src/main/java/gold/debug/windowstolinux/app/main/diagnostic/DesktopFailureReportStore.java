package gold.debug.windowstolinux.app.main.diagnostic;

import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportRecord;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

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

/** Writes bounded, rotated, secret-free UTF-8 failure reports under the fixed data directory. / 在固定数据目录下写入有界、轮转且无秘密的 UTF-8 失败报告。 */
public final class DesktopFailureReportStore implements FailureReportStore {
    static final int MAX_REPORT_BYTES = 256 * 1024;
    static final int MAX_REPORTS = 50;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passphrase|private[-_ ]?key|api[-_ ]?key|secret|token)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private final Path directory;

    /** Creates the report store and performs startup rotation best-effort. / 创建报告存储并在启动时尽力轮转。 */
    public DesktopFailureReportStore(Path dataDirectory) {
        directory = dataDirectory.toAbsolutePath().normalize().resolve("diagnostics");
        try {
            Files.createDirectories(directory);
            rotate();
        } catch (IOException ignored) {
            // Reporting must never recursively report its own write failure. / 报告功能绝不能递归报告自身写入失败。
        }
    }

    /** Records one report best-effort and never throws. / 尽力记录一份报告且绝不抛出异常。 */
    @Override
    public synchronized Optional<FailureReportRecord> record(Throwable throwable) {
        if (throwable == null) {
            return Optional.empty();
        }
        FailureDescriptor descriptor = structured(throwable).orElseGet(() -> unknownDescriptor(throwable));
        Optional<Path> report = Optional.empty();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            rotate();
            byte[] content = boundedReport(descriptor, throwable);
            temporary = Files.createTempFile(directory, ".failure-", ".tmp");
            Files.write(temporary, content);
            Path target = directory.resolve(FILE_TIME.format(Instant.now()) + "-"
                    + descriptor.operationIdentity() + ".txt");
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

    @Override public Optional<Path> diagnosticsDirectory() { return Optional.of(directory); }

    private void rotate() throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> reports;
        try (var files = Files.list(directory)) {
            reports = files.filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        int excess = reports.size() - MAX_REPORTS;
        for (int index = 0; index < excess; index++) {
            Files.deleteIfExists(reports.get(index));
        }
    }

    private static byte[] boundedReport(FailureDescriptor failure, Throwable throwable) {
        StringBuilder report = new StringBuilder(4096);
        append(report, "timestamp", Instant.now().toString());
        append(report, "code", failure.code());
        append(report, "domain", failure.definition().domain());
        append(report, "phase", failure.definition().phase());
        append(report, "severity", failure.definition().severity().name());
        append(report, "operationId", failure.operationIdentity().toString());
        append(report, "messageKey", failure.userMessage().key());
        append(report, "diagnostic", redact(failure.diagnostic()));
        append(report, "recoveryAction", failure.recoveryAction().name());
        append(report, "recoveryDisposition", failure.recoveryDisposition().name());
        Throwable current = throwable;
        int causeIndex = 0;
        while (current != null && causeIndex < 16) {
            append(report, "exceptionClass[" + causeIndex + "]", current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            for (int index = 0; index < frames.length && index < 256; index++) {
                StackTraceElement frame = frames[index];
                append(report, "frame[" + causeIndex + "][" + index + "]",
                        frame.getClassName() + "." + frame.getMethodName() + "(" + frame.getFileName()
                                + ":" + frame.getLineNumber() + ")");
            }
            current = current.getCause();
            causeIndex++;
        }
        return truncateUtf8(report.toString());
    }

    private static void append(StringBuilder report, String key, String value) {
        report.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

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

    private static String redact(String diagnostic) {
        return BEARER.matcher(SENSITIVE_ASSIGNMENT.matcher(diagnostic).replaceAll("$1=[REDACTED]"))
                .replaceAll("Bearer [REDACTED]");
    }

    private static Optional<FailureDescriptor> structured(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof FailureCarrier carrier) {
                return Optional.of(carrier.failure());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static FailureDescriptor unknownDescriptor(Throwable throwable) {
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
        return FailureDescriptor.create(type, OperationIdentity.create(), diagnostic);
    }
}
