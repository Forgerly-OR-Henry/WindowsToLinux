package gold.debug.windowstolinux.app.service.server;

import gold.debug.windowstolinux.app.db.repository.ServerProfileRepository;
import gold.debug.windowstolinux.app.secret.api.SecretStore;
import gold.debug.windowstolinux.app.secret.api.SecretStoreException;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Provides the {@code ServerUseCases} implementation.
 *
 * <p>提供 {@code ServerUseCases} 实现。
 */
public final class ServerUseCases {
    private final ServerProfileRepository profiles;
    private final DesktopSecretStores secrets;
    private final DeploymentLinuxGateway gateway;

    /**
     * Creates a {@code ServerUseCases} instance.
     *
     * <p>创建 {@code ServerUseCases} 实例。
     *
     * @param database the {@code database} value / {@code database} 值
     * @param secrets the {@code secrets} value / {@code secrets} 值
     * @param gateway the {@code gateway} value / {@code gateway} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ServerUseCases(ServerProfileRepository profiles, DesktopSecretStores secrets, DeploymentLinuxGateway gateway) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * Stores data through {@code save}.
     *
     * <p>通过 {@code save} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param store the {@code store} value / {@code store} 值
     * @param password the {@code password} value / {@code password} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public void save(ServerProfile profile, SecretStore store, char[] password) throws SQLException, SecretStoreException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(store, "store");
        try {
            store.save(profile.credentialKey(), password);
            profiles.saveServerProfile(profile.stored());
        } finally {
            clear(password);
        }
    }

    /**
     * Stores data through {@code save}.
     *
     * <p>通过 {@code save} 保存数据。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param password the {@code password} value / {@code password} 值
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     */
    public void save(ServerProfile profile, CredentialStorageMode mode, char[] masterPassword, char[] password)
            throws SQLException, SecretStoreException {
        try (SecretStore store = secrets.open(mode, masterPassword)) {
            save(profile, store, password);
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Returns the value produced by {@code find}.
     *
     * <p>返回 {@code find} 生成的值。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ServerProfile> find(String serverId) throws SQLException {
        return profiles.findServerProfile(serverId).map(ServerProfile::fromStored);
    }

    /**
     * Returns the value produced by {@code findTrusted}.
     *
     * <p>返回 {@code findTrusted} 生成的值。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     */
    public Optional<ServerIdentity> findTrusted(String serverId) throws SQLException {
        return profiles.findServer(serverId);
    }

    /**
     * Performs the {@code hostKeyVerifier} operation.
     *
     * <p>执行 {@code hostKeyVerifier} 操作。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param firstUseConfirmation the {@code firstUseConfirmation} value / {@code firstUseConfirmation} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public HostKeyVerifier hostKeyVerifier(ServerProfile profile, Predicate<String> firstUseConfirmation) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(firstUseConfirmation, "firstUseConfirmation");
        return (endpoint, observedFingerprint) -> {
            try {
                Optional<ServerIdentity> known = profiles.findServer(profile.id());
                if (known.isPresent()) {
                    ServerIdentity server = known.orElseThrow();
                    return server.host().equals(profile.host()) && server.sshPort() == profile.sshPort()
                            && server.hostKeySha256().equals(observedFingerprint)
                            ? HostKeyDecision.ACCEPT_EXISTING : HostKeyDecision.REJECT;
                }
                if (!firstUseConfirmation.test(observedFingerprint)) {
                    return HostKeyDecision.REJECT;
                }
                profiles.saveServer(new ServerIdentity(profile.id(), profile.host(), profile.sshPort(), observedFingerprint));
                return HostKeyDecision.ACCEPT_FIRST_USE;
            } catch (SQLException exception) {
                return HostKeyDecision.REJECT;
            }
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
        return secrets.loadPassword(profile, store);
    }

    /**
     * Validates the input through {@code verify}.
     *
     * <p>通过 {@code verify} 验证输入。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @param mode the {@code mode} value / {@code mode} 值
     * @param masterPassword the {@code masterPassword} value / {@code masterPassword} 值
     * @param confirmation the {@code confirmation} value / {@code confirmation} 值
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the operation cannot be completed / 无法完成操作时
     * @throws SQLException if the operation cannot be completed / 无法完成操作时
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public ServerCapabilities verify(ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
                                     Predicate<String> confirmation) throws SecretStoreException, SQLException,
            LinuxOperationException {
        try {
            requireMatchingMode(profile, mode);
            try (SecretStore store = secrets.open(mode, masterPassword);
                 DeploymentRemoteSession session = gateway.connect(profile.endpoint(), secrets.loadPassword(profile, store),
                     hostKeyVerifier(profile, confirmation))) {
                return session.collectCapabilities();
            }
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Collects the exact typed deployment capabilities through the selected saved credential without mutating the host.
     *
     * <p>通过选定的已保存凭据采集精确的类型化部署能力，且不修改主机。
     *
     * @param profile the saved server profile / 已保存的服务器资料
     * @param mode the selected credential storage mode / 选定的凭据存储模式
     * @param masterPassword the optional secret-store master password / 可选的秘密存储主密码
     * @param confirmation first-use host-key confirmation / 首次使用主机密钥确认
     * @return exact non-secret deployment capabilities / 精确且不含秘密的部署能力
     * @throws SecretStoreException if the saved credential cannot be read / 无法读取已保存凭据时
     * @throws LinuxOperationException if the bounded remote inspection fails / 有界远端检查失败时
     */
    public LinuxCapabilities inspectDeploymentCapabilities(
            ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation) throws SecretStoreException, LinuxOperationException {
        try {
            requireMatchingMode(profile, mode);
            try (SecretStore store = secrets.open(mode, masterPassword);
                 DeploymentRemoteSession session = gateway.connect(profile.endpoint(), secrets.loadPassword(profile, store),
                         hostKeyVerifier(profile, confirmation))) {
                return session.collectDeploymentCapabilities();
            }
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Performs the {@code secrets} operation.
     *
     * <p>执行 {@code secrets} 操作。
     *
     * @return the operation result / 操作结果
     */
    public DesktopSecretStores secrets() {
        return secrets;
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static void requireMatchingMode(ServerProfile profile, CredentialStorageMode mode) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(mode, "mode");
        if (profile.credentialMode() != mode) {
            throw new LocalizedOperationException(LocalizedMessage.of("validation.storageModeMismatch"),
                    "Credential storage mode does not match the saved server profile");
        }
    }
}
