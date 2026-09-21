package gold.debug.windowstolinux.shared.model.managed;

import java.util.Objects;

/**
 * A reviewed storage choice; an unresolved choice never becomes a default. / 已审阅存储选择，未确定位置绝不变成默认位置。
 *
 * @param type selected member of the supported type set / 受支持类型集合中的所选项
 * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
 */
public record ManagedStorageLocation(StorageLocationType type, String path) {
    /**
     * Distinguishes default, custom and unresolved managed storage locations.
     * <p>区分默认、自定义及未解析的受管存储位置。
     */
    public enum StorageLocationType {
    /**
     * UNRESOLVED classification within storage location type.
     * <p>存储位置类型中的未解析分类。
     */
     UNRESOLVED,
    /**
     * DEFAULT classification within storage location type.
     * <p>存储位置类型中的默认分类。
     */
     DEFAULT,
    /**
     * CUSTOM classification within storage location type.
     * <p>存储位置类型中的自定义分类。
     */
     CUSTOM }
    /**
     * Identifies the kind of persistent resource assigned to a managed storage location.
     * <p>标识分配到受管存储位置的持久化资源类型。
     */
    public enum StorageResourceType {
    /**
     * CONFIGURATION classification within storage resource type.
     * <p>存储资源类型中的配置分类。
     */
     CONFIGURATION,
    /**
     * FILE classification within storage resource type.
     * <p>存储资源类型中的文件分类。
     */
     FILE,
    /**
     * DATABASE classification within storage resource type.
     * <p>存储资源类型中的数据库分类。
     */
     DATABASE }

    /**
     * Validates and binds the inputs required by managed storage location.
     * <p>校验并绑定受管存储位置所需输入。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedStorageLocation {
        type = Objects.requireNonNull(type, "type");
        path = Objects.requireNonNull(path, "path").trim();
        if (type == StorageLocationType.CUSTOM) path = validatedPath(path);
        else if (!path.isEmpty()) throw new IllegalArgumentException("only a custom location may contain a path");
    }

    /**
     * Builds managed storage location from the supplied defaults inputs.
     * <p>根据所提供默认集合输入构建受管存储位置。
     *
     * @return managed storage location from the supplied defaults inputs / 根据所提供默认集合输入构建受管存储位置
     */
    public static ManagedStorageLocation defaults() { return new ManagedStorageLocation(StorageLocationType.DEFAULT, ""); }
    /**
     * Builds managed storage location from the supplied unresolved inputs.
     * <p>根据所提供未解析输入构建受管存储位置。
     *
     * @return managed storage location from the supplied unresolved inputs / 根据所提供未解析输入构建受管存储位置
     */
    public static ManagedStorageLocation unresolved() { return new ManagedStorageLocation(StorageLocationType.UNRESOLVED, ""); }
    /**
     * Builds managed storage location from the supplied custom inputs.
     * <p>根据所提供自定义输入构建受管存储位置。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return managed storage location from the supplied custom inputs / 根据所提供自定义输入构建受管存储位置
     */
    public static ManagedStorageLocation custom(String path) { return new ManagedStorageLocation(StorageLocationType.CUSTOM, path); }

    /**
     * Resolves a physical directory using the owning application's exact boundary. / 按所属应用精确边界解析物理目录。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param resource resource / 资源
     * @param bindingId binding id / 绑定标识
     * @return a physical directory using the owning application's exact boundary / 按所属应用精确边界解析物理目录
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public String resolve(String applicationId, StorageResourceType resource, String bindingId) {
        String root = installationRoot(applicationId);
        Objects.requireNonNull(resource, "resource");
        identifier(bindingId);
        if (type == StorageLocationType.UNRESOLVED) throw new IllegalArgumentException("storage location must be reviewed");
        String category = switch (resource) { case CONFIGURATION -> "configuration"; case FILE -> "files"; case DATABASE -> "databases"; };
        if (type == StorageLocationType.DEFAULT) {
            return resource == StorageResourceType.CONFIGURATION ? configurationRoot(applicationId) + "/files/" + bindingId
                    : dataRoot(applicationId) + "/" + category + "/" + bindingId;
        }
        if (!path.startsWith("/")) return root + "/persistent/" + category + "/" + bindingId;
        if (!path.startsWith(root + "/")) throw new IllegalArgumentException("custom storage must remain inside " + root);
        String first = path.substring(root.length() + 1).split("/", 2)[0];
        if (first.equals("releases") || first.equals("current") || first.equals("persistent") || first.startsWith(".")) {
            throw new IllegalArgumentException("custom storage overlaps a managed publication directory");
        }
        return resource == StorageResourceType.CONFIGURATION ? root + "/persistent/configuration/" + bindingId : path;
    }

    /**
     * Resolves the validated application's installation directory under /opt/windowstolinux/apps.
     * <p>在 /opt/windowstolinux/apps 下解析已校验应用的安装目录。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return the validated application's installation directory under /opt/windowstolinux/apps / 在 /opt/windowstolinux/apps 下解析已校验应用的安装目录
     */
    public static String installationRoot(String applicationId) { return "/opt/windowstolinux/apps/" + identifier(applicationId); }
    /**
     * Resolves the validated application's configuration directory under /etc/opt/windowstolinux/apps.
     * <p>在 /etc/opt/windowstolinux/apps 下解析已校验应用的配置目录。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return the validated application's configuration directory under /etc/opt/windowstolinux/apps / 在 /etc/opt/windowstolinux/apps 下解析已校验应用的配置目录
     */
    public static String configurationRoot(String applicationId) { return "/etc/opt/windowstolinux/apps/" + identifier(applicationId); }
    /**
     * Resolves the validated application's mutable data directory under /var/opt/windowstolinux/apps.
     * <p>在 /var/opt/windowstolinux/apps 下解析已校验应用的可变数据目录。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return the validated application's mutable data directory under /var/opt/windowstolinux/apps / 在 /var/opt/windowstolinux/apps 下解析已校验应用的可变数据目录
     */
    public static String dataRoot(String applicationId) { return "/var/opt/windowstolinux/apps/" + identifier(applicationId); }

    /**
     * Validates and produces validated path for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的已验证路径。
     *
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return validated path text / 已验证路径文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String validatedPath(String input) {
        String path = Objects.requireNonNull(input, "path").trim();
        if (path.startsWith("./")) path = path.substring(2);
        if (path.endsWith("/")) path = path.substring(0,path.length()-1);
        if (path.isEmpty() || path.equals("-") || path.length() > 512 || !path.matches("/?[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*"))
            throw new IllegalArgumentException("storage path must be a bounded literal Linux path");
        for (String part : path.split("/")) if (part.equals(".") || part.equals(".."))
            throw new IllegalArgumentException("storage path contains traversal");
        return path;
    }

    /**
     * Validates an identifier against the bounded syntax of the owning contract.
     * <p>按所属契约的有界语法验证标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return identifier text / 标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String identifier(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid managed storage identifier");
        return value;
    }
}
