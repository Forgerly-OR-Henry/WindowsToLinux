package gold.debug.windowstolinux.app.db.security;

import java.util.Arrays;
import java.util.Objects;

/** Opaque credential bytes; clear local copies after use, including plaintext format. / 不透明凭据字节，使用后清理本地副本，包括明文格式。
 * @param id stable persisted identifier / 稳定持久化标识
 * @param storageFormat versioned storage format / 带版本的存储格式
 * @param payload stored credential bytes / 已存凭据字节
 * @param nonce fresh GCM nonce / 新 GCM 随机数
 */
public record StoredSecret(String id, String storageFormat, byte[] payload, byte[] nonce) implements AutoCloseable {
    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param id stable persisted identifier / 稳定持久化标识
     * @param storageFormat versioned storage format / 带版本的存储格式
     * @param payload stored credential bytes / 已存凭据字节
     * @param nonce fresh GCM nonce / 新 GCM 随机数
     */
    public StoredSecret {
        Objects.requireNonNull(id);
        Objects.requireNonNull(storageFormat);
        payload = payload.clone();
        nonce = nonce == null ? null : nonce.clone();
    }

    /** Returns a defensive copy of the stored credential payload. / 返回已存凭据载荷的防御性副本。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Returns a defensive copy of the credential nonce when present. / 返回非空凭据随机数的防御性副本。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public byte[] nonce() {
        return nonce == null ? null : nonce.clone();
    }

    /** Returns metadata with protected material redacted. / 返回已隐藏受保护内容的元数据。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public String toString() {
        return "StoredSecret[protected]";
    }

    /** Releases this scope and its owned resources. / 释放当前作用域及其持有资源。
     */
    @Override
    public void close() {
        Arrays.fill(payload, (byte) 0);
        if (nonce != null)
            Arrays.fill(nonce, (byte) 0);
    }
}
