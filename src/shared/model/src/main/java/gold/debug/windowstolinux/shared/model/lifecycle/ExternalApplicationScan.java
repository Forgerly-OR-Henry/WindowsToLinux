package gold.debug.windowstolinux.shared.model.lifecycle;

import java.util.List;

/**
 * Successful candidates and independent partial scan failures. / 成功候选及独立的部分扫描失败。
 *
 * @param applications applications / 应用集合
 * @param issues issues / 问题集合
 */
public record ExternalApplicationScan(List<DiscoveredApplication> applications, List<ExternalScanIssueType> issues) {
    /**
     * Freezes one bounded observation. / 固化一次有界观测。
     *
     * @param applications applications / 应用集合
     * @param issues issues / 问题集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public ExternalApplicationScan {
        applications = List.copyOf(applications); issues = List.copyOf(issues);
        if (applications.size() > 512 || issues.size() > 32) throw new IllegalArgumentException("scan exceeds bounds");
        if (applications.stream().map(value -> value.target().key()).distinct().count() != applications.size())
            throw new IllegalArgumentException("duplicate scan identity");
    }
}
