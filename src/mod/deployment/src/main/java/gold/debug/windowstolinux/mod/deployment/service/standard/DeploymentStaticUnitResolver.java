package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentPublicationRepository;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;

/** Resolves the original managed owner or generates an unpersisted random owner for first publication. / 解析原受管所有者，首次发布则生成尚未持久化的随机所有者。 */
public final class DeploymentStaticUnitResolver {
    /** Original task-bound deployment records. / 原任务绑定的部署记录。 */
    private final DeploymentPublicationRepository publications;

    /** Binds original deployment storage without owning the source or target. / 绑定原部署存储，不取得来源或目标所有权。
     * @param database original Mod database / 原 Mod 数据库 */
    public DeploymentStaticUnitResolver(ModDatabase database) {
        this.publications = new DeploymentPublicationRepository(Objects.requireNonNull(database));
    }

    /** Reuses exact canonical ownership on update and never derives it from source or plan SHA. / 更新时复用精确规范所有权，绝不从来源或计划摘要派生。
     * @param task original admitted task / 原准入任务
     * @param applicationId reviewed managed unit ID / 已审阅受管单元 ID
     * @param server exact selected server / 精确选定服务器
     * @return original or newly proposed identity / 原身份或新建议身份
     * @throws SQLException if stored identity is unavailable or conflicts / 已存身份不可用或冲突时
     * @throws ApiException if original task access is unavailable / 原任务访问不可用时 */
    public DeploymentUnitIdentity resolve(TaskContext task, String applicationId, ServerRef server)
            throws SQLException, ApiException {
        Objects.requireNonNull(task);
        Objects.requireNonNull(server);
        var saved = publications.findUnit(task, applicationId);
        if (saved.isPresent()) {
            var existing = saved.orElseThrow();
            if (!existing.server().equals(server))
                throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "service.deployment.server-conflict",
                        "Managed application belongs to a different server", Map.of(), Optional.empty()));
            if (!existing.equals(
                    DeploymentUnitIdentity.forManaged(applicationId, server, existing.ownershipManifestSha256())))
                throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "service.deployment.identity-invalid",
                        "Stored managed application identity needs review", Map.of(), Optional.empty()));
            return existing;
        }
        byte[] ownership = new byte[32];
        new SecureRandom().nextBytes(ownership);
        return DeploymentUnitIdentity.forManaged(applicationId, server, HexFormat.of().formatHex(ownership));
    }
}
