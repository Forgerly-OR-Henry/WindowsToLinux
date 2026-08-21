package gold.debug.windowstolinux.shared.config;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by deterministic deployment configuration validation. / 确定性部署配置校验持有的失败类型。 */
public enum ConfigurationFailureType implements FailureDefinition {
    IDENTIFIER_INVALID("configuration.validation.identifier-invalid", "validation", "configuration.error.identifierInvalid"),
    REVISION_INVALID("configuration.validation.revision-invalid", "validation", "configuration.error.revisionInvalid"),
    HASH_INVALID("configuration.validation.hash-invalid", "validation", "configuration.error.hashInvalid"),
    SIZE_LIMIT_EXCEEDED("configuration.validation.size-limit-exceeded", "validation", "configuration.error.sizeLimitExceeded"),
    SECRET_VALUE_INVALID("configuration.secret.value-invalid", "validation", "configuration.error.secretValueInvalid"),
    SECRET_COLLISION("configuration.secret.identifier-collision", "validation", "configuration.error.secretCollision"),
    SNAPSHOT_EMPTY("configuration.snapshot.empty", "validation", "configuration.error.snapshotEmpty"),
    DUPLICATE_KEY("configuration.snapshot.duplicate-key", "validation", "configuration.error.duplicateKey"),
    SNAPSHOT_INTEGRITY_FAILED("configuration.snapshot.integrity-failed", "validation", "configuration.error.snapshotIntegrityFailed"),
    CONFIGURATION_KEY_INVALID("configuration.entry.key-invalid", "validation", "configuration.error.keyInvalid"),
    SECRET_LIKE_KEY("configuration.entry.secret-like-key", "validation", "configuration.error.secretLikeKey"),
    PORT_INVALID("configuration.entry.port-invalid", "validation", "configuration.error.portInvalid"),
    TEXT_VALUE_INVALID("configuration.entry.text-invalid", "validation", "configuration.error.textInvalid"),
    HASH_ALGORITHM_UNAVAILABLE("configuration.runtime.hash-unavailable", "runtime", "configuration.error.hashUnavailable");

    private final String code;
    private final String phase;
    private final String messageKey;

    ConfigurationFailureType(String code, String phase, String messageKey) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "configuration"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return FailureRecoveryAction.REQUEST_USER_CORRECTION; }
}
