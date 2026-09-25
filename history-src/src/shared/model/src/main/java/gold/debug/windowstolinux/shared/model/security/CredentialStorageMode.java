package gold.debug.windowstolinux.shared.model.security;

/**
 * User-selected storage mechanism for a platform credential, never a secret itself.
 *
 *  <p>用户选择的平台凭据存储机制，其本身绝不是秘密。
 */
public enum CredentialStorageMode {
    /**
     * Represents the {@code MASTER_PASSWORD} option.
     *
     *  <p>表示 {@code MASTER_PASSWORD} 选项。
     */
    MASTER_PASSWORD,
    /**
     * Represents the {@code WINDOWS_CREDENTIAL_MANAGER} option.
     *
     *  <p>表示 {@code WINDOWS_CREDENTIAL_MANAGER} 选项。
     */
    WINDOWS_CREDENTIAL_MANAGER
}
