package gold.debug.windowstolinux.shared.model.project.component;

import java.util.Objects;

/**
 * One logical persistent-data path with an explicit schema and access contract.
 *
 *  <p>一个具有显式模式和访问契约的逻辑持久化数据路径。
 *
 * @param path declared application access path / 已声明的应用访问路径
 * @param access access mode / 访问模式
 * @param schemaId stable compatibility schema identifier / 稳定兼容模式标识符
 * @param reversible whether writes can be reversed by this deployment transaction / 写入能否由本次部署事务回退
 */
public record ComponentDataPath(String path, AccessMode access, String schemaId, boolean reversible) {
    /**
     * Validates a bounded logical data contract. / 验证有界逻辑数据契约。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param access access mode / 访问模式
     * @param schemaId stable compatibility schema identifier / 稳定兼容模式标识符
     * @param reversible whether writes can be reversed by this deployment transaction / 写入能否由本次部署事务回退
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ComponentDataPath {
        path = gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.validatedPath(path);
        access = Objects.requireNonNull(access, "access");
        schemaId = requireIdentifier(schemaId, "schemaId");
        if (access == AccessMode.READ_ONLY && !reversible) {
            throw new IllegalArgumentException("read-only data access cannot introduce irreversible writes");
        }
    }

    /**
     * Component data access mode. / 组件数据访问模式。
     */
    public enum AccessMode {
        /**
         * Reads only. / 只读。
         */
        READ_ONLY,
        /**
         * Reads and writes. / 读写。
         */
        READ_WRITE
    }

    /**
     * Validates and returns the stable secret identifier and rejects inputs outside the declared constraints.
     * <p>校验并返回稳定的秘密标识并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require identifier text / 要求标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a bounded lowercase identifier");
        }
        return value;
    }
}
