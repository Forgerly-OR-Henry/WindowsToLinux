package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlan;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;

/** Narrow execution tools for independently generated Agent commands and attested publication. / 执行独立自主命令并核验发布结果的窄工具能力。 */
public interface DeploymentAgentDeliveryPort extends AutoCloseable {
    /** Whether an actual initial capacity failure permits explicitly replacing the original connection. */
    default boolean reconnectAvailable() {
        return false;
    }

    default java.util.Optional<gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation> approvalConversation() {
        return java.util.Optional.empty();
    }

    /** Whether this task has a local native review-response channel. */
    default boolean reviewResponseAvailable() {
        return false;
    }

    /** Replaces bounded model-authored DATA for an original OPEN refusal; never reviews or sends an action. */
    default Map<String, Object> reviewResponse(TaskContext task, String reviewBinding, String message) {
        throw new IllegalArgumentException("REVIEW_RESPONSE_UNAVAILABLE: original native approval channel unavailable");
    }

    /** Current immutable component source. / 当前不可变组件来源。
     * @param component selected component / 选定组件
     * @return current browser / 当前浏览器
     * @throws Exception unavailable source / 来源不可用 */
    default gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser source(String component) throws Exception {
        throw new UnsupportedOperationException("Current component source unavailable");
    }

    /** Runs one selected shared capability in the original task. / 在原任务中运行选定公共能力。
     * @param request strict capability / 严格能力
     * @param component planned component or null for secret catalog / 计划组件或秘密目录为空
     * @param candidate actual candidate or null for read-only tools / 实际候选或只读工具为空
     * @param revision current candidate revision / 当前候选修订
     * @return bounded actual feedback / 有界真实反馈
     * @throws Exception classified failure / 分类失败
     * @param binding Original effect identity / 原效果身份
     * @param applicationId Current declared application identity; does not imply a prepared candidate / 当前声明的应用身份，不代表候选已准备
     */
    default String capability(gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProposal.Capability request,
                              DeploymentAgentPlan.Part component, Prepared candidate, String revision, String binding, String applicationId) throws Exception {
        throw new IllegalArgumentException("CAPABILITY_NOT_APPLICABLE: " + request.name());
    }

    /** Validates actual source-bound recipe prerequisites without preparing a candidate, sending commands or granting authority. / 不准备候选、不发送命令、不产生权限，仅校验真实来源绑定配方前提。
     * @param request selected shared build capability / 所选公共构建能力
     * @param component current planned component / 当前计划组件
     * @throws Exception absent, stale, rejected or unsupported prerequisites / 前提缺失、过时、拒绝或不支持 */
    default void validateCapability(gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentProposal.Capability request,
                                    DeploymentAgentPlan.Part component) throws Exception {
        throw new IllegalArgumentException("CAPABILITY_NOT_APPLICABLE: source-bound recipe preflight unavailable");
    }

    /** Returns existing local Agent capability facts without execution, selection or authority. / 返回本地既有 Agent 能力事实，不执行、选择或授予权限。 */
    default Map<String, Object> capabilityState(DeploymentAgentPlan.Part component) {
        return Map.of();
    }

    /** Resolves only verified resource and secret references. / 只解析已核实资源及秘密引用。
     * @param component model plan / 模型计划
     * @return source-bound resolved plan / 来源绑定已解析计划
     * @throws Exception invalid references / 无效引用 */
    default DeploymentAgentPlan.Part resolve(DeploymentAgentPlan.Part component) throws Exception {
        if (component.databaseIds().map(v -> !v.isEmpty()).orElse(false) || !component.secretReferences().isEmpty())
            throw new IllegalArgumentException("Unverified Agent resource references");
        return component;
    }

    /** Actual task-owned source candidate. / 真实任务所属来源候选。
     * @param workspace exact remote workspace / 精确远端工作区
     * @param revision observed source revision / 已观察来源修订 */
    record Prepared(DeploymentRemoteWorkspace workspace, String revision,
                    gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Selection quota) {
        public Prepared(DeploymentRemoteWorkspace workspace, String revision) {
            this(workspace, revision, null);
        }
    }

    /** Actual bounded command feedback. / 真实有界命令反馈。
     * @param known whether the effect and result are known / 副作用和结果是否已知
     * @param succeeded whether exit was successful / 退出是否成功
     * @param exitStatus observed exit code / 已观察退出码
     * @param evidenceOutput bounded redacted output / 有界脱敏输出 */
    record CommandFeedback(boolean known, boolean succeeded, int exitStatus, String evidenceOutput) {
    }

    /** Model-authored publication result attested by common identity and health checks. / 经公共身份与健康核验的模型发布结果。
     * @param healthy exact verified components / 精确已核实组件
     * @param evidence actual evidence IDs / 实际证据标识
     */
    record Published(Set<String> healthy, String evidence) {
        /** Copies verified identities. / 复制已核实身份。
         * @param healthy verified components / 已核实组件
         * @param evidence actual evidence IDs / 实际证据标识
         */
        public Published {
            healthy = Set.copyOf(healthy);
        }
    }

    default List<DeploymentAgentPlan.Part> selectWorkspaces(DeploymentAgentPlan plan, Map<String, Prepared> existing) throws Exception {
        throw new IllegalArgumentException("WORKSPACE_CAPACITY_UNAVAILABLE: authenticated source and host budget facts required");
    }

    default void acceptWorkspaces(DeploymentAgentPlan plan) {
    }

    /** Observes current authenticated host capabilities. / 观察当前已认证主机能力。
     * @return nonsecret actual facts / 不含秘密的真实事实
     * @throws Exception if observation is unavailable / 观察不可用时 */
    Map<String, String> inspect() throws Exception;

    /** Executes an independently reviewed prerequisite command through the original session. / 经原会话执行独立审核的前置命令。
     * @param task original admitted task / 原已准入任务
     * @param script complete original reviewed executable script / 完整原已审阅可执行脚本
     * @param seconds bounded command timeout in seconds / 有界命令超时秒数
     * @param reason bounded evidence-supported review explanation / 有界依据支持的审核说明
     * @return classified original prerequisite command result / 分类原前置命令结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    default String hostCommand(TaskContext task, String script, int seconds, String reason) throws Exception {
        throw new UnsupportedOperationException("Host prerequisite commands unavailable");
    }

    /** Uploads original frozen source and opens one protected helper workspace. / 上传原冻结来源并开启一个受保护 helper 工作区。
     * @param applicationId reviewed application identity / 已审应用身份
     * @param component declared component / 已声明组件
     * @return observed candidate and revision / 已观察候选与修订
     * @throws Exception on unknown or denied preparation / 准备未知或被拒绝时 */
    Prepared prepare(String applicationId, DeploymentAgentPlan.Part component) throws Exception;

    /** Executes exact reviewed script in the protected candidate. / 在受保护候选中执行精确已审脚本。
     * @param task original task / 原任务
     * @param candidate actual candidate / 实际候选
     * @param revision current source revision / 当前源码修订
     * @param script exact script / 精确脚本
     * @param timeout execution deadline / 执行期限
     * @return actual bounded result / 真实有界结果
     * @throws Exception on denied or unknown command / 命令被拒绝或未知时 */
    CommandFeedback command(TaskContext task, Prepared candidate, String revision, String script, Duration timeout)
        throws Exception;

    /** Reads only current protected task source. / 仅读取当前受保护任务源码。
     * @param task original task / 原任务
     * @param candidate actual candidate / 实际候选
     * @param revision current source revision / 当前源码修订
     * @param path relative file / 相对文件
     * @param offset line cursor / 行游标
     * @param limit line count / 行数
     * @return bounded redacted lines / 有界脱敏行
     * @throws Exception on unavailable reading / 读取不可用时 */
    String read(TaskContext task, Prepared candidate, String revision, String path, int offset, int limit)
        throws Exception;

    /** Freezes a locally validated prediction before remote review. / 远端审阅前冻结本地已验证预测。
     * @param task original task / 原任务
     * @param candidate original source candidate / 原来源候选
     * @param patch exact proposed edit / 精确提议编辑
     * @throws Exception local conflict or unavailable snapshot / 本地冲突或快照不可用 */
    default void validatePatch(TaskContext task, Prepared candidate, DeploymentAgentPatch patch) throws Exception {
        throw new IllegalArgumentException("Local patch prediction unavailable");
    }

    /** Applies a separately reviewed exact source patch and returns a new revision. / 应用单独审核的精确源码补丁并返回新修订。
     * @param task original task / 原任务
     * @param candidate actual candidate / 实际候选
     * @param revision expected source revision / 预期源码修订
     * @param patch exact reviewed structured patch / 精确已审结构化补丁
     * @return observed new source revision / 已观察新来源修订
     * @throws Exception on denied, conflicting or unknown patch / 补丁被拒绝、冲突或未知时 */
    String patch(TaskContext task, Prepared candidate, String revision, DeploymentAgentPatch patch) throws Exception;

    /** Requires current actual successful-run evidence without sending or granting a seal. */
    default void validateSeal(TaskContext task, Prepared candidate, String revision) throws Exception {
        throw new IllegalArgumentException("SEAL_PRECONDITION: current successful-run evidence unavailable; no seal was sent");
    }

    /** Seals actual build output for its current source revision. / 按当前源码修订封存真实构建输出。
     * @param task original task / 原任务
     * @param candidate actual candidate / 实际候选
     * @param revision current source revision / 当前源码修订
     * @return observed artifact digest / 已观察制品摘要
     * @throws Exception when sealing cannot be verified / 封存无法核实时 */
    String seal(TaskContext task, Prepared candidate, String revision) throws Exception;

    /** Runs model-authored publication and independently verifies the complete graph. / 执行模型生成的发布命令并独立核验完整图。
     * @param task original task / 原任务
     * @param plan validated graph / 已校验图
     * @param candidates exact prepared candidates / 精确已准备候选
     * @param revisions current source revisions / 当前来源修订
     * @param artifacts verified sealed artifacts / 已核实封存制品
     * @param remainingDecisions remaining shared model decision budget / 剩余共享模型决策预算
     * @return actual result or retained uncertainty / 真实结果或保留的不确定性
     * @throws Exception on unavailable delivery evidence / 交付依据不可用时 */
    OperationResult<Published> deliver(TaskContext task, DeploymentAgentPlan plan, Map<String, Prepared> candidates,
                                       Map<String, String> revisions, Map<String, String> artifacts, int remainingDecisions) throws Exception;

    default java.util.Optional<DeploymentAgentFailedPublication> failedPublication(TaskContext task) {
        return java.util.Optional.empty();
    }

    default OperationResult<Void> settleFailedPublication(TaskContext task, DeploymentAgentFailedPublication accepted) throws Exception {
        throw new UnsupportedOperationException("Online failed publication settlement unavailable");
    }

    /** Rebinds all actual sends after an explicitly authorized paused model replacement. / 明确授权暂停模型替换后重新绑定全部真实发送。
     * @param revision newly verified whole-route revision / 新核实整路由修订
     * @throws Exception if this tool cannot safely replace its original authority / 此工具不能安全替换原权限时 */
    default void replaceConfiguration(String revision) throws Exception {
        throw new UnsupportedOperationException("Tool does not support explicit model replacement");
    }

    /** Releases only verified task-owned resources. / 仅释放已核实任务所属资源。
     * @throws Exception if release is unconfirmed / 释放无法确认时 */
    @Override
    void close() throws Exception;
}
