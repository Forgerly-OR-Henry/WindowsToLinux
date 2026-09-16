package gold.debug.windowstolinux.shared.linux.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Caller-owned bounded secret copy, closed after staging on every exit path. / 调用者持有的有界秘密副本，暂存结束后在所有退出路径清零。 */
public final class RemoteSecretPayload implements AutoCloseable {
    private final RemoteDeploymentInputs.SecretDigest digest;
    private final byte[] value;
    private boolean closed;

    /** Copies bytes only after checking the exact content binding. / 仅在校验精确内容绑定后复制字节。 */
    public RemoteSecretPayload(RemoteDeploymentInputs.SecretDigest digest, byte[] value) {
        this.digest = Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(value, "value");
        if (value.length != digest.byteCount()) throw new IllegalArgumentException("secret length mismatch");
        byte[] copy = value.clone();
        try {
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(copy)).equals(digest.sha256()))
                throw new IllegalArgumentException("secret digest mismatch");
            this.value = copy;
        } catch (RuntimeException | NoSuchAlgorithmException exception) {
            Arrays.fill(copy, (byte) 0);
            throw new IllegalArgumentException("invalid secret payload", exception);
        }
    }

    /** Returns public metadata only. / 仅返回公开元数据。 */
    public RemoteDeploymentInputs.SecretDigest digest() { return digest; }

    /** Transfers a copy whose receiver must clear it. / 返回必须由接收方清零的副本。 */
    public synchronized byte[] copyValue() {
        if (closed) throw new IllegalStateException("secret payload is closed");
        return value.clone();
    }

    /** Clears the owned copy and permanently prevents reuse. / 清零持有副本并永久禁止复用。 */
    @Override public synchronized void close() { Arrays.fill(value, (byte) 0); closed = true; }

    /** Never includes the payload in diagnostics. / 诊断中绝不包含载荷。 */
    @Override public String toString() { return "RemoteSecretPayload[redacted]"; }
}
