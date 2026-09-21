package gold.debug.windowstolinux.web.secret.masterkey;

import java.util.Arrays;

/**
 * Owns the in-memory key, clears the supplied buffer on construction and clears the key on close.
 * <p>持有内存密钥，构造时清空传入缓冲区，关闭时清空密钥。
 */
public final class WebMasterKey implements AutoCloseable {
    /**
     * Owned 256-bit key buffer cleared on close; access is synchronized with copying and closing.
     * <p>关闭时清空的自有 256 位密钥缓冲区；访问与复制及关闭同步。
     */
    private final byte[] bytes;
    /**
     * Copies an exactly 256-bit supplied key and clears the supplied buffer even when its length is rejected.
     * <p>复制所提供的精确 256 位密钥，并在长度被拒绝时仍清空传入缓冲区。
     *
     * @param supplied supplied / 提供的
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public WebMasterKey(byte[] supplied) {
        if (supplied == null) throw new IllegalArgumentException("Web master key is missing");
        try {
            if (supplied.length != 32) throw new IllegalArgumentException("Expected a 256-bit Web key");
            bytes = supplied.clone();
        } finally { Arrays.fill(supplied, (byte) 0); }
    }
    /**
     * Returns a caller-owned copy of the key while open; the caller must clear that copy after use.
     * <p>尚未关闭时返回由调用方持有的密钥副本；调用方须在使用后清空副本。
     *
     * @return caller-owned key bytes that must be cleared after use / 由调用方持有且须在使用后清空的密钥字节
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public synchronized byte[] copy() {
        if (closed) throw new IllegalStateException("Web master key is closed");
        return bytes.clone();
    }
    /**
     * Closed.
     * <p>已关闭。
     */
    private boolean closed;
    /**
     * Clears the owned key and marks it closed under the instance monitor; repeated closure is harmless.
     * <p>在实例监视器保护下清空自有密钥并标记为关闭；重复关闭无副作用。
     */
    @Override public synchronized void close() { Arrays.fill(bytes, (byte) 0); closed = true; }
}
