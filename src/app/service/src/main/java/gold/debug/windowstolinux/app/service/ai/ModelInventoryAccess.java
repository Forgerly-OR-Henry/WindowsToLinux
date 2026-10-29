package gold.debug.windowstolinux.app.service.ai;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.db.ai.*;
import gold.debug.windowstolinux.app.service.task.TaskCapabilityScope;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Task-bound non-secret discovery and explicit candidate resolution. / 任务绑定的非秘密查询及指定候选解析。
 */
final class ModelInventoryAccess {
    /** Keeps the original model capability ownership for Revision. / 为 Revision 保持原模型能力归属。
     */
    @FunctionalInterface
    interface Revision {
        /** Reads the captured configuration revision under its owner. / 在所属范围读取捕获的配置修订。
         * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
         * @throws SQLException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
         */
        String read() throws SQLException;
    }

    /** Authoritative inventory used inside the owning task or transaction. / 所属任务或事务内使用的权威清单。
     */
    private final AiModelStore profiles;

    /** Original owner of initialization and actual provider parameter mapping. / 初始化与实际厂商参数映射的原所有者。
     */
    private final ModelReasoningSettings reasoning;

    /** Captured scope for this model capability boundary. / 此模型能力边界捕获的 scope。
     */
    private final TaskCapabilityScope scope;

    /** Captured revision for this model capability boundary. / 此模型能力边界捕获的 revision。
     */
    private final Revision revision;

    /** Captures inputs at the ModelInventoryAccess ownership boundary. / 在 ModelInventoryAccess 所有权边界捕获输入。
     * @param profiles authoritative model inventory repository / 权威模型清单仓库
     * @param reasoning captured reasoning control or settings owner / 捕获的推理控制或设置所有者
     * @param scope original task admission scope / 原任务准入范围
     * @param revision captured configuration revision or revision source / 已捕获配置修订或修订来源
     */
    ModelInventoryAccess(AiModelStore profiles, ModelReasoningSettings reasoning, TaskCapabilityScope scope, Revision revision) {
        this.profiles = profiles;
        this.reasoning = reasoning;
        this.scope = scope;
        this.revision = revision;
    }

    /** Keeps the original model capability ownership for error. / 为 error 保持原模型能力归属。
     * @param code captured code at this validated model boundary / 此已校验模型边界捕获的 code
     * @param event captured event at this validated model boundary / 此已校验模型边界捕获的 event
     * @param message captured message at this validated model boundary / 此已校验模型边界捕获的 message
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }

    /** Reads non-secret model metadata inside the original task admission. / 在原任务准入内读取非秘密模型元数据。
     * @param task authentic original task context / 真实原任务上下文
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     * @throws ApiException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
     */
    public List<AiModelMetadata> models(TaskContext task) throws ApiException {
        try {
            return scope.call(task, false, () -> {
                reasoning.initialize();
                return profiles.routingState().inventory().stream().map(reasoning::metadata).toList();
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_CONFIGURATION_STORAGE", "Model inventory could not be read");
        }
    }

    /** Reads non-secret model metadata inside the original task admission. / 在原任务准入内读取非秘密模型元数据。
     * @param purpose declared invocation purpose / 声明的调用用途
     * @param task authentic original task context / 真实原任务上下文
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     * @throws ApiException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
     */
    public List<AiModelMetadata> models(AiPurpose purpose, TaskContext task) throws ApiException {
        Objects.requireNonNull(purpose);
        try {
            return scope.call(task, false, () -> {
                reasoning.initialize();
                return profiles.routingState().members().get(PlatformAiService.mapped(purpose)).stream()
                    .map(AiModelStore.RoutingMember::configuration).map(reasoning::metadata).toList();
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_CONFIGURATION_STORAGE", "Model recommendations could not be read");
        }
    }

    /** Captures the inventory revision without requiring recommendation membership. / 捕获清单修订，不要求推荐成员资格。
     * @param modelId optional or exact platform model configuration identity / 可选或精确的平台模型配置标识
     * @param task authentic original task context / 真实原任务上下文
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     * @throws ApiException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
     */
    public String modelConfigurationRevision(String modelId, TaskContext task) throws ApiException {
        try {
            return scope.call(task, false, () -> {
                var snapshot = profiles.routingState();
                if (snapshot.inventory().stream().noneMatch(model -> model.profile().id().equals(modelId)))
                    throw error(ProblemCode.UNAVAILABLE, "AI_MODEL_UNAVAILABLE", "The specified model is absent");
                return revision.read();
            });
        } catch (SQLException failure) {
            throw error(ProblemCode.IO_FAILURE, "AI_CONFIGURATION_STORAGE", "Model configuration could not be read");
        }
    }

    /** Resolves an exact verified configuration without selecting a backup. / 解析精确已验证配置，不选择后备。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @param capability captured capability at this validated model boundary / 此已校验模型边界捕获的 capability
     * @param revision captured configuration revision or revision source / 已捕获配置修订或修订来源
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     * @throws SQLException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
     * @throws ApiException if original admission, storage or configuration validation fails / 原准入、存储或配置校验失败时
     */
    StoredAiModelInfo selected(String id, AiCapabilityType capability, Optional<String> revision) throws SQLException, ApiException {
        reasoning.initialize();
        var snapshot = profiles.routingState();
        if (revision.isPresent() && !revision.orElseThrow().equals(this.revision.read()))
            throw error(ProblemCode.INVALID_INPUT, "AI_CONFIGURATION_CHANGED", "Frozen model configuration changed");
        var selected = snapshot.inventory().stream().filter(model -> model.profile().id().equals(id)).findFirst()
            .orElseThrow(() -> error(ProblemCode.UNAVAILABLE, "AI_MODEL_UNAVAILABLE", "The specified model is absent"));
        if (!selected.verified(capability))
            throw error(ProblemCode.UNAVAILABLE, "AI_MODEL_UNVERIFIED", "The specified model capability is not verified");
        return selected;
    }

}
