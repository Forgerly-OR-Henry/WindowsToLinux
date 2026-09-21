package gold.debug.windowstolinux.shared.linux.sshd.connection;

import gold.debug.windowstolinux.shared.linux.sshd.session.SshdLinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyObservation;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Apache MINA SSHD implementation of the managed-deployment allowlisted remote contract. It accepts a host key only through the supplied verifier and never exposes a public raw-command method.
 *
 *  <p>受管部署白名单远程契约的 Apache MINA SSHD 实现。它只通过提供的验证器接受主机密钥，并且绝不公开原始命令方法。
 */
public final class SshdLinuxGateway implements DeploymentLinuxGateway {
    /**
     * CONNECT TIMEOUT.
     * <p>连接超时。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    /**
     * HEARTBEAT INTERVAL.
     * <p>心跳间隔。
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    /**
     * Maximum consecutive unanswered SSH heartbeats before connection failure.
     * <p>判定连接失败前允许的连续未回复 SSH 心跳上限。
     */
    private static final int HEARTBEAT_NO_REPLY_MAX = 3;
    /**
     * TRANSIENT CONNECTION ATTEMPTS.
     * <p>暂时连接尝试集合。
     */
    private static final int TRANSIENT_CONNECTION_ATTEMPTS = 3;
    /**
     * TRANSIENT RETRY DELAY.
     * <p>暂时重试延迟。
     */
    private static final Duration TRANSIENT_RETRY_DELAY = Duration.ofMillis(250);
    /**
     * Opens an authenticated session after the supplied host-key verification.
     * <p>在所提供主机密钥验证完成后打开已认证会话。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return constructed or resolved deployment remote session / 构造或解析得到的部署远端会话
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    @Override
    public DeploymentRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator hostKeyVerifier)
            throws LinuxOperationException {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(hostKeyVerifier, "hostKeyVerifier");
        try {
            for (int attempt = 1; attempt <= TRANSIENT_CONNECTION_ATTEMPTS; attempt++) {
                try {
                    return connectOnce(endpoint, credential, hostKeyVerifier);
                } catch (LinuxOperationException exception) {
                    if (!isTransientConnectionFailure(exception) || attempt == TRANSIENT_CONNECTION_ATTEMPTS
                            || !waitForRetry()) {
                        throw exception;
                    }
                }
            }
            throw new IllegalStateException("SSH retry loop completed without a result");
        } finally {
            if (credential instanceof SshCredential.Password password) {
                password.clear();
            }
        }
    }

    /**
     * Makes one authenticated connection attempt using the supplied trust contract.
     * <p>按提供的信任契约进行一次已认证连接尝试。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return constructed or resolved deployment remote session / 构造或解析得到的部署远端会话
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private static DeploymentRemoteSession connectOnce(
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyEvaluator hostKeyVerifier
    ) throws LinuxOperationException {
        SshClient client = credentialScopedClient(credential);
        AtomicReference<String> observedFingerprint = new AtomicReference<>();
        AtomicReference<HostKeyObservation> observedKey = new AtomicReference<>();
        AtomicReference<HostKeyDecision> hostKeyDecision = new AtomicReference<>();
        client.setServerKeyVerifier((session, remote, key) -> {
            String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
            HostKeyObservation observation = new HostKeyObservation(fingerprint, legacyFingerprint(key));
            observedKey.set(observation);
            observedFingerprint.set(fingerprint);
            HostKeyDecision decision = hostKeyVerifier.verify(endpoint, observation);
            hostKeyDecision.set(decision);
            return decision == HostKeyDecision.ACCEPT_FIRST_USE || decision == HostKeyDecision.ACCEPT_EXISTING;
        });
        try {
            client.start();
            ClientSession session;
            try {
                session = client.connect(endpoint.username(), endpoint.host(), endpoint.port())
                        .verify(CONNECT_TIMEOUT)
                        .getSession();
            } catch (Exception exception) {
                SshSessionLifecycleExecutor.closeQuietly(client);
                String fingerprint = observedFingerprint.get();
                if (fingerprint != null) {
                    throw LinuxOperationException.create(LinuxOperationFailureType.HOST_KEY_REJECTED, Map.of(
                            "fingerprint", fingerprint),
                            "SSH host fingerprint was not accepted or the connection was rejected: " + fingerprint,
                            exception);
                }
                throw LinuxOperationException.create(LinuxOperationFailureType.CONNECTION_FAILED,
                        "Failed to establish the SSH connection", exception);
            }
            try {
                authenticate(session, credential);
            } catch (Exception exception) {
                SshSessionLifecycleExecutor.closeQuietly(client);
                String fingerprint = observedFingerprint.get();
                if (fingerprint == null) {
                    throw LinuxOperationException.create(LinuxOperationFailureType.CONNECTION_FAILED,
                            "SSH transport closed before host verification and authentication completed", exception);
                }
                if (hostKeyDecision.get() == HostKeyDecision.REJECT && fingerprint != null) {
                    throw LinuxOperationException.create(LinuxOperationFailureType.HOST_KEY_REJECTED, Map.of(
                            "fingerprint", fingerprint),
                            "SSH host fingerprint was not accepted or the connection was rejected: " + fingerprint,
                            exception);
                }
                String evidence = fingerprint == null ? "" : "; verified host fingerprint: " + fingerprint;
                throw LinuxOperationException.create(LinuxOperationFailureType.AUTHENTICATION_FAILED,
                        "SSH authentication failed; verify the SSH user, credential, and server authentication policy"
                                + evidence, exception);
            }
            if (observedKey.get() == null || !hostKeyVerifier.authenticated(endpoint, observedKey.get())) {
                throw LinuxOperationException.create(LinuxOperationFailureType.HOST_KEY_REJECTED,
                        "Authenticated host key could not be committed without replacing a conflicting trust record");
            }
            return new SshdLinuxRemoteSession(client, session, endpoint, observedFingerprint.get());
        } catch (Exception exception) {
            SshSessionLifecycleExecutor.closeQuietly(client);
            if (exception instanceof LinuxOperationException linuxOperationException) {
                throw linuxOperationException;
            }
            throw LinuxOperationException.create(LinuxOperationFailureType.CONNECTION_FAILED,
                    "Failed to establish the SSH connection", exception);
        }
    }

    /**
     * Reports whether the transient connection failure condition holds for this contract.
     * <p>判断当前契约是否满足暂时连接失败条件。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return true when transient connection failure condition holds for this contract, false otherwise / 当前契约是否满足暂时连接失败条件时为 true，否则为 false
     */
    static boolean isTransientConnectionFailure(LinuxOperationException failure) {
        return LinuxOperationFailureType.CONNECTION_FAILED.code().equals(failure.failure().code())
                || SshCommandExecutor.isTransientTransportFailure(failure);
    }

    /**
     * Waits for for retry.
     * <p>等待对应重试。
     *
     * @return true when waits for for retry, false otherwise / 等待对应重试时为 true，否则为 false
     */
    private static boolean waitForRetry() {
        try {
            Thread.sleep(TRANSIENT_RETRY_DELAY.toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Adds the supplied SSH credential and authenticates the session, clearing temporary password copies after use.
     * <p>添加所提供 SSH 凭据并认证会话，使用后清空临时密码副本。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void authenticate(ClientSession session, SshCredential credential) throws IOException {
        if (credential instanceof SshCredential.Password password) {
            char[] chars = password.copy();
            try {
                session.addPasswordIdentity(new String(chars));
            } finally {
                java.util.Arrays.fill(chars, '\0');
            }
        } else if (credential instanceof SshCredential.PrivateKey privateKey) {
            session.addPublicKeyIdentity(privateKey.keyPair());
        } else {
            throw new IOException("unsupported SSH credential type");
        }
        session.auth().verify(CONNECT_TIMEOUT);
    }

    /**
     * Prevents ambient keys from the desktop user's SSH directory or agent from consuming the remote server's authentication-attempt budget. Only the credential selected for the saved server profile is ever offered.
     *
     *  <p>防止桌面用户 SSH 目录或代理中的环境密钥消耗远程服务器的认证尝试额度。只会提供已保存服务器资料选定的凭据。
     *
     * @param credential authentication material scoped to the current connection / 限定于当前连接的认证素材
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static SshClient credentialScopedClient(SshCredential credential) {
        Objects.requireNonNull(credential, "credential");
        SshClient client = SshClient.setUpDefaultClient();
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, HEARTBEAT_INTERVAL);
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, HEARTBEAT_NO_REPLY_MAX);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        if (credential instanceof SshCredential.Password) {
            client.setUserAuthFactories(List.of(
                    UserAuthPasswordFactory.INSTANCE,
                    UserAuthKeyboardInteractiveFactory.INSTANCE
            ));
        } else if (credential instanceof SshCredential.PrivateKey) {
            client.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
        } else {
            throw new IllegalArgumentException("unsupported SSH credential type");
        }
        return client;
    }

    /**
     * Delegates client cleanup to the session-owned close policy. / 将客户端清理委托给会话层持有的关闭策略。
     *
     * @param client client / 客户端
     */
    public static void closeQuietly(SshClient client) {
        SshSessionLifecycleExecutor.closeQuietly(client);
    }

    /**
     * Delegates session cleanup to the session-owned close policy. / 将会话清理委托给会话层持有的关闭策略。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     */
    public static void closeQuietly(ClientSession session) {
        SshSessionLifecycleExecutor.closeQuietly(session);
    }

    /**
     * Hashes the encoded public key into the historical unpadded SHA256 fingerprint representation.
     * <p>对编码公钥计算哈希，得到历史不带填充的 SHA256 指纹表示。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return legacy fingerprint text / 历史指纹文本
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static String legacyFingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }

}
