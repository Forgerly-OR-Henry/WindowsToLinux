package gold.debug.windowstolinux.app.service.source;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.assessment.MultiComponentProjectAssessment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Static assessment plus independent immutable archives for a whole mixed application. / 整个混合应用的静态评估及独立不可变归档。 */
public record PreparedMultiComponentSource(
        MultiComponentProjectAssessment assessment,
        Map<String, PreparedComponentSource> components
) {
    /** Validates exact archive coverage for planning-ready components. / 验证可计划组件的精确归档覆盖。 */
    public PreparedMultiComponentSource {
        assessment = Objects.requireNonNull(assessment, "assessment");
        LinkedHashMap<String, PreparedComponentSource> normalized = new LinkedHashMap<>();
        Objects.requireNonNull(components, "components").entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> normalized.put(entry.getKey(), entry.getValue()));
        components = java.util.Collections.unmodifiableMap(normalized);
        var expected = assessment.components().stream().filter(component -> component.runtime().isPresent())
                .map(component -> component.componentId()).sorted().toList();
        if (assessment.admission() == DeploymentAdmissionStatus.READY_FOR_PLANNING) {
            if (!components.keySet().equals(new java.util.LinkedHashSet<>(expected))) {
                throw new IllegalArgumentException("prepared component archives must exactly cover the admitted graph");
            }
        } else if (!components.isEmpty()) {
            throw new IllegalArgumentException("a non-admitted mixed project cannot create component archives");
        }
    }
}
