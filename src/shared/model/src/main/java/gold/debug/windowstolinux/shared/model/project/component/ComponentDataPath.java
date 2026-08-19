package gold.debug.windowstolinux.shared.model.project.component;

import java.util.Objects;

/**
 * One logical persistent-data path with an explicit schema and access contract.
 *
 * <p>一个具有显式模式和访问契约的逻辑持久化数据路径。
 *
 * @param path managed relative data path / 受管相对数据路径
 * @param access access mode / 访问模式
 * @param schemaId stable compatibility schema identifier / 稳定兼容模式标识符
 * @param reversible whether writes can be reversed by this deployment transaction / 写入能否由本次部署事务回退
 */
public record ComponentDataPath(String path, AccessMode access, String schemaId, boolean reversible) {
    /** Validates a bounded logical data contract. / 验证有界逻辑数据契约。 */
    public ComponentDataPath {
        path = requireRelative(path);
        access = Objects.requireNonNull(access, "access");
        schemaId = requireIdentifier(schemaId, "schemaId");
        if (access == AccessMode.READ_ONLY && !reversible) {
            throw new IllegalArgumentException("read-only data access cannot introduce irreversible writes");
        }
    }

    /** Component data access mode. / 组件数据访问模式。 */
    public enum AccessMode {
        /** Reads only. / 只读。 */
        READ_ONLY,
        /** Reads and writes. / 读写。 */
        READ_WRITE
    }

    private static String requireRelative(String value) {
        value = Objects.requireNonNull(value, "path").trim().replace('\\', '/');
        if (!value.matches("[a-z0-9][a-z0-9._/-]{0,254}") || value.startsWith("/")
                || value.contains("..") || value.contains("//")) {
            throw new IllegalArgumentException("data path must be a bounded managed relative path");
        }
        return value;
    }

    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a bounded lowercase identifier");
        }
        return value;
    }
}
