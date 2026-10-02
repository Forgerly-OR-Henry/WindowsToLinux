package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Resolves reviewed storage while rejecting the original overlapping and reserved paths. / 解析已审阅存储并拒绝原有重叠和保留路径。 */
public final class DeploymentStoragePlan {
    /** Prevents utility construction. / 防止工具类实例化。 */
    private DeploymentStoragePlan() {
    }

    /** One exact physical and runtime access mapping. / 一项精确物理及运行访问映射。
     * @param id binding identity / 绑定身份
     * @param kind resource category / 资源类别
     * @param location reviewed location / 已审阅位置
     * @param accessPath runtime access path / 运行访问路径
     * @param physicalPath resolved managed path / 已解析受管路径
     * @param readOnly read-only access / 只读访问 */
    public record Resource(String id, DeploymentStorageLocation.StorageResourceType kind,
                           DeploymentStorageLocation location, String accessPath, String physicalPath,
                           boolean readOnly) {
    }

    /** Resolves and validates every file and SQLite directory before remote execution. / 在远端执行前解析并校验全部文件及 SQLite 目录。
     * @param applicationId reviewed application / 已审阅应用
     * @param bindings exact reviewed bindings / 精确已审阅绑定
     * @param runtime reviewed runtime / 已审阅运行定义
     * @return validated resource paths / 已校验资源路径 */
    public static List<Resource> resolve(String applicationId, DeploymentResourceBindings bindings,
                                         DeploymentRuntimeDefinition runtime) {
        boolean container = runtime instanceof DeploymentRuntimeDefinition.Container;
        var result = new ArrayList<Resource>();
        for (var file : bindings.fileBindings())
            result.add(new Resource(file.bindingId(), file.resourceType(), file.location(), file.dataPath().path(),
                file.physicalPath(applicationId),
                file.dataPath().access() == DeploymentStoragePath.AccessMode.READ_ONLY));
        for (var database : bindings.databaseBindings().orElse(List.of()))
            if (database.connection() instanceof DeploymentDatabaseConnection.Sqlite sqlite) {
                String physical = sqlite.physicalPath(applicationId, database.databaseId());
                result.add(new Resource(database.databaseId(), DeploymentStorageLocation.StorageResourceType.DATABASE,
                    sqlite.location(), sqlite.accessPath().isEmpty() ? physical : sqlite.accessPath(), physical,
                    false));
            }
        var ids = new HashSet<String>();
        for (var resource : result) {
            if (!ids.add(resource.id()))
                throw new IllegalArgumentException(
                    "storage binding identifiers must be unique across files and databases");
            String access = resource.accessPath();
            if (!container && access.startsWith("/") && !access.equals(resource.physicalPath()))
                DeploymentStorageLocation.custom(access).resolve(applicationId, resource.kind(), resource.id());
            if (!access.startsWith("/")) {
                String first = access.split("/")[0];
                if (first.startsWith(".windowstolinux") || first.equals(".w2l"))
                    throw new IllegalArgumentException("storage access path conflicts with deployment control files");
            }
            for (var other : result) {
                if (resource == other)
                    continue;
                String root = resource.location().resolve(applicationId, resource.kind(), resource.id());
                String otherRoot = other.location().resolve(applicationId, other.kind(), other.id());
                if (root.equals(otherRoot) || root.startsWith(otherRoot + "/") || otherRoot.startsWith(root + "/"))
                    throw new IllegalArgumentException("physical storage binding directories must not overlap");
                if (access.equals(other.accessPath()) || resource.physicalPath().equals(other.physicalPath()))
                    throw new IllegalArgumentException("storage paths conflict");
                if (resource.kind() != DeploymentStorageLocation.StorageResourceType.DATABASE
                    && other.kind() != DeploymentStorageLocation.StorageResourceType.DATABASE
                    && (access.startsWith(other.accessPath() + "/")
                    || resource.physicalPath().startsWith(other.physicalPath() + "/")))
                    throw new IllegalArgumentException("storage directory bindings must not overlap");
            }
        }
        return List.copyOf(result);
    }
}
