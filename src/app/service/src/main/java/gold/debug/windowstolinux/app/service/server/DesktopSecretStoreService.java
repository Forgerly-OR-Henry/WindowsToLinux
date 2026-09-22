package gold.debug.windowstolinux.app.service.server;

import java.util.Arrays;
import java.util.Objects;

import gold.debug.windowstolinux.app.db.persistence.repository.EncryptedSecretRepository;
import gold.debug.windowstolinux.app.secret.Argon2AesSecretStore;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.SecretStoreFailureType;
import gold.debug.windowstolinux.app.secret.WindowsCredentialManagerSecretStore;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Selects the desktop credential adapter for the requested storage mode.
 * <p>根据所请求的存储模式选择桌面凭据适配器。
 */
public final class DesktopSecretStoreService {
    /**
     * Bound encrypted secret repository collaborator for encrypted secrets.
     * <p>处理加密秘密集合的加密秘密仓库协作对象。
     */
    private final EncryptedSecretRepository encryptedSecrets;

    /**
     * Validates and binds the inputs required by desktop secret store service.
     * <p>校验并绑定Desktop秘密存储服务所需输入。
     *
     * @param encryptedSecrets encrypted secrets / 加密秘密集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopSecretStoreService(EncryptedSecretRepository encryptedSecrets) {
        this.encryptedSecrets = Objects.requireNonNull(encryptedSecrets, "encryptedSecrets");
    }

    /**
     * Opens secret store.
     * <p>打开秘密存储。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SecretStore open(CredentialStorageMode mode, char[] masterPassword) throws SecretStoreException {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case MASTER_PASSWORD ->
                new Argon2AesSecretStore(encryptedSecrets, Objects.requireNonNull(masterPassword, "masterPassword"));
            case WINDOWS_CREDENTIAL_MANAGER -> new WindowsCredentialManagerSecretStore();
        };
    }

    /**
     * Returns the value produced by {@code loadPassword}.
     *
     *  <p>返回 {@code loadPassword} 生成的值。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param store store / 存储
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public SshCredential.Password loadPassword(ServerProfile profile, SecretStore store) throws SecretStoreException {
        char[] password = store.read(profile.credentialKey())
                .orElseThrow(() -> SecretStoreException.create(SecretStoreFailureType.SSH_CREDENTIAL_MISSING,
                        "The target server SSH credential was not found"));
        try {
            return new SshCredential.Password(password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
