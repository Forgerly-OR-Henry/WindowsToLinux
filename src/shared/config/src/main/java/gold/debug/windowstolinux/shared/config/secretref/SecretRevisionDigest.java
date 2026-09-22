package gold.debug.windowstolinux.shared.config.secretref;

import java.util.Objects;

import gold.debug.windowstolinux.shared.config.ConfigurationException;
import gold.debug.windowstolinux.shared.config.ConfigurationFailureType;

/**
 * Public integrity metadata for one resolved secret revision; it never contains the secret value.
 *
 *  <p>一个已解析秘密修订的公开完整性元数据；它绝不包含秘密值。
 *
 * @param reference the immutable secret reference / 不可变秘密引用
 * @param sha256 the UTF-8 value digest / UTF-8 值摘要
 * @param byteCount the bounded UTF-8 byte count / 有界 UTF-8 字节数
 */
public record SecretRevisionDigest(SecretReference reference, String sha256, int byteCount) {
    /**
     * Creates and validates public secret integrity metadata. / 创建并校验公开秘密完整性元数据。
     *
     * @param reference the immutable secret reference / 不可变秘密引用
     * @param sha256 lower-case hexadecimal SHA-256 digest / 小写十六进制 SHA-256 摘要
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SecretRevisionDigest {
        reference = Objects.requireNonNull(reference, "reference");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw ConfigurationException.create(ConfigurationFailureType.HASH_INVALID,
                    "A SHA-256 value must use the canonical lowercase form");
        }
        if (byteCount < 1 || byteCount > ResolvedSecretRevision.MAX_VALUE_BYTES) {
            throw ConfigurationException.create(ConfigurationFailureType.SIZE_LIMIT_EXCEEDED,
                    "A secret revision exceeds the bounded transfer size");
        }
    }
}
