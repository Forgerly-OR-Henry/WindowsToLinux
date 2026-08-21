package gold.debug.windowstolinux.app.ui.diagnostic;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Result of a best-effort local diagnostic write. / 本地诊断尽力写入的结果。 */
public record FailureReportRecord(
        FailureDescriptor failure,
        Path diagnosticsDirectory,
        Optional<Path> reportPath
) {
    /** Validates the safe report reference. / 校验安全报告引用。 */
    public FailureReportRecord {
        failure = Objects.requireNonNull(failure, "failure");
        diagnosticsDirectory = Objects.requireNonNull(diagnosticsDirectory, "diagnosticsDirectory").toAbsolutePath().normalize();
        reportPath = Objects.requireNonNull(reportPath, "reportPath")
                .map(path -> path.toAbsolutePath().normalize());
    }
}
