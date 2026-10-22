package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.util.*;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPatch;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentAlternativeRequiredException;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;

/** Independent action reviewer with exact policy and human escalation. / 具精确策略及人工升级的独立动作审核者。 */
public final class DeploymentAgentReview {
    /** Review-only instruction, separate from the deployment model. / 与部署模型分离的审核专用指令。 */
    private static final String POLICY = DeploymentReviewDecision.INSTRUCTION
        + DeploymentSkillResources.text(DeploymentAgentReview.class, "action-review.md");

    /** Original platform review services. / 原平台审核服务。 */
    private final ModContext context;

    /** Strict deployment JSON adapter using the platform capability. / 使用平台能力的严格部署 JSON 适配器。 */
    private final DeploymentAiJson ai;

    /** Exact admitted target server reference. / 精确已准入目标服务器引用。 */
    private final ServerId server;

    /** Authentic original platform task context. / 真实原平台任务上下文。 */
    private final TaskContext task;

    /** Original human-selected task approval policy. / 原人工所选任务审批策略。 */
    private final String mode;

    /** Original frozen rememberAuthorization repetition. / 原冻结授权重复规则。 */
    private final boolean rememberAuthorization;

    /** Frozen whole model routing and policy identity. / 冻结完整模型路由及策略身份。 */
    private final String revision;

    /** Read-only live observer of the task-authorized AI revision. / 任务授权 AI 修订的只读实时观察器。 */
    private final Supplier<String> currentRevision;

    /** Dedicated original task identity and action evidence ledger. / 专有原任务身份及动作依据账本。 */
    private final DeploymentAgentActionLog journal;

    private final DeploymentAgentReviewSource.Bound reviewSource;

    /** Binds independent review to the exact original task and configuration. / 将独立审核绑定到精确原任务与配置。
     * @param context active Mod / 活动 Mod
     * @param server admitted server / 已准入服务器
     * @param task original task / 原任务
     * @param mode selected review mode / 所选审核模式
     * @param rememberAuthorization selected rememberAuthorization repetition / 所选授权重复规则
     * @param revision frozen all-purpose configuration / 冻结全用途配置
     * @param currentRevision live revision / 实时修订
     * @param journal original durable ledger / 原持久账本 */
    public DeploymentAgentReview(ModContext context, ServerId server, TaskContext task, String mode, boolean rememberAuthorization, String revision,
                                 Supplier<String> currentRevision, DeploymentAgentActionLog journal) {
        this(context, server, task, mode, rememberAuthorization, revision, currentRevision, journal, null);
    }

    public DeploymentAgentReview(ModContext context, ServerId server, TaskContext task, String mode, boolean rememberAuthorization, String revision,
                                 Supplier<String> currentRevision, DeploymentAgentActionLog journal,
                                 gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation conversation) {
        this(context, server, task, mode, rememberAuthorization, revision, currentRevision, journal, conversation, null);
    }

    public DeploymentAgentReview(ModContext context, ServerId server, TaskContext task, String mode, boolean rememberAuthorization, String revision,
                                 Supplier<String> currentRevision, DeploymentAgentActionLog journal,
                                 gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation conversation,
                                 DeploymentAgentReviewSource.Bound reviewSource) {
        this.reviewSource = reviewSource;
        this.context = Objects.requireNonNull(context);
        this.ai = conversation == null ? new DeploymentAiJson(context) : new DeploymentAiJson(context, conversation);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
        DeploymentAuthorizationPolicy.requireAllowed(mode, rememberAuthorization);
        this.mode = mode;
        this.rememberAuthorization = rememberAuthorization;
        this.revision = Objects.requireNonNull(revision);
        this.currentRevision = Objects.requireNonNull(currentRevision);
        this.journal = Objects.requireNonNull(journal);
    }

    /** Reviews one exact candidate script before any execution journal or remote send. / 在执行记录及远端发送前审核精确候选脚本。
     * @param component declared component / 已声明组件
     * @param sourceRevision current protected source / 当前受保护源码
     * @param script exact script / 精确脚本
     * @throws Exception if review is denied or unavailable / 审核拒绝或不可用时 */
    public void command(String component, String sourceRevision, String script) throws Exception {
        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandStaticGate.requireAllowed(script);
        String action = hash(component + ":" + sourceRevision + ":" + script);
        var reviewed = review("COMMAND", action,
            Map.of("component", component, "sourceRevision", sourceRevision, "script", script));
        journal.event(task, "COMMAND_APPROVED", action, Boolean.toString(reviewed.requiresUserConfirmation()));
    }

    /** Reviews the whole source patch before its actual command review and host rememberAuthorization. / 实际命令审核及宿主授权前审核完整源码补丁。
     * @param patch exact structured diff / 精确结构化差异
     * @throws Exception if review is denied or unavailable / 审核拒绝或不可用时 */
    public void patch(DeploymentAgentPatch patch) throws Exception {
        String action = hash(patch.toString());
        var reviewed = review("PATCH", action, Map.of("path", patch.path(), "sourceRevision", patch.sourceRevision(),
            "beforeDigest", patch.beforeDigest(), "edits", patch.edits()));
        journal.event(task, "PATCH_APPROVED", action, Boolean.toString(reviewed.requiresUserConfirmation()));
    }

    /** Obtains strict independent AI review with frozen all-purpose configuration. / 以冻结全用途配置取得严格独立 AI 审核。
     * @param type action type / 动作种类
     * @param action digest binding / 摘要绑定
     * @param facts bounded action facts / 有界动作事实
     * @return exact independent decision / 精确独立审核结论
     * @throws Exception if reviewer denies or fails / 审核者拒绝或失败时 */
    private DeploymentReviewDecision review(String type, String action, Map<String, ?> facts) throws Exception {
        requireRevision();
        DeploymentReviewDecision reviewed;
        if (reviewSource == null) {
            JsonNode answer = ai.ask(AiPurpose.APPROVAL, POLICY,
                Map.of("type", type, "action", action, "target", server.id(), "facts", facts), revision, task);
            reviewed = DeploymentReviewDecision.parse(answer);
        } else {
            reviewed = Objects.requireNonNull(reviewSource.action(context, type, action, facts, server, revision, task));
        }
        requireRevision();
        if (!reviewed.decision().equals("APPROVE")) {
            gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentReviewDenied(
                context, task, type, action, reviewed.reason());
            if (type.equals("COMMAND"))
                throw new DeploymentAgentAlternativeRequiredException(task, denialReason(reviewed.reason()));
            throw new SecurityException("Independent deployment review denied action");
        }
        return reviewed;
    }

    /** Keeps an unsent Agent refusal as bounded data without exposing credential assignments. / 将未发送的自主拒绝保留为有界数据，不暴露凭据赋值。
     * @param reason actual independent refusal / 实际独立拒绝
     * @return bounded redacted reason, never an execution approval / 有界脱敏原因，绝不是执行批准 */
    public static String denialReason(String reason) {
        String safe = Objects.requireNonNull(reason).replaceAll(
            "(?im)^.*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key).*[:=].*$",
            "[credential content excluded]").replaceAll("[\\p{Cntrl}]", " ");
        return safe.substring(0, Math.min(512, safe.length()));
    }

    /** Invalidates all approvals on a live configuration change. / 实时配置变化时使全部审批失效。 */
    private void requireRevision() {
        if (!revision.equals(currentRevision.get()))
            throw new SecurityException("Agent approval configuration changed");
    }

    /** Hashes nonsecret binding without storing source or command content. / 散列不含秘密的绑定且不储存源码或命令内容。
     * @param value bounded binding / 有界绑定
     * @return lowercase SHA-256 / 小写 SHA-256 */
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
