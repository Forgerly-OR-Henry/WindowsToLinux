package gold.debug.windowstolinux.app.service.server;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.db.persistence.repository.ServerProfileRepository;
import gold.debug.windowstolinux.app.secret.SecretStore;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyObservation;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

/**
 * Coordinates server profiles, protected credentials, pinned host keys and connection observations.
 * <p>协调服务器资料、受保护凭据、固定主机密钥及连接观测。
 */
public final class ServerUseCaseFacade {
    /**
     * Lists saved profiles for the desktop selector. / 列出桌面选择器使用的已保存配置。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public java.util.List<ServerProfile> list() throws SQLException {
        return profiles.listServerProfiles().stream().map(ServerProfile::fromStored).toList();
    }

    /**
     * Lists server cards using saved check evidence. / 使用已保存的检查证据列出服务器卡片。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public java.util.List<ServerSummary> summaries() throws SQLException {
        java.util.List<ServerSummary> result = new java.util.ArrayList<>();
        for (ServerProfile profile : list()) {
            var observation = profiles.observation(profile.id());
            result.add(new ServerSummary(profile, observation.checkedAt(), observation.connected(),
                    observation.operatingSystem()));
        }
        return java.util.List.copyOf(result);
    }
    /**
     * Bound server profile repository collaborator for profiles.
     * <p>处理配置资料集合的服务器配置资料仓库协作对象。
     */
    private final ServerProfileRepository profiles;

    /**
     * Bound desktop secret store service collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的Desktop秘密存储服务协作对象。
     */
    private final DesktopSecretStoreService secrets;

    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final DeploymentLinuxGateway gateway;

    /**
     * Validates and binds the inputs required by server use case facade.
     * <p>校验并绑定服务器用例门面所需输入。
     *
     * @param profiles profiles / 配置资料集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ServerUseCaseFacade(ServerProfileRepository profiles, DesktopSecretStoreService secrets,
            DeploymentLinuxGateway gateway) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * Stores data through {@code save}.
     *
     *  <p>通过 {@code save} 保存数据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param store store / 存储
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void save(ServerProfile profile, SecretStore store, char[] password)
            throws SQLException, SecretStoreException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(store, "store");
        try {
            if (password.length == 0) {
                var existing = profiles.findServerProfile(profile.id())
                        .orElseThrow(() -> new IllegalArgumentException("server password is required"));
                if (!existing.credentialKey().equals(profile.credentialKey())
                        || !existing.credentialMode().equals(profile.credentialMode().name()))
                    throw new IllegalArgumentException("changing credential storage requires a password");
            } else
                store.save(profile.credentialKey(), password);
            profiles.saveServerProfile(profile.stored());
        } finally {
            clear(password);
        }
    }

    /**
     * Stores data through {@code save}.
     *
     *  <p>通过 {@code save} 保存数据。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param password temporary plaintext authentication buffer / 临时明文认证缓冲区
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
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
     *  <p>返回 {@code find} 生成的值。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ServerProfile> find(String serverId) throws SQLException {
        return profiles.findServerProfile(serverId).map(ServerProfile::fromStored);
    }

    /**
     * Returns the value produced by {@code findTrusted}.
     *
     *  <p>返回 {@code findTrusted} 生成的值。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @return the optional operation result / 可选操作结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ServerIdentity> findTrusted(String serverId) throws SQLException {
        return profiles.findServer(serverId);
    }

    /**
     * Builds a stateful host-key verifier that rejects identity drift, confirms first use and commits accepted keys only after authentication.
     * <p>构建有状态主机密钥验证器，拒绝身份漂移、确认首次使用，并仅在认证后提交已接受密钥。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return stateful verifier bound to the saved profile and its first-use confirmation / 绑定已保存资料及首次使用确认的有状态验证器
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public HostKeyEvaluator hostKeyVerifier(ServerProfile profile, Predicate<String> firstUseConfirmation) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(firstUseConfirmation, "firstUseConfirmation");
        return new HostKeyEvaluator() {
            /**
             * Identity, value or state required for verification.
             * <p>验证要求的身份、内容或状态。
             */
            private Optional<ServerIdentity> expected = Optional.empty();

            /**
             * Accepted.
             * <p>已接受。
             */
            private HostKeyObservation accepted;

            /**
             * Verifies a standard fingerprint without historical conversion. / 验证标准指纹，不执行历史转换。
             *
             * @param endpoint reviewed network endpoint / 已审阅网络端点
             * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
             * @return constructed or resolved host key decision / 构造或解析得到的主机键决定
             */
            @Override
            public HostKeyDecision verify(SshEndpoint endpoint, String fingerprint) {
                return verify(endpoint, new HostKeyObservation(fingerprint, fingerprint));
            }

            /**
             * Rejects changed keys before any authentication credential is sent. / 在发送认证凭据前拒绝变化的公钥。
             *
             * @param endpoint reviewed network endpoint / 已审阅网络端点
             * @param observation observation / 观测
             * @return constructed or resolved host key decision / 构造或解析得到的主机键决定
             */
            @Override
            public HostKeyDecision verify(SshEndpoint endpoint, HostKeyObservation observation) {
                accepted = null;
                if (!endpoint.host().equals(profile.host()) || endpoint.port() != profile.sshPort()
                        || !endpoint.username().equals(profile.username()))
                    return HostKeyDecision.REJECT;
                try {
                    expected = profiles.findServer(profile.id());
                    if (expected.isPresent()) {
                        ServerIdentity known = expected.orElseThrow();
                        String matching = profiles.hasLegacyHostKey(known)
                                ? observation.legacyEncodedSha256()
                                : observation.sshSha256();
                        if (!known.host().equals(endpoint.host()) || known.sshPort() != endpoint.port()
                                || !known.hostKeySha256().equals(matching))
                            return HostKeyDecision.REJECT;
                        accepted = observation;
                        return HostKeyDecision.ACCEPT_EXISTING;
                    }
                    if (!firstUseConfirmation.test(observation.sshSha256()))
                        return HostKeyDecision.REJECT;
                    accepted = observation;
                    return HostKeyDecision.ACCEPT_FIRST_USE;
                } catch (SQLException failure) {
                    return HostKeyDecision.REJECT;
                }
            }

            /**
             * Commits only the same accepted handshake key after authentication. / 认证后仅提交同一握手中已接受的公钥。
             *
             * @param endpoint reviewed network endpoint / 已审阅网络端点
             * @param observation observation / 观测
             * @return true when commits only the same accepted handshake key after authentication, false otherwise / 认证后仅提交同一握手中已接受的公钥时为 true，否则为 false
             */
            @Override
            public boolean authenticated(SshEndpoint endpoint, HostKeyObservation observation) {
                if (!observation.equals(accepted) || !endpoint.equals(profile.endpoint()))
                    return false;
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
     *  <p>返回 {@code loadPassword} 生成的值。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param store store / 存储
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public SshCredential.Password loadPassword(ServerProfile profile, SecretStore store) throws SecretStoreException {
        return secrets.loadPassword(profile, store);
    }

    /**
     * Validates the input through {@code verify}.
     *
     *  <p>通过 {@code verify} 验证输入。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param confirmation token or decision binding approval to the exact proposed action / 将批准绑定到精确提议动作的令牌或决定
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public ServerCapabilityFacts verify(ServerProfile profile, CredentialStorageMode mode, char[] masterPassword,
            Predicate<String> confirmation) throws SecretStoreException, SQLException, LinuxOperationException {
        try {
            requireMatchingMode(profile, mode);
            try (SecretStore store = secrets.open(mode, masterPassword);
                    DeploymentRemoteSession session = gateway.connect(profile.endpoint(),
                            secrets.loadPassword(profile, store), hostKeyVerifier(profile, confirmation))) {
                ServerCapabilityFacts facts = session.collectCapabilities();
                profiles.recordObservation(profile.stored(), true,
                        facts.operatingSystem() + " / " + facts.architecture());
                return facts;
            }
        } catch (SecretStoreException | LinuxOperationException failure) {
            try {
                profiles.recordObservation(profile.stored(), false, "");
            } catch (SQLException recording) {
                failure.addSuppressed(recording);
            }
            throw failure;
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Collects the exact typed deployment capabilities through the selected saved credential without mutating the host.
     *
     *  <p>通过选定的已保存凭据采集精确的类型化部署能力，且不修改主机。
     *
     * @param profile the saved server profile / 已保存的服务器资料
     * @param mode the selected credential storage mode / 选定的凭据存储模式
     * @param masterPassword the optional secret-store master password / 可选的秘密存储主密码
     * @param confirmation first-use host-key confirmation / 首次使用主机密钥确认
     * @return exact non-secret deployment capabilities / 精确且不含秘密的部署能力
     * @throws SecretStoreException if the saved credential cannot be read / 无法读取已保存凭据时
     * @throws LinuxOperationException if the bounded remote inspection fails / 有界远端检查失败时
     */
    public LinuxCapabilityFacts inspectDeploymentCapabilities(ServerProfile profile, CredentialStorageMode mode,
            char[] masterPassword, Predicate<String> confirmation)
            throws SecretStoreException, LinuxOperationException {
        try {
            requireMatchingMode(profile, mode);
            try (SecretStore store = secrets.open(mode, masterPassword);
                    DeploymentRemoteSession session = gateway.connect(profile.endpoint(),
                            secrets.loadPassword(profile, store), hostKeyVerifier(profile, confirmation))) {
                return session.collectDeploymentCapabilities();
            }
        } finally {
            clear(masterPassword);
        }
    }

    /**
     * Returns credential references or scoped secret-access service.
     * <p>返回凭据引用或限定作用域的秘密访问服务。
     *
     * @return the operation result / 操作结果
     */
    public DesktopSecretStoreService secrets() {
        return secrets;
    }

    /**
     * Clears retained credential material after its scoped use.
     * <p>在限定作用域使用结束后清空保留的凭据素材。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    /**
     * Requires matching mode.
     * <p>要求匹配模式。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static void requireMatchingMode(ServerProfile profile, CredentialStorageMode mode) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(mode, "mode");
        if (profile.credentialMode() != mode) {
            throw ApplicationServiceException.create(ApplicationServiceFailureType.STORAGE_MODE_MISMATCH,
                    "Credential storage mode does not match the saved server profile");
        }
    }
}
