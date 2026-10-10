package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Actual limited observations retain unknown coverage instead of absent-tool defaults. / 实际有限观测保留未知范围，不使用工具缺失缺省值。 */
public record DeploymentAgentHostFacts(Map<String, String> baseline, Optional<Map<String, String>> selectedContainer) {
    public DeploymentAgentHostFacts {
        baseline = Map.copyOf(baseline);
        selectedContainer = selectedContainer.map(Map::copyOf);
    }

    /** Returns measured facts and explicitly unassessed platform/runtime boundaries. / 返回实测事实与明确未评估的平台及运行时边界。 */
    public Map<String, Object> facts() {
        var result = new LinkedHashMap<String, Object>();
        result.put("baseline", new TreeMap<>(baseline));
        result.put("coverage", Map.of("baseline", "OBSERVED", "tools", "UNKNOWN", "cpu", "UNKNOWN",
            "security", "UNKNOWN", "capacity", "UNKNOWN", "container", selectedContainer.isPresent() ? "SELECTED_ENGINE_OBSERVED" : "UNKNOWN"));
        result.put("assessmentCoverage", Map.of("platform", "NOT_PERFORMED_BY_THIS_OBSERVATION",
            "runtimeBoundary", "NOT_PERFORMED_BY_THIS_OBSERVATION"));
        selectedContainer.ifPresent(value -> result.put("selectedContainer", new TreeMap<>(value)));
        return java.util.Collections.unmodifiableMap(result);
    }
}
