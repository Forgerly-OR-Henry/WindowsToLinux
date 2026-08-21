package gold.debug.windowstolinux.shared.linux.sshd.connection;

import gold.debug.windowstolinux.shared.linux.sshd.session.SshdLinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
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
 * <p>受管部署白名单远程契约的 Apache MINA SSHD 实现。它只通过提供的验证器接受主机密钥，并且绝不公开原始命令方法。
 */
public final class SshdLinuxGateway implements DeploymentLinuxGateway {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final int HEARTBEAT_NO_REPLY_MAX = 3;
    private static final int TRANSIENT_CONNECTION_ATTEMPTS = 3;
    private static final Duration TRANSIENT_RETRY_DELAY = Duration.ofMillis(250);
    /** Performs the {@code connect} operation. / 执行 {@code connect} 操作。 */
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

    private static DeploymentRemoteSession connectOnce(
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyEvaluator hostKeyVerifier
    ) throws LinuxOperationException {
        SshClient client = credentialScopedClient(credential);
        AtomicReference<String> observedFingerprint = new AtomicReference<>();
        AtomicReference<HostKeyDecision> hostKeyDecision = new AtomicReference<>();
        client.setServerKeyVerifier((session, remote, key) -> {
            String fingerprint = fingerprint(key);
            observedFingerprint.set(fingerprint);
            HostKeyDecision decision = hostKeyVerifier.verify(endpoint, fingerprint);
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

    static boolean isTransientConnectionFailure(LinuxOperationException failure) {
        return SshCommandExecutor.isTransientTransportFailure(failure);
    }

    private static boolean waitForRetry() {
        try {
            Thread.sleep(TRANSIENT_RETRY_DELAY.toMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

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
     * <p>防止桌面用户 SSH 目录或代理中的环境密钥消耗远程服务器的认证尝试额度。只会提供已保存服务器资料选定的凭据。
     *
     * @param credential the {@code credential} value / {@code credential} 值
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
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

    /** Delegates client cleanup to the session-owned close policy. / 将客户端清理委托给会话层持有的关闭策略。 */
    public static void closeQuietly(SshClient client) {
        SshSessionLifecycleExecutor.closeQuietly(client);
    }

    /** Delegates session cleanup to the session-owned close policy. / 将会话清理委托给会话层持有的关闭策略。 */
    public static void closeQuietly(ClientSession session) {
        SshSessionLifecycleExecutor.closeQuietly(session);
    }

    private static String fingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }

}
