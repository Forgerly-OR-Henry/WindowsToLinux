package gold.debug.windowstolinux.mod.deployment.service.core;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.mod.deployment.db.*;

/** Immutable reviewed publication facts available before any platform reservation. / 平台预留前已具备的不可变审阅发布事实。
 * @param executionId stable business operation / 稳定业务操作
 * @param project reviewed saved source, if present / 已审阅来源项目，可为空
 * @param applicationId exact application association / 精确应用关联
 * @param server admitted original server / 已准入原服务器
 * @param mode selected execution mode / 所选执行模式
 * @param reviewedRevision reviewed content digest / 已审阅内容摘要
 * @param references exact durable application, unit and secret-revision associations / 精确的应用、单元及秘密修订持久关联
 * @param createdAt original observed creation time / 原始观测创建时间 */
public record DeploymentPublicationPlan(UUID executionId, Optional<DeploymentProjectRecord> project,
                                        String applicationId, ServerRef server,
                                        DeploymentExecutionIdentity.DeploymentExecutionMode mode,
                                        String reviewedRevision, List<DeploymentReferenceIntent> references,
                                        Instant createdAt) {
    /** Copies the original request and requires its application server association. / 复制原请求并要求其应用服务器关联。
     * @param executionId original operation / 原操作
     * @param project reviewed source / 已审阅来源
     * @param applicationId original application / 原应用
     * @param server original server / 原服务器
     * @param mode original mode / 原模式
     * @param reviewedRevision original review digest / 原审阅摘要
     * @param references complete original associations / 完整原关联
     * @param createdAt original time / 原时间 */
    public DeploymentPublicationPlan {
        Objects.requireNonNull(executionId);
        Objects.requireNonNull(project);
        Objects.requireNonNull(server);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(createdAt);
        references = List.copyOf(references);
        if (references.size() != new HashSet<>(references).size() || references.isEmpty())
            throw new IllegalArgumentException("Original references must be unique");
        if (reviewedRevision == null || !reviewedRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid reviewed revision");
        if (!references.contains(
            DeploymentReferenceIntent.server(server, DeploymentReferenceKey.applicationKey(applicationId))))
            throw new IllegalArgumentException("Application server association is required");
        for (var intent : references)
            DeploymentReferenceKey.requireOriginal(intent, applicationId, server);
    }
}
