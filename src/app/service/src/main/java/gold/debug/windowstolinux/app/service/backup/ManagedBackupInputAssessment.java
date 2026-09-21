package gold.debug.windowstolinux.app.service.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured local readiness evidence for exact persisted managed backup inputs. / 精确受管备份持久化输入的结构化本地准入证据。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param componentIds affected component identifiers / 受影响的组件标识符
 * @param currentReleaseIdentities current release identities / 当前发布身份集合
 * @param applicationMissingInputs application missing inputs / 应用缺失输入集合
 * @param componentMissingInputs component missing inputs / 组件缺失输入集合
 */
public record ManagedBackupInputAssessment(
        String applicationId,
        List<String> componentIds,
        Map<String, String> currentReleaseIdentities,
        List<MissingInputType> applicationMissingInputs,
        Map<String, List<MissingInputType>> componentMissingInputs
) {
    /**
     * Exact reasons that prevent a complete persisted-input result. / 阻止持久化输入完整结果的精确原因。
     */
    public enum MissingInputType {
        /**
         * MANAGED APPLICATION GRAPH classification within missing input type.
         * <p>缺失输入类型中的受管应用图分类。
         */
        MANAGED_APPLICATION_GRAPH,
        /**
         * APPLICATION HEALTH CHECK classification within missing input type.
         * <p>缺失输入类型中的应用健康检查分类。
         */
        APPLICATION_HEALTH_CHECK,
        /**
         * CURRENT RELEASE classification within missing input type.
         * <p>缺失输入类型中的当前发布分类。
         */
        CURRENT_RELEASE,
        /**
         * REVIEWED RUNTIME classification within missing input type.
         * <p>缺失输入类型中的已审阅运行时分类。
         */
        REVIEWED_RUNTIME,
        /**
         * REVIEWED DATA PATHS classification within missing input type.
         * <p>缺失输入类型中的已审阅数据路径集合分类。
         */
        REVIEWED_DATA_PATHS,
        /**
         * REVIEWED RESOURCE BINDINGS classification within missing input type.
         * <p>缺失输入类型中的已审阅资源绑定集合分类。
         */
        REVIEWED_RESOURCE_BINDINGS,
        /**
         * REVIEWED DATABASE BINDINGS classification within missing input type.
         * <p>缺失输入类型中的已审阅数据库绑定集合分类。
         */
        REVIEWED_DATABASE_BINDINGS,
        /**
         * UNSUPPORTED DATABASE BACKUP classification within missing input type.
         * <p>缺失输入类型中的不支持数据库备份分类。
         */
        UNSUPPORTED_DATABASE_BACKUP,
        /**
         * RELEASE CONFIGURATION classification within missing input type.
         * <p>缺失输入类型中的发布配置分类。
         */
        RELEASE_CONFIGURATION,
        /**
         * RELEASE SECRET REFERENCES classification within missing input type.
         * <p>缺失输入类型中的发布秘密引用集合分类。
         */
        RELEASE_SECRET_REFERENCES,
        /**
         * LOCAL STATE CHANGED DURING ASSESSMENT classification within missing input type.
         * <p>缺失输入类型中的本地状态已变化期间评估分类。
         */
        LOCAL_STATE_CHANGED_DURING_ASSESSMENT
    }

    /**
     * Validates bounded identities and deep immutable evidence. / 校验有界标识及深度不可变证据。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param componentIds affected component identifiers / 受影响的组件标识符
     * @param currentReleaseIdentities current release identities / 当前发布身份集合
     * @param applicationMissingInputs application missing inputs / 应用缺失输入集合
     * @param componentMissingInputs component missing inputs / 组件缺失输入集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
                && value != MissingInputType.APPLICATION_HEALTH_CHECK
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

    /**
     * Reports only persisted deployment metadata readiness, not remote backup creation readiness. / 仅报告持久化部署元数据准入，不代表远端备份创建就绪。
     *
     * @return true when reports only persisted deployment metadata readiness, not remote backup creation readiness, false otherwise / 仅报告持久化部署元数据准入，不代表远端备份创建就绪时为 true，否则为 false
     */
    public boolean persistedInputsComplete() {
        return applicationMissingInputs.isEmpty() && componentMissingInputs.isEmpty();
    }

    /**
     * Validates and produces immutable releases for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的不可变发布集合。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param componentIds affected component identifiers / 受影响的组件标识符
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Validates and produces immutable missing inputs for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的不可变缺失输入集合。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param componentIds affected component identifiers / 受影响的组件标识符
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Validates and produces distinct inputs for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的去重输入集合。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static List<MissingInputType> distinctInputs(List<MissingInputType> values, String name) {
        List<MissingInputType> copied = new ArrayList<>(Objects.requireNonNull(values, name));
        copied.forEach(value -> Objects.requireNonNull(value, name + " value"));
        if (new LinkedHashSet<>(copied).size() != copied.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        return List.copyOf(copied);
    }

    /**
     * Validates a managed identifier before it reaches a remote resource boundary.
     * <p>在标识到达远端资源边界前验证受管标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return managed id text / 受管标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String managedId(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
