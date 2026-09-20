package gold.debug.windowstolinux.shared.model.managed;

import java.util.Objects;

/** A reviewed storage choice; an unresolved choice never becomes a default. / 已审阅存储选择，未确定位置绝不变成默认位置。 */
public record ManagedStorageLocation(StorageLocationType type, String path) {
    public enum StorageLocationType { UNRESOLVED, DEFAULT, CUSTOM }
    public enum StorageResourceType { CONFIGURATION, FILE, DATABASE }

    public ManagedStorageLocation {
        type = Objects.requireNonNull(type, "type");
        path = Objects.requireNonNull(path, "path").trim();
        if (type == StorageLocationType.CUSTOM) path = validatedPath(path);
        else if (!path.isEmpty()) throw new IllegalArgumentException("only a custom location may contain a path");
    }

    public static ManagedStorageLocation defaults() { return new ManagedStorageLocation(StorageLocationType.DEFAULT, ""); }
    public static ManagedStorageLocation unresolved() { return new ManagedStorageLocation(StorageLocationType.UNRESOLVED, ""); }
    public static ManagedStorageLocation custom(String path) { return new ManagedStorageLocation(StorageLocationType.CUSTOM, path); }

    /** Resolves a physical directory using the owning application's exact boundary. / 按所属应用精确边界解析物理目录。 */
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

    public static String installationRoot(String applicationId) { return "/opt/windowstolinux/apps/" + identifier(applicationId); }
    public static String configurationRoot(String applicationId) { return "/etc/opt/windowstolinux/apps/" + identifier(applicationId); }
    public static String dataRoot(String applicationId) { return "/var/opt/windowstolinux/apps/" + identifier(applicationId); }

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

    private static String identifier(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]{0,62}")) throw new IllegalArgumentException("invalid managed storage identifier");
        return value;
    }
}
