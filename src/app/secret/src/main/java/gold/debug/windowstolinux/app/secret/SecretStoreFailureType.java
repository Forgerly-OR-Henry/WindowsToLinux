package gold.debug.windowstolinux.app.secret;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by secret persistence adapters. / 秘密持久化适配器持有的失败类型。 */
public enum SecretStoreFailureType implements FailureDefinition {
    ENCRYPT_FAILED("secret.persistence.encrypt-failed", "persistence", "secret.error.encryptFailed", FailureRecoveryAction.RETRY),
    DELETE_FAILED("secret.persistence.delete-failed", "persistence", "secret.error.deleteFailed", FailureRecoveryAction.RETRY),
    VERSION_UNSUPPORTED("secret.persistence.version-unsupported", "persistence", "secret.error.versionUnsupported", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    READ_FAILED("secret.persistence.read-failed", "persistence", "secret.error.readFailed", FailureRecoveryAction.RETRY),
    DECRYPT_FAILED("secret.persistence.decrypt-failed", "persistence", "secret.error.decryptFailed", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    WINDOWS_ONLY("secret.adapter.windows-only", "adapter", "secret.error.windowsOnly", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    WINDOWS_WRITE_FAILED("secret.adapter.windows-write-failed", "adapter", "secret.error.windowsWriteFailed", FailureRecoveryAction.RETRY),
    WINDOWS_DELETE_FAILED("secret.adapter.windows-delete-failed", "adapter", "secret.error.windowsDeleteFailed", FailureRecoveryAction.RETRY),
    WINDOWS_EMPTY_RESPONSE("secret.adapter.windows-empty-response", "adapter", "secret.error.windowsEmptyResponse", FailureRecoveryAction.RETRY),
    WINDOWS_INVALID_RESPONSE("secret.adapter.windows-invalid-response", "adapter", "secret.error.windowsInvalidResponse", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    WINDOWS_TIMEOUT("secret.adapter.windows-timeout", "adapter", "secret.error.windowsTimeout", FailureRecoveryAction.RETRY),
    WINDOWS_OPERATION_FAILED("secret.adapter.windows-operation-failed", "adapter", "secret.error.windowsOperationFailed", FailureRecoveryAction.RETRY),
    WINDOWS_START_FAILED("secret.adapter.windows-start-failed", "adapter", "secret.error.windowsStartFailed", FailureRecoveryAction.RESTART_APPLICATION),
    WINDOWS_INTERRUPTED("secret.adapter.windows-interrupted", "adapter", "secret.error.windowsInterrupted", FailureRecoveryAction.NONE),
    WINDOWS_UNCONTROLLED_RESPONSE("secret.adapter.windows-uncontrolled-response", "adapter", "secret.error.windowsUncontrolledResponse", FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    APPLICATION_VALUE_MISSING("secret.configuration.application-value-missing", "configuration", "secret.error.applicationValueMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    APPLICATION_REFERENCE_MISSING("secret.configuration.application-reference-missing", "configuration", "secret.error.applicationReferenceMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    SSH_CREDENTIAL_MISSING("secret.configuration.ssh-credential-missing", "configuration", "secret.error.sshCredentialMissing", FailureRecoveryAction.REQUEST_USER_CORRECTION);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    SecretStoreFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "secret"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}
