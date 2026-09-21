package gold.debug.windowstolinux.app.service.recovery;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.ai.RecoveryAiUseCase;
import gold.debug.windowstolinux.app.service.contract.*;
import gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot;
import gold.debug.windowstolinux.app.service.lock.ServerOperationLockRegistry;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.windows.recovery.PlaywrightTerminalSession;
import gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Assembles rescue sessions using the same server mutex as deployment. / 使用与部署相同的服务器互斥装配救援会话。
 */
public final class SshRecoveryUseCase implements AutoCloseable {
    /**
     * Persistence.
     * <p>持久化。
     */
    private final DesktopPersistence persistence;
    /**
     * Shared operation locks indexed by target identity.
     * <p>按目标身份索引的共享操作锁。
     */
    private final ServerOperationLockRegistry locks;
    /**
     * The supplied recovery ai use case.
     * <p>所提供的恢复AI用例。
     */
    private final RecoveryAiUseCase ai;
    /**
     * Probe.
     * <p>探测。
     */
    private final RecoverySshProbe probe;
    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;
    /**
     * Sessions.
     * <p>会话集合。
     */
    private final Set<BrowserRecoverySession> sessions = ConcurrentHashMap.newKeySet();
    /**
     * Bound executor service collaborator for workers.
     * <p>处理工作线程集合的执行器服务协作对象。
     */
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        var thread = new Thread(r, "ssh-rescue-service"); thread.setDaemon(true); return thread;
    });
    /**
     * Reuses existing protected credentials, models, journals and SSH implementation. / 复用受保护凭据、模型、记录和 SSH 实现。
     *
     * @param persistence persistence / 持久化
     * @param locks shared operation locks indexed by target identity / 按目标身份索引的共享操作锁
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     */
    public SshRecoveryUseCase(DesktopPersistence persistence, ServerOperationLockRegistry locks,
            ServerUseCaseFacade servers, DesktopSecretStoreService secrets, DeploymentLinuxGateway gateway) {
        this.persistence = persistence; this.locks = locks; this.servers = servers;
        ai = new RecoveryAiUseCase(persistence.aiProfiles(), secrets, new RecoveryModelClient());
        probe = new RecoverySshProbe(servers, secrets, gateway);
    }
    /**
     * Starts a manually requested session asynchronously. / 异步启动人工请求的会话。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return constructed or resolved ssh recovery session / 构造或解析得到的SSH恢复会话
     */
    public SshRecoverySession start(ServerProfile profile, char[] master, Predicate<String> fingerprint) {
        BrowserRecoverySession session = create(profile, master, fingerprint, "manual");
        sessions.add(session);
        try { workers.execute(() -> { try { session.run(); } finally { sessions.remove(session); } }); }
        catch (RejectedExecutionException failure) {
            session.close();
            try { session.run(); } finally { sessions.remove(session); Arrays.fill(master, '\0'); }
            throw failure;
        }
        return session;
    }
    /**
     * Runs on the operation thread so its existing reentrant server lock stays held. / 在原操作线程运行，保持既有可重入服务器锁。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param operation operation / 操作
     * @param show show / 显示
     * @return true when runs on the operation thread so its existing reentrant server lock stays held, false otherwise / 在原操作线程运行，保持既有可重入服务器锁时为 true，否则为 false
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public boolean recoverInline(ServerProfile profile, char[] master, Predicate<String> fingerprint,
            String operation, Consumer<SshRecoverySession> show) throws Exception {
        BrowserRecoverySession session = create(profile, master, fingerprint, operation);
        sessions.add(session);
        boolean started = false;
        try {
            show.accept(session); started = true; session.run();
            RecoverySnapshot result = session.await();
            return result.state() == RecoverySnapshot.State.RECOVERED && !result.resultUnknown();
        } finally {
            session.close();
            try { if (!started) session.run(); }
            finally { sessions.remove(session); Arrays.fill(master, '\0'); }
        }
    }
    /**
     * Resumes only the original read-only verification under the same mutex. / 在同一互斥内仅继续原只读验证。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved server capability facts / 构造或解析得到的服务器能力事实
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts verify(ServerProfile profile,
            gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode, char[] master, Predicate<String> fingerprint,
            gold.debug.windowstolinux.app.service.contract.DesktopRecoveryInteraction interaction) throws Exception {
        var lock = locks.forServer(profile.id()); boolean acquired = lock.tryLock();
        try {
            if (!acquired) throw new IllegalStateException("server-operation-busy");
            try { return servers.verify(profile, mode, master.clone(), fingerprint); }
            catch (gold.debug.windowstolinux.shared.linux.error.LinuxOperationException failure) {
                if (!recoverFailure(profile, master, fingerprint, interaction, failure)) throw failure;
                return servers.verify(profile, mode, master.clone(), fingerprint);
            }
        } finally { Arrays.fill(master, '\0'); if (acquired) lock.unlock(); }
    }
    /**
     * Reconciles completed installation without submitting installation again. / 核对已完成安装，不再次提交安装操作。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param confirmed confirmed / 已确认
     * @param system system / 系统
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param environment environment / 环境
     * @return constructed or resolved environment setup result / 构造或解析得到的环境Setup结果
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult prepare(ServerProfile profile,
            gold.debug.windowstolinux.shared.model.security.CredentialStorageMode mode, char[] master, Predicate<String> fingerprint,
            boolean confirmed, Predicate<gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan> system,
            gold.debug.windowstolinux.app.service.contract.DesktopRecoveryInteraction interaction,
            gold.debug.windowstolinux.app.service.execution.environment.EnvironmentSetupUseCase environment) throws Exception {
        var lock = locks.forServer(profile.id()); boolean acquired = lock.tryLock();
        try {
            if (!acquired) throw new IllegalStateException("server-operation-busy");
            try { return environment.prepare(profile, mode, master.clone(), fingerprint, confirmed, system); }
            catch (gold.debug.windowstolinux.shared.linux.error.LinuxOperationException failure) {
                if (!recoverFailure(profile, master, fingerprint, interaction, failure)) throw failure;
                if (failure.environmentNotStarted()) return environment.prepare(profile, mode, master.clone(), fingerprint, confirmed, system);
                if (failure.completedEnvironment().isEmpty()) throw failure;
                return new gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult(
                        servers.verify(profile, mode, master.clone(), fingerprint), failure.completedEnvironment().orElseThrow().evidence());
            }
        } finally { Arrays.fill(master, '\0'); if (acquired) lock.unlock(); }
    }
    /**
     * Recovers structured failure occurrence retained for safe reporting.
     * <p>恢复保留用于安全报告的结构化失败实例。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return true when recovers structured failure occurrence retained for safe reporting, false otherwise / 恢复保留用于安全报告的结构化失败实例时为 true，否则为 false
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private boolean recoverFailure(ServerProfile profile, char[] master, Predicate<String> fingerprint,
            gold.debug.windowstolinux.app.service.contract.DesktopRecoveryInteraction interaction,
            gold.debug.windowstolinux.shared.linux.error.LinuxOperationException failure) throws Exception {
        return failure.failure().definition() == gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.CONNECTION_FAILED
                && interaction.offerSshRecovery(profile) && recoverInline(profile, master.clone(), fingerprint,
                failure.failure().operationIdentity().toString(), session -> interaction.showSshRecovery(profile, session));
    }
    /**
     * Assembles a recovery worker, isolated browser and metadata-only journal under the existing server lock. Model responses and terminal command text are never journaled.
     * <p>在既有服务器锁下装配救援工作线程、隔离浏览器及仅含元数据的日志。模型响应及终端命令正文绝不写入日志。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param operation operation / 操作
     * @return constructed or resolved browser recovery session / 构造或解析得到的浏览器恢复会话
     */
    private BrowserRecoverySession create(ServerProfile server, char[] master, Predicate<String> fingerprint, String operation) {
        return new BrowserRecoverySession(new PlaywrightTerminalSession(), ai,  () -> probe.check(server, master, fingerprint), new Consumer<>() {
            /**
             * Content identity used for independent verification.
             * <p>独立验证所用的内容身份。
             */
            private String digest = "";
            /**
             * Accepts anonymous.
             * <p>接受匿名。
             *
             * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
             */
            @Override public void accept(RecoverySnapshot snapshot) {
                try {
                    if (snapshot.action().isPresent()) digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(snapshot.action().orElseThrow().command().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    String event = snapshot.messageCode().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
                    persistence.recovery().save(snapshot.id(), server.id(), operation, snapshot.state().name(), digest, event);
                } catch (Exception failure) { throw new gold.debug.windowstolinux.app.service.failure.ApplicationServiceException(gold.debug.windowstolinux.shared.model.failure.FailureDescriptor.create(gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType.RECOVERY_JOURNAL_FAILED, gold.debug.windowstolinux.shared.model.failure.OperationIdentity.from(snapshot.id()), "Recovery journal could not be saved"), failure); }
            }
        }, server, master, locks.forServer(server.id()));
    }
    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     */
    @Override public void close() {
        var active = List.copyOf(sessions); active.forEach(BrowserRecoverySession::close); workers.shutdown();
        try {
            for (var session : active) session.awaitCleanup(20_000);
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        }
        catch (InterruptedException failure) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException failure) { workers.shutdownNow(); throw gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.create(gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType.RECOVERY_CLEANUP_FAILED, "Recovery workers did not finish cleanup", failure); }
    }
}
