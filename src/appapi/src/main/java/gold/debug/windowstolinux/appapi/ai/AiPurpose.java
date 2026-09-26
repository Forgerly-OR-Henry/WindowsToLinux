package gold.debug.windowstolinux.appapi.ai;

/**
 * Configured model group selected for a platform invocation. / 平台调用选用的配置模型组。
 */
public enum AiPurpose {
    /**
     * Configured models for general text requests. / 常规文本请求使用的配置模型组。
     */
    LANGUAGE,
    /**
     * Configured models for approval assistance. / 审批辅助使用的配置模型组。
     */
    APPROVAL,
    /**
     * Configured models that accept image input. / 接受图像输入的配置模型组。
     */
    VISION
}
