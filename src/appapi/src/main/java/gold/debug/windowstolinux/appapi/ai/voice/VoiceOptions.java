package gold.debug.windowstolinux.appapi.ai.voice;

import java.time.Duration;

/** Bounded realtime input with optional exact model selection. / 带可选精确模型选择的有界实时输入。
 * @param timeout whole session deadline / 整个会话期限
 * @param maxBufferedBytes queued PCM16 audio limit from 64 KiB to 1 MiB / 排队 PCM16 音频上限，64 KiB 至 1 MiB
 * @param modelId exact configured model or empty to use voice recommendations / 精确配置模型，空值使用语音推荐
 */
public record VoiceOptions(Duration timeout, int maxBufferedBytes, java.util.Optional<String> modelId) {
    /** Captures inputs at the VoiceOptions ownership boundary. / 在 VoiceOptions 所有权边界捕获输入。
     * @param timeout captured timeout at this validated model boundary / 此已校验模型边界捕获的 timeout
     * @param maxBufferedBytes captured maxBufferedBytes at this validated model boundary / 此已校验模型边界捕获的 maxBufferedBytes
     */
    public VoiceOptions(Duration timeout, int maxBufferedBytes) {
        this(timeout, maxBufferedBytes, java.util.Optional.empty());
    }

    /** Selects one configured model without fallback. / 选择单个配置模型，不切换后备。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public VoiceOptions withModel(String id) {
        return new VoiceOptions(timeout, maxBufferedBytes, java.util.Optional.of(id));
    }

    /** Validates bounded audio input and an optional exact model selector. / 校验有界音频输入及可选精确模型选择器。
     * @param timeout whole session deadline / 整个会话期限
     * @param maxBufferedBytes queued audio ceiling from 64 KiB to 1 MiB / 排队音频上限，64 KiB 至 1 MiB
     * @param modelId exact configuration or empty for voice recommendations / 精确配置，空值使用语音推荐
     */
    public VoiceOptions {
        java.util.Objects.requireNonNull(modelId);
        if (modelId.isPresent() && !modelId.orElseThrow().matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid model configuration identity");
        VoiceRequest.validateTimeout(timeout);
        if (maxBufferedBytes < 65_536 || maxBufferedBytes > 1_048_576)
            throw new IllegalArgumentException("Voice queue must be between 64 KiB and 1 MiB");
    }
}
