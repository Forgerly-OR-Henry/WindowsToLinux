package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan;

import java.util.Map;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentAssistedBuildReviewCodec;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReviewedPlanDigest;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentRequest;

/** Stable pre-execution review identity using the unchanged shared framed writer. / 使用不变共用分界写入器的稳定执行前审阅身份。 */
public final class ReviewedPlanIdentity {
    /** Prevents construction. / 禁止构造。 */
    private ReviewedPlanIdentity() {}

    /** Hashes original live inputs without changing source authority or original bytes. / 哈希原活动输入，不改变来源权限或原字节。
     * @param request complete original reviewed input / 完整原审阅输入
     * @return original lowercase SHA / 原小写SHA */
    public static String from(ReviewedDeploymentRequest request) {
        var facts = request.facts();
        return DeploymentReviewedPlanDigest.from(new DeploymentReviewedPlanDigest.Values(request.server().id(),
                request.material().snapshot().id(), request.archive().sourceManifestSha256(), request.archive().sha256(),
                request.archive().byteCount(), request.archive().uncompressedByteCount(),
                Map.of("applicationId", facts.applicationId(), "projectType", facts.projectType().name(), "buildTool", facts.buildTool().name(),
                        "buildDirectory", facts.buildDirectory(), "support", facts.support().level().name(), "language", facts.support().language().name(),
                        "architecture", facts.support().architecture(), "framework", facts.support().framework()),
                facts.toolchainRequirements(), request.configuration(), request.secretReferences(), request.resources(), request.runtime(),
                request.userAccessUrl(), request.limits(), request.review().confirmedAt(), request.containerDaemonRiskAccepted(),
                request.experimentalAdapterRiskAccepted()));
    }

    /** Copies a real executed plan into its full durable description without substituting its source facts. / 将真实执行计划复制为完整持久描述，不替换源码事实。
     * @param plan actual deterministic plan / 实际确定性计划
     * @param inputs four originally reviewed typed inputs / 四项原审阅类型化输入
     * @return complete source-bound persistent values / 完整来源绑定持久值 */
    public static DeploymentAssistedBuildReviewCodec.Description description(ReviewedDeploymentPlan plan, Map<String, String> inputs) {
        var request = plan.request(); var facts = request.facts();
        var typed = new java.util.LinkedHashMap<String, String>();
        for (String key : java.util.List.of("primary", "secondary", "version", "jvmTarget")) typed.put(key, inputs.getOrDefault(key, ""));
        return new DeploymentAssistedBuildReviewCodec.Description(facts.applicationId(), request.server().id(),
                Map.of("snapshot", request.material().snapshot().id().toString(), "manifest", request.archive().sourceManifestSha256(),
                        "archive", request.archive().sha256(), "relativeRoot", request.archive().relativeRoot(),
                        "bytes", Long.toString(request.archive().byteCount()), "uncompressedBytes", Long.toString(request.archive().uncompressedByteCount())),
                Map.of("projectType", facts.projectType().name(), "buildTool", facts.buildTool().name(), "buildDirectory", facts.buildDirectory(),
                        "support", facts.support().level().name(), "language", facts.support().language().name(), "architecture", facts.support().architecture(), "framework", facts.support().framework()),
                facts.toolchainRequirements(), request.userAccessUrl(), request.configuration(), request.secretReferences(), request.resources(), request.runtime(),
                request.limits(), plan.steps().stream().map(Enum::name).toList(), typed, request.review().confirmedAt(), request.containerDaemonRiskAccepted(),
                request.experimentalAdapterRiskAccepted(), from(request));
    }
}
