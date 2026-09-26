package gold.debug.windowstolinux.appapi.mod;

import java.util.Optional;

/**
 * Stable Mod identity independent of display names and package filenames. / 独立于显示名和包文件名的稳定插件身份。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param version canonical semantic release version / 规范的语义发布版本
 * @param schemaVersion positive canonical decimal data version of at most 64 digits / 最长 64 位的规范正整数数据版本
 */
public record ModIdentity(String id, String version, Optional<String> schemaVersion) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     * @param version canonical semantic release version / 规范的语义发布版本
     * @param schemaVersion positive canonical decimal data version of at most 64 digits / 最长 64 位的规范正整数数据版本
     */
    public ModIdentity {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(version, "version");
        java.util.Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (schemaVersion.isPresent() && !schemaVersion.get().matches("[1-9][0-9]{0,63}"))
            throw new IllegalArgumentException("Invalid schema version");
    }
}
