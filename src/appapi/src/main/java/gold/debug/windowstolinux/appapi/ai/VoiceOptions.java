package gold.debug.windowstolinux.appapi.ai;

import java.time.Duration;

/** Raw realtime input is mono PCM16 little-endian at 24 kHz, at most 30 minutes. / 实时输入为单声道小端 PCM16、24 kHz，最多 30 分钟。
 * @param timeout whole session deadline / 整个会话时限
 * @param maxBufferedBytes maximum queued raw audio, 64 KiB to 1 MiB / 排队原始音频上限，64 KiB 至 1 MiB */
public record VoiceOptions(Duration timeout, int maxBufferedBytes) {
    /** Checks finite deadlines and bounded backpressure. / 检查有限时限和有界背压。
     * @param timeout whole session deadline / 整个会话时限
     * @param maxBufferedBytes queued audio ceiling / 排队音频上限 */
    public VoiceOptions {
        VoiceRequest.validateTimeout(timeout);
        if (maxBufferedBytes < 65_536 || maxBufferedBytes > 1_048_576)
            throw new IllegalArgumentException("Voice queue must be between 64 KiB and 1 MiB");
    }
}
