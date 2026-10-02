package gold.debug.windowstolinux.mod.deployment.linux;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact read-only helper observation for one original publication task. / 一项原发布任务的精确只读 helper 观察。
 * @param current original owned current digest, if any / 原所属当前摘要；可能为空
 * @param currentKind saved current release kind / 当前发布的保存类别
 * @param currentMode saved current execution mode / 当前执行模式
 * @param snapshot original task-bound rollback snapshot / 原任务绑定回滚快照
 * @param previousDigest snapshot's old content digest / 快照的旧内容摘要
 * @param previousKind snapshot's old runtime kind / 快照的旧运行类别
 * @param previousRunning snapshot's prior activity, if present / 快照旧运行状态；可能为空
 * @param maintenance whether the exact attempted release owns maintenance / 尝试发布是否持有精确维护标记
 * @param candidate whether the original candidate remains / 原候选是否仍存在
 * @param buildActive whether the original candidate build remains active / 原候选构建是否仍活动
 * @param unitPresent whether the application unit exists / 应用单元是否存在
 * @param containerPresent whether the application container exists, if applicable / 应用容器是否存在；若适用
 * @param retainedCount exact owned release count after optional retention / 可选裁剪后的精确所属发布数量 */
public record DeploymentOriginalPublicationStatus(Optional<String> current, String currentKind, String currentMode,
                                                  boolean snapshot, Optional<String> previousDigest,
                                                  String previousKind, Optional<Boolean> previousRunning,
                                                  boolean maintenance, boolean candidate, boolean buildActive,
                                                  boolean unitPresent,
                                                  Optional<Boolean> containerPresent, int retainedCount) {
    /** Rejects internally inconsistent or unbounded remote facts. / 拒绝自相矛盾或无界的远端事实。
     * @param current owned release / 所属发布
     * @param currentKind release kind / 发布类别
     * @param currentMode release mode / 发布模式
     * @param snapshot task snapshot / 任务快照
     * @param previousDigest old release / 旧发布
     * @param previousKind old kind / 旧类别
     * @param previousRunning prior activity / 旧活动状态
     * @param maintenance exact marker / 精确标记
     * @param candidate original candidate / 原候选
     * @param buildActive original build / 原构建
     * @param unitPresent application unit / 应用单元
     * @param containerPresent application container / 应用容器
     * @param retainedCount exact release count / 精确发布数 */
    public DeploymentOriginalPublicationStatus {
        current = Objects.requireNonNull(current);
        currentKind = Objects.requireNonNull(currentKind);
        currentMode = Objects.requireNonNull(currentMode);
        previousDigest = Objects.requireNonNull(previousDigest);
        previousKind = Objects.requireNonNull(previousKind);
        previousRunning = Objects.requireNonNull(previousRunning);
        containerPresent = Objects.requireNonNull(containerPresent);
        if (current.isPresent() && !current.orElseThrow().matches("[0-9a-f]{64}")
            || previousDigest.isPresent() && !previousDigest.orElseThrow().matches("[0-9a-f]{64}")
            || current.isEmpty() && (!currentKind.equals("none") || !currentMode.equals("none"))
            || current.isPresent() && (!Set.of("ordinary", "deployment", "container").contains(currentKind)
            || !Set.of("unknown", "DAEMON", "ON_DEMAND").contains(currentMode))
            || !snapshot
            && (previousDigest.isPresent() || previousRunning.isPresent() || !previousKind.equals("none"))
            || snapshot && (previousDigest.isEmpty() || previousRunning.isEmpty()
            || !Set.of("ordinary", "deployment", "container").contains(previousKind))
            || retainedCount < 0 || retainedCount > 1000 || current.isPresent() && retainedCount == 0)
            throw new IllegalArgumentException("Invalid original publication observation");
    }
}
