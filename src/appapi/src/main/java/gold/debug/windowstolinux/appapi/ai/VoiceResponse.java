package gold.debug.windowstolinux.appapi.ai;

import java.util.List;
import java.util.Objects;

/** Confirmed final text and ordered parts; failed realtime results may retain confirmed parts. / 已确认最终文字及有序片段；实时失败结果可以保留已确认片段。
 * @param text concatenated final transcript / 拼接的最终转写
 * @param modelId selected platform model identity / 所选平台模型身份
 * @param segments final parts in input order / 按输入顺序排列的最终片段 */
public record VoiceResponse(String text, String modelId, List<VoiceEvent> segments) {
    /** Freezes the confirmed result. / 冻结已确认结果。 */
    public VoiceResponse {
        Objects.requireNonNull(text); Objects.requireNonNull(modelId);
        segments = List.copyOf(segments);
        if (text.length() > 1_048_576 || segments.stream().anyMatch(part -> !part.finalResult()))
            throw new IllegalArgumentException("Invalid final voice result");
    }
}
