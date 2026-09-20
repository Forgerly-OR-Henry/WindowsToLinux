package gold.debug.windowstolinux.shared.backup.crypto;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by independent backup-secret cryptography. / 独立备份秘密密码学持有的失败类型。 */
public enum BackupSecretFailureType implements FailureDefinition {
    PASSWORD_INVALID("secret.backup.password-invalid", "backup", "secret.error.backupPasswordInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    ENCRYPT_FAILED("secret.backup.encrypt-failed", "backup", "secret.error.backupEncryptFailed", FailureRecoveryAction.RETRY),
    DECRYPT_FAILED("secret.backup.decrypt-failed", "backup", "secret.error.backupDecryptFailed", FailureRecoveryAction.REQUEST_USER_CORRECTION),
    PAYLOAD_INVALID("secret.backup.payload-invalid", "backup", "secret.error.backupPayloadInvalid", FailureRecoveryAction.REQUEST_USER_CORRECTION);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureRecoveryAction recoveryAction;

    BackupSecretFailureType(String code, String phase, String messageKey, FailureRecoveryAction recoveryAction) {
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
