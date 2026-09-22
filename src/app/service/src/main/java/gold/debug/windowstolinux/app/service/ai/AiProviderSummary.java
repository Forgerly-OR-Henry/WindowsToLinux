package gold.debug.windowstolinux.app.service.ai;

import java.time.Instant;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;

/** Credential-free inventory row with separate capability evidence. / 不含凭据明文且能力证据独立的清单行。
 * @param profile connection settings / 连接设置
 * @param name display name / 显示名称
 * @param priority display order / 展示顺序
 * @param revision connection revision / 连接修订
 * @param textVerifiedAt text probe time / 文本测试时间
 * @param visionVerifiedAt vision probe time / 视觉测试时间
 */
public record AiProviderSummary(AiProviderProfile profile, String name, int priority, long revision,
        Optional<Instant> textVerifiedAt, Optional<Instant> visionVerifiedAt) {
    /** Reports whether a purpose has current capability evidence. / 判断用途是否具有当前能力证据。
     * @param purpose selected purpose / 所选用途
     * @return verified capability / 已验证能力
     */
    public boolean verified(AiPurposeType purpose) {
        return (purpose == AiPurposeType.VISION ? visionVerifiedAt : textVerifiedAt).isPresent();
    }
}
