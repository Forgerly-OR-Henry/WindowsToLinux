package gold.debug.windowstolinux.appapi.db;

import java.util.UUID;

/**
 * Logical owner and data version tied to a unique generation. / 与唯一代次绑定的数据所有者及版本。
 * @param ownerId stable identity of the owning Mod / 所属插件的稳定身份
 * @param schemaVersion positive canonical decimal data version of at most 64 digits / 最长 64 位的规范正整数数据版本
 * @param generation unique generation of the concrete data instance / 具体数据实例的唯一代次
 */
public record SchemaIdentity(String ownerId, String schemaVersion, UUID generation) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param ownerId stable identity of the owning Mod / 所属插件的稳定身份
     * @param schemaVersion positive canonical decimal data version of at most 64 digits / 最长 64 位的规范正整数数据版本
     * @param generation unique generation of the concrete data instance / 具体数据实例的唯一代次
     */
    public SchemaIdentity {
        java.util.Objects.requireNonNull(ownerId, "ownerId");
        java.util.Objects.requireNonNull(schemaVersion, "schemaVersion");
        java.util.Objects.requireNonNull(generation, "generation");
        if (!schemaVersion.matches("[1-9][0-9]{0,63}"))
            throw new IllegalArgumentException("Invalid schema version");
    }
}
