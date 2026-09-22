package gold.debug.windowstolinux.shared.linux.protocol.backup;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;

/**
 * Exact reviewed file identity and logical data path for publication. / 发布所需的精确审阅文件身份与逻辑数据路径。
 *
 * @param bindingId binding id / 绑定标识
 * @param dataPath data path / 数据路径
 * @param location the remote URI / 远端 URI
 * @param resourceType resource type / 资源类型
 * @param databaseFileName database file name / 数据库文件名称
 * @param seedFile seed file / 初始种子文件
 * @param initializationFiles initialization files / 初始化文件集合
 * @param configurationSha256 the immutable configuration digest / 不可变配置摘要
 */
public record RemoteManagedFileBinding(String bindingId, ComponentDataPath dataPath, ManagedStorageLocation location,
        ManagedStorageLocation.StorageResourceType resourceType, String databaseFileName, String seedFile,
        java.util.List<String> initializationFiles, String configurationSha256) {
    /**
     * Rejects unbounded identities and absent paths. / 拒绝无界身份和缺失路径。
     *
     * @param bindingId binding id / 绑定标识
     * @param dataPath data path / 数据路径
     * @param location the remote URI / 远端 URI
     * @param resourceType resource type / 资源类型
     * @param databaseFileName database file name / 数据库文件名称
     * @param seedFile seed file / 初始种子文件
     * @param initializationFiles initialization files / 初始化文件集合
     * @param configurationSha256 the immutable configuration digest / 不可变配置摘要
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RemoteManagedFileBinding {
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(dataPath, "dataPath");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(configurationSha256);
        if (resourceType == ManagedStorageLocation.StorageResourceType.CONFIGURATION
                && !configurationSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("configuration revision must bind source content");
        if (resourceType != ManagedStorageLocation.StorageResourceType.CONFIGURATION && !configurationSha256.isEmpty())
            throw new IllegalArgumentException("only configuration binds immutable content");
        Objects.requireNonNull(databaseFileName, "databaseFileName");
        seedFile = gold.debug.windowstolinux.shared.model.ecosystem.db.SqliteFileRequirement
                .relativeSourceFile(seedFile);
        initializationFiles = java.util.List.copyOf(initializationFiles);
        if (initializationFiles.size() > 32)
            throw new IllegalArgumentException("too many initialization files");
        initializationFiles
                .forEach(gold.debug.windowstolinux.shared.model.ecosystem.db.SqliteFileRequirement::relativeSourceFile);
        if (resourceType == ManagedStorageLocation.StorageResourceType.DATABASE) {
            if (!databaseFileName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                throw new IllegalArgumentException("invalid database file name");
        } else if (!databaseFileName.isEmpty())
            throw new IllegalArgumentException("only database bindings declare a database file");
        if (!bindingId.matches("[a-z0-9][a-z0-9-]{0,62}"))
            throw new IllegalArgumentException("invalid file binding identity");
    }

    /**
     * Initializes remote managed file binding through its shared constructor contract.
     * <p>通过共享构造契约初始化远端受管文件绑定。
     *
     * @param bindingId binding id / 绑定标识
     * @param dataPath data path / 数据路径
     * @param location the remote URI / 远端 URI
     * @param resourceType resource type / 资源类型
     * @param databaseFileName database file name / 数据库文件名称
     */
    public RemoteManagedFileBinding(String bindingId, ComponentDataPath dataPath, ManagedStorageLocation location,
            ManagedStorageLocation.StorageResourceType resourceType, String databaseFileName) {
        this(bindingId, dataPath, location, resourceType, databaseFileName, "", java.util.List.of(), "");
    }

    /**
     * Initializes remote managed file binding through its shared constructor contract.
     * <p>通过共享构造契约初始化远端受管文件绑定。
     *
     * @param bindingId binding id / 绑定标识
     * @param dataPath data path / 数据路径
     */
    public RemoteManagedFileBinding(String bindingId, ComponentDataPath dataPath) {
        this(bindingId, dataPath, ManagedStorageLocation.custom(dataPath.path()));
    }

    /**
     * Initializes remote managed file binding through its shared constructor contract.
     * <p>通过共享构造契约初始化远端受管文件绑定。
     *
     * @param bindingId binding id / 绑定标识
     * @param dataPath data path / 数据路径
     * @param location the remote URI / 远端 URI
     */
    public RemoteManagedFileBinding(String bindingId, ComponentDataPath dataPath, ManagedStorageLocation location) {
        this(bindingId, dataPath, location, ManagedStorageLocation.StorageResourceType.FILE, "");
    }
}
