package gold.debug.windowstolinux.mod.deployment.service.core.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable secret metadata; the opaque credential identity never contains its value. / 不可变秘密元数据，不透明凭据身份绝不包含其值。
 * @param reference exact business secret revision / 精确业务秘密修订
 * @param credentialId original platform-issued opaque identity / 原平台签发的不透明身份
 * @param createdAt immutable creation time / 不可变创建时间
 * @param purpose exact original credential purpose, absent only for migrated schema-1 rows / 精确原凭据用途，仅迁移自结构1的旧行可缺失 */
public record DeploymentSecretRevision(DeploymentSecretReference reference, String credentialId, Instant createdAt,
        Optional<String> purpose) {
    /** Retains the exact opaque identity without parsing a platform-private key format. / 保留精确不透明身份，不解析平台私有键格式。
     * @param reference exact business secret revision / 精确业务秘密修订
     * @param credentialId original platform-issued opaque identity / 原平台签发的不透明身份
     * @param createdAt immutable creation time / 不可变创建时间
     * @param purpose exact original purpose or historical absence / 精确原用途或历史缺失 */
    public DeploymentSecretRevision {
        Objects.requireNonNull(reference);
        Objects.requireNonNull(credentialId);
        Objects.requireNonNull(createdAt);
        purpose = Objects.requireNonNull(purpose);
        if (credentialId.isBlank() || credentialId.length() > 512 || credentialId.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid opaque credential identity");
        if (purpose.filter(value -> value.isBlank() || value.length() > 128 || value.indexOf('\0') >= 0).isPresent())
            throw new IllegalArgumentException("Invalid original credential purpose");
    }
}
