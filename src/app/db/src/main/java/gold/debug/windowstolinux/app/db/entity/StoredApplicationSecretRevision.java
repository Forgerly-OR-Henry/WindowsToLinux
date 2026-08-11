package gold.debug.windowstolinux.app.db.entity;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable metadata that locates a secret outside normal configuration and release directories.
 *
 * <p>用于定位普通配置和发布目录之外秘密的不可变元数据。
 *
 * @param reference immutable public secret identity / 不可变公开秘密身份
 * @param credentialKey platform-secret-store key / 平台秘密存储键
 * @param credentialMode selected platform-secret-store mode / 已选择的平台秘密存储模式
 * @param createdAt immutable revision creation time / 不可变修订创建时间
 */
public record StoredApplicationSecretRevision(
        SecretReference reference,
        String credentialKey,
        CredentialStorageMode credentialMode,
        Instant createdAt
) {
    /**
     * Creates a {@code StoredApplicationSecretRevision} instance.
     *
     * <p>创建 {@code StoredApplicationSecretRevision} 实例。
     */
    public StoredApplicationSecretRevision {
        reference = Objects.requireNonNull(reference, "reference");
        credentialKey = requireKey(credentialKey);
        credentialMode = Objects.requireNonNull(credentialMode, "credentialMode");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireKey(String value) {
        value = Objects.requireNonNull(value, "credentialKey").trim();
        if (value.isBlank() || value.length() > 240 || value.indexOf('\u0000') >= 0 || value.contains("..")) {
            throw new IllegalArgumentException("credentialKey is not a safe platform-secret-store key");
        }
        return value;
    }
}
