package gold.debug.windowstolinux.app.service.server;

import gold.debug.windowstolinux.app.db.repository.EncryptedSecretRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.secret.Argon2AesSecretStore;
import gold.debug.windowstolinux.app.secret.WindowsCredentialManagerSecretStore;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Arrays;
import java.util.Objects;

/**
 * Provides the {@code DesktopSecretStores} implementation.
 *
 * <p>提供 {@code DesktopSecretStores} 实现。
 */
public final class DesktopSecretStores {
    private final EncryptedSecretRepository encryptedSecrets;

    /**
     * Creates a {@code DesktopSecretStores} instance.
     *
     * <p>创建 {@code DesktopSecretStores} 实例。
     *
     * @param encryptedSecrets the {@code encryptedSecrets} value / {@code encryptedSecrets} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public DesktopSecretStores(EncryptedSecretRepository encryptedSecrets) {
        this.encryptedSecrets = Objects.requireNonNull(encryptedSecrets, "encryptedSecrets");
    }

    /**
     * Performs the {@code open} operation.
     *
     * <p>执行 {@code open} 操作。
     *
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SecretStore open(CredentialStorageMode mode, char[] masterPassword) throws SecretStoreException {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case MASTER_PASSWORD -> new Argon2AesSecretStore(encryptedSecrets,
                    Objects.requireNonNull(masterPassword, "masterPassword"));
            case WINDOWS_CREDENTIAL_MANAGER -> new WindowsCredentialManagerSecretStore();
        };
    }

    /**
     * Returns the value produced by {@code loadPassword}.
     *
     * <p>返回 {@code loadPassword} 生成的值。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param store the {@code store} value / {@code store} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    public SshCredential.Password loadPassword(ServerProfile profile, SecretStore store) throws SecretStoreException {
        char[] password = store.read(profile.credentialKey())
                .orElseThrow(() -> new SecretStoreException(LocalizedMessage.of("secret.sshCredentialMissing"),
                        "The target server SSH credential was not found"));
        try {
            return new SshCredential.Password(password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
