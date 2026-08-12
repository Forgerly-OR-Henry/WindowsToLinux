package gold.debug.windowstolinux.shared.config.secretref;

import java.util.Objects;

/**
 * Public integrity metadata for one resolved secret revision; it never contains the secret value.
 *
 * <p>一个已解析秘密修订的公开完整性元数据；它绝不包含秘密值。
 *
 * @param reference the immutable secret reference / 不可变秘密引用
 * @param sha256 the UTF-8 value digest / UTF-8 值摘要
 * @param byteCount the bounded UTF-8 byte count / 有界 UTF-8 字节数
 */
public record SecretRevisionDigest(SecretReference reference, String sha256, int byteCount) {
    /** Creates and validates public secret integrity metadata. / 创建并校验公开秘密完整性元数据。 */
    public SecretRevisionDigest {
        reference = Objects.requireNonNull(reference, "reference");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be lowercase SHA-256");
        }
        if (byteCount < 1 || byteCount > ResolvedSecretRevision.MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("secret byte count exceeds the bounded transfer size");
        }
    }
}
