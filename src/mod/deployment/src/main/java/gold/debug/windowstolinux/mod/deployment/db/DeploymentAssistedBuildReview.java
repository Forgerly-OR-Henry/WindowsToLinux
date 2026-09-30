package gold.debug.windowstolinux.mod.deployment.db;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import static gold.debug.windowstolinux.mod.deployment.db.DeploymentJsonDocument.*;

/** One actual known-failure review, never permission to replay a publication. / 一次实际已知失败审阅，绝非重放发布权限。
 * @param attempt original monotonic retry number / 原单调重试次数
 * @param previousRoot original confirmed root review / 原已确认根审阅
 * @param nextRoot new complete confirmed root review / 新完整已确认根审阅
 * @param originalGraph full original graph framing or empty for one unit / 完整原图分界或单单元空值
 * @param unitId original component unit / 原组件单元
 * @param previousPlan full original typed description / 原完整类型化描述
 * @param nextPlan full newly reviewed description / 新完整审阅描述
 * @param previousScript exact previously executed script / 此前实际执行脚本
 * @param nextScript exact newly approved fixed script / 新明确审批固定脚本
 * @param previousTools complete previous installed binding / 原完整安装绑定
 * @param nextTools complete newly observed installed binding / 新完整已观察安装绑定
 * @param failureBinding exact prior input and known failure evidence / 精确原输入及已知失败依据
 * @param failureEvidence full bounded known receipt metadata / 完整有界已知回执元数据
 * @param approvalAction exact action digest reviewed by the host / 宿主审核精确动作摘要
 * @param recoveryApprovalAction prior typed environment recovery consent SHA / 先前类型化环境恢复同意SHA
 * @param modelRevision frozen task model route / 冻结任务模型路由
 * @param approvedAt actual explicit review time / 实际明确审阅时刻 */
public record DeploymentAssistedBuildReview(int attempt, String previousRoot, String nextRoot, String originalGraph, String unitId,
        String previousPlan, String nextPlan, String previousScript, String nextScript, String previousTools,
        String nextTools, String failureBinding, String failureEvidence, String approvalAction, String recoveryApprovalAction, String modelRevision,
        Instant approvedAt) {
    /** Validates fixed scope and genuine typed or observed environment changes. / 校验固定范围及真实类型化或已观察环境变化。
     * @param attempt monotonic retry / 单调重试
     * @param previousRoot original root SHA / 原根SHA
     * @param nextRoot new root SHA / 新根SHA
     * @param originalGraph original complete graph or empty / 原完整图或空值
     * @param unitId original unit / 原单元
     * @param previousPlan original full description / 原完整描述
     * @param nextPlan new full description / 新完整描述
     * @param previousScript original executed script / 原执行脚本
     * @param nextScript newly reviewed script / 新审阅脚本
     * @param previousTools original tools / 原工具
     * @param nextTools newly observed tools / 新观察工具
     * @param failureBinding original failure binding / 原失败绑定
     * @param failureEvidence exact known evidence / 精确已知依据
     * @param approvalAction actual action SHA / 实际动作SHA
     * @param recoveryApprovalAction original environment recovery consent SHA / 原环境恢复同意SHA
     * @param modelRevision frozen model SHA / 冻结模型SHA
     * @param approvedAt actual approval time / 实际审批时刻 */
    public DeploymentAssistedBuildReview {
        if (attempt < 1 || attempt > 2 || !previousRoot.matches("[0-9a-f]{64}") || !nextRoot.matches("[0-9a-f]{64}")
                || previousRoot.equals(nextRoot) || !unitId.matches("[a-z0-9][a-z0-9-]{0,62}")
                || previousScript.length() > 1_048_576 || nextScript.length() > 1_048_576
                || previousScript.indexOf('\0') >= 0 || nextScript.indexOf('\0') >= 0
                || !failureBinding.matches("[0-9a-f]{64}") || failureEvidence.length() > 8192
                || !approvalAction.matches("[0-9a-f]{64}") || !recoveryApprovalAction.matches("[0-9a-f]{64}") || !modelRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid assisted build review binding");
        Objects.requireNonNull(approvedAt);
        try {
            var before = DeploymentAssistedBuildReviewCodec.decodeDescription(previousPlan);
            var after = DeploymentAssistedBuildReviewCodec.decodeDescription(nextPlan);
            requireFixed(before, after, unitId);
            requireRoots(before, after, previousRoot, nextRoot, originalGraph, unitId);
            var oldTools = DeploymentToolchainBindingCodec.decode(previousTools);
            var newTools = DeploymentToolchainBindingCodec.decode(nextTools);
            boolean corrected = !before.inputs().equals(after.inputs()) && !before.runtime().equals(after.runtime());
            boolean environmentChanged = !oldTools.selections().stream().map(selection -> List.of(selection.requirement().ecosystem().name(),
                    selection.requirement().purpose().name(), selection.version().text(), selection.directory(), selection.origin().name(), selection.source(), selection.sha256())).toList()
                    .equals(newTools.selections().stream().map(selection -> List.of(selection.requirement().ecosystem().name(), selection.requirement().purpose().name(),
                            selection.version().text(), selection.directory(), selection.origin().name(), selection.source(), selection.sha256())).toList());
            if ((!corrected && !environmentChanged) || previousScript.equals(nextScript) && previousTools.equals(nextTools))
                throw new IllegalArgumentException("No actual corrected input or changed environment");
            var proof = parse(failureEvidence, 8192);
            fields(proof, Set.of("formatVersion", "exit", "bytes", "outputSHA", "uid", "engine", "tool", "diagnosticSHA", "event"));
            if (!proof.path("formatVersion").isInt() || proof.path("formatVersion").intValue() != 1)
                throw new IllegalArgumentException("Unsupported physical build evidence version");
            if (!proof.path("exit").isInt() || proof.path("exit").intValue() <= 0 || !proof.path("bytes").isIntegralNumber()
                    || !proof.path("bytes").canConvertToLong() || proof.path("bytes").longValue() < 0
                    || !string(proof, "uid").matches("[1-9][0-9]{0,9}") || !string(proof, "tool").equals(before.facts().get("buildTool"))
                    || !engine(before.runtime()).equals(string(proof, "engine")) || proof.path("bytes").longValue() > before.limits().maxOutputBytes() + 65536L
                    || !string(proof, "outputSHA").matches("[0-9a-f]{64}") || !string(proof, "diagnosticSHA").matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Incomplete known physical build failure");
            if (!Set.of("DEPLOYMENT_BUILD_FAILED", "DEPLOYMENT_BUILD_LIMIT", "DEPLOYMENT_BUILD_COMPATIBLE_FAILURE", "DEPLOYMENT_BUILD_CANDIDATES_EXHAUSTED")
                    .contains(string(proof, "event")) || !failureBinding.equals(failureBinding(previousPlan, previousScript, previousTools, failureEvidence)))
                throw new IllegalArgumentException("Original executed input and failure binding changed");
        } catch (SQLException invalid) { throw new IllegalArgumentException("Invalid durable build review", invalid); }
    }

    /** Requires original source, target, configuration and every unrelated runtime contract to remain fixed. / 要求原来源、目标、配置及所有无关运行契约固定。
     * @param before original complete plan / 原完整计划
     * @param after new complete plan / 新完整计划
     * @param unit original unit / 原单元 */
    static void requireFixed(DeploymentAssistedBuildReviewCodec.Description before,
            DeploymentAssistedBuildReviewCodec.Description after, String unit) {
        var a = before.runtime().workload(); var b = after.runtime().workload();
        if (!unit.equals(before.applicationId()) || !unit.equals(after.applicationId()) || !before.serverId().equals(after.serverId())
                || !before.source().equals(after.source()) || !before.facts().equals(after.facts()) || !before.configuration().equals(after.configuration())
                || !before.requirements().equals(after.requirements()) || !before.userAccessUrl().equals(after.userAccessUrl())
                || !before.secrets().equals(after.secrets()) || !before.resources().equals(after.resources()) || !before.limits().equals(after.limits())
                || !before.steps().equals(after.steps()) || before.containerRisk() != after.containerRisk() || before.adapterRisk() != after.adapterRisk()
                || before.runtime().getClass() != after.runtime().getClass() || before.runtime().identityPolicy() != after.runtime().identityPolicy()
                || !before.runtime().healthCheck().equals(after.runtime().healthCheck()) || a.mode() != b.mode() || a.reviewed() != b.reviewed()
                || !a.command().equals(b.command()) || !a.workingDirectory().equals(b.workingDirectory()) || !a.endpoints().equals(b.endpoints())
                || !a.verification().equals(b.verification()) || !a.expectedOutput().equals(b.expectedOutput()) || !a.client().equals(b.client())
                || !a.inputs().equals(b.inputs()) || !a.companions().equals(b.companions()) || !a.buildDirectory().equals(b.buildDirectory()) || !a.workers().equals(b.workers()))
            throw new IllegalArgumentException("Assisted correction changed immutable original scope");
    }

    /** Proves one complete child transition belongs to the exact original root. / 证明一次完整子转换属于精确原根。
     * @param before original child / 原子节点
     * @param after corrected child / 修正子节点
     * @param previous original root / 原根
     * @param next corrected root / 修正根
     * @param graph original complete framing or empty / 原完整分界或空值
     * @param unit actual unit / 实际单元
     * @throws SQLException if durable graph facts conflict / 持久图事实冲突时 */
    static void requireRoots(DeploymentAssistedBuildReviewCodec.Description before, DeploymentAssistedBuildReviewCodec.Description after,
            String previous, String next, String graph, String unit) throws SQLException {
        if (graph.isEmpty()) {
            if (!previous.equals(before.reviewedRevision()) || !next.equals(after.reviewedRevision()))
                throw new IllegalArgumentException("Single-unit review does not match its original root");
            return;
        }
        var original = DeploymentAssistedBuildReviewCodec.decodeGraph(graph);
        var component = original.namespaces().entrySet().stream().filter(entry -> entry.getValue().equals(unit)).map(Map.Entry::getKey).findFirst().orElseThrow();
        if (!original.identities().get(component).equals(before.reviewedRevision()) || !previous.equals(DeploymentReviewedPlanDigest.graphFrom(original)))
            throw new IllegalArgumentException("Original graph child does not belong to its root");
        var identities = new LinkedHashMap<>(original.identities()); identities.put(component, after.reviewedRevision());
        if (!next.equals(DeploymentReviewedPlanDigest.graphFrom(new DeploymentReviewedPlanDigest.GraphValues(original.applicationId(), original.healthOwner(),
                original.health(), original.order(), original.namespaces(), original.dependencies(), identities))))
            throw new IllegalArgumentException("Corrected child does not produce the exact next graph root");
    }

    /** Requires another correction of one unit to consume its exact last confirmed full plan and executed inputs. / 要求同单元另一纠正消费其精确最近已确认完整计划及执行输入。
     * @param prior original monotonic reviews / 原单调审阅
     * @param unit original unit / 原单元
     * @param plan complete actual previous plan / 完整实际原计划
     * @param script actual previously executed script / 实际此前执行脚本
     * @param tools actual previously installed binding / 实际此前安装绑定
     * @throws SQLException if an earlier full binding was replaced / 先前完整绑定被替换时 */
    static void requireContinuation(List<DeploymentAssistedBuildReview> prior, String unit, String plan, String script, String tools) throws SQLException {
        for (int index = prior.size() - 1; index >= 0; index--) {
            var previous = prior.get(index);
            if (!previous.unitId().equals(unit)) continue;
            if (!previous.nextPlan().equals(plan) || !previous.nextScript().equals(script) || !previous.nextTools().equals(tools))
                throw new SQLException("Another correction changed its last confirmed complete unit binding");
            return;
        }
    }

    /** Binds an actual fixed script, observed tool identity and complete failure to its source plan. / 将实际固定脚本、已观察工具身份及完整失败绑定到来源计划。
     * @param plan complete original plan / 完整原计划
     * @param script exact executed script / 精确已执行脚本
     * @param tools complete executed tool binding / 完整已执行工具绑定
     * @param evidence complete known failure / 完整已知失败
     * @return original lowercase failure SHA / 原小写失败SHA */
    public static String failureBinding(String plan, String script, String tools, String evidence) {
        try {
            var semanticPlan = parse(plan, DeploymentAssistedBuildReviewCodec.LIMIT);
            semanticPlan.remove(List.of("reviewedAt", "reviewedRevision"));
            String actualInputs = DeploymentJsonDocument.encode(semanticPlan, DeploymentAssistedBuildReviewCodec.LIMIT);
            var bytes = new java.io.ByteArrayOutputStream();
            try (var output = new java.io.DataOutputStream(bytes)) {
                for (String value : List.of("deployment-assisted-build-failure-v1", actualInputs, script, tools, evidence)) {
                    byte[] field = value.getBytes(java.nio.charset.StandardCharsets.UTF_8); output.writeInt(field.length); output.write(field);
                }
            }
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (SQLException invalid) { throw new IllegalArgumentException("Original failure plan is invalid", invalid); }
        catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("JDK failure binding unavailable", impossible); }
    }

    /** Reads the fixed engine from the complete reviewed runtime. / 从完整审阅运行定义读取固定引擎。
     * @param runtime original reviewed runtime / 原审阅运行定义
     * @return exact restricted engine / 精确受限引擎 */
    private static String engine(gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition runtime) {
        return runtime instanceof gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition.Container container
                ? container.engine().name().toLowerCase(Locale.ROOT) : "ordinary";
    }

    /** Encodes complete reviewed values in a bounded formal request, never a checkpoint. / 在有界正式请求中编码完整审阅值，绝非检查点。
     * @return canonical review / 规范审阅
     * @throws SQLException if the supported byte bound is exceeded / 超过受支持字节边界时 */
    public String encode() throws SQLException {
        var node = object().put("formatVersion", 1).put("attempt", attempt).put("previousRoot", previousRoot).put("nextRoot", nextRoot).put("originalGraph", originalGraph).put("unitId", unitId)
                .put("previousPlan", previousPlan).put("nextPlan", nextPlan).put("previousScript", previousScript).put("nextScript", nextScript)
                .put("previousTools", previousTools).put("nextTools", nextTools).put("failureBinding", failureBinding).put("failureEvidence", failureEvidence)
                .put("approvalAction", approvalAction).put("recoveryApprovalAction", recoveryApprovalAction).put("modelRevision", modelRevision).put("approvedAt", approvedAt.toEpochMilli());
        return DeploymentJsonDocument.encode(node, 2 * DeploymentAssistedBuildReviewCodec.LIMIT + 4 * 1_048_576);
    }

    /** Reads exact supported complete review fields without implicit type conversions. / 不隐式转换类型地读取精确受支持完整审阅字段。
     * @param node stored canonical review / 已存规范审阅
     * @return strict original review / 严格原审阅
     * @throws SQLException if identity or canonical framing differs / 身份或规范信封不符时 */
    static DeploymentAssistedBuildReview decode(JsonNode node) throws SQLException {
        fields(node, Set.of("formatVersion", "attempt", "previousRoot", "nextRoot", "originalGraph", "unitId", "previousPlan", "nextPlan", "previousScript", "nextScript",
                "previousTools", "nextTools", "failureBinding", "failureEvidence", "approvalAction", "recoveryApprovalAction", "modelRevision", "approvedAt"));
        if (!node.path("formatVersion").isInt() || node.path("formatVersion").intValue() != 1 || !node.path("attempt").isInt() || !node.path("approvedAt").isIntegralNumber() || !node.path("approvedAt").canConvertToLong())
            throw new SQLException("Invalid actual build review attempt or time");
        try {
            var review = new DeploymentAssistedBuildReview(node.path("attempt").intValue(), string(node, "previousRoot"), string(node, "nextRoot"),
                    string(node, "originalGraph"), string(node, "unitId"), string(node, "previousPlan"), string(node, "nextPlan"), string(node, "previousScript"), string(node, "nextScript"),
                    string(node, "previousTools"), string(node, "nextTools"), string(node, "failureBinding"), string(node, "failureEvidence"),
                    string(node, "approvalAction"), string(node, "recoveryApprovalAction"), string(node, "modelRevision"), Instant.ofEpochMilli(node.path("approvedAt").longValue()));
            if (!review.encode().equals(node.toString())) throw new SQLException("Noncanonical actual build review");
            return review;
        } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid actual build review", invalid); }
    }
}
