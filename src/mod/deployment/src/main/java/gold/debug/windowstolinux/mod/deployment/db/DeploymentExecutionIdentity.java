package gold.debug.windowstolinux.mod.deployment.db;

import java.util.*;

import gold.debug.windowstolinux.appapi.server.ServerRef;

/** Immutable identity of the original reviewed operation. / 原审阅操作的不可变身份。
 * @param executionId business operation ID / 业务操作ID
 * @param taskId original platform task / 原平台任务
 * @param projectId optional saved project / 可选已保存项目
 * @param applicationId exact target application / 精确目标应用
 * @param server original server reference / 原服务器引用
 * @param mode independent execution engine / 独立执行引擎
 * @param reviewedRevision original reviewed digest / 原审阅摘要
 */
public record DeploymentExecutionIdentity(UUID executionId, UUID taskId, Optional<UUID> projectId, String applicationId,
        ServerRef server, DeploymentExecutionMode mode, String reviewedRevision) {
    /** Validates exact immutable metadata without normalizing identifiers. / 校验精确不可变元数据，不规范化标识。
     * @param executionId business operation ID / 业务操作ID
     * @param taskId original platform task / 原平台任务
     * @param projectId optional saved project / 可选已保存项目
     * @param applicationId exact target application / 精确目标应用
     * @param server original server reference / 原服务器引用
     * @param mode independent execution engine / 独立执行引擎
     * @param reviewedRevision original reviewed digest / 原审阅摘要
     */
    public DeploymentExecutionIdentity {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(server);
        Objects.requireNonNull(mode);
        if (!DeploymentConfigurationRepository.boundedIdentity(applicationId, "applicationId").equals(applicationId))
            throw new IllegalArgumentException("Application identity must be exact");
        if (server.id().isBlank() || server.id().length() > 512 || server.id().indexOf(0) >= 0)
            throw new IllegalArgumentException("Invalid execution server");
        if (reviewedRevision == null || !reviewedRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid reviewed revision");
    }

    /** Separate execution engines sharing only durable business records. / 仅共享持久业务记录的独立执行引擎。 */
    public enum DeploymentExecutionMode {
        /** Deterministic engine. / 确定性引擎。 */
        STATIC,
        /** AI-assisted deterministic engine. / AI辅助确定性引擎。 */
        ASSISTED,
        /** Independent Agent engine. / 独立Agent引擎。 */
        AGENT
    }
}
