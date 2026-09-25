package gold.debug.windowstolinux.app.linux.ssh;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.api.linux.CommandRequest;
import gold.debug.windowstolinux.api.linux.CommandResult;
import gold.debug.windowstolinux.api.linux.TransferRequest;
import gold.debug.windowstolinux.api.linux.TransferResult;
import gold.debug.windowstolinux.api.task.*;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.future.WaitableFuture;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;

/** Owns platform SSH clients without importing deployment code or ambient credentials. / 持有平台 SSH 客户端，不引入部署代码或环境凭据。 */
public final class SshConnector implements AutoCloseable {
    /** Connections retained until client and trust callbacks have actually closed. / 客户端及信任回调实际结束前保留的连接。 */
    private final Set<Connection> connections = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Prevents new connections once shutdown starts. / 开始关闭后阻止新连接。 */
    private boolean sealed;

    /** Creates a connector without starting network or background resources. / 创建连接器，不启动网络或后台资源。 */
    public SshConnector() {
    }

    /**
     * Opens one authenticated connection with bounded cancellation and explicit host trust.
     * / 使用有界取消及明确信任校验打开一个已认证连接。
     * @param endpoint exact saved endpoint / 精确的已保存端点
     * @param password caller-owned password, copied internally / 调用方持有的密码，内部复制
     * @param trust platform-owned first-use or pinned trust decision / 平台持有的首次或固定信任决定
     * @param timeout total connection and authentication budget / 连接与认证的总时间预算
     * @param cancellation original task cancellation / 原任务取消请求
     * @return owned connection with no exposed SSHD types / 不暴露 SSHD 类型的所属连接
     * @throws ApiException if connection, authorization or actual cleanup fails / 连接、授权或实际清理失败时
     */
    public Connection connect(SshEndpoint endpoint, char[] password, HostTrust trust, Duration timeout,
            CancellationToken cancellation) throws ApiException {
        worker();
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(password);
        Objects.requireNonNull(trust);
        Objects.requireNonNull(cancellation);
        long budget;
        try {
            budget = Objects.requireNonNull(timeout).toNanos();
            if (budget <= 0 || password.length == 0 || password.length > 65536)
                throw new IllegalArgumentException();
        } catch (ArithmeticException | IllegalArgumentException failure) {
            throw failure(ProblemCode.INVALID_INPUT, "SSH_CONNECT_INPUT",
                    "Invalid SSH connection budget or credential");
        }
        if (cancellation.requested())
            throw failure(ProblemCode.CANCELLED, "SSH_CONNECT_CANCELLED",
                    "SSH connection was cancelled before admission");
        Connection connection;
        synchronized (this) {
            if (sealed)
                throw failure(ProblemCode.BUSY, "SSH_CONNECTOR_CLOSING", "SSH connections are closing");
            connection = new Connection(endpoint, trust);
            connections.add(connection);
        }
        try {
            connection.authenticate(password, budget, cancellation);
            synchronized (this) {
                if (sealed)
                    throw failure(ProblemCode.CANCELLED, "SSH_CONNECTOR_CLOSING", "SSH connection owner is closing");
            }
            return connection;
        } catch (ApiException failure) {
            try {
                connection.close();
            } catch (ApiException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Returns the count of retained connections, including failed cleanup. / 返回包含清理失败在内的保留连接数。
     * @return current retained connection count / 当前保留连接数 */
    public synchronized int retainedConnections() {
        return connections.size();
    }

    /**
     * Seals admission and closes all owned connections, retaining failures for retry. / 封闭准入并关闭所有所属连接，失败项保留供重试。
     * @throws ApiException if any connection or trust callback remains / 仍有连接或信任回调时
     */
    @Override
    public void close() throws ApiException {
        worker();
        List<Connection> retained;
        synchronized (this) {
            sealed = true;
            retained = List.copyOf(connections);
        }
        ApiException failure = null;
        for (var connection : retained) {
            try {
                connection.close();
            } catch (ApiException problem) {
                if (failure == null)
                    failure = problem;
                else
                    failure.addSuppressed(problem);
            }
        }
        if (failure != null)
            throw failure;
    }

    /**
     * Requires a platform decision for the actual SSH wire-key fingerprint. / 要求平台对实际 SSH 编码公钥指纹作出决定。
     */
    @FunctionalInterface
    public interface HostTrust {
        /**
         * Accepts or rejects the observed host key without receiving credential contents. / 接受或拒绝观测的主机密钥，不接收凭据内容。
         * @param endpoint exact target endpoint / 精确目标端点
         * @param fingerprint SHA-256 SSH wire-key fingerprint / SSH 编码公钥的 SHA-256 指纹
         * @return whether this key is explicitly trusted / 是否明确信任该密钥
         * @throws ApiException if the trust decision is unavailable or rejected / 信任决定不可用或被拒绝时
         */
        boolean accepts(SshEndpoint endpoint, String fingerprint) throws ApiException;
    }

    /** Authenticated transport retained independently of a Mod page lifetime. / 独立于 Mod 页面生命周期保留的已认证传输。 */
    public final class Connection implements AutoCloseable {
        /** Immutable endpoint bound before authentication. / 认证前绑定的不可变端点。 */
        private final SshEndpoint endpoint;

        /** Client owning every physical session and worker. / 持有所有物理会话及工作线程的客户端。 */
        private final SshClient client = SshClient.setUpDefaultClient();

        /** Rejects concurrent lifecycle or transport mutation. / 拒绝并发的生命周期或传输修改。 */
        private final AtomicBoolean operating = new AtomicBoolean();

        /** Trust callbacks that have not actually returned. / 尚未实际返回的信任回调。 */
        private final AtomicInteger callbacks = new AtomicInteger();

        /** Sanitized trust failure kept apart from SSHD diagnostics. / 与 SSHD 诊断分离保留的脱敏信任失败。 */
        private final AtomicReference<ApiException> trustFailure = new AtomicReference<>();

        /** Authenticated physical session, never exposed to Mod callers. / 不向 Mod 调用方暴露的已认证物理会话。 */
        private volatile ClientSession session;

        /** Accepted SSH wire-key fingerprint, absent before verification. / 已接受的 SSH 编码密钥指纹，校验前为空。 */
        private volatile String fingerprint;

        /** Confirmed resource release, permitting idempotent close. / 已确认的资源释放，允许幂等关闭。 */
        private volatile boolean closed;

        /** Command channel and input sender still requiring actual cleanup. / 仍需实际清理的命令通道及输入发送线程。 */
        private SshCommandExecution command;

        /** Transfer resources retained until actual worker and channel release. / 实际工作线程和通道释放前保留的传输资源。 */
        private SftpTransfer transfer;

        /** Prevents ordinary reuse after unconfirmed remote command effects. / 远端命令副作用未确认后阻止普通复用。 */
        private boolean uncertain;

        /**
         * Binds explicit credentials and trust before starting the client. / 在启动客户端前绑定明确凭据范围及信任。
         * @param endpoint exact endpoint / 精确端点
         * @param trust platform host trust callback / 平台主机信任回调
         */
        private Connection(SshEndpoint endpoint, HostTrust trust) {
            this.endpoint = endpoint;
            client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.setPasswordIdentityProvider(PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
            client.setUserInteraction(UserInteraction.NONE);
            client.setUserAuthFactories(
                    List.of(UserAuthPasswordFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE));
            CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(30));
            CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, 3);
            client.setServerKeyVerifier((ignored, address, key) -> {
                callbacks.incrementAndGet();
                try {
                    String observed = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
                    if (fingerprint != null)
                        return fingerprint.equals(observed);
                    if (!trust.accepts(endpoint, observed)) {
                        trustFailure.set(failure(ProblemCode.ACCESS_DENIED, "SSH_HOST_KEY_REJECTED",
                                "SSH host key was not accepted"));
                        return false;
                    }
                    fingerprint = observed;
                    return true;
                } catch (ApiException problem) {
                    trustFailure.set(problem);
                    return false;
                } catch (RuntimeException problem) {
                    trustFailure.set(failure(ProblemCode.CONTRACT_VIOLATION, "SSH_HOST_TRUST_FAILED",
                            "SSH host trust could not be evaluated"));
                    return false;
                } finally {
                    callbacks.decrementAndGet();
                }
            });
        }

        /**
         * Authenticates once and removes the temporary SSHD password identity. / 认证一次并移除临时 SSHD 密码身份。
         * @param supplied caller-owned password / 调用方持有的密码
         * @param budget total nanosecond budget / 总纳秒预算
         * @param cancellation original cancellation / 原取消请求
         * @throws ApiException if authentication or transport fails / 认证或传输失败时
         */
        private void authenticate(char[] supplied, long budget, CancellationToken cancellation) throws ApiException {
            if (!operating.compareAndSet(false, true))
                throw failure(ProblemCode.BUSY, "SSH_CONNECT_BUSY", "SSH connection is busy");
            char[] password = supplied.clone();
            String identity = new String(password);
            long started = System.nanoTime();
            try {
                client.start();
                var connecting = client.connect(endpoint.username(), endpoint.host(), endpoint.port());
                await(connecting, started, budget, cancellation);
                session = connecting.verify(Duration.ofMillis(1)).getSession();
                session.addPasswordIdentity(identity);
                var authentication = session.auth();
                await(authentication, started, budget, cancellation);
                authentication.verify(Duration.ofMillis(1));
                if (fingerprint == null || !session.isAuthenticated())
                    throw failure(ProblemCode.ACCESS_DENIED, "SSH_AUTHENTICATION_FAILED",
                            "SSH authentication was not confirmed");
            } catch (ApiException problem) {
                throw problem;
            } catch (Exception problem) {
                if (problem instanceof java.io.InterruptedIOException)
                    Thread.currentThread().interrupt();
                if (trustFailure.get() != null)
                    throw trustFailure.get();
                throw failure(fingerprint == null ? ProblemCode.IO_FAILURE : ProblemCode.ACCESS_DENIED,
                        fingerprint == null ? "SSH_CONNECTION_FAILED" : "SSH_AUTHENTICATION_FAILED",
                        fingerprint == null
                                ? "SSH connection was not established"
                                : "SSH authentication was not confirmed");
            } finally {
                Arrays.fill(password, '\0');
                try {
                    if (session != null)
                        session.removePasswordIdentity(identity);
                } finally {
                    operating.set(false);
                }
            }
        }

        /** Returns the exact authenticated endpoint. / 返回精确的已认证端点。
         * @return immutable server endpoint / 不可变服务器端点 */
        public SshEndpoint endpoint() {
            return endpoint;
        }

        /** Returns the accepted SSH wire-key fingerprint. / 返回已接受的 SSH 编码公钥指纹。
         * @return authenticated host fingerprint / 已认证主机指纹 */
        public String fingerprint() {
            return fingerprint;
        }

        /** Returns the current local transport observation without making a network health claim. / 返回当前本地传输观测，不据此断言网络健康。
         * @return whether the authenticated local transport remains open / 已认证本地传输是否仍然打开 */
        public boolean connected() {
            return !closed && session != null && session.isAuthenticated() && session.isOpen();
        }

        /**
         * Executes the host-authorized request once, retaining any unclosed local resources.
         * / 执行一次宿主已授权请求，并保留尚未关闭的本地资源。
         * @param request exact request already checked by the platform approval adapter / 平台审批适配器已核对的精确请求
         * @param cancellation original task cancellation / 原任务取消请求
         * @return observed outcome independent of later session release / 独立于后续会话释放的已观测结果
         * @throws ApiException if admission fails before dispatch / 发送前准入失败时
         */
        public OperationResult<CommandResult> execute(CommandRequest request, CancellationToken cancellation)
                throws ApiException {
            worker();
            Objects.requireNonNull(cancellation);
            if (!operating.compareAndSet(false, true))
                throw failure(ProblemCode.BUSY, "SSH_CONNECTION_BUSY", "SSH connection work has not returned");
            try {
                if (!connected())
                    throw failure(ProblemCode.UNAVAILABLE, "SSH_CONNECTION_CLOSED", "SSH connection is not available");
                if (uncertain || command != null || transfer != null)
                    throw failure(ProblemCode.BUSY, "SSH_CONNECTION_UNCERTAIN",
                            "SSH connection requires closure after unfinished work");
                command = new SshCommandExecution(session, request);
                var result = command.run(cancellation);
                uncertain = result.outcome() == Outcome.UNKNOWN;
                if (command.close())
                    command = null;
                return result;
            } finally {
                operating.set(false);
            }
        }

        /**
         * Transfers one reviewed regular file to a new destination without retries. / 向新目标传输一个已审核普通文件，不进行重试。
         * @param request exact approved transfer request / 精确已批准传输请求
         * @param sha256 approved source digest checked before destination creation / 创建目标前核对的已批准源摘要
         * @param upload whether the destination is remote / 目标是否在远端
         * @param timeout host total operation deadline / 宿主操作总期限
         * @param cancellation original task cancellation / 原任务取消
         * @return verified transfer or explicit uncertainty / 已核验传输或明确不确定性
         * @throws ApiException if admission fails before transfer / 传输前准入失败时
         */
        public OperationResult<TransferResult> transfer(TransferRequest request, String sha256, boolean upload,
                Duration timeout, CancellationToken cancellation) throws ApiException {
            worker();
            if (!operating.compareAndSet(false, true))
                throw failure(ProblemCode.BUSY, "SSH_CONNECTION_BUSY", "SSH connection work has not returned");
            try {
                if (!connected())
                    throw failure(ProblemCode.UNAVAILABLE, "SSH_CONNECTION_CLOSED", "SSH connection is not available");
                if (uncertain || command != null || transfer != null)
                    throw failure(ProblemCode.BUSY, "SSH_CONNECTION_UNCERTAIN",
                            "SSH connection requires closure after unfinished work");
                transfer = new SftpTransfer(session, request, sha256, upload, timeout, cancellation);
                var result = transfer.run();
                uncertain = result.outcome() == Outcome.UNKNOWN;
                if (transfer.close())
                    transfer = null;
                return result;
            } finally {
                operating.set(false);
            }
        }

        /**
         * Closes physical resources and verifies that no trust callback remains. / 关闭物理资源并核对没有遗留信任回调。
         * @throws ApiException if actual close is still incomplete / 实际关闭仍未完成时
         */
        @Override
        public void close() throws ApiException {
            worker();
            if (closed)
                return;
            if (!operating.compareAndSet(false, true))
                throw failure(ProblemCode.BUSY, "SSH_CONNECTION_BUSY", "SSH connection work has not returned");
            try {
                if (!client.close(true).awaitUninterruptibly(Duration.ofSeconds(5)) || !client.isClosed()
                        || session != null && !session.isClosed() || callbacks.get() != 0)
                    throw failure(ProblemCode.BUSY, "SSH_CLOSE_PENDING", "SSH connection cleanup remains incomplete");
                if (command != null && !command.close())
                    throw failure(ProblemCode.BUSY, "SSH_COMMAND_CLOSE_PENDING",
                            "SSH command cleanup remains incomplete");
                if (transfer != null && !transfer.close())
                    throw failure(ProblemCode.BUSY, "SFTP_CLOSE_PENDING", "SFTP cleanup remains incomplete");
                command = null;
                transfer = null;
                closed = true;
                synchronized (SshConnector.this) {
                    connections.remove(this);
                }
            } catch (ApiException problem) {
                throw problem;
            } catch (RuntimeException problem) {
                throw failure(ProblemCode.IO_FAILURE, "SSH_CLOSE_FAILED",
                        "SSH connection cleanup could not be confirmed");
            } finally {
                operating.set(false);
            }
        }
    }

    /**
     * Waits within the original budget while observing cooperative cancellation. / 在原预算内等待并观察协作取消。
     * @param pending actual SSHD operation / 实际 SSHD 操作
     * @param started monotonic start time / 单调时钟启动时间
     * @param budget total nanosecond budget / 总纳秒预算
     * @param cancellation task cancellation / 任务取消请求
     * @throws Exception if time, cancellation or the transport prevents completion / 时间、取消或传输阻止完成时
     */
    private static void await(WaitableFuture pending, long started, long budget, CancellationToken cancellation)
            throws Exception {
        while (true) {
            if (cancellation.requested() || Thread.currentThread().isInterrupted())
                throw failure(ProblemCode.CANCELLED, "SSH_CONNECT_CANCELLED", "SSH connection was cancelled");
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0)
                throw failure(ProblemCode.TIMEOUT, "SSH_CONNECT_TIMEOUT", "SSH connection budget expired");
            if (pending.await(Math.max(1, Math.min(100, remaining / 1_000_000))))
                return;
        }
    }

    /** Rejects blocking SSH work on the Swing event thread. / 拒绝在 Swing 事件线程执行阻塞 SSH 操作。 */
    private static void worker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("SSH operations require a worker thread");
    }

    /**
     * Creates a bounded problem without retaining provider messages or credentials. / 创建有界问题，不保留提供方消息或凭据。
     * @param code public category / 公开分类
     * @param detail stable diagnostic code / 稳定诊断代码
     * @param message safe diagnostic text / 安全诊断文本
     * @return structured exception / 结构化异常
     */
    static ApiException failure(ProblemCode code, String detail, String message) {
        return new ApiException(new Problem(code, detail, message, Map.of(), Optional.empty()));
    }
}
