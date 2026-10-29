package gold.debug.windowstolinux.appapi.ai.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Objects;

/** Complete WAV input with optional exact model selection. / 带可选精确模型选择的完整 WAV 输入。
 * @param audio complete mono PCM16 24 kHz WAV, at most 25 MB / 完整单声道 PCM16 24 kHz WAV，最多 25 MB
 * @param filename safe WAV upload filename / 安全 WAV 上传文件名
 * @param timeout whole operation deadline, at most 30 minutes / 整体操作期限，最多 30 分钟
 * @param modelId exact configured model or empty to use voice recommendations / 精确配置模型，空值使用语音推荐
 */
public record VoiceRequest(byte[] audio, String filename, Duration timeout, java.util.Optional<String> modelId) {
    /** Captures inputs at the VoiceRequest ownership boundary. / 在 VoiceRequest 所有权边界捕获输入。
     * @param audio captured audio at this validated model boundary / 此已校验模型边界捕获的 audio
     * @param filename captured filename at this validated model boundary / 此已校验模型边界捕获的 filename
     * @param timeout captured timeout at this validated model boundary / 此已校验模型边界捕获的 timeout
     */
    public VoiceRequest(byte[] audio, String filename, Duration timeout) {
        this(audio, filename, timeout, java.util.Optional.empty());
    }

    /** Selects one configured model without fallback. / 选择单个配置模型，不切换后备。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @return validated immutable result or admitted completion / 已校验不可变结果或已准入完成状态
     */
    public VoiceRequest withModel(String id) {
        return new VoiceRequest(audio, filename, timeout, java.util.Optional.of(id));
    }

    /** Provider upload ceiling in bytes. / 提供方上传字节上限。 */
    public static final int MAX_BYTES = 25_000_000;

    /** Copies validated WAV input while retaining its selected model. / 复制已校验 WAV 输入并保留所选模型。
     * @param audio complete bounded WAV input / 完整有界 WAV 输入
     * @param filename safe WAV upload filename / 安全 WAV 上传文件名
     * @param timeout positive whole operation deadline / 正的整体操作期限
     * @param modelId exact configuration or empty for voice recommendations / 精确配置，空值使用语音推荐
     */
    public VoiceRequest {
        java.util.Objects.requireNonNull(modelId);
        if (modelId.isPresent() && !modelId.orElseThrow().matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid model configuration identity");
        Objects.requireNonNull(audio);
        filename = Objects.requireNonNull(filename).strip();
        if (!filename.matches("[A-Za-z0-9._-]{1,120}\\.wav"))
            throw new IllegalArgumentException("A safe .wav filename is required");
        validateTimeout(timeout);
        if (audio.length < 44 || audio.length > MAX_BYTES)
            throw new IllegalArgumentException("WAV input is empty or exceeds 25 MB");
        audio = audio.clone();
        byte[] samples = pcm(audio);
        java.util.Arrays.fill(samples, (byte) 0);
    }

    /** Returns a copy, preserving immutable request ownership. / 返回副本，保持不可变请求归属。
     * @return independent complete WAV bytes / 独立完整 WAV 字节 */
    @Override
    public byte[] audio() {
        return audio.clone();
    }

    /** Extracts validated raw samples without transcoding. / 提取已校验原始样本，不转码。
     * @return owned PCM16 sample copy / 所属 PCM16 样本副本 */
    public byte[] pcm() {
        return pcm(audio);
    }

    /** Checks the shared operation deadline. / 检查共用操作时限。
     * @param timeout total operation deadline / 整体操作时限 */
    public static void validateTimeout(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(30)) > 0)
            throw new IllegalArgumentException("Voice timeout must be positive and at most 30 minutes");
    }

    /** Validates bounded RIFF chunks and extracts PCM. / 校验有界 RIFF 块并提取 PCM。
     * @param data complete bounded WAV bytes / 完整有界 WAV 字节
     * @return copied mono PCM16 samples / 复制的单声道 PCM16 样本 */
    private static byte[] pcm(byte[] data) {
        if (data.length < 44 || data.length > MAX_BYTES)
            throw new IllegalArgumentException("WAV input is empty or exceeds 25 MB");
        var bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.getInt(0) != 0x46464952 || bytes.getInt(8) != 0x45564157
            || Integer.toUnsignedLong(bytes.getInt(4)) != data.length - 8L)
            throw new IllegalArgumentException("Invalid RIFF/WAVE envelope");
        boolean format = false;
        int offset = 12, start = -1, length = 0;
        while (offset < data.length) {
            if (data.length - offset < 8)
                throw new IllegalArgumentException("Truncated WAV chunk");
            int kind = bytes.getInt(offset);
            long size = Integer.toUnsignedLong(bytes.getInt(offset + 4));
            long end = offset + 8L + size;
            if (end > data.length || end + (size & 1) > data.length)
                throw new IllegalArgumentException("Truncated WAV chunk data");
            if (kind == 0x20746d66) {
                if (format || size != 16 || bytes.getShort(offset + 8) != 1
                    || bytes.getShort(offset + 10) != 1 || bytes.getInt(offset + 12) != 24000
                    || bytes.getInt(offset + 16) != 48000 || bytes.getShort(offset + 20) != 2
                    || bytes.getShort(offset + 22) != 16)
                    throw new IllegalArgumentException("WAV must be mono PCM16 at 24 kHz");
                format = true;
            } else if (kind == 0x61746164) {
                if (start != -1 || !format || size < 4800 || (size & 1) != 0)
                    throw new IllegalArgumentException("WAV needs one nonempty sample-aligned data chunk of at least 100 ms");
                start = offset + 8;
                length = (int) size;
            }
            offset = (int) (end + (size & 1));
        }
        if (!format || start < 0)
            throw new IllegalArgumentException("WAV format or data is absent");
        return java.util.Arrays.copyOfRange(data, start, start + length);
    }
}
