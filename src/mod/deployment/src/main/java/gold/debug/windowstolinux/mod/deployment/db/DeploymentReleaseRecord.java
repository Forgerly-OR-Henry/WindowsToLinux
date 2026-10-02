package gold.debug.windowstolinux.mod.deployment.db;

import java.time.Instant;
import java.util.*;

/** Immutable historical documents and the current recorded release disposition. / 不可变历史文档与当前记录的发布状态。
 * @param applicationId original application identity / 原应用身份
 * @param identity immutable release identity / 不可变发布身份
 * @param executionId original creating execution / 原创建执行
 * @param state current recorded disposition / 当前记录状态
 * @param runtimeJson original versioned runtime manifest / 原版本化运行清单
 * @param healthJson original versioned application health contract / 原版本化应用健康契约
 * @param createdAt original history creation time / 原历史创建时间 */
public record DeploymentReleaseRecord(String applicationId, DeploymentReleaseIdentity identity, UUID executionId,
                                      DeploymentReleaseState state, String runtimeJson, String healthJson,
                                      Instant createdAt) {
    /** Retains original history without replacing observations with current application settings. / 保留原历史，不用当前应用设置替换观测。
     * @param applicationId original application identity / 原应用身份
     * @param identity immutable release identity / 不可变发布身份
     * @param executionId original creating execution / 原创建执行
     * @param state current recorded disposition / 当前记录状态
     * @param runtimeJson original runtime manifest / 原运行清单
     * @param healthJson original health contract / 原健康契约
     * @param createdAt original creation time / 原创建时间 */
    public DeploymentReleaseRecord {
        if (applicationId == null || !applicationId.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("Invalid release application");
        Objects.requireNonNull(identity);
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(state);
        Objects.requireNonNull(runtimeJson);
        Objects.requireNonNull(healthJson);
        Objects.requireNonNull(createdAt);
    }

    /** Documented release dispositions; unresolved states are never promoted by read operations. / 文档发布状态，读取操作绝不提升未决状态。 */
    public enum DeploymentReleaseState {
        /** Prepared candidate. / 已准备候选。 */
        CANDIDATE,
        /** Verified active release. / 已验证活动发布。 */
        ACTIVE,
        /** Retained previous release. / 保留的先前发布。 */
        RETIRED,
        /** Confirmed failed candidate. / 已确认失败候选。 */
        FAILED,
        /** Unresolved activation result. / 未决激活结果。 */
        UNKNOWN
    }
}
