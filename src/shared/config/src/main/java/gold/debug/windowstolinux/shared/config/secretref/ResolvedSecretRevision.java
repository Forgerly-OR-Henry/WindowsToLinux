package gold.debug.windowstolinux.shared.config.secretref;

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
 * <p>仅用于已认证部署传输的短生命周期已解析秘密字节。
 */
public final class ResolvedSecretRevision implements AutoCloseable {
    /** Maximum UTF-8 payload accepted for one revision. / 单个修订允许的最大 UTF-8 载荷。 */
    public static final int MAX_VALUE_BYTES = 65_536;

    private final SecretReference reference;
    private final byte[] value;
    private final SecretRevisionDigest digest;

    /**
     * Copies a caller-owned character value without creating an immutable plaintext string.
     *
     * <p>复制调用方持有的字符值，且不创建不可清除的明文字符串。
     */
    public ResolvedSecretRevision(SecretReference reference, char[] value) {
        this.reference = Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(value, "value");
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (bytes.length == 0 || bytes.length > MAX_VALUE_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new IllegalArgumentException("secret values must be non-empty and bounded");
        }
        this.value = bytes;
        this.digest = new SecretRevisionDigest(reference, sha256(bytes), bytes.length);
    }

    /** Returns the opaque immutable reference. / 返回透明的不可变引用。 */
    public SecretReference reference() {
        return reference;
    }

    /** Returns public integrity metadata without exposing plaintext. / 返回不暴露明文的公开完整性元数据。 */
    public SecretRevisionDigest digest() {
        return digest;
    }

    /** Returns a caller-owned byte copy that must be cleared. / 返回必须由调用方清除的字节副本。 */
    public synchronized byte[] copyValue() {
        return value.clone();
    }

    @Override
    public synchronized void close() {
        Arrays.fill(value, (byte) 0);
    }

    @Override
    public String toString() {
        return "ResolvedSecretRevision[reference=" + reference + ", value=<redacted>]";
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
