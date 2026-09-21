package gold.debug.windowstolinux.shared.config.resource;

import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;

import java.util.Objects;

/**
 * Stable managed identity for one reviewed logical persistent file tree. / 一个经审阅逻辑持久化文件树的稳定受管身份。
 *
 * @param bindingId binding id / 绑定标识
 * @param dataPath data path / 数据路径
 * @param location the remote URI / 远端 URI
 * @param resourceType resource type / 资源类型
 * @param seedFile seed file / 初始种子文件
 * @param contentSha256 content sha 256 / 内容SHA256
 */
public record ManagedFileBinding(
        String bindingId,
        ComponentDataPath dataPath,
        ManagedStorageLocation location,
        ManagedStorageLocation.StorageResourceType resourceType,
        String seedFile, String contentSha256
) {
    /**
     * Validates the identity and exact reviewed logical path. / 校验身份及精确审阅逻辑路径。
     *
     * @param bindingId binding id / 绑定标识
     * @param dataPath data path / 数据路径
     * @param location the remote URI / 远端 URI
     * @param resourceType resource type / 资源类型
     * @param seedFile seed file / 初始种子文件
     * @param contentSha256 content sha 256 / 内容SHA256
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedFileBinding {
        seedFile = gold.debug.windowstolinux.shared.model.ecosystem.db.SqliteFileRequirement.relativeSourceFile(seedFile);
        contentSha256 = Objects.requireNonNull(contentSha256);
        if (resourceType == ManagedStorageLocation.StorageResourceType.CONFIGURATION) {
            if (seedFile.isEmpty() || !contentSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("configuration requires a source file and its reviewed digest");
        } else if (!contentSha256.isEmpty()) throw new IllegalArgumentException("mutable data cannot declare an immutable content revision");
        bindingId = ManagedDatabaseBinding.managedIdentifier(bindingId, "bindingId");
        dataPath = Objects.requireNonNull(dataPath, "dataPath");
        location = Objects.requireNonNull(location, "location");
        resourceType = Objects.requireNonNull(resourceType, "resourceType");
        if (resourceType == ManagedStorageLocation.StorageResourceType.DATABASE)
            throw new IllegalArgumentException("databases require an explicit database binding");
        if (resourceType == ManagedStorageLocation.StorageResourceType.CONFIGURATION
                && dataPath.access() != ComponentDataPath.AccessMode.READ_ONLY)
            throw new IllegalArgumentException("configuration bindings must be read-only");
    }

    /**
     * Initializes managed file binding through its shared constructor contract.
     * <p>通过共享构造契约初始化受管文件绑定。
     *
     * @param bindingId binding id / 绑定标识
     * @param dataPath data path / 数据路径
     * @param location the remote URI / 远端 URI
     * @param resourceType resource type / 资源类型
     */
    public ManagedFileBinding(String bindingId, ComponentDataPath dataPath, ManagedStorageLocation location,
            ManagedStorageLocation.StorageResourceType resourceType) {
        this(bindingId,dataPath,location,resourceType,"","");
    }

    /**
     * Resolves the managed binding path, including the immutable content revision for configuration files.
     * <p>解析受管绑定路径，配置文件路径包含不可变内容修订。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return the managed binding path, including the immutable content revision for configuration files / 受管绑定路径，配置文件路径包含不可变内容修订
     */
    public String physicalPath(String applicationId) {
        String root = location.resolve(applicationId, resourceType, bindingId);
        return resourceType == ManagedStorageLocation.StorageResourceType.CONFIGURATION ? root+"/revisions/"+contentSha256+"/value" : root;
    }

    /**
     * A supplied application path is an explicit custom location. / 提供的应用路径属于显式自定义位置。
     *
     * @param bindingId binding id / 绑定标识
     * @param dataPath data path / 数据路径
     */
    public ManagedFileBinding(String bindingId, ComponentDataPath dataPath) {
        this(bindingId, dataPath, ManagedStorageLocation.custom(dataPath.path()));
    }
    /**
     * Initializes managed file binding through its shared constructor contract.
     * <p>通过共享构造契约初始化受管文件绑定。
     *
     * @param bindingId binding id / 绑定标识
     * @param dataPath data path / 数据路径
     * @param location the remote URI / 远端 URI
     */
    public ManagedFileBinding(String bindingId, ComponentDataPath dataPath, ManagedStorageLocation location) {
        this(bindingId, dataPath, location, ManagedStorageLocation.StorageResourceType.FILE);
    }
}
