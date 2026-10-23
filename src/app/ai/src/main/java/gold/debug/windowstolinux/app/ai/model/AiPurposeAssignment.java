package gold.debug.windowstolinux.app.ai.model;

import java.util.Objects;

/** One ordered membership; position is supplied by the containing list. / 一个有序用途成员，位置由所属列表确定。
 * @param profileId stable model identity / 稳定模型标识
 */
public record AiPurposeAssignment(String profileId) {
    /** Validates the referenced identity. / 校验引用标识。
     * @param profileId stable model identity / 稳定模型标识
     */
    public AiPurposeAssignment {
        Objects.requireNonNull(profileId);
        if (profileId.isBlank())
            throw new IllegalArgumentException("model identity is required");
    }
}
