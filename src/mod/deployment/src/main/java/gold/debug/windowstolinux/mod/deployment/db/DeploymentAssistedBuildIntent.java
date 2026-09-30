package gold.debug.windowstolinux.mod.deployment.db;

import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;

/** Durable typed recovery consent before any fixed environment recovery effect. / 任一固定环境恢复效果前的持久类型化恢复同意。
 * @param attempt bounded original retry number / 有界原重试编号
 * @param previousRoot original formal root / 原正式根
 * @param nextRoot actual newly reviewed root / 实际新审阅根
 * @param originalGraph complete original graph or empty / 完整原图或空值
 * @param unitId exact original unit / 精确原单元
 * @param previousPlan complete actually executed plan / 完整实际执行计划
 * @param nextPlan complete typed human correction / 完整类型化人工纠正
 * @param previousScript exact executed script / 精确执行脚本
 * @param previousTools complete observed tools / 完整已观察工具
 * @param failureEvidence complete confirmed failure / 完整已确认失败
 * @param catalogBranch explicitly reviewed fixed catalog branch / 明确审阅固定目录分支
 * @param approvalAction exact original consent action SHA / 精确原同意动作SHA
 * @param modelRevision frozen original model routing / 冻结原模型路由
 * @param approvedAt actual original consent time / 实际原同意时刻 */
public record DeploymentAssistedBuildIntent(int attempt, String previousRoot, String nextRoot, String originalGraph,
        String unitId, String previousPlan, String nextPlan, String previousScript, String previousTools,
        String failureEvidence, int catalogBranch, String approvalAction, String modelRevision, Instant approvedAt) {
    /** Validates full immutable source and typed changes before storing a recovery intent. / 存储恢复意图前校验完整不可变来源及类型化变化。
     * @param attempt original retry number / 原重试编号
     * @param previousRoot original formal root / 原正式根
     * @param nextRoot actual newly reviewed root / 实际新审阅根
     * @param originalGraph complete original graph or empty / 完整原图或空值
     * @param unitId exact original unit / 精确原单元
     * @param previousPlan actual executed plan / 实际执行计划
     * @param nextPlan full typed human correction / 完整类型化人工纠正
     * @param previousScript exact executed script / 精确执行脚本
     * @param previousTools actual observed tools / 实际已观察工具
     * @param failureEvidence complete confirmed failure / 完整已确认失败
     * @param catalogBranch reviewed fixed branch / 已审阅固定分支
     * @param approvalAction exact consent action SHA / 精确同意动作SHA
     * @param modelRevision frozen model routing / 冻结模型路由
     * @param approvedAt actual consent time / 实际同意时刻 */
    public DeploymentAssistedBuildIntent {
        if (attempt < 1 || attempt > 2 || catalogBranch < 0 || catalogBranch > 1 || previousScript.length() > 1_048_576
                || previousScript.indexOf('\0') >= 0 || !approvalAction.matches("[0-9a-f]{64}") || !modelRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid fixed assisted recovery intent");
        Objects.requireNonNull(approvedAt);
        try {
            var before = DeploymentAssistedBuildReviewCodec.decodeDescription(previousPlan);
            var after = DeploymentAssistedBuildReviewCodec.decodeDescription(nextPlan);
            DeploymentAssistedBuildReview.requireFixed(before, after, unitId);
            DeploymentAssistedBuildReview.requireRoots(before, after, previousRoot, nextRoot, originalGraph, unitId);
            DeploymentToolchainBindingCodec.decode(previousTools);
            var evidence = parse(failureEvidence, 8192);
            fields(evidence, Set.of("formatVersion", "exit", "bytes", "outputSHA", "uid", "engine", "tool", "diagnosticSHA", "event"));
            if (!evidence.path("formatVersion").isInt() || evidence.path("formatVersion").intValue() != 1 || !evidence.path("exit").isInt() || evidence.path("exit").intValue() <= 0
                    || !evidence.path("bytes").isIntegralNumber() || !evidence.path("bytes").canConvertToLong()
                    || evidence.path("bytes").longValue() < 0 || evidence.path("bytes").longValue() > before.limits().maxOutputBytes() + 65536L
                    || !string(evidence, "uid").matches("[1-9][0-9]{0,9}") || !string(evidence, "outputSHA").matches("[0-9a-f]{64}")
                    || !string(evidence, "diagnosticSHA").matches("[0-9a-f]{64}") || !string(evidence, "tool").equals(before.facts().get("buildTool"))
                    || !(before.runtime() instanceof gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition.Container container
                            ? container.engine().name().toLowerCase(Locale.ROOT) : "ordinary").equals(string(evidence, "engine"))
                    || !Set.of("DEPLOYMENT_BUILD_FAILED", "DEPLOYMENT_BUILD_LIMIT", "DEPLOYMENT_BUILD_COMPATIBLE_FAILURE", "DEPLOYMENT_BUILD_CANDIDATES_EXHAUSTED").contains(string(evidence, "event")))
                throw new IllegalArgumentException("Original physical failure is incomplete");
            boolean typed = !before.inputs().equals(after.inputs()) && !before.runtime().equals(after.runtime());
            if (!typed && catalogBranch != 1) throw new IllegalArgumentException("Recovery has no typed correction or next fixed catalog branch");
        } catch (SQLException invalid) { throw new IllegalArgumentException("Invalid complete recovery intent", invalid); }
    }

    /** Writes full typed evidence to the formal request, never to a checkpoint. / 将完整类型化依据写入正式请求，绝不写入检查点。
     * @return canonical bounded intent / 规范有界意图
     * @throws SQLException if the formal bound is exceeded / 超出正式边界时 */
    public String encode() throws SQLException {
        return DeploymentJsonDocument.encode(object().put("formatVersion", 1).put("attempt", attempt).put("previousRoot", previousRoot)
                .put("nextRoot", nextRoot).put("originalGraph", originalGraph).put("unitId", unitId).put("previousPlan", previousPlan).put("nextPlan", nextPlan)
                .put("previousScript", previousScript).put("previousTools", previousTools).put("failureEvidence", failureEvidence).put("catalogBranch", catalogBranch)
                .put("approvalAction", approvalAction).put("modelRevision", modelRevision).put("approvedAt", approvedAt.toString()),
                2 * DeploymentAssistedBuildReviewCodec.LIMIT + 2 * 1_048_576);
    }

    /** Reads one exact explicitly versioned consent without granting replay. / 读取一份精确且明确版本化的同意，不授予重放。
     * @param node stored full intent / 已存完整意图
     * @return reconstructed original intent / 已重建原意图
     * @throws SQLException if strict fields or canonical bytes conflict / 严格字段或规范字节冲突时 */
    static DeploymentAssistedBuildIntent decode(JsonNode node) throws SQLException {
        fields(node, Set.of("formatVersion", "attempt", "previousRoot", "nextRoot", "originalGraph", "unitId", "previousPlan", "nextPlan",
                "previousScript", "previousTools", "failureEvidence", "catalogBranch", "approvalAction", "modelRevision", "approvedAt"));
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1 || !node.path("attempt").isInt() || !node.path("catalogBranch").isInt())
            throw new SQLException("Unsupported original recovery intent");
        try {
            var value = new DeploymentAssistedBuildIntent(node.path("attempt").intValue(), string(node, "previousRoot"), string(node, "nextRoot"), string(node, "originalGraph"),
                    string(node, "unitId"), string(node, "previousPlan"), string(node, "nextPlan"), string(node, "previousScript"), string(node, "previousTools"),
                    string(node, "failureEvidence"), node.path("catalogBranch").intValue(), string(node, "approvalAction"), string(node, "modelRevision"), Instant.parse(string(node, "approvedAt")));
            if (!value.encode().equals(node.toString())) throw new SQLException("Noncanonical recovery intent");
            return value;
        } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid original recovery intent", invalid); }
    }

    /** Requires the final observed review to consume this exact previously authorized recovery. / 要求最终已观察审阅消费此次精确的先前已授权恢复。
     * @param review actual complete observed review / 实际完整已观察审阅
     * @return whether every persistent intent binding matches / 每项持久意图绑定是否匹配 */
    boolean matches(DeploymentAssistedBuildReview review) {
        return attempt == review.attempt() && previousRoot.equals(review.previousRoot()) && nextRoot.equals(review.nextRoot())
                && originalGraph.equals(review.originalGraph()) && unitId.equals(review.unitId()) && previousPlan.equals(review.previousPlan())
                && nextPlan.equals(review.nextPlan()) && previousScript.equals(review.previousScript()) && previousTools.equals(review.previousTools())
                && failureEvidence.equals(review.failureEvidence()) && approvalAction.equals(review.recoveryApprovalAction()) && modelRevision.equals(review.modelRevision());
    }
}
