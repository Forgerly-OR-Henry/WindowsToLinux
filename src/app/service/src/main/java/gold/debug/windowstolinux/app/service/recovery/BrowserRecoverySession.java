package gold.debug.windowstolinux.app.service.recovery;

import gold.debug.windowstolinux.app.service.ai.RecoveryAiUseCase;
import gold.debug.windowstolinux.app.service.contract.SshRecoverySession;
import gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.windows.recovery.BrowserTerminalSession;
import gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient;
import gold.debug.windowstolinux.shared.model.recovery.*;
import gold.debug.windowstolinux.app.service.failure.*;
import gold.debug.windowstolinux.app.windows.recovery.BrowserRecoveryFailureType;
import gold.debug.windowstolinux.shared.model.failure.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import static gold.debug.windowstolinux.app.service.contract.definition.RecoverySnapshot.State.*;

/**
 * Serial service state machine with revocable exact-action approval. / 带可撤销精确动作确认的串行服务状态机。
 */
public final class BrowserRecoverySession implements SshRecoverySession, Runnable {
    /**
     * Current lifecycle or workflow state.
     * <p>当前生命周期或工作流状态。
     */
    private final RecoverySessionState state = new RecoverySessionState();
    /**
     * Browser.
     * <p>浏览器。
     */
    private final BrowserTerminalSession browser;
    /**
     * The supplied recovery ai use case.
     * <p>所提供的恢复AI用例。
     */
    private final RecoveryAiUseCase ai;

    /**
     * Probe.
     * <p>探测。
     */
    private final Supplier<RecoveryProbeResult> probe;
    /**
     * Journal.
     * <p>日志。
     */
    private final Consumer<RecoverySnapshot> journal;
    /**
     * Server identity or selected server configuration.
     * <p>服务器身份或所选服务器配置。
     */
    private final ServerProfile server;
    /**
     * Master-password buffer used for the scoped secret operation.
     * <p>限定秘密操作使用的主密码缓冲区。
     */
    private final char[] master;
    /**
     * Lock.
     * <p>锁。
     */
    private final ReentrantLock lock;
    /**
     * Clock.
     * <p>时钟。
     */
    private final LongSupplier clock;
    /**
     * Requests.
     * <p>请求集合。
     */
    private final BlockingQueue<Runnable> requests = new LinkedBlockingQueue<>(32);
    /**
     * Authorization.
     * <p>授权。
     */
    private final AtomicLong authorization = new AtomicLong();
    /**
     * Completion.
     * <p>完成。
     */
    private final CompletableFuture<RecoverySnapshot> completion = new CompletableFuture<>();
    /**
     * Immutable observation or configuration revision used by the operation.
     * <p>操作使用的不可变观测或配置修订。
     */
    private volatile RecoverySnapshot snapshot = state.snapshot();
    /**
     * Stop.
     * <p>停止。
     */
    private volatile boolean stop;
    /**
     * Handoff.
     * <p>交接。
     */
    private volatile boolean handoff = true;
    /**
     * Runner.
     * <p>执行线程。
     */
    private volatile Thread runner;

    /**
     * Initializes browser recovery session through its shared constructor contract.
     * <p>通过共享构造契约初始化浏览器恢复会话。
     *
     * @param browser browser / 浏览器
     * @param ai the supplied recovery ai use case / 所提供的恢复AI用例
     * @param probe probe / 探测
     * @param journal journal / 日志
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param lock lock / 锁
     */
    BrowserRecoverySession(BrowserTerminalSession browser, RecoveryAiUseCase ai,
            Supplier<RecoveryProbeResult> probe, Consumer<RecoverySnapshot> journal, ServerProfile server, char[] master, ReentrantLock lock) {
        this(browser, ai, probe, journal, server, master, lock, System::nanoTime);
    }
    /**
     * Binds the supplied dependencies and state for browser recovery session.
     * <p>为浏览器恢复会话绑定传入的依赖及状态。
     *
     * @param browser browser / 浏览器
     * @param ai the supplied recovery ai use case / 所提供的恢复AI用例
     * @param probe probe / 探测
     * @param journal journal / 日志
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param lock lock / 锁
     * @param clock clock / 时钟
     */
    BrowserRecoverySession(BrowserTerminalSession browser, RecoveryAiUseCase ai,
            Supplier<RecoveryProbeResult> probe, Consumer<RecoverySnapshot> journal, ServerProfile server, char[] master,
            ReentrantLock lock, LongSupplier clock) {
        this.clock = clock;
        this.browser = browser; this.ai = ai; this.probe = probe;
        this.journal = journal; this.server = server; this.master = master; this.lock = lock;
    }
    /**
     * Returns immutable observation or configuration revision used by the operation.
     * <p>返回操作使用的不可变观测或配置修订。
     *
     * @return immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     */
    @Override public RecoverySnapshot snapshot() { return snapshot; }
    /**
     * Returns terminals.
     * <p>返回终端集合。
     *
     * @return terminals / 终端集合
     */
    @Override public List<TerminalTarget> terminals() {
        if (!handoff || snapshot.ended()) return List.of();
        return browser.targets();
    }
    /**
     * Binds terminal.
     * <p>绑定终端。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param consent consent / 同意
     * @param reconciled reconciled / 已核对
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override public void bindTerminal(String target, String serverId, boolean consent, boolean reconciled) {
        if (!server.id().equals(serverId) || !consent) throw new IllegalArgumentException("terminal-consent-required");
        long epoch = authorization.incrementAndGet();
        enqueue(() -> {
            if (!handoff || epoch != authorization.get() || (state.unknown && !reconciled)) return;
            state.generation = browser.bind(target); state.bound = true; state.unknown = false;
            state.marker = ""; state.revoke(); handoff = false; change(OBSERVING, "observing");
        });
    }
    /**
     * Confirms explicit action selected for the current target.
     * <p>确认为当前目标显式选择的动作。
     *
     * @param token token / 令牌
     * @param impact impact / 影响
     */
    @Override public void confirmAction(String token, boolean impact) {
        long epoch = authorization.get();
        enqueue(() -> {
            if (handoff || state.phase != AWAITING_CONFIRMATION || !state.token.equals(token)
                    || epoch != authorization.get() || (state.action.highImpact() && !impact)) return;
            execute(epoch);
        });
    }
    /**
     * Revokes authorization immediately and queues browser invalidation followed by the paused state.
     * <p>立即撤销授权，并将浏览器失效及转入暂停状态加入队列。
     */
    @Override public void pause() {
        handoff = true; authorization.incrementAndGet();
        enqueue(() -> { browser.invalidate(); state.bound = false; state.revoke(); change(PAUSED, "paused"); });
    }
    /**
     * Revokes prior authorization and queues a fresh browser handoff with reset decision and active-time budgets.
     * <p>撤销此前授权，并将新的浏览器交接加入队列，同时重置决策及活跃时间预算。
     */
    @Override public void resume() {
        handoff = true; authorization.incrementAndGet();
        enqueue(() -> {
            browser.invalidate(); browser.open(); state.bound = false; state.revoke();
            state.decisions = 0; state.activeNanos = 0; change(WAITING_TERMINAL, "handoff");
        });
    }
    /**
     * Closes the resources owned by this instance and completes its cleanup boundary.
     * <p>关闭当前实例持有的资源并完成其清理边界。
     */
    @Override public void close() {
        handoff = true; authorization.incrementAndGet(); stop = true;
        Thread current = runner; if (current != null && current != Thread.currentThread()) current.interrupt();
    }
    /**
     * Waits for resource cleanup as well as the final state. / 同时等待最终状态和资源清理。
     *
     * @return constructed or resolved recovery snapshot / 构造或解析得到的恢复快照
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     * @throws ExecutionException if the awaited worker completed exceptionally / 所等待的工作线程异常结束时
     */
    public RecoverySnapshot await() throws InterruptedException, ExecutionException { return completion.get(); }
    /**
     * Bounds shutdown waits without declaring an unfinished command cancelled. / 限制退出等待，不宣称未完成的命令已取消。
     *
     * @param milliseconds milliseconds / 毫秒
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     * @throws ExecutionException if the awaited worker completed exceptionally / 所等待的工作线程异常结束时
     * @throws TimeoutException if the completion deadline expires / 完成期限届满时
     */
    public void awaitCleanup(long milliseconds) throws InterruptedException, ExecutionException, TimeoutException {
        completion.get(milliseconds, TimeUnit.MILLISECONDS);
    }

    /**
     * Owns the serial recovery state machine under the server mutex. Restricts retries to typed connection failures and always revokes actions, clears the master password and waits for browser cleanup before completing the session.
     * <p>在服务器互斥下持有串行救援状态机。仅重试类型化连接失败，并在会话完成前始终撤销动作、清空主密码及等待浏览器清理。
     */
    @Override public void run() {
        runner = Thread.currentThread();
        boolean acquired = lock.tryLock();
        try {
            if (stop) { change(CANCELLED, "cancelled"); return; }
            if (!acquired) { change(FAILED, "serverBusy"); return; }
            RecoveryProbeResult initial = probe.get(); state.failure = initial.failure().orElse(null);
            if (initial.status() == RecoveryProbeResult.StatusType.RECOVERED) { change(RECOVERED, "recovered"); return; }
            if (initial.status() != RecoveryProbeResult.StatusType.RETRYABLE_CONNECTION) {
                change(initial.status() == RecoveryProbeResult.StatusType.INTERRUPTED ? CANCELLED : FAILED, initial.messageCode()); return;
            }
            // A bounded second attempt precedes starting an independent control channel. / 在启动独立控制通道前先进行一次有界的第二次尝试。
            if (checkSsh()) return;
            if (state.phase == INTERRUPTED) { change(FAILED, state.code); return; }
            browser.open(); change(WAITING_TERMINAL, "handoff");
            while (!stop && !snapshot.ended()) tick();
            if (stop && !snapshot.ended()) change(CANCELLED, state.unknown ? "resultUnknown" : "cancelled");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); recordFailure(failure, ApplicationServiceFailureType.RECOVERY_INTERRUPTED); change(CANCELLED, "cancelled");
        } catch (Exception failure) {
            recordFailure(failure, BrowserRecoveryFailureType.OPERATION_FAILED);
            change(Thread.currentThread().isInterrupted() ? CANCELLED : FAILED, state.unknown ? "resultUnknown" : "browserUnavailable");
        }
        finally {
            requests.clear(); state.revoke(); Arrays.fill(master, '\0');
            try { browser.close(); }
            catch (RuntimeException failure) { recordFailure(failure, BrowserRecoveryFailureType.CLEANUP_FAILED); change(FAILED, "cleanupIncomplete"); }
            finally { if (acquired) lock.unlock(); completion.complete(snapshot); runner = null; }
        }
    }
    /**
     * Processes one queued request, due SSH probe or active recovery step while accounting for elapsed active time.
     * <p>处理一个队列请求、到期 SSH 探测或活跃恢复步骤，并累计活跃耗时。
     *
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    private void tick() throws InterruptedException {
        long start = clock.getAsLong(); boolean activeBefore = state.active();
        Runnable request = requests.poll(300, TimeUnit.MILLISECONDS);
        if (clock.getAsLong() >= state.nextProbe && checkSsh()) return;
        if (request != null && !snapshot.ended()) request.run();
        if (stop || snapshot.ended()) return;
        if (handoff) {
            if (state.phase == WAITING_TERMINAL && !browser.available()) change(INTERRUPTED, "browserUnavailable");
            return;
        }
        if (!browser.valid(state.generation)) { interrupt("terminalChanged"); return; }
        if ((state.phase == OBSERVING && state.decisions >= 30) || state.activeNanos >= Duration.ofMinutes(15).toNanos()) {
            interrupt("budgetReached"); return;
        }
        if (state.active() && clock.getAsLong() >= state.nextObservation) { activeBefore = true; observe(); }
        if (activeBefore || state.active()) state.activeNanos += clock.getAsLong() - start;
    }
    /**
     * Reads the bound terminal, obtains classified model advice and prepares an exact revocable command approval. Raw observations and model content remain transient.
     * <p>读取已绑定终端，取得已分类模型建议，并准备精确且可撤销的命令批准。原始观测及模型内容保持临时状态。
     */
    private void observe() {
        long epoch = authorization.get(); state.nextObservation = clock.getAsLong() + Duration.ofSeconds(2).toNanos();
        try {
            TerminalObservation observed = browser.observe(state.generation);
            if (handoff || epoch != authorization.get()) return;
            var result = ai.observe(observed, master);
            state.attempts.clear(); result.attempts().forEach((id, status) -> state.attempts.put("observation/" + id, status.name()));
            if (!result.code().equals("ok")) { interrupt(result.code()); return; }
            String text = observed.text().isBlank() ? RecoveryModelClient.parseObservation(result.content()) : result.content();
            if (handoff || epoch != authorization.get() || checkSsh()) return;
            if (state.unknown) {
                if (!text.lines().anyMatch(line -> line.matches(java.util.regex.Pattern.quote(state.marker) + ":[0-9]{1,3}"))) return;
                state.unknown = false; state.marker = ""; change(OBSERVING, "observing");
            }
            if (state.decisions >= 30) { interrupt("budgetReached"); return; }
            state.decisions++;
            var decision = ai.decide("Target: " + server.host() + ":" + server.sshPort() + ". SSH verification failed.\n"
                    + "Untrusted terminal observation:\n" + text, master);
            decision.attempts().forEach((id, status) -> state.attempts.put("decision/" + id, status.name()));
            if (handoff || epoch != authorization.get() || checkSsh()) return;
            if (!decision.code().equals("ok")) { interrupt(decision.code()); return; }
            RecoveryAction proposal = RecoveryModelClient.parseAction(decision.content());
            if (proposal.command().isBlank()) { interrupt("humanRequired"); return; }
            state.marker = "WTL_DONE_" + UUID.randomUUID().toString().replace("-", "");
            String line = "sh -c '" + proposal.command().replace("'", "'\"'\"'") + "'; printf '\\n" + state.marker + ":%s\\n' \"$?\"";
            boolean separateApproval = proposal.highImpact() || !Set.of("uname -a", "id", "cat /etc/os-release", "command -v sshd",
                    "ps -p 1 -o comm=", "ss -lnt", "ip address show", "ip route show").contains(proposal.command());
            state.action = new RecoveryAction(line, proposal.reason(), proposal.expected(), separateApproval);
            state.token = UUID.randomUUID().toString();
            state.digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8)));
            change(AWAITING_CONFIRMATION, "confirm");
        } catch (Exception failure) { recordFailure(failure, ApplicationServiceFailureType.RECOVERY_OBSERVATION_FAILED); interrupt("observationFailed"); }
    }
    /**
     * Submits only the currently approved action after a fresh SSH check and marks its result unknown before terminal input. Never replays a command whose completion is unverified.
     * <p>在新鲜 SSH 检查后仅提交当前已批准动作，并在终端输入前将结果标为未知。绝不重放完成状态未验证的命令。
     *
     * @param epoch epoch / 代次
     */
    private void execute(long epoch) {
        if (state.activeNanos >= Duration.ofMinutes(15).toNanos()) { interrupt("budgetReached"); return; }
        if (checkSsh() || handoff || epoch != authorization.get()) return;
        String command = state.action.command(); state.revoke();
        change(EXECUTING, "executing");
        try {
            if (stop || handoff || epoch != authorization.get()) { interrupt("paused"); return; }
            state.unknown = true; publish("submitted");
            browser.submit(state.generation, command);
            checkSsh();
        } catch (RuntimeException failure) { recordFailure(failure, BrowserRecoveryFailureType.OPERATION_FAILED); interrupt("resultUnknown"); }
    }
    /**
     * Rechecks SSH and preserves the original failure descriptor. Verified recovery revokes pending actions; interruption ends the session, while other non-retryable failures return control to the user.
     * <p>复核 SSH 并保留原始失败描述。已验证恢复会撤销待执行动作；中断结束会话，其他不可重试失败将控制交还用户。
     *
     * @return true when SSH recovery or cancellation ends further work; false otherwise / SSH 恢复或取消终止后续工作时为 true，否则为 false
     */
    private boolean checkSsh() {
        state.nextProbe = clock.getAsLong() + Duration.ofSeconds(10).toNanos();
        RecoveryProbeResult result = probe.get();
        if (result.status() == RecoveryProbeResult.StatusType.RECOVERED) {
            state.failure = null;
            if (state.unknown && state.bound) {
                try {
                    String text = browser.observe(state.generation).text();
                    if (text.lines().anyMatch(line -> line.matches(java.util.regex.Pattern.quote(state.marker) + ":[0-9]{1,3}"))) state.unknown = false;
                } catch (RuntimeException failure) {
                    recordFailure(failure, BrowserRecoveryFailureType.OBSERVATION_FAILED);
                    // Missing completion evidence remains unknown and must not be replayed. / 缺少完成证据仍为未知，不得重放。
                }
            }
            handoff = true; authorization.incrementAndGet(); requests.clear(); state.revoke();
            change(RECOVERED, state.unknown ? "recoveredUnknown" : "recovered"); return true;
        }
        state.failure = result.failure().orElse(null);
        if (result.status() == RecoveryProbeResult.StatusType.INTERRUPTED) {
            handoff = true; state.revoke(); change(CANCELLED, result.messageCode()); return true;
        }
        if (result.status() != RecoveryProbeResult.StatusType.RETRYABLE_CONNECTION) { interrupt(result.messageCode()); }
        return false;
    }
    /**
     * Revokes pending authority, invalidates the terminal binding and publishes the interrupted state.
     * <p>撤销待执行授权、使终端绑定失效，并发布中断状态。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     */
    private void interrupt(String code) {
        handoff = true; authorization.incrementAndGet(); state.revoke(); state.bound = false;
        browser.invalidate(); change(INTERRUPTED, code);
    }
    /**
     * Queues browser recovery session.
     * <p>加入队列：浏览器恢复会话。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private void enqueue(Runnable request) { if (!snapshot.ended() && !stop && !requests.offer(request)) throw new IllegalStateException("recovery-busy"); }
    /**
     * Changes the lifecycle phase and safe message code, then publishes the new snapshot.
     * <p>变更生命周期阶段及安全消息码，随后发布新快照。
     *
     * @param phase stage associated with the result or failure / 结果或失败所属阶段
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     */
    private void change(RecoverySnapshot.State phase, String code) { state.phase = phase; state.code = code; publish(code); }
    /**
     * Publishes a transient snapshot and records only the journal's approved metadata. Journal failure ends the session with a persistence-owned descriptor and never recursively retries the failed journal write.
     * <p>发布临时快照并仅记录日志契约批准的元数据。日志失败以持久化模块持有的描述结束会话，绝不递归重试已失败的日志写入。
     *
     * @param code stable machine-readable classification code / 稳定的机器可读分类码
     */
    private void publish(String code) {
        snapshot = state.snapshot();
        try { journal.accept(snapshot); }
        catch (RuntimeException failure) {
            recordFailure(failure, gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType.RECOVERY_JOURNAL_FAILED);
            handoff = true; authorization.incrementAndGet(); state.revoke(); state.phase = FAILED; state.code = "journalUnavailable";
            snapshot = state.snapshot();
        }
    }
    /**
     * Retains an existing structured cause unchanged; otherwise creates a bounded module-owned descriptor without copying exception text or terminal contents.
     * <p>原样保留既有结构化原因；否则创建有界的模块自有描述，不复制异常文本或终端内容。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @param fallback fallback / 回退
     */
    private void recordFailure(Throwable exception, FailureDefinition fallback) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof FailureCarrier carrier) { state.failure = carrier.failure(); return; }
        }
        state.failure = FailureDescriptor.create(fallback, OperationIdentity.from(state.id), "Recovery stage failed without verified completion");
    }
}
