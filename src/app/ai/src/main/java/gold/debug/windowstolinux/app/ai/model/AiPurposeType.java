package gold.debug.windowstolinux.app.ai.model;

/** Independent invocation purposes, unrelated to inventory display order. / 与清单展示顺序无关的独立调用用途。 */
public enum AiPurposeType {
    /** Deployment reasoning. / 部署推理。 */
    DEPLOYMENT,
    /** Independent action approval. / 独立动作审批。 */
    APPROVAL,
    /** Image observation only. / 仅图像观察。 */
    VISION,
    /** Speech recognition with mode-specific capability filtering. / 按输入方式筛选能力的语音识别。 */
    VOICE
}
