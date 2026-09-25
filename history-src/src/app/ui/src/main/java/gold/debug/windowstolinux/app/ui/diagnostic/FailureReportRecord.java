package gold.debug.windowstolinux.app.ui.diagnostic;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;

/**
 * Result of a best-effort local diagnostic write. / 本地诊断尽力写入的结果。
 *
 * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
 * @param diagnosticsDirectory diagnostics directory / 诊断目录
 * @param reportPath report path / 报告路径
 */
public record FailureReportRecord(FailureDescriptor failure, Path diagnosticsDirectory, Optional<Path> reportPath) {
    /**
     * Validates the safe report reference. / 校验安全报告引用。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param diagnosticsDirectory diagnostics directory / 诊断目录
     * @param reportPath report path / 报告路径
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public FailureReportRecord {
        failure = Objects.requireNonNull(failure, "failure");
        diagnosticsDirectory = Objects.requireNonNull(diagnosticsDirectory, "diagnosticsDirectory").toAbsolutePath()
                .normalize();
        reportPath = Objects.requireNonNull(reportPath, "reportPath").map(path -> path.toAbsolutePath().normalize());
    }
}
