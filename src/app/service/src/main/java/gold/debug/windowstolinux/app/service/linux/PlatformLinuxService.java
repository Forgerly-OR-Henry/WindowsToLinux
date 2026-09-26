package gold.debug.windowstolinux.app.service.linux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.persistence.repository.ServerProfileRepository;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.linux.ssh.*;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

/** Adapts saved servers and reviewed effects to actual task-owned SSH resources. / 将已保存服务器及已审核副作用接入真实任务所属 SSH 资源。 */
public final class PlatformLinuxService implements LinuxService, AutoCloseable {
    /** Authentic task and resource coordinator. / 真实任务及资源协调器。 */
    private final PlatformTaskRuntime tasks;

    /** Original retained credential authorization. / 原始保留凭据授权。 */
    private final DesktopSecurityService security;

    /** Platform-owned connection metadata and host trust. / 平台所属连接元数据及主机信任。 */
    private final ServerProfileRepository servers;

    /** Same caller's human review capability. / 同一调用方的人工审核能力。 */
    private final PlatformInteractionService interaction;

    /** Exact owning Mod identity. / 精确所属 Mod 身份。 */
    private final String owner;

    /** Exact owning loaded release. / 精确所属已加载版本。 */
    private final String version;

    /** Total authentication budget. / 认证总时间预算。 */
    private final Duration connectTimeout;

    /** Total file-transfer budget, since the public transfer request has no timeout field. / 文件传输总预算，公开传输请求未设超时字段。 */
    private final Duration transferTimeout;

    /** Includes failed connections whose physical cleanup has not completed. / 包含尚未完成物理清理的失败连接。 */
    private final Set<Session> retained = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Rejects new work after host shutdown begins. / 宿主开始关闭后拒绝新工作。 */
    private boolean closed;

    /**
     * Binds one activation to real platform services and bounded transport budgets. / 将一次启用绑定到真实平台服务及有界传输预算。
     * @param tasks central task authority / 中央任务授权方
     * @param security current-user credential owner / 当前用户凭据所有者
     * @param servers authoritative saved-server repository / 权威已保存服务器仓库
     * @param interaction same activation's review authority / 同次启用的审核授权方
     * @param owner exact caller identity / 精确调用方身份
     * @param version exact caller release / 精确调用方版本
     * @param connectTimeout positive authentication deadline / 正认证期限
     * @param transferTimeout positive transfer deadline / 正传输期限
     */
    public PlatformLinuxService(PlatformTaskRuntime tasks, DesktopSecurityService security,
            ServerProfileRepository servers, PlatformInteractionService interaction, String owner, String version,
            Duration connectTimeout, Duration transferTimeout) {
        this.tasks = Objects.requireNonNull(tasks);
        this.security = Objects.requireNonNull(security);
        this.servers = Objects.requireNonNull(servers);
        this.interaction = Objects.requireNonNull(interaction);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        this.connectTimeout = positive(connectTimeout);
        this.transferTimeout = positive(transferTimeout);
    }

    /**
     * Authenticates only an initially admitted server using the original task's credentials. / 仅使用原任务凭据认证初始准入服务器。
     * @param server saved server reference / 已保存服务器引用
     * @param task authentic original task / 真实原始任务
     * @return actual owned SSH session / 实际所属 SSH 会话
     * @throws ApiException if admission, authentication, trust or cleanup fails / 准入、认证、信任或清理失败时
     */
    @Override
    public RemoteSession connect(ServerRef server, TaskContext task) throws ApiException {
        worker();
        tasks.requireServer(task, owner, version, Objects.requireNonNull(server).id());
        Session session;
        synchronized (this) {
            if (closed)
                throw error(ProblemCode.UNAVAILABLE, "LINUX_SERVICE_CLOSED", "The owning Linux capability has closed");
            var lease = tasks.retain(task, new TaskResource("CONNECTION", UUID.randomUUID().toString(), owner, false));
            session = new Session(server, task, lease);
            retained.add(session);
        }
        char[] password = null;
        try {
            var profile = servers.findServerProfile(server.id()).orElseThrow(
                    () -> error(ProblemCode.NOT_FOUND, "SERVER_MISSING", "The saved server is unavailable"));
            var expected = servers.findServer(server.id());
            var endpoint = new SshEndpoint(server.id(), profile.host(), profile.sshPort(), profile.username());
            password = tasks.authorized(task, owner, version, () -> {
                try (var credentials = security.openCredentials()) {
                    return credentials.read(profile.credentialKey()).orElseThrow(() -> error(ProblemCode.NOT_FOUND,
                            "SERVER_CREDENTIAL_MISSING", "The saved server credential is unavailable"));
                }
            });
            session.connection = session.connector.connect(endpoint, password, (target, fingerprint) -> {
                if (expected.isPresent()) {
                    var trusted = expected.orElseThrow();
                    return trusted.host().equals(target.host()) && trusted.sshPort() == target.port()
                            && trusted.hostKeySha256().equals(fingerprint);
                }
                var intent = new ActionIntent("platform.ssh.trust", List.of(server.id()), server.id(), "first-use",
                        "host=" + target.host() + "\nport=" + target.port() + "\nusername=" + target.username()
                                + "\nSSH wire-key fingerprint=" + fingerprint,
                        "Trust this exact SSH host key for the selected saved server after successful authentication.");
                var decision = interaction.approve(intent, task);
                if (decision.outcome() == Outcome.CANCELLED)
                    throw error(ProblemCode.CANCELLED, "SSH_TRUST_CANCELLED", "SSH host trust review was cancelled");
                if (decision.outcome() != Outcome.SUCCEEDED)
                    return false;
                interaction.consume(decision.value().orElseThrow(), intent, task);
                return true;
            }, connectTimeout, tasks.operationCancellation(task));
            tasks.requireServer(task, owner, version, server.id());
            servers.saveAuthenticatedServer(
                    new ServerIdentity(server.id(), endpoint.host(), endpoint.port(), session.connection.fingerprint()),
                    expected);
            session.opening.set(false);
            return session;
        } catch (Exception failure) {
            session.opening.set(false);
            ApiException reported = failure instanceof ApiException known
                    ? known
                    : error(ProblemCode.IO_FAILURE, "SSH_PLATFORM_CONNECT",
                            "SSH connection or authenticated trust could not be established");
            try {
                session.close();
            } catch (ApiException cleanup) {
                reported.addSuppressed(cleanup);
            }
            throw reported;
        } finally {
            if (password != null)
                Arrays.fill(password, '\0');
        }
    }

    /**
     * Retries physical cleanup for one retained task without replaying remote business. / 重试一个保留任务的物理清理，不重放远端业务。
     * @param task authentic retained task / 真实保留任务
     * @throws ApiException if physical resources or ledger releases remain / 仍有物理资源或账本释放未完成时
     */
    public void closeTaskResources(TaskContext task) throws ApiException {
        worker();
        var snapshot = tasks.requireContext(task);
        if (!snapshot.ownerId().equals(owner) || !snapshot.ownerVersion().equals(version))
            throw error(ProblemCode.ACCESS_DENIED, "SSH_TASK_OWNER", "The task belongs to another Linux capability");
        closeSessions(task);
    }

    /**
     * Seals the capability and closes physical clients before releasing their leases. / 封闭能力并在释放占用前关闭物理客户端。
     * @throws ApiException if actual cleanup has not completed / 实际清理尚未完成时
     */
    @Override
    public void close() throws ApiException {
        worker();
        synchronized (this) {
            closed = true;
        }
        closeSessions(null);
    }

    /**
     * Attempts every matching close while retaining failed ownership. / 尝试每个匹配关闭，保留失败归属。
     * @param task optional exact task filter / 可选精确任务筛选
     * @throws ApiException if any close remains incomplete / 任一关闭仍未完成时
     */
    private void closeSessions(TaskContext task) throws ApiException {
        List<Session> sessions;
        synchronized (this) {
            sessions = retained.stream().filter(session -> task == null || session.task == task).toList();
        }
        ApiException first = null;
        for (var session : sessions) {
            try {
                session.close();
            } catch (ApiException failure) {
                if (first == null)
                    first = failure;
                else
                    first.addSuppressed(failure);
            }
        }
        if (first != null)
            throw first;
    }

    /** Actual session retained independently of author-visible handle delivery. / 独立于作者可见句柄交付保留的实际会话。 */
    private final class Session implements RemoteSession {
        /** Exact saved server identity. / 精确已保存服务器身份。 */
        private final ServerRef server;

        /** Original accepted task object. / 原始已接纳任务对象。 */
        private final TaskContext task;

        /** Persisted connection occupancy retained until physical close. / 持久连接占用，保留至物理关闭。 */
        private final ResourceLease lease;

        /** Owns even a failed authentication's retained client. / 即使认证失败也持有被保留的客户端。 */
        private final SshConnector connector = new SshConnector();

        /** Prevents cleanup from racing incomplete handle construction. / 防止清理与尚未完成的句柄构造竞争。 */
        private final AtomicBoolean opening = new AtomicBoolean(true);

        /** Serializes commands, transfers and actual release. / 串行化命令、传输及实际释放。 */
        private final AtomicBoolean busy = new AtomicBoolean();

        /** Physical authenticated connection, absent during failed or pending authentication. / 物理已认证连接，认证失败或等待期间不存在。 */
        private volatile SshConnector.Connection connection;

        /** Refuses new effects once cleanup is requested. / 请求清理后拒绝新副作用。 */
        private volatile boolean closing;

        /** Whether both physical close and persistent release succeeded. / 物理关闭与持久释放是否均成功。 */
        private volatile boolean released;

        /**
         * Retains the connection lease before any network operation. / 在任何网络操作之前保留连接占用。
         * @param server exact saved server / 精确已保存服务器
         * @param task original task / 原始任务
         * @param lease durable resource claim / 持久资源申请
         */
        private Session(ServerRef server, TaskContext task, ResourceLease lease) {
            this.server = server;
            this.task = task;
            this.lease = lease;
        }

        /** Returns only observed facts; unknown OS and architecture remain empty. / 仅返回已观察事实，未知系统及架构保持为空。
         * @return authenticated fingerprint and unobserved empty fields / 已认证指纹及未观察的空字段 */
        @Override
        public RemoteFacts facts() {
            return new RemoteFacts("", "", connection.fingerprint());
        }

        /**
         * Executes the complete reviewed script and input exactly once. / 精确执行一次已审核完整脚本及输入。
         * @param request exact immutable command / 精确不可变命令
         * @param grant original single-use host grant / 原始单次宿主授权
         * @return observed transport result / 已观察传输结果
         * @throws ApiException if authorization or resource ownership fails / 授权或资源归属失败时
         */
        @Override
        public OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant) throws ApiException {
            return operate(() -> {
                var reviewed = interaction.reviewed(grant, task);
                interaction.consume(grant, RemoteActions.command(server, reviewed.revision(), request), task);
                return connection.execute(request, tasks.operationCancellation(task));
            });
        }

        /**
         * Uploads by exclusive creation within the exact reviewed local path. / 在精确已审核本地路径范围内通过排他创建上传。
         * @param request exact file endpoints / 精确文件端点
         * @param grant reviewed single-use upload grant / 已审核单次上传授权
         * @return observed transfer result / 已观察传输结果
         * @throws ApiException if review or transfer admission fails / 审核或传输准入失败时
         */
        @Override
        public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant)
                throws ApiException {
            return transfer(request, grant, true);
        }

        /**
         * Downloads by exclusive creation within the exact reviewed local path. / 在精确已审核本地路径范围内通过排他创建下载。
         * @param request exact file endpoints / 精确文件端点
         * @param grant reviewed single-use download grant / 已审核单次下载授权
         * @return observed transfer result / 已观察传输结果
         * @throws ApiException if review or transfer admission fails / 审核或传输准入失败时
         */
        @Override
        public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant)
                throws ApiException {
            return transfer(request, grant, false);
        }

        /**
         * Checks the reviewed source digest and every transfer field before opening SFTP. / 打开 SFTP 前核对已审核源摘要及每个传输字段。
         * @param request exact transfer request / 精确传输请求
         * @param grant original grant object / 原始授权对象
         * @param upload transfer direction / 传输方向
         * @return verified or uncertain transfer result / 已核验或不确定的传输结果
         * @throws ApiException if action matching or transport fails / 动作匹配或传输失败时
         */
        private OperationResult<TransferResult> transfer(TransferRequest request, ApprovalGrant grant, boolean upload)
                throws ApiException {
            return operate(() -> {
                var reviewed = interaction.reviewed(grant, task);
                var digest = java.util.regex.Pattern.compile("\\AWTL-SFTP-1\\nsha256=([0-9a-f]{64})\\n")
                        .matcher(reviewed.exactAction());
                if (!digest.find())
                    throw error(ProblemCode.ACCESS_DENIED, "SFTP_APPROVAL",
                            "Transfer approval has no exact source digest");
                String sha256 = digest.group(1);
                interaction.consume(grant, RemoteActions.transfer(server, reviewed.revision(), request, sha256, upload),
                        task);
                return connection.transfer(request, sha256, upload, transferTimeout, tasks.operationCancellation(task),
                        conflict -> interaction.transferConflict(conflict, server, task), plan -> {
                            tasks.requireServer(task, owner, version, server.id());
                            var facts = new TreeMap<>(plan.recoveryFields());
                            facts.put("serverId", server.id());
                            facts.put("fingerprint", connection.fingerprint());
                            return tasks.retain(task, new TaskResource("FILE", "sftp:" + plan.id(), owner, true),
                                    new ResourceRecovery("sftp.v1", facts));
                        });
            });
        }

        /**
         * Serializes side effects and reports transport uncertainty to the central task. / 串行化副作用并向中央任务报告传输不确定性。
         * @param <T> observed public value type / 已观察公开值类型
         * @param work already owned transport action / 已有所属的传输动作
         * @return observed result / 已观察结果
         * @throws ApiException if ownership, dispatch or result tracking fails / 归属、发送或结果跟踪失败时
         */
        private <T> OperationResult<T> operate(TransportWork<T> work) throws ApiException {
            worker();
            if (!busy.compareAndSet(false, true))
                throw error(ProblemCode.BUSY, "SSH_SESSION_BUSY", "The previous session operation has not returned");
            try {
                if (closing || released || opening.get())
                    throw error(ProblemCode.UNAVAILABLE, "SSH_SESSION_CLOSING", "The owned session is not available");
                tasks.requireServer(task, owner, version, server.id());
                var result = work.run();
                if (result.outcome() == Outcome.UNKNOWN)
                    tasks.reportUncertainEffect(task);
                return result;
            } catch (RuntimeException failure) {
                tasks.reportUncertainEffect(task);
                throw error(ProblemCode.UNKNOWN, "SSH_ADAPTER_UNCERTAIN", "The transport did not establish an outcome");
            } finally {
                busy.set(false);
            }
        }

        /**
         * Releases the ledger only after the actual client and channel workers close. / 仅在实际客户端及通道工作线程关闭后释放账本。
         * @throws ApiException if physical or persistent cleanup needs retry / 物理或持久清理需要重试时
         */
        @Override
        public void close() throws ApiException {
            worker();
            closing = true;
            if (released)
                return;
            if (opening.get() || !busy.compareAndSet(false, true))
                throw error(ProblemCode.BUSY, "SSH_SESSION_BUSY", "Session work must return before resource release");
            try {
                connector.close();
                lease.close();
                released = true;
                synchronized (PlatformLinuxService.this) {
                    retained.remove(this);
                }
            } finally {
                busy.set(false);
            }
        }
    }

    /**
     * Internal checked transport action. / 内部受检传输动作。
     * @param <T> public operation value / 公开操作值
     */
    @FunctionalInterface
    private interface TransportWork<T> {
        /**
         * Performs one verified operation. / 执行一次已核验操作。
         * @return known or uncertain outcome / 已知或不确定结果
         * @throws ApiException if dispatch is rejected / 发送被拒绝时
         */
        OperationResult<T> run() throws ApiException;
    }

    /**
     * Requires an exactly representable positive transport budget. / 要求可精确表示的正传输预算。
     * @param value configured duration / 配置时长
     * @return validated duration / 已校验时长
     */
    private static Duration positive(Duration value) {
        if (Objects.requireNonNull(value).toNanos() <= 0)
            throw new IllegalArgumentException("Transport timeout must be positive");
        return value;
    }

    /**
     * Rejects network and physical close operations on the event thread. / 拒绝在事件线程执行网络及物理关闭。
     * @throws ApiException if called from the event thread / 从事件线程调用时
     */
    private static void worker() throws ApiException {
        if (SwingUtilities.isEventDispatchThread())
            throw error(ProblemCode.CONTRACT_VIOLATION, "SSH_REQUIRES_BACKGROUND",
                    "SSH work requires a background task");
    }

    /**
     * Constructs diagnostics without endpoint, script or credential contents. / 构造不含端点、脚本或凭据内容的诊断。
     * @param code public category / 公开类别
     * @param event stable identifier / 稳定标识
     * @param message safe explanation / 安全说明
     * @return public checked failure / 公开受检失败
     */
    private static ApiException error(ProblemCode code, String event, String message) {
        return new ApiException(new Problem(code, event, message, Map.of(), Optional.empty()));
    }
}
