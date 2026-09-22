package gold.debug.windowstolinux.app.ui.diagnostic;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Narrow UI-facing boundary for safe local diagnostic reports. / 面向 UI 的安全本地诊断报告窄边界。
 */
@FunctionalInterface
public interface FailureReportStore {
    /**
     * Records a failure without allowing report-write recursion. / 记录失败且不允许报告写入递归。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    Optional<FailureReportRecord> record(Throwable failure);

    /**
     * Returns the diagnostics directory when one is configured. / 返回已配置的诊断目录。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    default Optional<Path> diagnosticsDirectory() {
        return Optional.empty();
    }

    /**
     * Returns a no-write implementation for isolated UI tests. / 返回用于隔离 UI 测试的不写入实现。
     *
     * @return a no-write implementation for isolated UI tests / 用于隔离 UI 测试的不写入实现
     */
    static FailureReportStore disabled() {
        return failure -> Optional.empty();
    }
}
