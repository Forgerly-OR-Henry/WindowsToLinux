package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Complete human consent before any fixed environment effect for a distinct publication operation. / 独立发布操作的任一固定环境效果前完整人工同意。
 * @param retry cumulative original retry / 原累计重试
 * @param previousRoot exact original formal root / 精确原正式根
 * @param nextRoot complete newly reviewed root / 完整新审阅根
 * @param previousOperation fully closed original operation / 完整关闭原操作
 * @param nextOperation distinct explicit next operation / 独立明确下一操作
 * @param originalGraph complete original graph or empty / 完整原图或空值
 * @param unitId exact corrected unit / 精确纠正单元
 * @param previousPlan full actually executed description / 完整实际执行描述
 * @param nextPlan full human-confirmed description / 完整人工确认描述
 * @param proofSha256 full original restoration proof SHA / 完整原恢复证明SHA
 * @param catalogBranch fixed explicitly confirmed tool branch / 固定明确确认工具分支
 * @param approvalAction exact original environment approval SHA / 精确原环境审批SHA
 * @param modelRevision frozen original model route / 冻结原模型路由
 * @param approvedAt actual consent time / 实际同意时刻 */
public record DeploymentPublicationAttemptIntent(int retry, String previousRoot, String nextRoot, UUID previousOperation,
        UUID nextOperation, String originalGraph, String unitId, String previousPlan, String nextPlan, String proofSha256,
        int catalogBranch, String approvalAction, String modelRevision, Instant approvedAt) {
    /** Preserves the existing two-description recovery bound. / 保留既有双描述恢复上限。 */
    static final int LIMIT = 34 * 1_048_576;

    /** Validates only the original four technical fields and the original catalog. / 仅校验原四项技术字段及原目录。
     * @param retry original cumulative retry / 原累计重试
     * @param previousRoot original formal root / 原正式根
     * @param nextRoot new complete root / 新完整根
     * @param previousOperation closed operation / 已关闭操作
     * @param nextOperation distinct next operation / 独立下一操作
     * @param originalGraph original complete graph / 原完整图
     * @param unitId exact original unit / 精确原单元
     * @param previousPlan actual executed plan / 实际执行计划
     * @param nextPlan full human consent / 完整人工同意
     * @param proofSha256 original cleanup proof / 原清理证明
     * @param catalogBranch reviewed fixed branch / 已审阅固定分支
     * @param approvalAction environment action SHA / 环境动作SHA
     * @param modelRevision frozen route / 冻结路由
     * @param approvedAt actual review time / 实际审阅时刻 */
    public DeploymentPublicationAttemptIntent {
        Objects.requireNonNull(previousOperation); Objects.requireNonNull(nextOperation); Objects.requireNonNull(approvedAt);
        if (retry < 1 || retry > 2 || catalogBranch < 0 || catalogBranch > 1 || previousOperation.equals(nextOperation)
                || !unitId.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("Invalid explicit publication recovery intent");
        for (var hash : List.of(previousRoot, nextRoot, proofSha256, approvalAction, modelRevision))
            if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Incomplete publication consent binding");
        try {
            var before = DeploymentAssistedBuildReviewCodec.decodeDescription(previousPlan);
            var after = DeploymentAssistedBuildReviewCodec.decodeDescription(nextPlan);
            DeploymentAssistedBuildReview.requireFixed(before, after, unitId);
            DeploymentAssistedBuildReview.requireRoots(before, after, previousRoot, nextRoot, originalGraph, unitId);
            if (!(!before.inputs().equals(after.inputs()) && !before.runtime().equals(after.runtime())) && catalogBranch != 1)
                throw new IllegalArgumentException("Recovery has no actual typed correction or next fixed catalog branch");
        } catch (SQLException invalid) { throw new IllegalArgumentException("Incomplete durable publication consent", invalid); }
    }

    /** Stores full immutable source and typed data outside the small task checkpoint. / 将完整不可变来源及类型化数据存于小任务检查点之外。
     * @return complete canonical bounded intent / 完整规范有界意图
     * @throws SQLException if either original description or envelope rejects / 任一原描述或信封拒绝时 */
    public String encode() throws SQLException {
        var node = object().put("formatVersion", 1).put("retry", retry).put("previousRoot", previousRoot).put("nextRoot", nextRoot)
                .put("previousOperation", previousOperation.toString()).put("nextOperation", nextOperation.toString()).put("originalGraph", originalGraph)
                .put("unitId", unitId).put("proofSha256", proofSha256).put("catalogBranch", catalogBranch)
                .put("approvalAction", approvalAction).put("modelRevision", modelRevision).put("approvedAt", approvedAt.toString());
        node.set("previousPlan", parse(previousPlan, DeploymentAssistedBuildReviewCodec.LIMIT));
        node.set("nextPlan", parse(nextPlan, DeploymentAssistedBuildReviewCodec.LIMIT));
        return DeploymentJsonDocument.encode(node, LIMIT);
    }

    /** Binds consent to the actual retired source, runtime, configuration and time allowance before tool effects. / 工具效果前将同意绑定实际已退出来源、运行时、配置及时间额度。
     * @param proof complete physically restored predecessor / 完整物理恢复前驱
     * @param proposal actual immutable predecessor template / 实际不可变前驱模板
     * @throws SQLException if any prior reviewed input or physical binding differs / 任一原审阅输入或物理绑定不符时 */
    void requirePrevious(DeploymentPublicationAttemptProof proof, DeploymentPublicationProposal proposal) throws SQLException {
        var before = DeploymentAssistedBuildReviewCodec.decodeDescription(previousPlan);
        var child = proof.children().stream().filter(value -> value.unitId().equals(unitId)).findFirst()
                .orElseThrow(() -> new SQLException("Corrected unit is absent from the restored predecessor"));
        var component = proposal.graph().components().stream().filter(value -> value.unit().id().equals(unitId)).findFirst()
                .orElseThrow(() -> new SQLException("Corrected unit is absent from the immutable predecessor"));
        var commit = proposal.units().stream().filter(value -> value.unit().id().equals(unitId)).findFirst().orElseThrow();
        if (!previousRoot.equals(proof.reviewedRevision()) || !previousOperation.equals(proof.operation())
                || !proofSha256.equals(DeploymentPublicationAttemptProof.digest(proof.encode()))
                || !before.reviewedRevision().equals(string(child.checkpoint(), "planSha256"))
                || !before.source().get("archive").equals(string(child.checkpoint(), "archiveSha256"))
                || before.limits().timeoutSeconds() != child.limitSeconds()
                || !component.reviewedRuntime().equals(before.runtime()) || !component.resources().equals(before.resources())
                || !component.unit().server().id().equals(before.serverId()) || !commit.configuration().equals(before.configuration())
                || !new HashSet<>(commit.secrets()).equals(new HashSet<>(before.secrets())))
            throw new SQLException("Pending consent replaced its actual prior executed plan");
    }

    /** Reconstructs exact full consent without authorizing any command replay. / 重建精确完整同意，不授权任何命令重放。
     * @param node actual durable consent / 实际持久同意
     * @return complete original consent / 完整原同意
     * @throws SQLException if fields, types or canonical data conflict / 字段、类型或规范数据冲突时 */
    static DeploymentPublicationAttemptIntent decode(JsonNode node) throws SQLException {
        fields(node, Set.of("formatVersion", "retry", "previousRoot", "nextRoot", "previousOperation", "nextOperation", "originalGraph", "unitId",
                "previousPlan", "nextPlan", "proofSha256", "catalogBranch", "approvalAction", "modelRevision", "approvedAt"));
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1 || !node.path("retry").isInt()
                || !node.path("catalogBranch").isInt() || !node.path("previousPlan").isObject() || !node.path("nextPlan").isObject())
            throw new SQLException("Unsupported original publication consent");
        try {
            var value = new DeploymentPublicationAttemptIntent(node.path("retry").intValue(), string(node, "previousRoot"), string(node, "nextRoot"),
                    uuid(node, "previousOperation"), uuid(node, "nextOperation"), string(node, "originalGraph"), string(node, "unitId"), node.path("previousPlan").toString(),
                    node.path("nextPlan").toString(), string(node, "proofSha256"), node.path("catalogBranch").intValue(), string(node, "approvalAction"), string(node, "modelRevision"), Instant.parse(string(node, "approvedAt")));
            if (!value.encode().equals(node.toString())) throw new SQLException("Noncanonical publication consent");
            return value;
        } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid original publication consent", invalid); }
    }

    /** Requires final observed tools and script approval to consume this exact prior consent. / 要求最终已观察工具及脚本审批消费此次精确原同意。
     * @param review complete final review / 完整最终审阅
     * @return whether original task-independent business bindings remain exact / 原独立于任务的业务绑定是否精确保持 */
    boolean matches(DeploymentPublicationAttemptReview review) {
        return retry == review.retry() && previousRoot.equals(review.previousRoot()) && nextRoot.equals(review.nextRoot())
                && previousOperation.equals(review.previousOperation()) && nextOperation.equals(review.nextOperation())
                && originalGraph.equals(review.originalGraph()) && unitId.equals(review.unitId()) && previousPlan.equals(review.previousPlan())
                && nextPlan.equals(review.nextPlan()) && proofSha256.equals(review.proofSha256()) && approvalAction.equals(review.recoveryAction())
                && modelRevision.equals(review.modelRevision());
    }
}
