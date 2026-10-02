package gold.debug.windowstolinux.app.db.ai;

import java.time.Instant;
import java.util.*;
import gold.debug.windowstolinux.app.ai.model.*;

/** Revision-bound protocol capabilities for one model. / 同一模型绑定修订的分协议能力。
 * @param profile non-secret protocol settings / 非秘密协议设置
 * @param name display label / 显示名称
 * @param priority inventory display order / 清单展示顺序
 * @param revision connection revision / 连接修订
 * @param textVerifiedAt available text evidence / 可用文字证据
 * @param visionVerifiedAt available vision evidence / 可用视觉证据
 * @param fileVoiceVerifiedAt available file transcription evidence / 可用完整音频证据
 * @param realtimeVoiceVerifiedAt available realtime evidence / 可用实时证据
 * @param checks independent current probe states / 独立当前验证状态 */
public record StoredAiProviderConfiguration(StoredAiProviderProfile profile, String name, int priority, long revision,
    Optional<Instant> textVerifiedAt, Optional<Instant> visionVerifiedAt, Optional<Instant> fileVoiceVerifiedAt,
    Optional<Instant> realtimeVoiceVerifiedAt, Map<AiCapabilityType, AiCapabilityCheck> checks) {
    /** Validates and freezes independent capability evidence. / 校验并冻结独立能力证据。
     * @param profile protocol settings / 协议设置
     * @param name label / 名称
     * @param priority display order / 展示顺序
     * @param revision revision / 修订
     * @param textVerifiedAt text evidence / 文字证据
     * @param visionVerifiedAt vision evidence / 视觉证据
     * @param fileVoiceVerifiedAt file evidence / 文件证据
     * @param realtimeVoiceVerifiedAt realtime evidence / 实时证据
     * @param checks actual results / 实际结果 */
    public StoredAiProviderConfiguration {
        Objects.requireNonNull(profile); name = Objects.requireNonNull(name).strip();
        if (name.isEmpty() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)
            || priority < 0 || revision < 1) throw new IllegalArgumentException("Invalid model inventory");
        var times = Map.of(AiCapabilityType.TEXT, textVerifiedAt, AiCapabilityType.VISION, visionVerifiedAt,
            AiCapabilityType.VOICE_FILE, fileVoiceVerifiedAt, AiCapabilityType.VOICE_REALTIME, realtimeVoiceVerifiedAt);
        var evidence = new EnumMap<AiCapabilityType, AiCapabilityCheck>(AiCapabilityType.class);
        for (var capability : AiCapabilityType.values()) {
            Optional<Instant> time = times.get(capability);
            evidence.put(capability, Objects.requireNonNull(checks).getOrDefault(capability,
                time.map(t -> new AiCapabilityCheck(AiCapabilityStateType.AVAILABLE, "", Optional.of(t)))
                    .orElseGet(AiCapabilityCheck::unverified)));
        }
        checks = Map.copyOf(evidence);
        textVerifiedAt = availableTime(evidence.get(AiCapabilityType.TEXT));
        visionVerifiedAt = availableTime(evidence.get(AiCapabilityType.VISION));
        fileVoiceVerifiedAt = availableTime(evidence.get(AiCapabilityType.VOICE_FILE));
        realtimeVoiceVerifiedAt = availableTime(evidence.get(AiCapabilityType.VOICE_REALTIME));
    }
    /** Creates a text and vision inventory value. / 创建文字及视觉清单值。
     * @param profile settings / 设置
     * @param name label / 名称
     * @param priority order / 顺序
     * @param revision revision / 修订
     * @param textVerifiedAt text evidence / 文字证据
     * @param visionVerifiedAt vision evidence / 视觉证据 */
    public StoredAiProviderConfiguration(StoredAiProviderProfile profile, String name, int priority, long revision,
        Optional<Instant> textVerifiedAt, Optional<Instant> visionVerifiedAt) {
        this(profile, name, priority, revision, textVerifiedAt, visionVerifiedAt, Optional.empty(), Optional.empty(), Map.of());
    }
    /** Creates independently verified protocol metadata. / 创建独立验证的协议元数据。
     * @param profile settings / 设置
     * @param name label / 名称
     * @param priority order / 顺序
     * @param revision revision / 修订
     * @param textVerifiedAt text evidence / 文字证据
     * @param visionVerifiedAt vision evidence / 视觉证据
     * @param fileVoiceVerifiedAt file evidence / 文件证据
     * @param realtimeVoiceVerifiedAt realtime evidence / 实时证据 */
    public StoredAiProviderConfiguration(StoredAiProviderProfile profile, String name, int priority, long revision,
        Optional<Instant> textVerifiedAt, Optional<Instant> visionVerifiedAt, Optional<Instant> fileVoiceVerifiedAt,
        Optional<Instant> realtimeVoiceVerifiedAt) {
        this(profile, name, priority, revision, textVerifiedAt, visionVerifiedAt, fileVoiceVerifiedAt,
            realtimeVoiceVerifiedAt, Map.of());
    }
    /** Requires the capability corresponding to the actual input protocol. / 要求对应实际输入协议的能力。
     * @param capability actual input capability / 实际输入能力
     * @return actual available evidence / 实际可用证据 */
    public boolean verified(AiCapabilityType capability) { return checks.get(Objects.requireNonNull(capability)).available(); }
    /** Checks independent purpose eligibility. / 检查独立用途资格。
     * @param purpose configured purpose / 已配置用途
     * @return whether a matching capability passed / 匹配能力是否通过 */
    public boolean verified(AiPurposeType purpose) {
        return switch (Objects.requireNonNull(purpose)) {
            case VISION -> verified(AiCapabilityType.VISION);
            case VOICE -> verified(AiCapabilityType.VOICE_FILE) || verified(AiCapabilityType.VOICE_REALTIME);
            default -> verified(AiCapabilityType.TEXT);
        };
    }
    /** Exposes a timestamp only for successful evidence. / 仅对成功证据公开时间。
     * @param check actual probe state / 实际验证状态
     * @return successful timestamp or absent / 成功时间或空 */
    private static Optional<Instant> availableTime(AiCapabilityCheck check) {
        return check.available() ? check.checkedAt() : Optional.empty();
    }
}
