package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;

/** Small non-secret original-operation evidence retained by the host checkpoint. / 由宿主检查点保留的小型非秘密原操作依据。
 * @param formatVersion exact original checkpoint version / 精确原检查点版本
 * @param target original task and fixed target / 原任务及固定目标
 * @param operation original environment UUID / 原环境 UUID
 * @param bodySha exact fixed resource digest / 精确固定资源摘要
 * @param stage original dispatch or observed stage / 原发送或观测阶段
 * @param requestSha exact original stage request digest or none / 精确原阶段请求摘要或 none
 * @param observation original latest verified observation / 原最近已核观测 */
record DeploymentEnvironmentState(int formatVersion, RemoteTarget target, UUID operation, String bodySha,
                                  String stage, String requestSha,
                                  Optional<DeploymentEnvironmentObservation> observation) {
    /** Validates strict fixed identity before any recovery connection. / 任何恢复连接前校验严格固定身份。
     * @param formatVersion checkpoint version / 检查点版本
     * @param target fixed target / 固定目标
     * @param operation original operation / 原操作
     * @param bodySha unchanged resource digest / 未改变资源摘要
     * @param stage original stage / 原阶段
     * @param requestSha original request digest / 原请求摘要
     * @param observation original observation / 原观测 */
    DeploymentEnvironmentState {
        java.util.Objects.requireNonNull(target);
        java.util.Objects.requireNonNull(operation);
        java.util.Objects.requireNonNull(observation);
        if (formatVersion != 1 || bodySha == null || !bodySha.matches("[0-9a-f]{64}")
            || requestSha == null || !requestSha.equals("none") && !requestSha.matches("[0-9a-f]{64}")
            || !Set.of("INTENT", "OBSERVED", "PREPARE_DISPATCHED", "REBOOT_PENDING", "ENFORCE_DISPATCHED",
            "ENFORCEMENT_PENDING", "COMMIT_DISPATCHED", "SELINUX_COMPLETE", "PACKAGES_DISPATCHED",
            "PACKAGES_INSTALLED", "HELPER_DISPATCHED", "HELPER_INSTALLED", "VERIFIED", "FAILED").contains(stage)
            || !stage.equals("INTENT") && (target.fingerprint().isEmpty() || observation.isEmpty()))
            throw new IllegalArgumentException("Invalid original environment checkpoint identity");
        if (stage.equals("INTENT") && (!requestSha.equals("none") || observation.isPresent())
            || stage.equals("OBSERVED") && !requestSha.equals("none"))
            throw new IllegalArgumentException("Unsent environment identity contains dispatched evidence");
    }

    /** Produces one immutable next state. / 生成一个不可变后续状态。
     * @param next next stage / 后续阶段
     * @param request exact next request digest / 精确后续请求摘要
     * @param observed verified new observation / 已核新观测
     * @return exact next state / 精确后续状态 */
    DeploymentEnvironmentState next(String next, String request, DeploymentEnvironmentObservation observed) {
        return new DeploymentEnvironmentState(1, target, operation, bodySha, next, request, Optional.of(observed));
    }
}
