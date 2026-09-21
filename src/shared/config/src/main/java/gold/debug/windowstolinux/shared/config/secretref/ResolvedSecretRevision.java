
package gold.debug.windowstolinux.shared.config.secretref;

import gold.debug.windowstolinux.shared.config.ConfigurationException;
import gold.debug.windowstolinux.shared.config.ConfigurationFailureType;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Short-lived resolved secret bytes used only for an authenticated deployment transfer.
 *
 *  <p>仅用于已认证部署传输的短生命周期已解析秘密字节。
 */
public final class ResolvedSecretRevision implements AutoCloseable {
    /**
     * Maximum UTF-8 payload accepted for one revision. / 单个修订允许的最大 UTF-8 载荷。
     */
    public static final int MAX_VALUE_BYTES = 65_536;

    /**
     * Immutable public secret identity.
     * <p>不可变公开秘密身份。
     */
    private final SecretReference reference;
    /**
     * Candidate content accepted or rejected by this contract.
     * <p>由当前契约接收或拒绝的候选内容。
     */
    private final byte[] value;
    /**
     * Content identity used for independent verification.
     * <p>独立验证所用的内容身份。
     */
    private final SecretRevisionDigest digest;

    /**
     * Copies a caller-owned character value without creating an immutable plaintext string.
     *
     *  <p>复制调用方持有的字符值，且不创建不可清除的明文字符串。
     *
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ResolvedSecretRevision(SecretReference reference, char[] value) {
        this.reference = Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(value, "value");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (bytes.length == 0 || bytes.length > MAX_VALUE_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw ConfigurationException.create(ConfigurationFailureType.SECRET_VALUE_INVALID, "A secret value must be non-empty and bounded");
        }
        this.value = bytes;
        this.digest = new SecretRevisionDigest(reference, sha256(bytes), bytes.length);
    }

    /**
     * Returns the opaque immutable reference. / 返回透明的不可变引用。
     *
     * @return the opaque immutable reference / 透明的不可变引用
     */
    public SecretReference reference() {
        return reference;
    }

    /**
     * Returns public integrity metadata without exposing plaintext. / 返回不暴露明文的公开完整性元数据。
     *
     * @return public integrity metadata without exposing plaintext / 不暴露明文的公开完整性元数据
     */
    public SecretRevisionDigest digest() {
        return digest;
    }

    /**
     * Returns a caller-owned byte copy that must be cleared. / 返回必须由调用方清除的字节副本。
     *
     * @return a caller-owned byte copy that must be cleared / 必须由调用方清除的字节副本
     */
    public synchronized byte[] copyValue() {
        return value.clone();
    }

    /**
     * Returns a caller-owned character copy for a text-only platform secret store. / 为仅文本的平台秘密存储返回调用方持有的字符副本。
     *
     * @return a caller-owned character copy for a text-only platform secret store / 为仅文本的平台秘密存储返回调用方持有的字符副本
     */
    public synchronized char[] copyCharacters() {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            return result;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw ConfigurationException.create(ConfigurationFailureType.SECRET_VALUE_INVALID,
                    "A secret revision is not valid UTF-8 text", exception);
        }
    }

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    public synchronized void close() {
        Arrays.fill(value, (byte) 0);
    }

    /**
     * Returns the diagnostic text representation of this object.
     * <p>返回当前对象的诊断文本表示。
     *
     * @return the diagnostic text representation of this object / 当前对象的诊断文本表示
     */
    @Override
    public String toString() {
        return "ResolvedSecretRevision[reference=" + reference + ", value=<redacted>]";
    }

    /**
     * Computes the SHA-256 content identity used for independent integrity checks.
     * <p>计算独立完整性检查使用的 SHA-256 内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return computed SHA-256 content digest / 已计算的 SHA-256 内容摘要
     */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw ConfigurationException.create(ConfigurationFailureType.HASH_ALGORITHM_UNAVAILABLE, "The required SHA-256 implementation is unavailable", exception);
        }
    }
}
