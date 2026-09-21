package gold.debug.windowstolinux.shared.model.ai;

/** Independent invocation purposes, unrelated to inventory display order. / 与清单展示顺序无关的独立调用用途。 */
public enum AiPurposeType {
    /** Deployment reasoning. / 部署推理。 */
    DEPLOYMENT,
    /** Independent action approval. / 独立动作审批。 */
    APPROVAL,
    /** Image observation only. / 仅图像观察。 */
    VISION
}
