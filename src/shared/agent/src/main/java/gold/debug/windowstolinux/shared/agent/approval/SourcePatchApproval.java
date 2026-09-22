package gold.debug.windowstolinux.shared.agent.approval;

import java.util.*;
import java.util.function.*;

import gold.debug.windowstolinux.shared.deploy.approval.*;
import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.linux.workspace.RemoteSourcePatch;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode;

/** Dedicated source authority, independent from general command permission. / 独立于通用命令权限的专用源码授权。 */
public final class SourcePatchApproval {
    /** Exact task and endpoint. / 精确任务及端点。 */
    private final String task, target;

    /** Selected source policy. / 所选源码策略。 */
    private final AgentApprovalMode mode;

    /** Independent approval model. / 独立审批模型。 */
    private final DeploymentReviewPort reviewer;

    /** Explicit UI confirmation. / 显式界面确认。 */
    private final BiPredicate<String, Map<String, ?>> human;

    /** Durable nonsecret metadata. / 持久化非秘密元数据。 */
    private final Consumer<Map<String, String>> journal;

    /** Task controller. / 任务控制器。 */
    private final AgentTaskControl control;

    /** Current independent-review configuration identity. / 当前独立审批配置身份。 */
    private final Supplier<String> reviewRevision;

    /** One task-only automatic source grant. / 仅适用于一个任务的自动源码授权。 */
    private boolean authorized;

    /** Creates source policy without granting authority. / 创建源码策略，不授予权限。
     * @param task task identity / 任务身份
     * @param target endpoint / 端点
     * @param mode selected policy / 所选策略
     * @param reviewer independent reviewer / 独立审批者
     * @param human UI review port / 界面审核端口
     * @param journal durable metadata / 持久化元数据
     * @param control safe task boundary / 安全任务边界
     * @param reviewRevision current model configuration identity / 当前模型配置身份
     */
    public SourcePatchApproval(String task, String target, AgentApprovalMode mode, DeploymentReviewPort reviewer,
            BiPredicate<String, Map<String, ?>> human, Consumer<Map<String, String>> journal, AgentTaskControl control,
            Supplier<String> reviewRevision) {
        this.task = Objects.requireNonNull(task);
        this.target = Objects.requireNonNull(target);
        this.mode = Objects.requireNonNull(mode);
        this.reviewer = Objects.requireNonNull(reviewer);
        this.human = Objects.requireNonNull(human);
        this.journal = Objects.requireNonNull(journal);
        this.control = Objects.requireNonNull(control);
        this.reviewRevision = Objects.requireNonNull(reviewRevision);
    }

    /** Reviews the complete diff and each changed line when required. / 按要求审核完整差异及每一变更行。
     * @param patch exact proposal / 精确提议
     * @param currentRevision current remote evidence / 当前远端证据
     * @return approved exact patch binding / 已批准精确补丁绑定
     * @throws Exception when approval is unavailable or refused / 审批不可用或拒绝时
     */
    public String approve(RemoteSourcePatch patch, String currentRevision) throws Exception {
        control.checkpoint();
        String reviewedConfiguration = reviewRevision.get();
        if (!patch.sourceRevision().equals(currentRevision))
            throw new SecurityException("stale source patch");
        String lower = patch.path().toLowerCase(Locale.ROOT);
        for (String part : lower.split("/"))
            if (Set.of(".git", ".ssh", ".env", ".npmrc", ".pypirc", ".w2l").contains(part) || part.startsWith(".env.")
                    || part.endsWith(".pem") || part.endsWith(".key") || part.endsWith(".p12") || part.endsWith(".pfx"))
                throw new SecurityException("source patch crosses protected path");
        AgentRiskLevel local = lower
                .matches(".*(auth|security|permission|migration|database|credential|secret|login|dockerfile|deploy).*")
                        ? AgentRiskLevel.HIGH
                        : AgentRiskLevel.NORMAL;
        if (mode == AgentApprovalMode.AUTOMATIC && !authorized) {
            if (!human.test("deployment.agent.sourceAuthorization", Map.of("task", task, "target", target)))
                throw new java.util.concurrent.CancellationException("source authorization refused");
            event("SOURCE_AUTHORIZATION", patch, "task-authorized");
            authorized = true;
        }
        String diff = String.join("\n", patch.difference());
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("path", patch.path());
        parameters.put("beforeDigest", patch.beforeDigest());
        parameters.put("patchBinding", patch.binding());
        for (int offset = 0, index = 0; offset < diff.length(); offset += 6000, index++)
            parameters.put("diff" + index, diff.substring(offset, Math.min(diff.length(), offset + 6000)));
        var action = new AgentAction("patch-" + patch.binding().substring(0, 24), task, target, currentRevision, 1,
                AgentToolType.PATCH_SOURCE, parameters, Map.of("sourceRevision", currentRevision, "sourceAuthority",
                        "Linux task copy only; local snapshot and original source remain unchanged."),
                local);
        AgentRiskLevel risk = DeploymentApprovalGate.admit(action, local,
                reviewer.review("Review this exact remote source diff and its consequences.", action, local));
        if (mode == AgentApprovalMode.MANUAL_REVIEW) {
            int line = 0;
            for (String changed : patch.difference()) {
                if (!human.test("deployment.agent.sourceLine", Map.of("target", target, "difference", diff, "line",
                        changed, "index", ++line, "binding", patch.binding())))
                    throw new java.util.concurrent.CancellationException("source line rejected");
            }
        } else if (DeploymentApprovalGate.needsHuman(mode, risk) && !human.test("deployment.agent.sourceHighRisk",
                Map.of("difference", diff, "binding", patch.binding())))
            throw new java.util.concurrent.CancellationException("high-risk source patch rejected");
        control.checkpoint();
        if (!reviewedConfiguration.equals(reviewRevision.get()))
            throw new SecurityException("source approval configuration changed");
        event("PATCH_APPROVED", patch, risk.name());
        return patch.binding();
    }

    /** Records digests only; source diff remains transient. / 仅记录摘要，源码差异保持临时状态。
     * @param type event kind / 事件类型
     * @param patch patch identity / 补丁身份
     * @param detail nonsecret policy result / 非秘密策略结果
     */
    private void event(String type, RemoteSourcePatch patch, String detail) {
        journal.accept(Map.of("type", type, "action", patch.binding(), "detail",
                detail + ":" + patch.beforeDigest() + ":" + patch.sourceRevision()));
    }
}
