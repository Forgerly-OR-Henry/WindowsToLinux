package gold.debug.windowstolinux.appapi.ai.voice;

import java.util.Objects;

/** One transcript observation; deltas append, final text replaces all deltas for this part. / 一个转写观测；增量追加，最终文字替换本片段所有增量。
 * @param segmentId provider input item identity / 提供方输入项身份
 * @param contentIndex content part within the item / 项内内容片段
 * @param sequence monotonically increasing delivery order / 单调递增投递序号
 * @param inputOrder input commit order, independent of final arrival order / 输入提交顺序，与最终到达顺序独立
 * @param finalResult whether this part is confirmed / 本片段是否已定稿
 * @param text delta or complete final text / 增量或完整最终文字 */
public record VoiceEvent(String segmentId, int contentIndex, long sequence, long inputOrder,
                         boolean finalResult, String text) {
    /** Checks bounded, unambiguous event identity. / 检查有界且明确的事件身份。
     * @param segmentId provider input identity / 提供方输入身份
     * @param contentIndex item content part / 项内内容片段
     * @param sequence delivery sequence / 投递序号
     * @param inputOrder original input sequence / 原输入次序
     * @param finalResult confirmed final marker / 已确认定稿标记
     * @param text bounded transcript observation / 有界转写观测 */
    public VoiceEvent {
        Objects.requireNonNull(segmentId);
        Objects.requireNonNull(text);
        if (segmentId.isBlank() || segmentId.length() > 256 || contentIndex < 0
            || sequence < 0 || inputOrder < 0 || text.length() > 262_144)
            throw new IllegalArgumentException("Invalid voice observation");
    }
}
