package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentPublishStore;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;

/** Resolves exact managed owners and reviewed credential identities before original publication. / 在原发布前解析精确受管所有者及已审阅凭据身份。 */
public final class DeploymentStaticUnitResolver {
    /** Original task-bound deployment records. / 原任务绑定的部署记录。 */
    private final DeploymentPublishStore publications;

    /** Original immutable secret revisions, without access to secret values. / 原不可变秘密修订，不访问秘密值。 */
    private final DeploymentSecretStore secrets;

    /** Binds original deployment storage without owning the source or target. / 绑定原部署存储，不取得来源或目标所有权。
     * @param database original Mod database / 原 Mod 数据库 */
    public DeploymentStaticUnitResolver(ModDatabase database) {
        this.publications = new DeploymentPublishStore(Objects.requireNonNull(database));
        this.secrets = new DeploymentSecretStore(database);
    }

    /** Complete original graph identities with no additional task or resource admission. / 完整原图身份，不增加任务或资源准入。
     * @param owners exact component ownership / 精确组件所有权
     * @param credentials reviewed immutable credential revisions / 已审阅不可变凭据修订 */
    public record GraphBindings(Map<String, DeploymentUnitIdentity> owners,
                                Map<DeploymentSecretRef, SecretId> credentials) {
        /** Retains only the complete immutable views. / 仅保留完整不可变视图。
         * @param owners original ownership / 原所有权
         * @param credentials original credentials / 原凭据 */
        public GraphBindings {
            owners = Map.copyOf(owners);
            credentials = Map.copyOf(credentials);
        }
    }

    /** Resolves every reviewed component and its combined exact credentials in the original task. / 在原任务解析每个已审阅组件及其合并精确凭据。
     * @param task original admitted task / 原已准入任务
     * @param server exact selected target / 精确选定目标
     * @param plans complete original reviewed graph / 完整原已审阅图
     * @return complete owner and credential bindings / 完整所有者及凭据绑定
     * @throws SQLException if original stored identities cannot be verified / 原存储身份不能核实时
     * @throws ApiException if original access or purpose is unavailable / 原访问或用途不可用时 */
    public GraphBindings resolveGraph(TaskContext task, ServerId server, DeploymentStandardPlanner.PartPlans plans)
        throws SQLException, ApiException {
        var owners = new HashMap<String, DeploymentUnitIdentity>();
        var references = new HashSet<DeploymentSecretRef>();
        for (var entry : plans.components().entrySet()) {
            owners.put(entry.getKey(), resolve(task, entry.getValue().request().facts().applicationId(), server));
            references.addAll(entry.getValue().request().secretReferences());
        }
        return new GraphBindings(owners, credentials(task, List.copyOf(references)));
    }

    /** Maps reviewed immutable revisions to their actual opaque platform identities. / 将已审阅不可变修订映射到实际平台不透明身份。
     * @param task original task / 原任务
     * @param references exact reviewed revisions / 精确已审阅修订
     * @return precise saved credential map / 精确已存凭据映射
     * @throws SQLException if metadata cannot be read / 元数据不能读取时
     * @throws ApiException if original access or credential purpose is unavailable / 原访问或凭据用途不可用时 */
    public Map<DeploymentSecretRef, SecretId> credentials(TaskContext task,
                                                          List<DeploymentSecretRef> references) throws SQLException, ApiException {
        var result = new HashMap<DeploymentSecretRef, SecretId>();
        for (var reference : references) {
            var saved = secrets.findRevision(task, reference);
            if (saved.isEmpty())
                throw new IllegalArgumentException("Reviewed secret revision does not exist");
            var purpose = saved.orElseThrow().purpose();
            if (purpose.isEmpty())
                throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SECRET_PURPOSE_UNAVAILABLE",
                    "Original secret purpose is unavailable", Map.of(), Optional.empty()));
            result.put(reference, new SecretId(saved.orElseThrow().credentialId(), purpose.orElseThrow()));
        }
        return Map.copyOf(result);
    }

    /** Reuses exact canonical ownership on update and never derives it from source or plan SHA. / 更新时复用精确规范所有权，绝不从来源或计划摘要派生。
     * @param task original admitted task / 原准入任务
     * @param applicationId reviewed managed unit ID / 已审阅受管单元 ID
     * @param server exact selected server / 精确选定服务器
     * @return original or newly proposed identity / 原身份或新建议身份
     * @throws SQLException if stored identity is unavailable or conflicts / 已存身份不可用或冲突时
     * @throws ApiException if original task access is unavailable / 原任务访问不可用时 */
    public DeploymentUnitIdentity resolve(TaskContext task, String applicationId, ServerId server)
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
