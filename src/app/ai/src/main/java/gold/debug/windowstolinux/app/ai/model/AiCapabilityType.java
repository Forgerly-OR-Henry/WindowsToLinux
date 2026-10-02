package gold.debug.windowstolinux.app.ai.model;

/** Separately verified model input capabilities. / 分别验证的模型输入能力。 */
public enum AiCapabilityType {
    /** Structured text responses. / 结构化文本响应。 */
    TEXT,
    /** Image understanding. / 图像理解。 */
    VISION,
    /** Complete audio file transcription. / 完整音频文件转写。 */
    VOICE_FILE,
    /** Actual realtime audio input transcription. / 实际实时音频输入转写。 */
    VOICE_REALTIME
}
