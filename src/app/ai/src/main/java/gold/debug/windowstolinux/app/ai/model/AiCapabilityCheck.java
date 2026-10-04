package gold.debug.windowstolinux.app.ai.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Safe evidence for one capability, without provider payloads. / 单项能力的安全证据，不含提供方载荷。
 * @param state verification result / 验证结果
 * @param reason stable safe reason key or empty / 稳定安全原因键或空
 * @param checkedAt actual probe completion time / 实际验证完成时间 */
public record AiCapabilityCheck(AiCapabilityStatus state, String reason, Optional<Instant> checkedAt) {
    /** Validates bounded evidence and its completion time. / 校验有界证据及完成时间。
     * @param state result classification / 结果分类
     * @param reason safe reason key / 安全原因键
     * @param checkedAt completed probe time / 完成的验证时间 */
    public AiCapabilityCheck {
        Objects.requireNonNull(state);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(checkedAt);
        if (!reason.isEmpty() && !reason.matches("[A-Z][A-Z0-9_]{0,79}")
            || (state == AiCapabilityStatus.UNVERIFIED) != checkedAt.isEmpty()
            || state == AiCapabilityStatus.UNVERIFIED && !reason.isEmpty())
            throw new IllegalArgumentException("Invalid capability evidence");
    }

    /** Creates an absent capability conclusion. / 创建未验证的能力结论。
     * @return no current evidence / 无当前证据 */
    public static AiCapabilityCheck unverified() {
        return new AiCapabilityCheck(AiCapabilityStatus.UNVERIFIED, "", Optional.empty());
    }

    /** Reports whether the actual probe passed. / 判断实际验证是否通过。
     * @return available capability / 可用能力 */
    public boolean available() {
        return state == AiCapabilityStatus.AVAILABLE;
    }
}
