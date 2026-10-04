package gold.debug.windowstolinux.appapi.file.source;

import java.util.UUID;

/**
 * Opaque user-approved source selection. / 用户批准的不透明来源选择。
 * @param id stable identity within the owning capability / 所属能力内的稳定身份
 * @param displayName human-readable category name / 可读的类别名称
 */
public record FileSelection(UUID id, String displayName) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param id stable identity within the owning capability / 所属能力内的稳定身份
     * @param displayName human-readable category name / 可读的类别名称
     */
    public FileSelection {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(displayName, "displayName");
    }
}
