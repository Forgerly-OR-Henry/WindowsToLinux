package gold.debug.windowstolinux.app.db.security;

import java.util.Objects;

/** Persisted password verifier and wrapped key, never an unwrapped data key. / 持久密码验证信息和包装密钥，不含明文数据密钥。
 * @param encryptionEnabled global credential encryption policy / 全局凭据加密策略
 * @param passwordVerifier password verifier supplied to this boundary / 传入当前边界的passwordVerifier值
 * @param wrappedDataKey wrapped data key supplied to this boundary / 传入当前边界的wrappedDataKey值
 * @param keyWrapAlgorithm key wrap algorithm supplied to this boundary / 传入当前边界的keyWrapAlgorithm值
 * @param keyWrapNonce key wrap nonce supplied to this boundary / 传入当前边界的keyWrapNonce值
 * @param keyDerivationAlgorithm key derivation algorithm supplied to this boundary / 传入当前边界的keyDerivationAlgorithm值
 * @param keyDerivationSalt key derivation salt supplied to this boundary / 传入当前边界的keyDerivationSalt值
 * @param keyDerivationParameters key derivation parameters supplied to this boundary / 传入当前边界的keyDerivationParameters值
 * @param formatVersion supported security format version / 受支持安全格式版本
 */
public record StoredSecurityProfile(boolean encryptionEnabled, String passwordVerifier, byte[] wrappedDataKey,
                                    String keyWrapAlgorithm, byte[] keyWrapNonce, String keyDerivationAlgorithm,
                                    byte[] keyDerivationSalt,
                                    String keyDerivationParameters, int formatVersion) {
    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param encryptionEnabled global credential encryption policy / 全局凭据加密策略
     * @param passwordVerifier password verifier supplied to this boundary / 传入当前边界的passwordVerifier值
     * @param wrappedDataKey wrapped data key supplied to this boundary / 传入当前边界的wrappedDataKey值
     * @param keyWrapAlgorithm key wrap algorithm supplied to this boundary / 传入当前边界的keyWrapAlgorithm值
     * @param keyWrapNonce key wrap nonce supplied to this boundary / 传入当前边界的keyWrapNonce值
     * @param keyDerivationAlgorithm key derivation algorithm supplied to this boundary / 传入当前边界的keyDerivationAlgorithm值
     * @param keyDerivationSalt key derivation salt supplied to this boundary / 传入当前边界的keyDerivationSalt值
     * @param keyDerivationParameters key derivation parameters supplied to this boundary / 传入当前边界的keyDerivationParameters值
     * @param formatVersion supported security format version / 受支持安全格式版本
     */
    public StoredSecurityProfile {
        Objects.requireNonNull(passwordVerifier);
        Objects.requireNonNull(keyWrapAlgorithm);
        Objects.requireNonNull(keyDerivationAlgorithm);
        Objects.requireNonNull(keyDerivationParameters);
        wrappedDataKey = wrappedDataKey.clone();
        keyWrapNonce = keyWrapNonce.clone();
        keyDerivationSalt = keyDerivationSalt.clone();
    }

    /** Returns a defensive copy of the wrapped data key. / 返回已包装数据密钥的防御性副本。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public byte[] wrappedDataKey() {
        return wrappedDataKey.clone();
    }

    /** Returns a defensive copy of the key-wrapping nonce. / 返回密钥包装随机数的防御性副本。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public byte[] keyWrapNonce() {
        return keyWrapNonce.clone();
    }

    /** Returns a defensive copy of the wrapping-key salt. / 返回包装密钥派生盐的防御性副本。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public byte[] keyDerivationSalt() {
        return keyDerivationSalt.clone();
    }

    /** Returns metadata with protected material redacted. / 返回已隐藏受保护内容的元数据。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    @Override
    public String toString() {
        return "StoredSecurityProfile[protected]";
    }

    /** Copies the profile with only its credential encryption flag changed. / 复制安全配置，仅更改凭据加密标记。
     * @param enabled requested enabled flag / 请求启用标记
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public StoredSecurityProfile withEncryption(boolean enabled) {
        return new StoredSecurityProfile(enabled, passwordVerifier, wrappedDataKey, keyWrapAlgorithm, keyWrapNonce,
            keyDerivationAlgorithm, keyDerivationSalt, keyDerivationParameters, formatVersion);
    }
}
