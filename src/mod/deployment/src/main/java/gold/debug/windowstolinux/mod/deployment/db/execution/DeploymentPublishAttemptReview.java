package gold.debug.windowstolinux.mod.deployment.db.execution;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;

/** Complete typed consent for a new operation after verified restoration, never a task replay. / 核实恢复后的新操作完整类型化同意，绝非任务重放。
 * @param retry cumulative original retry number / 原累计重试编号
 * @param previousRoot exact original formal root / 精确原正式根
 * @param nextRoot complete corrected formal root / 完整纠正正式根
 * @param previousOperation exact fully closed operation / 精确完整关闭操作
 * @param nextOperation distinct explicitly approved operation / 独立明确审批操作
 * @param originalGraph original complete graph framing or empty / 原完整图分界或空值
 * @param unitId exact corrected original unit / 精确纠正原单元
 * @param previousPlan complete actually executed description / 完整实际执行描述
 * @param nextPlan complete newly confirmed description / 完整新确认描述
 * @param previousScript exact original dispatch SHA / 精确原发送SHA
 * @param nextScript exact next fixed script SHA / 精确下一固定脚本SHA
 * @param previousTools complete original binding SHA / 完整原绑定SHA
 * @param nextTools complete physically observed selection / 完整物理观察选择
 * @param proofSha256 complete restored operation proof SHA / 完整恢复操作证明SHA
 * @param approvalAction exact final fixed review SHA / 精确最终固定审阅SHA
 * @param recoveryAction prior typed environment consent SHA / 原类型化环境同意SHA
 * @param modelRevision authentic frozen model route / 真实冻结模型路由
 * @param approvedAt actual explicit final review time / 实际明确最终审阅时刻 */
public record DeploymentPublishAttemptReview(int retry, String previousRoot, String nextRoot,
                                             UUID previousOperation,
                                             UUID nextOperation, String originalGraph, String unitId,
                                             String previousPlan, String nextPlan, String previousScript,
                                             String nextScript, String previousTools, String nextTools,
                                             String proofSha256, String approvalAction, String recoveryAction,
                                             String modelRevision, Instant approvedAt) {
    /** Two original sixteen-MiB descriptions, one bounded observed tool binding and fixed review framing. / 两份原十六MiB描述、一份有界已观察工具绑定及固定审阅分界。 */
    static final int LIMIT = 36 * 1_048_576;

    /** Checks exact old/new roots and genuine typed or physically observed changes. / 检查精确旧新根及真实类型化或物理观察变化。
     * @param retry cumulative retry / 累计重试
     * @param previousRoot original formal root / 原正式根
     * @param nextRoot corrected root / 纠正根
     * @param previousOperation closed operation / 已关闭操作
     * @param nextOperation distinct next operation / 独立下一操作
     * @param originalGraph original graph / 原图
     * @param unitId corrected unit / 纠正单元
     * @param previousPlan actual executed description / 实际执行描述
     * @param nextPlan newly confirmed description / 新确认描述
     * @param previousScript original dispatch SHA / 原发送SHA
     * @param nextScript next fixed script SHA / 下一固定脚本SHA
     * @param previousTools original binding SHA / 原绑定SHA
     * @param nextTools observed complete selection / 已观察完整选择
     * @param proofSha256 full cleanup proof SHA / 完整清理证明SHA
     * @param approvalAction final exact approval SHA / 最终精确审批SHA
     * @param recoveryAction prior typed consent SHA / 原类型化同意SHA
     * @param modelRevision frozen route / 冻结路由
     * @param approvedAt actual final review time / 实际最终审阅时刻 */
    public DeploymentPublishAttemptReview {
        Objects.requireNonNull(previousOperation);
        Objects.requireNonNull(nextOperation);
        Objects.requireNonNull(approvedAt);
        if (retry < 1 || retry > 2 || previousOperation.equals(nextOperation) || !unitId.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Invalid original publication retry identity");
        for (var hash : List.of(previousRoot, nextRoot, previousScript, nextScript, previousTools, proofSha256, approvalAction, recoveryAction, modelRevision))
            if (!hash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Incomplete fixed publication review binding");
        try {
            var before = DeploymentAssistedBuildReviewCodec.decodeDescription(previousPlan);
            var after = DeploymentAssistedBuildReviewCodec.decodeDescription(nextPlan);
            DeploymentAssistedBuildReview.requireFixed(before, after, unitId);
            DeploymentAssistedBuildReview.requireRoots(before, after, previousRoot, nextRoot, originalGraph, unitId);
            DeploymentToolchainBindingCodec.decode(nextTools);
            boolean corrected = !before.inputs().equals(after.inputs()) && !before.runtime().equals(after.runtime());
            boolean toolsChanged = !previousTools.equals(DeploymentPublishAttemptProof.digest(nextTools));
            if ((!corrected && !toolsChanged) || previousScript.equals(nextScript) && !toolsChanged)
                throw new IllegalArgumentException("Publish retry has no actual corrected input or changed environment");
        } catch (SQLException invalid) {
            throw new IllegalArgumentException("Incomplete durable publication review", invalid);
        }
    }

    /** Requires the old complete author proof and actual original template to match this review. / 要求旧完整作者证明及实际原模板匹配此次审阅。
     * @param proof original fully closed operation / 原完整关闭操作
     * @param original complete immutable previous proposal / 完整不可变原提案
     * @throws SQLException if any old executed unit, config or root was replaced / 任一原执行单元、配置或根被替换时 */
    void requirePrevious(DeploymentPublishAttemptProof proof, DeploymentPublishProposal original) throws SQLException {
        String encoded = DeploymentPublishTemplateCodec.encodeTemplate(original);
        var child = proof.children().stream().filter(value -> value.unitId().equals(unitId)).findFirst()
            .orElseThrow(() -> new SQLException("Corrected unit is absent from the restored original operation"));
        var before = DeploymentAssistedBuildReviewCodec.decodeDescription(previousPlan);
        var component = original.graph().components().stream().filter(value -> value.unit().id().equals(unitId)).findFirst().orElseThrow();
        var commit = original.units().stream().filter(value -> value.unit().id().equals(unitId)).findFirst().orElseThrow();
        var after = DeploymentAssistedBuildReviewCodec.decodeDescription(nextPlan);
        var oldSelections = DeploymentToolchainBindingCodec.decode(child.tools()).selections().stream().map(value -> List.of(value.requirement().ecosystem().name(),
            value.requirement().purpose().name(), value.version().text(), value.directory(), value.origin().name(), value.source(), value.sha256())).toList();
        var nextSelections = DeploymentToolchainBindingCodec.decode(nextTools).selections().stream().map(value -> List.of(value.requirement().ecosystem().name(),
            value.requirement().purpose().name(), value.version().text(), value.directory(), value.origin().name(), value.source(), value.sha256())).toList();
        if (!(!before.inputs().equals(after.inputs()) && !before.runtime().equals(after.runtime())) && oldSelections.equals(nextSelections))
            throw new SQLException("Incidental evidence or free space change cannot authorize publication replay");
        if (!previousOperation.equals(proof.operation()) || !previousRoot.equals(proof.reviewedRevision())
            || !proofSha256.equals(DeploymentPublishAttemptProof.digest(proof.encode()))
            || !proof.templateSha256().equals(DeploymentPublishAttemptProof.digest(encoded))
            || !before.reviewedRevision().equals(string(child.checkpoint(), "planSha256"))
            || !before.source().get("archive").equals(string(child.checkpoint(), "archiveSha256"))
            || !previousScript.equals(string(child.checkpoint(), "buildScript")) || !previousTools.equals(string(child.checkpoint(), "buildTools"))
            || !component.reviewedRuntime().equals(before.runtime()) || !component.resources().equals(before.resources())
            || !component.unit().server().id().equals(before.serverId()) || !commit.configuration().equals(before.configuration())
            || !new HashSet<>(commit.secrets()).equals(new HashSet<>(before.secrets())))
            throw new SQLException("Publish retry replaced its actual previous execution or complete configuration");
    }

    /** Writes descriptions as nested objects to avoid re-escaping admitted configuration bytes. / 将描述写为嵌套对象，避免再次转义已准入配置字节。
     * @return complete canonical bounded review / 完整规范有界审阅
     * @throws SQLException if original description or envelope bounds reject / 原描述或信封边界拒绝时 */
    public String encode() throws SQLException {
        var node = object().put("formatVersion", 1).put("retry", retry).put("previousRoot", previousRoot).put("nextRoot", nextRoot)
            .put("previousOperation", previousOperation.toString()).put("nextOperation", nextOperation.toString()).put("originalGraph", originalGraph)
            .put("unitId", unitId).put("previousScript", previousScript).put("nextScript", nextScript).put("previousTools", previousTools)
            .put("nextTools", nextTools).put("proofSha256", proofSha256).put("approvalAction", approvalAction)
            .put("recoveryAction", recoveryAction).put("modelRevision", modelRevision).put("approvedAt", approvedAt.toString());
        node.set("previousPlan", parse(previousPlan, DeploymentAssistedBuildReviewCodec.LIMIT));
        node.set("nextPlan", parse(nextPlan, DeploymentAssistedBuildReviewCodec.LIMIT));
        return DeploymentJsonDocument.encode(node, LIMIT);
    }

    /** Reads exactly one full original review without permitting a continuation by itself. / 精确读取一份完整原审阅，本身不准许继续。
     * @param node actual persisted review / 实际持久审阅
     * @return strict complete review / 严格完整审阅
     * @throws SQLException if identity, types or canonical bytes conflict / 身份、类型或规范字节冲突时 */
    static DeploymentPublishAttemptReview decode(JsonNode node) throws SQLException {
        fields(node, Set.of("formatVersion", "retry", "previousRoot", "nextRoot", "previousOperation", "nextOperation", "originalGraph", "unitId",
            "previousPlan", "nextPlan", "previousScript", "nextScript", "previousTools", "nextTools", "proofSha256", "approvalAction", "recoveryAction", "modelRevision", "approvedAt"));
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1 || !node.path("retry").isInt()
            || !node.path("previousPlan").isObject() || !node.path("nextPlan").isObject())
            throw new SQLException("Unsupported original publication review");
        try {
            var review = new DeploymentPublishAttemptReview(node.path("retry").intValue(), string(node, "previousRoot"), string(node, "nextRoot"),
                uuid(node, "previousOperation"), uuid(node, "nextOperation"), string(node, "originalGraph"), string(node, "unitId"), node.path("previousPlan").toString(), node.path("nextPlan").toString(),
                string(node, "previousScript"), string(node, "nextScript"), string(node, "previousTools"), string(node, "nextTools"), string(node, "proofSha256"),
                string(node, "approvalAction"), string(node, "recoveryAction"), string(node, "modelRevision"), Instant.parse(string(node, "approvedAt")));
            if (!review.encode().equals(node.toString()))
                throw new SQLException("Noncanonical original publication review");
            return review;
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Incomplete original publication review", invalid);
        }
    }
}
