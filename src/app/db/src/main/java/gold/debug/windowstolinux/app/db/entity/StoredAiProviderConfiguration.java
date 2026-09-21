package gold.debug.windowstolinux.app.db.entity;

import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Inventory metadata and capability evidence for an exact profile revision. / 精确模型修订的清单元数据及能力证据。
 * @param profile credential-free connection settings / 不含凭据明文的连接设置
 * @param name display label / 显示名称
 * @param priority display order only / 仅用于展示的顺序
 * @param revision connection revision / 连接修订
 * @param textVerifiedAt text capability verification / 文本能力验证时间
 * @param visionVerifiedAt image capability verification / 图像能力验证时间
 */
public record StoredAiProviderConfiguration(StoredAiProviderProfile profile, String name, int priority, long revision,
        Optional<Instant> textVerifiedAt, Optional<Instant> visionVerifiedAt) {
    /** Validates immutable inventory metadata. / 校验不可变清单元数据。
     * @param profile connection settings / 连接设置
     * @param name display label / 显示名称
     * @param priority display order / 展示顺序
     * @param revision connection revision / 连接修订
     * @param textVerifiedAt text verification / 文本验证
     * @param visionVerifiedAt image verification / 图像验证
     */
    public StoredAiProviderConfiguration {
        Objects.requireNonNull(profile); name = Objects.requireNonNull(name).trim();
        Objects.requireNonNull(textVerifiedAt); Objects.requireNonNull(visionVerifiedAt);
        if (name.isEmpty() || name.length() > 120 || priority < 0 || revision < 1)
            throw new IllegalArgumentException("invalid AI inventory metadata");
    }
    /** Checks the capability needed by the selected purpose. / 检查所选用途需要的能力。
     * @param purpose invocation purpose / 调用用途
     * @return whether current configuration passed the relevant probe / 当前配置是否通过对应测试
     */
    public boolean verified(AiPurposeType purpose) {
        return (purpose == AiPurposeType.VISION ? visionVerifiedAt : textVerifiedAt).isPresent();
    }
}
