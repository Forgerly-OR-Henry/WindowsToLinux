package gold.debug.windowstolinux.mod.deployment.api;

/** Verified target activation and cleanup result. / 已验证目标激活与清理结果。
 * @param handoff durable handoff / 持久交接
 * @param accessDescription verified access location / 已验证访问位置
 * @param healthEvidence bounded health evidence / 有界健康依据
 * @param cleanupComplete whether owned staging was cleaned / 所属暂存是否已清理 */
public record DeploymentActivation(DeploymentHandoff handoff, String accessDescription, String healthEvidence,
        boolean cleanupComplete) {}
