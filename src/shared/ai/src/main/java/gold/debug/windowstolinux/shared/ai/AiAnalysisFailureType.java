package gold.debug.windowstolinux.shared.ai;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by the optional AI explanation boundary. / 可选 AI 解释边界持有的失败类型。 */
public enum AiAnalysisFailureType implements FailureDefinition {
    ENDPOINT_INVALID("ai.configuration.endpoint-invalid", "configuration", "ai.error.endpointInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    HTTPS_REQUIRED("ai.configuration.https-required", "configuration", "ai.error.httpsRequired", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    API_KEY_MISSING("ai.authentication.api-key-missing", "authentication", "ai.error.apiKeyMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    HTTP_REJECTED("ai.request.http-rejected", "request", "ai.error.httpRejected", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    SERVICE_UNAVAILABLE("ai.request.service-unavailable", "request", "ai.error.unavailable", FailureRecoveryAction.RETRY),
    REQUEST_INTERRUPTED("ai.request.interrupted", "request", "ai.error.interrupted", FailureRecoveryAction.NONE),
    RESPONSE_INVALID("ai.response.invalid", "response", "ai.error.responseInvalid", FailureRecoveryAction.NONE),
    RESPONSE_CONTENT_MISSING("ai.response.content-missing", "response", "ai.error.responseContentMissing", FailureRecoveryAction.NONE),
    RESPONSE_FORMAT_INVALID("ai.response.format-invalid", "response", "ai.error.responseFormatInvalid", FailureRecoveryAction.NONE),
    RESPONSE_NOT_STRING("ai.response.not-string", "response", "ai.error.responseNotString", FailureRecoveryAction.NONE),
    RESPONSE_EMPTY("ai.response.empty", "response", "ai.error.responseEmpty", FailureRecoveryAction.NONE),
    UNICODE_ESCAPE_INVALID("ai.response.unicode-escape-invalid", "response", "ai.error.unicodeEscapeInvalid", FailureRecoveryAction.NONE),
    STRING_ESCAPE_INVALID("ai.response.string-escape-invalid", "response", "ai.error.stringEscapeInvalid", FailureRecoveryAction.NONE),
    UNTERMINATED_STRING("ai.response.unterminated-string", "response", "ai.error.unterminatedString", FailureRecoveryAction.NONE);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    AiAnalysisFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "ai"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.WARNING; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}
