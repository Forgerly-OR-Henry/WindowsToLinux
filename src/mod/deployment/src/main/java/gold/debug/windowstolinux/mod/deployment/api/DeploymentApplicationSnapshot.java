package gold.debug.windowstolinux.mod.deployment.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Copied application facts; only inspect refreshes remote status. / 应用事实副本；只有检查会刷新远端状态。
 * @param application exact application identity / 精确应用身份
 * @param displayName current display text / 当前展示名称
 * @param revisionSha256 complete current release digest / 当前完整发布摘要
 * @param ownershipSha256 reviewed ownership digest / 已审阅归属摘要
 * @param state last observed run state / 最后观测运行状态
 * @param observedAt last observation time / 最后观测时间
 * @param runtime opaque versioned runtime / 不透明版本化运行数据
 * @param bindings reviewed data bindings / 已审阅数据绑定
 * @param healthPolicySha256 exact health policy digest / 精确健康策略摘要 */
public record DeploymentApplicationSnapshot(DeploymentApplicationId application, String displayName,
                                            String revisionSha256, String ownershipSha256, ApplicationRunState state,
                                            Instant observedAt,
                                            VersionedRuntime runtime, List<DeploymentBinding> bindings,
                                            String healthPolicySha256) {
    /** Copies public facts without interpreting runtime bytes. / 复制公开事实，不解释运行字节。
     * @param application exact application identity / 精确应用身份
     * @param displayName current display text / 当前展示名称
     * @param revisionSha256 complete current release digest / 当前完整发布摘要
     * @param ownershipSha256 reviewed ownership digest / 已审阅归属摘要
     * @param state last observed run state / 最后观测运行状态
     * @param observedAt last observation time / 最后观测时间
     * @param runtime opaque versioned runtime / 不透明版本化运行数据
     * @param bindings reviewed data bindings / 已审阅数据绑定
     * @param healthPolicySha256 exact health policy digest / 精确健康策略摘要 */
    public DeploymentApplicationSnapshot {
        Objects.requireNonNull(application);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(revisionSha256);
        Objects.requireNonNull(ownershipSha256);
        Objects.requireNonNull(state);
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(runtime);
        bindings = List.copyOf(bindings);
        Objects.requireNonNull(healthPolicySha256);
    }
}
