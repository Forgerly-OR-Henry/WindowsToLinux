package gold.debug.windowstolinux.shared.ai;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/**
 * Failures owned by the optional AI explanation boundary. / 可选 AI 解释边界持有的失败类型。
 */
public enum AiAnalysisFailureType implements FailureDefinition {
    /**
     * ENDPOINT INVALID classification within ai analysis failure type.
     * <p>AI分析失败类型中的端点无效分类。
     */
    ENDPOINT_INVALID("ai.configuration.endpoint-invalid", "configuration", "ai.error.endpointInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * HTTPS REQUIRED classification within ai analysis failure type.
     * <p>AI分析失败类型中的HTTPS必需分类。
     */
    HTTPS_REQUIRED("ai.configuration.https-required", "configuration", "ai.error.httpsRequired", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * API KEY MISSING classification within ai analysis failure type.
     * <p>AI分析失败类型中的API键缺失分类。
     */
    API_KEY_MISSING("ai.authentication.api-key-missing", "authentication", "ai.error.apiKeyMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * HTTP REJECTED classification within ai analysis failure type.
     * <p>AI分析失败类型中的HTTP已拒绝分类。
     */
    HTTP_REJECTED("ai.request.http-rejected", "request", "ai.error.httpRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    /**
     * SERVICE UNAVAILABLE classification within ai analysis failure type.
     * <p>AI分析失败类型中的服务不可用分类。
     */
    SERVICE_UNAVAILABLE("ai.request.service-unavailable", "request", "ai.error.unavailable", FailureRecoveryAction.RETRY),
    /**
     * REQUEST INTERRUPTED classification within ai analysis failure type.
     * <p>AI分析失败类型中的请求已中断分类。
     */
    REQUEST_INTERRUPTED("ai.request.interrupted", "request", "ai.error.interrupted", FailureRecoveryAction.NONE),
    /**
     * RESPONSE INVALID classification within ai analysis failure type.
     * <p>AI分析失败类型中的响应无效分类。
     */
    RESPONSE_INVALID("ai.response.invalid", "response", "ai.error.responseInvalid", FailureRecoveryAction.NONE),
    /**
     * RESPONSE CONTENT MISSING classification within ai analysis failure type.
     * <p>AI分析失败类型中的响应内容缺失分类。
     */
    RESPONSE_CONTENT_MISSING("ai.response.content-missing", "response", "ai.error.responseContentMissing", FailureRecoveryAction.NONE),
    /**
     * RESPONSE FORMAT INVALID classification within ai analysis failure type.
     * <p>AI分析失败类型中的响应格式无效分类。
     */
    RESPONSE_FORMAT_INVALID("ai.response.format-invalid", "response", "ai.error.responseFormatInvalid", FailureRecoveryAction.NONE),
    /**
     * RESPONSE NOT STRING classification within ai analysis failure type.
     * <p>AI分析失败类型中的响应未字符串分类。
     */
    RESPONSE_NOT_STRING("ai.response.not-string", "response", "ai.error.responseNotString", FailureRecoveryAction.NONE),
    /**
     * RESPONSE EMPTY classification within ai analysis failure type.
     * <p>AI分析失败类型中的响应空分类。
     */
    RESPONSE_EMPTY("ai.response.empty", "response", "ai.error.responseEmpty", FailureRecoveryAction.NONE),
    /**
     * UNICODE ESCAPE INVALID classification within ai analysis failure type.
     * <p>AI分析失败类型中的Unicode转义无效分类。
     */
    UNICODE_ESCAPE_INVALID("ai.response.unicode-escape-invalid", "response", "ai.error.unicodeEscapeInvalid", FailureRecoveryAction.NONE),
    /**
     * STRING ESCAPE INVALID classification within ai analysis failure type.
     * <p>AI分析失败类型中的字符串转义无效分类。
     */
    STRING_ESCAPE_INVALID("ai.response.string-escape-invalid", "response", "ai.error.stringEscapeInvalid", FailureRecoveryAction.NONE),
    /**
     * UNTERMINATED STRING classification within ai analysis failure type.
     * <p>AI分析失败类型中的未终止字符串分类。
     */
    UNTERMINATED_STRING("ai.response.unterminated-string", "response", "ai.error.unterminatedString", FailureRecoveryAction.NONE);

    /**
     * Stable machine-readable classification code.
     * <p>稳定的机器可读分类码。
     */
    private final String code;
    /**
     * Stage associated with the result or failure.
     * <p>结果或失败所属阶段。
     */
    private final String phase;
    /**
     * Stable localization key for user-facing text.
     * <p>用户可见文本的稳定本地化键。
     */
    private final String messageKey;
    /**
     * Action required to recover from the classified failure.
     * <p>从已分类失败中恢复所需的动作。
     */
    private final FailureRecoveryAction recoveryAction;

    /**
     * Binds the supplied dependencies and state for ai analysis failure type.
     * <p>为AI分析失败类型绑定传入的依赖及状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param messageKey stable localization key for user-facing text / 用户可见文本的稳定本地化键
     * @param recoveryAction action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    AiAnalysisFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    /**
     * Returns stable machine-readable classification code.
     * <p>返回稳定的机器可读分类码。
     *
     * @return stable machine-readable classification code / 稳定的机器可读分类码
     */
    @Override public String code() { return code; }
    /**
     * Returns the module domain that owns this failure definition.
     * <p>返回持有当前失败定义的模块领域。
     *
     * @return the module domain that owns this failure definition / 持有当前失败定义的模块领域
     */
    @Override public String domain() { return "ai"; }
    /**
     * Returns stage associated with the result or failure.
     * <p>返回结果或失败所属阶段。
     *
     * @return stage associated with the result or failure / 结果或失败所属阶段
     */
    @Override public String phase() { return phase; }
    /**
     * Returns stable localization key for user-facing text.
     * <p>返回用户可见文本的稳定本地化键。
     *
     * @return stable localization key for user-facing text / 用户可见文本的稳定本地化键
     */
    @Override public String messageKey() { return messageKey; }
    /**
     * Returns the severity assigned to this failure definition.
     * <p>返回当前失败定义的严重级别。
     *
     * @return the severity assigned to this failure definition / 当前失败定义的严重级别
     */
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.WARNING; }
    /**
     * Returns action required to recover from the classified failure.
     * <p>返回从已分类失败中恢复所需的动作。
     *
     * @return action required to recover from the classified failure / 从已分类失败中恢复所需的动作
     */
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}
