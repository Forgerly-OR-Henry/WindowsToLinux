package gold.debug.windowstolinux.shared.config.resource;

import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** One client-side boundary for publication and restoration. / 发布与恢复共用的客户端路径边界。 */
public final class ManagedStoragePlan {
    private ManagedStoragePlan() { }

    public record Resource(String id, ManagedStorageLocation.StorageResourceType kind,
                           ManagedStorageLocation location, String accessPath, String physicalPath, boolean readOnly) { }

    public static List<Resource> resolve(String applicationId, ManagedComponentResourceBindings bindings,
                                         DeploymentRuntimeSpecification runtime) {
        boolean container = runtime instanceof DeploymentRuntimeSpecification.Container;
        var result = new ArrayList<Resource>();
        for (var file : bindings.fileBindings()) {
            result.add(new Resource(file.bindingId(), file.resourceType(), file.location(), file.dataPath().path(),
                    file.physicalPath(applicationId),
                    file.dataPath().access() == gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath.AccessMode.READ_ONLY));
        }
        for (var database : bindings.databaseBindings().orElse(List.of())) {
            if (database.connection() instanceof ManagedDatabaseConnection.Sqlite sqlite) {
                String physical = sqlite.physicalPath(applicationId, database.databaseId());
                result.add(new Resource(database.databaseId(), ManagedStorageLocation.StorageResourceType.DATABASE,
                        sqlite.location(), sqlite.accessPath().isEmpty() ? physical : sqlite.accessPath(), physical, false));
            }
        }
        var ids = new HashSet<String>();
        for (var resource : result) {
            if (!ids.add(resource.id())) throw new IllegalArgumentException("storage binding identifiers must be unique across files and databases");
            String access = resource.accessPath();
            if (!container && access.startsWith("/") && !access.equals(resource.physicalPath())) {
                ManagedStorageLocation.custom(access).resolve(applicationId, resource.kind(), resource.id());
            }
            if (!access.startsWith("/")) {
                String first = access.split("/")[0];
                if (first.startsWith(".windowstolinux") || first.equals(".w2l"))
                    throw new IllegalArgumentException("storage access path conflicts with deployment control files");
            }
            for (var other : result) {
                if (resource == other) continue;
                String root = resource.location().resolve(applicationId,resource.kind(),resource.id());
                String otherRoot = other.location().resolve(applicationId,other.kind(),other.id());
                if (root.equals(otherRoot) || root.startsWith(otherRoot+"/") || otherRoot.startsWith(root+"/"))
                    throw new IllegalArgumentException("physical storage binding directories must not overlap");
                if (access.equals(other.accessPath()) || resource.physicalPath().equals(other.physicalPath()))
                    throw new IllegalArgumentException("storage paths conflict");
                if (resource.kind() != ManagedStorageLocation.StorageResourceType.DATABASE
                        && other.kind() != ManagedStorageLocation.StorageResourceType.DATABASE
                        && (access.startsWith(other.accessPath() + "/") || resource.physicalPath().startsWith(other.physicalPath() + "/")))
                    throw new IllegalArgumentException("storage directory bindings must not overlap");
            }
        }
        return List.copyOf(result);
    }
}
