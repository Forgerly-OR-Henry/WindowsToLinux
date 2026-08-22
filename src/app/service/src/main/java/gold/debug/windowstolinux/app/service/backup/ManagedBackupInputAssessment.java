package gold.debug.windowstolinux.app.service.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured local readiness evidence for exact persisted managed backup inputs. / 精确受管备份持久化输入的结构化本地准入证据。 */
public record ManagedBackupInputAssessment(
        String applicationId,
        List<String> componentIds,
        Map<String, String> currentReleaseIdentities,
        List<MissingInputType> applicationMissingInputs,
        Map<String, List<MissingInputType>> componentMissingInputs
) {
    /** Exact reasons that prevent a complete persisted-input result. / 阻止持久化输入完整结果的精确原因。 */
    public enum MissingInputType {
        MANAGED_APPLICATION_GRAPH,
        CURRENT_RELEASE,
        REVIEWED_RUNTIME,
        REVIEWED_DATA_PATHS,
        REVIEWED_RESOURCE_BINDINGS,
        REVIEWED_DATABASE_BINDINGS,
        RELEASE_CONFIGURATION,
        RELEASE_SECRET_REFERENCES,
        LOCAL_STATE_CHANGED_DURING_ASSESSMENT
    }

    /** Validates bounded identities and deep immutable evidence. / 校验有界标识及深度不可变证据。 */
    public ManagedBackupInputAssessment {
        applicationId = managedId(applicationId, "applicationId");
        componentIds = List.copyOf(Objects.requireNonNull(componentIds, "componentIds"));
        if (componentIds.size() > 256 || componentIds.stream().distinct().count() != componentIds.size()) {
            throw new IllegalArgumentException("componentIds must be bounded and unique");
        }
        componentIds.forEach(value -> managedId(value, "componentId"));
        currentReleaseIdentities = immutableReleases(currentReleaseIdentities, componentIds);
        applicationMissingInputs = distinctInputs(applicationMissingInputs, "applicationMissingInputs");
        componentMissingInputs = immutableMissingInputs(componentMissingInputs, componentIds);
        if (componentIds.isEmpty() != applicationMissingInputs.contains(MissingInputType.MANAGED_APPLICATION_GRAPH)) {
            throw new IllegalArgumentException("missing graph evidence must match the component list");
        }
        if (applicationMissingInputs.stream().anyMatch(value -> value != MissingInputType.MANAGED_APPLICATION_GRAPH
                && value != MissingInputType.LOCAL_STATE_CHANGED_DURING_ASSESSMENT)) {
            throw new IllegalArgumentException("application missing inputs contain a component-scoped reason");
        }
        for (String componentId : componentIds) {
            List<MissingInputType> missing = componentMissingInputs.getOrDefault(componentId, List.of());
            if (missing.stream().anyMatch(value -> value == MissingInputType.MANAGED_APPLICATION_GRAPH
                    || value == MissingInputType.LOCAL_STATE_CHANGED_DURING_ASSESSMENT)
                    || currentReleaseIdentities.containsKey(componentId)
                    == missing.contains(MissingInputType.CURRENT_RELEASE)) {
                throw new IllegalArgumentException("component missing inputs differ from their release evidence");
            }
        }
    }

    /** Reports only persisted deployment metadata readiness, not remote backup creation readiness. / 仅报告持久化部署元数据准入，不代表远端备份创建就绪。 */
    public boolean persistedInputsComplete() {
        return applicationMissingInputs.isEmpty() && componentMissingInputs.isEmpty();
    }

    private static Map<String, String> immutableReleases(Map<String, String> values, List<String> componentIds) {
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        Objects.requireNonNull(values, "currentReleaseIdentities").forEach((componentId, releaseIdentity) -> {
            componentId = managedId(componentId, "release componentId");
            releaseIdentity = Objects.requireNonNull(releaseIdentity, "releaseIdentity").trim();
            if (!componentIds.contains(componentId) || !releaseIdentity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("current release evidence is invalid");
            }
            copied.put(componentId, releaseIdentity);
        });
        return Collections.unmodifiableMap(copied);
    }

    private static Map<String, List<MissingInputType>> immutableMissingInputs(
            Map<String, List<MissingInputType>> values,
            List<String> componentIds
    ) {
        LinkedHashMap<String, List<MissingInputType>> copied = new LinkedHashMap<>();
        Objects.requireNonNull(values, "componentMissingInputs").forEach((componentId, missing) -> {
            componentId = managedId(componentId, "missing componentId");
            if (!componentIds.contains(componentId)) {
                throw new IllegalArgumentException("missing-input component is not declared");
            }
            List<MissingInputType> distinct = distinctInputs(missing, "component missing inputs");
            if (distinct.isEmpty()) throw new IllegalArgumentException("empty component missing inputs are not evidence");
            copied.put(componentId, distinct);
        });
        return Collections.unmodifiableMap(copied);
    }

    private static List<MissingInputType> distinctInputs(List<MissingInputType> values, String name) {
        List<MissingInputType> copied = new ArrayList<>(Objects.requireNonNull(values, name));
        copied.forEach(value -> Objects.requireNonNull(value, name + " value"));
        if (new LinkedHashSet<>(copied).size() != copied.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return List.copyOf(copied);
    }

    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
