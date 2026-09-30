package gold.debug.windowstolinux.mod.deployment.api;

import java.util.List;
import java.util.UUID;

/** Public durable handoff progress. / 公开的持久交接进度。
 * @param id original handoff ID / 原交接ID
 * @param state durable phase / 持久阶段
 * @param application exact application / 精确应用
 * @param revisionSha256 reviewed revision / 已审阅修订
 * @param blockers outstanding recovery blockers / 未解决恢复阻碍 */
public record DeploymentHandoff(UUID id, DeploymentHandoffState state, DeploymentApplicationRef application,
        String revisionSha256, List<String> blockers) {
    /** Copies the blocker list. / 复制阻碍列表。
     * @param id original handoff ID / 原交接ID
     * @param state durable phase / 持久阶段
     * @param application exact application / 精确应用
     * @param revisionSha256 reviewed revision / 已审阅修订
     * @param blockers outstanding recovery blockers / 未解决恢复阻碍 */
    public DeploymentHandoff { blockers = List.copyOf(blockers); }
}
