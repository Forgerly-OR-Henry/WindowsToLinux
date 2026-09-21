package gold.debug.windowstolinux.shared.model.ai;

import java.util.Objects;

/** One ordered membership; position is supplied by the containing list. / 一个有序用途成员，位置由所属列表确定。
 * @param profileId stable model identity / 稳定模型标识
 * @param enabled whether this purpose may invoke the model / 当前用途是否允许调用模型
 */
public record AiPurposeAssignment(String profileId, boolean enabled) {
    /** Validates the referenced identity. / 校验引用标识。
     * @param profileId stable model identity / 稳定模型标识
     * @param enabled purpose enablement / 用途启用状态
     */
    public AiPurposeAssignment {
        Objects.requireNonNull(profileId);
        if (profileId.isBlank()) throw new IllegalArgumentException("model identity is required");
    }
}
