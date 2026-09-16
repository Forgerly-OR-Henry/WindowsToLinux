package gold.debug.windowstolinux.app.service.server;

import gold.debug.windowstolinux.app.db.persistence.repository.ServerProfileRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyObservation;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Provides the {@code ServerUseCaseFacade} implementation.
 *
 * <p>提供 {@code ServerUseCaseFacade} 实现。
 */
public final class ServerUseCaseFacade {
    /** Lists saved profiles for the desktop selector. / 列出桌面选择器使用的已保存配置。 */
    public java.util.List<ServerProfile> list() throws SQLException {
        return profiles.listServerProfiles().stream().map(ServerProfile::fromStored).toList();
    }
    private final ServerProfileRepository profiles;
    private final DesktopSecretStoreService secrets;
    private final DeploymentLinuxGateway gateway;

    /**
     * Creates a {@code ServerUseCaseFacade} instance.
     *
     * <p>创建 {@code ServerUseCaseFacade} 实例。
     *
     * @param profiles the {@code profiles} value / {@code profiles} 值
     * @param secrets the {@code secrets} value / {@code secrets} 值
     * @param gateway the {@code gateway} value / {@code gateway} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ServerUseCaseFacade(ServerProfileRepository profiles, DesktopSecretStoreService secrets, DeploymentLinuxGateway gateway) {
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
    public HostKeyEvaluator hostKeyVerifier(ServerProfile profile, Predicate<String> firstUseConfirmation) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(firstUseConfirmation, "firstUseConfirmation");
        return new HostKeyEvaluator() {
            private Optional<ServerIdentity> expected = Optional.empty();
            private HostKeyObservation accepted;

            /** Verifies a standard fingerprint without historical conversion. / 验证标准指纹，不执行历史转换。 */
            @Override public HostKeyDecision verify(SshEndpoint endpoint, String fingerprint) {
                return verify(endpoint, new HostKeyObservation(fingerprint, fingerprint));
            }

            /** Rejects changed keys before any authentication credential is sent. / 在发送认证凭据前拒绝变化的公钥。 */
            @Override public HostKeyDecision verify(SshEndpoint endpoint, HostKeyObservation observation) {
                accepted = null;
                if (!endpoint.host().equals(profile.host()) || endpoint.port() != profile.sshPort()
                        || !endpoint.username().equals(profile.username())) return HostKeyDecision.REJECT;
                try {
                    expected = profiles.findServer(profile.id());
                    if (expected.isPresent()) {
                        ServerIdentity known = expected.orElseThrow();
                        String matching = profiles.hasLegacyHostKey(known)
                                ? observation.legacyEncodedSha256() : observation.sshSha256();
                        if (!known.host().equals(endpoint.host()) || known.sshPort() != endpoint.port()
                                || !known.hostKeySha256().equals(matching)) return HostKeyDecision.REJECT;
                        accepted = observation;
                        return HostKeyDecision.ACCEPT_EXISTING;
                    }
                    if (!firstUseConfirmation.test(observation.sshSha256())) return HostKeyDecision.REJECT;
                    accepted = observation;
                    return HostKeyDecision.ACCEPT_FIRST_USE;
                } catch (SQLException failure) {
                    return HostKeyDecision.REJECT;
                }
            }

            /** Commits only the same accepted handshake key after authentication. / 认证后仅提交同一握手中已接受的公钥。 */
            @Override public boolean authenticated(SshEndpoint endpoint, HostKeyObservation observation) {
                if (!observation.equals(accepted) || !endpoint.equals(profile.endpoint())) return false;
                try {
                    profiles.saveAuthenticatedServer(new ServerIdentity(profile.id(), profile.host(), profile.sshPort(),
                            observation.sshSha256()), expected);
                    accepted = null;
                    return true;
                } catch (SQLException failure) {
                    return false;
                }
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
    public ServerCapabilityFacts verify(ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
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
    public LinuxCapabilityFacts inspectDeploymentCapabilities(
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
    public DesktopSecretStoreService secrets() {
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
            throw ApplicationServiceException.create(ApplicationServiceFailureType.STORAGE_MODE_MISMATCH,
                    "Credential storage mode does not match the saved server profile");
        }
    }
}
