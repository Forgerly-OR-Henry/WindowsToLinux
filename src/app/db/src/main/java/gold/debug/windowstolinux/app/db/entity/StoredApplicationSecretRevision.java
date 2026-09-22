package gold.debug.windowstolinux.app.db.entity;

import java.time.Instant;
import java.util.Objects;

import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Immutable metadata that locates a secret outside normal configuration and release directories.
 *
 *  <p>用于定位普通配置和发布目录之外秘密的不可变元数据。
 *
 * @param reference immutable public secret identity / 不可变公开秘密身份
 * @param credentialKey platform-secret-store key / 平台秘密存储键
 * @param credentialMode selected platform-secret-store mode / 已选择的平台秘密存储模式
 * @param createdAt immutable revision creation time / 不可变修订创建时间
 */
public record StoredApplicationSecretRevision(SecretReference reference, String credentialKey,
        CredentialStorageMode credentialMode, Instant createdAt) {
    /**
     * Validates and binds the inputs required by stored application secret revision.
     * <p>校验并绑定已存储应用秘密修订所需输入。
     *
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     * @param createdAt instant at which this record was created / 当前记录创建时刻
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public StoredApplicationSecretRevision {
        reference = Objects.requireNonNull(reference, "reference");
        credentialKey = requireKey(credentialKey);
        credentialMode = Objects.requireNonNull(credentialMode, "credentialMode");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Validates and returns lookup key within the current contract and rejects inputs outside the declared constraints.
     * <p>校验并返回当前契约内的查找键并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return require key text / 要求键文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireKey(String value) {
        value = Objects.requireNonNull(value, "credentialKey").trim();
        if (value.isBlank() || value.length() > 240 || value.indexOf('\u0000') >= 0 || value.contains("..")) {
            throw new IllegalArgumentException("credentialKey is not a safe platform-secret-store key");
        }
        return value;
    }
}
