package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;

import java.util.List;
import java.util.Objects;

/**
 * Reviewed immutable source input for one mixed-project component. / 一个混合项目组件的经审阅不可变源码输入。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
 * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
 * @param sourceRevision the immutable local or pinned-Git source identity / 不可变本地或固定 Git 源码身份
 * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
 */
public record PreparedComponentSource(
        String componentId,
        DeploymentProjectFacts facts,
        SourceArchiveDescriptor archive,
        SourceRevision sourceRevision,
        List<String> excludedEntries
) {
    /**
     * Validates the component and archive identity. / 验证组件与归档身份。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param sourceRevision the immutable local or pinned-Git source identity / 不可变本地或固定 Git 源码身份
     * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public PreparedComponentSource {
        componentId = Objects.requireNonNull(componentId, "componentId");
        facts = Objects.requireNonNull(facts, "facts");
        archive = Objects.requireNonNull(archive, "archive");
        sourceRevision = Objects.requireNonNull(sourceRevision, "sourceRevision");
        excludedEntries = List.copyOf(Objects.requireNonNull(excludedEntries, "excludedEntries"));
        if (!archive.contentSha256().equals(sourceRevision.sourceSha256())) {
            throw new IllegalArgumentException("component source revision must bind the reviewed archive");
        }
    }
}
