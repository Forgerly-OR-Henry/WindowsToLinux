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

    /** Shared persisted thresholds read at each logical operation boundary. / 在每个逻辑操作边界读取的共用持久阈值。 */
    private final TransferSettingsService settings;

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
     * @param settings shared persisted threshold owner / 共用持久阈值所有者
     */
    public PlatformLinuxService(PlatformTaskRuntime tasks, DesktopSecurityService security,
            ServerProfileRepository servers, PlatformInteractionService interaction, String owner, String version,
            TransferSettingsService settings) {
        this.tasks = Objects.requireNonNull(tasks);
        this.security = Objects.requireNonNull(security);
        this.servers = Objects.requireNonNull(servers);
        this.interaction = Objects.requireNonNull(interaction);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        this.settings = Objects.requireNonNull(settings);
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
        var thresholds = thresholds(task);
        Session session;
        synchronized (this) {
            if (closed)
                throw error(ProblemCode.UNAVAILABLE, "LINUX_SERVICE_CLOSED", "The owning Linux capability has closed");
            var lease = tasks.retainSshConnection(task, owner, version, server.id());
            session = new Session(server, task, lease);
            retained.add(session);
        }
        try {
            authenticate(session, thresholds.connectTimeout());
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
        }
    }

    /** Verifies a host rescue using a fixed read-only command after normal identity and authentication checks. / 正常身份及认证检查后使用固定只读命令复核宿主救援。
     * @param server originally admitted server / 原准入服务器
     * @param task original platform rescue context / 原平台救援上下文
     * @return observed fixed verification outcome / 已观察固定复核结果
     * @throws ApiException if ownership, trust, transport or actual cleanup is unavailable / 归属、信任、传输或实际清理不可用时 */
    public OperationResult<Void> verifyRecovery(ServerRef server, TaskContext task) throws ApiException {
        worker();
        var identity = tasks.requireExecuting(task, owner, version);
        if (!owner.equals(PlatformTaskAdmission.PLATFORM_OWNER)
                || !identity.type().equals(PlatformTaskAdmission.RESCUE_TYPE))
            throw error(ProblemCode.ACCESS_DENIED, "RESCUE_TASK_REQUIRED",
                    "Fixed rescue verification requires its original host task");
        try (var session = (Session) connect(server, task)) {
            var request = new CommandRequest("printf '%s\\n' WTL_SSH_RESCUE_OK", new byte[0], Duration.ofSeconds(15),
                    1024);
            var result = session.operate(() -> session.connection.execute(request, tasks.operationCancellation(task)));
            if (result.outcome() != Outcome.SUCCEEDED)
                return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
            var output = result.value().orElseThrow();
            if (!output.exitCode().equals(Optional.of(0)) || output.truncated()
                    || !output.stdout().equals("WTL_SSH_RESCUE_OK\n"))
                return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.IO_FAILURE, "RESCUE_VERIFICATION_OUTPUT",
                                "The fixed SSH verification did not produce its expected result", Map.of(),
                                Optional.empty())));
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
        }
    }

    /** Authenticates one physical connection using the original task and current saved trust. / 使用原任务及当前保存信任认证一个物理连接。
     * @param session retained logical session / 保留的逻辑会话
     * @param connectTimeout captured connection threshold / 已捕获连接阈值
     * @throws Exception if credentials, trust, authentication or persistence fails / 凭据、信任、认证或持久化失败时 */
    private void authenticate(Session session, Duration connectTimeout) throws Exception {
        var server = session.server;
        var task = session.task;
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
            var trust = tasks.<SshEndpoint, String, Boolean>hostCallback(task, owner, version,
                    (target, fingerprint) -> {
                        if (expected.isPresent()) {
                            var trusted = expected.orElseThrow();
                            return trusted.host().equals(target.host()) && trusted.sshPort() == target.port()
                                    && trusted.hostKeySha256().equals(fingerprint);
                        }
                        var intent = new ActionIntent("platform.ssh.trust", List.of(server.id()), server.id(),
                                "first-use",
                                "host=" + target.host() + "\nport=" + target.port() + "\nusername=" + target.username()
                                        + "\nSSH wire-key fingerprint=" + fingerprint,
                                "Trust this exact SSH host key for the selected saved server after successful authentication.");
                        var decision = interaction.approve(intent, task);
                        if (decision.outcome() == Outcome.CANCELLED)
                            throw error(ProblemCode.CANCELLED, "SSH_TRUST_CANCELLED",
                                    "SSH host trust review was cancelled");
                        if (decision.outcome() != Outcome.SUCCEEDED)
                            return false;
                        interaction.consume(decision.value().orElseThrow(), intent, task);
                        return true;
                    });
            session.connection = session.connector.connect(endpoint, password, trust::apply, connectTimeout,
                    tasks.operationCancellation(task));
            tasks.requireServer(task, owner, version, server.id());
            servers.saveAuthenticatedServer(
                    new ServerIdentity(server.id(), endpoint.host(), endpoint.port(), session.connection.fingerprint()),
                    expected);
        } finally {
            if (password != null)
                Arrays.fill(password, '\0');
        }
    }

    /** Lists only the original caller's outstanding transfer descriptions. / 仅列出原调用方未完成的传输描述。
     * @param task authentic executing or recovery task / 真实执行或恢复任务
     * @return immutable checkpoint descriptions / 不可变检查点描述
     * @throws ApiException if ownership or recovery facts are invalid / 归属或恢复事实无效时 */
    @Override
    public List<TransferCheckpoint> pendingTransfers(TaskContext task) throws ApiException {
        worker();
        return recoveryRecords(task).stream().map(RecoveredTransfer::checkpoint)
                .sorted(Comparator.comparing(TransferCheckpoint::id)).toList();
    }

    /** Loads strict transport facts separately from their task and server binding. / 严格加载传输事实，单独核对任务及服务器绑定。
     * @param task authentic original task / 真实原任务
     * @return validated host recovery records / 已校验宿主恢复记录
     * @throws ApiException if any matching record cannot be verified / 无法核验任一匹配记录时 */
    private List<RecoveredTransfer> recoveryRecords(TaskContext task) throws ApiException {
        synchronized (this) {
            if (closed)
                throw error(ProblemCode.UNAVAILABLE, "LINUX_SERVICE_CLOSED", "The Linux capability has closed");
        }
        var records = tasks.recoveries(task, owner, version, "FILE", "sftp:");
        var found = new ArrayList<RecoveredTransfer>();
        try {
            for (var entry : records.entrySet()) {
                var resource = entry.getKey();
                var metadata = entry.getValue();
                if (!resource.exclusive() || !metadata.protocol().equals("sftp.v1"))
                    throw new IllegalArgumentException("Unknown transfer resource protocol");
                var fields = new TreeMap<>(metadata.fields());
                var server = new ServerRef(Objects.requireNonNull(fields.remove("serverId")));
                String fingerprint = Objects.requireNonNull(fields.remove("fingerprint"));
                var plan = TransferPlan.fromRecoveryFields(fields);
                if (!resource.id().equals("sftp:" + plan.id()) || !fingerprint.startsWith("SHA256:"))
                    throw new IllegalArgumentException("Transfer binding differs");
                found.add(new RecoveredTransfer(resource, metadata, server, fingerprint, plan));
            }
            return List.copyOf(found);
        } catch (RuntimeException invalid) {
            throw error(ProblemCode.IO_FAILURE, "SFTP_RECOVERY_FACTS", "Original transfer facts cannot be verified");
        }
    }

    /** Host-only binding retained independently of the public checkpoint. / 独立于公开检查点保留的宿主绑定。
     * @param resource exact original file claim / 精确原文件申请
     * @param metadata exact persisted envelope / 精确持久封套
     * @param server original server identity / 原服务器身份
     * @param fingerprint original authenticated host key / 原已认证主机密钥
     * @param plan exact transport facts / 精确传输事实 */
    private record RecoveredTransfer(TaskResource resource, ResourceRecovery metadata, ServerRef server,
            String fingerprint, TransferPlan plan) {
        /** Exposes only facts needed to review the recovery operation. / 仅公开审核恢复操作所需事实。
         * @return public review description / 公开审核描述 */
        TransferCheckpoint checkpoint() {
            return new TransferCheckpoint(plan.id(), server, plan.upload(),
                    new TransferRequest(plan.localPath(), plan.remotePath(), plan.request().maxBytes()),
                    plan.source().sha256(), plan.source().size(), plan.target() != null);
        }
    }

    /**
     * Retries physical cleanup for one retained task without replaying remote business. / 重试一个保留任务的物理清理，不重放远端业务。
     * @param task authentic retained task / 真实保留任务
     * @throws ApiException if physical resources or ledger releases remain / 仍有物理资源或账本释放未完成时
     */
    public void closeTaskResources(TaskContext task) throws ApiException {
        worker();
        tasks.requireCleanup(task, owner, version);
        closeSessions(task);
        tasks.releasePreviousSshConnections(task, owner, version);
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
                interaction.consumeRemote(grant, RemoteActions.command(server, reviewed.revision(), request), task);
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

        /** Resumes only a verified original transfer owned by this task and server. / 仅续传此任务及服务器所属的经核验原传输。
         * @param transferId original checkpoint identity / 原检查点身份
         * @param grant exact reviewed resume approval / 精确已审核续传审批
         * @return verified file result or uncertainty / 经核验文件结果或不确定性
         * @throws ApiException if authorization or ownership fails / 授权或归属失败时 */
        @Override
        public OperationResult<TransferResult> resumeTransfer(UUID transferId, ApprovalGrant grant)
                throws ApiException {
            return recoverTransfer(transferId, grant, true);
        }

        /** Discards verified staging without deleting or rewriting the final target. / 丢弃经核验暂存，不删除或重写最终目标。
         * @param transferId original checkpoint identity / 原检查点身份
         * @param grant exact reviewed discard approval / 精确已审核丢弃审批
         * @return staging cleanup conclusion / 暂存清理结论
         * @throws ApiException if authorization or ownership fails / 授权或归属失败时 */
        @Override
        public OperationResult<Void> discardTransfer(UUID transferId, ApprovalGrant grant) throws ApiException {
            var result = recoverTransfer(transferId, grant, false);
            return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
        }

        /** Reloads original facts and consumes the exact recovery-specific grant before transport. / 传输前重新加载原事实并消费精确恢复专用授权。
         * @param transferId original checkpoint / 原检查点
         * @param grant original approval object / 原审批对象
         * @param resume whether to continue rather than discard / 是否继续而非丢弃
         * @return observed recovery result / 已观察恢复结果
         * @throws ApiException if original identity, grant or physical ownership differs / 原身份、授权或物理归属不符时 */
        private OperationResult<TransferResult> recoverTransfer(UUID transferId, ApprovalGrant grant, boolean resume)
                throws ApiException {
            return operate(() -> {
                tasks.releasePreviousSshConnections(task, owner, version);
                tasks.requireSoleSshConnection(task, owner, version, lease.id());
                var record = recoveryRecords(task).stream()
                        .filter(value -> value.plan().id().equals(transferId) && value.server().equals(server))
                        .findFirst().orElseThrow(() -> error(ProblemCode.NOT_FOUND, "SFTP_CHECKPOINT_MISSING",
                                "Original transfer checkpoint is unavailable"));
                if (!record.fingerprint().equals(connection.fingerprint()))
                    throw error(ProblemCode.ACCESS_DENIED, "SFTP_RECOVERY_SERVER",
                            "The authenticated server differs from the original transfer");
                var reviewed = interaction.reviewed(grant, task);
                interaction.consumeRemote(grant,
                        RemoteActions.recoverTransfer(record.checkpoint(), reviewed.revision(), resume), task);
                return new TransferRun(record.plan(), plan -> tasks.retain(task, record.resource(), record.metadata()))
                        .run(record.plan().request(), record.plan().source().sha256(), record.plan().upload(), !resume);
            });
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
                interaction.consumeRemote(grant,
                        RemoteActions.transfer(server, reviewed.revision(), request, sha256, upload), task);
                return new TransferRun(null, plan -> {
                    tasks.requireServer(task, owner, version, server.id());
                    var facts = new TreeMap<>(plan.recoveryFields());
                    facts.put("serverId", server.id());
                    facts.put("fingerprint", connection.fingerprint());
                    return tasks.retain(task, new TaskResource("FILE", "sftp:" + plan.id(), owner, true),
                            new ResourceRecovery("sftp.v1", facts));
                }).run(request, sha256, upload, false);
            });
        }

        /** Owns repeated physical attempts of one reviewed file operation. / 持有一个已审核文件操作的多次物理尝试。 */
        private final class TransferRun implements TransferJournal {
            /** Original persistence operation, reused only after its prior lease closes. / 原持久化操作，仅在原占用关闭后复用。 */
            private final TransferJournal journal;

            /** Original facts, never inferred from an unverified offset. / 原事实，不从未核验偏移推导。 */
            private TransferPlan plan;

            /** Same actual file lease across stopped attempts. / 已停止尝试之间共用的实际文件占用。 */
            private ResourceLease file;

            /** Latest actual stage and completed byte observation. / 最近实际阶段及完成字节观测。 */
            private TransferProgress latest;

            /** Binds new or original recovery facts to the durable owner. / 将新传输或原恢复事实绑定到持久所有者。
             * @param plan original plan, or null before first preparation / 原计划，首次准备前为 null
             * @param journal original durable ownership gate / 原持久归属门禁 */
            private TransferRun(TransferPlan plan, TransferJournal journal) {
                this.plan = plan;
                this.journal = journal;
            }

            /** Keeps one lease while each stopped attempt revalidates the same facts. / 保留一个占用，每次已停止尝试重新核验相同事实。
             * @param prepared verified original plan / 已核验原计划
             * @return same owned release handle until resolved / 解决前保持相同所属释放句柄
             * @throws Exception if facts differ or durable admission fails / 事实不符或持久准入失败时 */
            @Override
            public synchronized ResourceLease prepare(TransferPlan prepared) throws Exception {
                if (plan != null && !plan.equals(prepared))
                    throw error(ProblemCode.ACCESS_DENIED, "SFTP_RETRY_FACTS",
                            "Retry facts differ from the original transfer");
                if (file == null) {
                    var retainedFile = journal.prepare(prepared);
                    plan = prepared;
                    file = new ResourceLease() {
                        /** Returns the original durable lease identity. / 返回原持久占用身份。
                         * @return exact resource identity / 精确资源身份 */
                        @Override
                        public UUID id() {
                            return retainedFile.id();
                        }

                        /** Releases only when the physical operation confirms resolution. / 仅在物理操作确认解决后释放。
                         * @throws ApiException if persistent release fails / 持久释放失败时 */
                        @Override
                        public void close() throws ApiException {
                            synchronized (TransferRun.this) {
                                retainedFile.close();
                                file = null;
                                plan = null;
                            }
                        }
                    };
                }
                return file;
            }

            /** Publishes physical stage progress without manufacturing effective bytes. / 发布物理阶段进度，不制造有效字节。
             * @param value current physical observation / 当前物理观测
             * @throws ApiException if task publication fails / 任务发布失败时 */
            private void progress(TransferProgress value) throws ApiException {
                latest = value;
                interaction.transferProgress(value, task);
            }

            /** Retries only after actual transport closure and an explicit human choice. / 仅在实际传输关闭及明确人工选择后重试。
             * @param request original reviewed request / 原已审核请求
             * @param digest original reviewed digest / 原已审核摘要
             * @param upload original direction / 原方向
             * @param discard whether this operation explicitly discards verified staging / 此操作是否明确丢弃经核验暂存
             * @return final observed result with unresolved effects preserved / 保留未解决副作用的最终观测结果
             * @throws ApiException if original ownership or explicit retry cannot continue / 原归属或明确重试无法继续时 */
            private OperationResult<TransferResult> run(TransferRequest request, String digest, boolean upload,
                    boolean discard) throws ApiException {
                var thresholds = thresholds(task);
                var transferTimeout = thresholds.idleTimeout();
                var cancellation = tasks.operationCancellation(task);
                String fingerprint = connection.fingerprint();
                var prepare = tasks.<TransferPlan, Void, ResourceLease>hostCallback(task, owner, version,
                        (prepared, ignored) -> {
                            try {
                                return prepare(prepared);
                            } catch (ApiException failure) {
                                throw failure;
                            } catch (Exception failure) {
                                tasks.reportUncertainEffect(task);
                                throw error(ProblemCode.IO_FAILURE, "SFTP_JOURNAL_CALLBACK",
                                        "Original transfer ownership could not be recorded");
                            }
                        });
                var choose = tasks.<TransferConflict, Void, TransferConflictChoice>hostCallback(task, owner, version,
                        (conflict, ignored) -> interaction.transferConflict(conflict, server, task));
                var observe = tasks.<TransferProgress, Void, Void>hostCallback(task, owner, version,
                        (progress, ignored) -> {
                            progress(progress);
                            return null;
                        });
                TransferJournal ownedJournal = prepared -> prepare.apply(prepared, null);
                while (true) {
                    var result = plan == null
                            ? connection.transfer(request, digest, upload, transferTimeout, cancellation,
                                    progress -> observe.apply(progress, null), conflict -> choose.apply(conflict, null),
                                    ownedJournal)
                            : connection.recoverTransfer(plan, discard, transferTimeout, cancellation,
                                    progress -> observe.apply(progress, null), ownedJournal);
                    if (result.problem().isEmpty()
                            || !result.problem().orElseThrow().eventCode().equals("SFTP_STALLED"))
                        return result;
                    try {
                        connection.close();
                        var actual = plan == null
                                ? request
                                : new TransferRequest(plan.localPath(), plan.remotePath(), request.maxBytes());
                        var choice = interaction.transferStalled(
                                new TransferStall(latest, actual, upload, transferTimeout), server, task);
                        if (choice != TransferStallChoice.RETRY || cancellation.requested() || closing)
                            return result.outcome() == Outcome.UNKNOWN
                                    ? result
                                    : new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                                            Optional.of(error(ProblemCode.CANCELLED, "SFTP_TERMINATED",
                                                    "Transfer was terminated before file effects").problem()));
                        tasks.requireServer(task, owner, version, server.id());
                        tasks.requireSoleSshConnection(task, owner, version, lease.id());
                        authenticate(Session.this, thresholds.connectTimeout());
                        if (!fingerprint.equals(connection.fingerprint()))
                            throw error(ProblemCode.ACCESS_DENIED, "SFTP_RETRY_SERVER",
                                    "Retry server differs from the original transfer");
                    } catch (Exception failure) {
                        if (result.outcome() == Outcome.UNKNOWN)
                            tasks.reportUncertainEffect(task);
                        if (failure instanceof ApiException known)
                            throw known;
                        throw error(ProblemCode.IO_FAILURE, "SFTP_RETRY_UNAVAILABLE",
                                "Original transfer retry could not proceed");
                    }
                }
            }
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

    /** Reads current thresholds using the original accepted task authorization. / 使用原已接纳任务授权读取当前阈值。
     * @param task original executing task / 原执行任务
     * @return immutable settings for this operation / 本操作不可变设置
     * @throws ApiException if identity or settings are unavailable / 身份或设置不可用时 */
    private TransferSettings thresholds(TaskContext task) throws ApiException {
        try {
            return tasks.authorized(task, owner, version, settings::read);
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error(ProblemCode.UNAVAILABLE, "TRANSFER_SETTINGS_UNAVAILABLE",
                    "Transfer settings could not be read");
        }
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
