package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.SourceRevision;

import java.util.List;
import java.util.Objects;

/** Reviewed immutable source input for one mixed-project component. / 一个混合项目组件的经审阅不可变源码输入。 */
public record PreparedComponentSource(
        String componentId,
        DeploymentProjectFacts facts,
        SourceArchiveDescriptor archive,
        SourceRevision sourceRevision,
        List<String> excludedEntries
) {
    /** Validates the component and archive identity. / 验证组件与归档身份。 */
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
