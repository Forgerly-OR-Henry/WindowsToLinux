package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;

/** Checks the component's combined helper binding namespace before accepting an Agent plan. / 接受 Agent 计划前校验组件合并后的 helper 绑定命名空间。 */
public final class DeploymentAgentPlanResourceConstraints {
    private DeploymentAgentPlanResourceConstraints() {
    }

    /** Rejects duplicate identities and access paths without relabeling or dropping verified resources. / 拒绝重复身份及访问路径，不重命名或丢弃已核实资源。
     * @param part source-bound resolved component / 来源绑定的已解析组件
     * @param applicationId existing candidate's exact managed identity / 既有候选的精确受管身份
     * @param layout original task layout / 原任务布局
     * @throws IllegalArgumentException if combined managed content is ambiguous / 合并后的受管内容有歧义时 */
    public static void require(DeploymentAgentPlan.Part part, Optional<String> applicationId, DeploymentRemoteLayout layout) {
        var identities = new LinkedHashMap<String, String>();
        var paths = new LinkedHashMap<String, String>();
        for (var file : part.resources().fileBindings())
            binding(part.id(), identities, paths, file.bindingId(), file.dataPath().path(), file.resourceType().name());
        for (var database : part.resources().databaseBindings().orElse(java.util.List.of())) {
            if (!(database.connection() instanceof DeploymentDatabaseConnection.Sqlite sqlite)) continue;
            String path = sqlite.accessPath();
            if (path.isEmpty()) {
                String owner = applicationId.orElseThrow(() -> new IllegalArgumentException(
                    "AGENT_PLAN_MANAGED_DATA_CONSTRAINT: component " + part.id() + " has SQLite bindings without an existing candidate identity"));
                path = sqlite.location().resolve(owner, DeploymentStorageLocation.StorageResourceType.DATABASE, database.databaseId(), layout)
                    + "/" + sqlite.fileName();
            }
            binding(part.id(), identities, paths, database.databaseId(), path, "SQLITE");
        }
    }

    private static void binding(String component, Map<String, String> identities, Map<String, String> paths,
                                String id, String path, String kind) {
        String previous = identities.putIfAbsent(id, kind);
        if (previous != null)
            throw new IllegalArgumentException("AGENT_PLAN_MANAGED_DATA_CONSTRAINT: component " + component
                + " duplicates binding ID " + id + " across " + previous + " and " + kind
                + "; file/configuration and SQLite bindings share one namespace. Correct the file declaration while retaining verified database targets.");
        previous = paths.putIfAbsent(path, id);
        if (previous != null)
            throw new IllegalArgumentException("AGENT_PLAN_MANAGED_DATA_CONSTRAINT: component " + component
                + " bindings " + previous + " and " + id + " duplicate logical access path " + path
                + "; declare distinct access paths or remove the redundant file declaration while retaining verified database targets.");
    }
}
