package gold.debug.windowstolinux.app.service.recovery;

import java.util.*;
import java.util.function.*;

import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.linux.recovery.*;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.appapi.task.*;

/** Keeps an isolated browser in its original central task until physical and durable cleanup finish. / 将隔离浏览器保留在原中央任务，直到物理及持久清理完成。 */
public final class TaskBrowserSession implements BrowserTerminalSession {
    /** Exact host browser resource namespace. / 精确宿主浏览器资源命名空间。 */
    private static final String PREFIX = "browser-v1:";

    /** Original database and physical boot. / 原数据库及物理启动代次。 */
    private final PlatformDatabase database;

    /** Actual task authority. / 实际任务授权方。 */
    private final PlatformTaskRuntime tasks;

    /** Authentic original task, never replaced for cleanup. / 真实原任务，不为清理替换任务。 */
    private final TaskContext task;

    /** Actual task owner and release. / 实际任务所有者及发布版本。 */
    private final String owner, version;

    /** Original browser, retained when close fails. / 关闭失败时保留的原浏览器。 */
    private final PlaywrightTerminalSession browser;

    /** Claims for all actual launches, guarded independently of blocking cleanup. / 全部实际启动的申请，独立于阻塞清理加锁。 */
    private final List<Claim> claims = new ArrayList<>();

    /** Original claim and pre-creation identity. / 原申请及创建前身份。
     * @param resource exact physical resource / 精确物理资源
     * @param before original creation facts / 原创建事实
     * @param lease actual retained claim / 实际保留申请 */
    private record Claim(TaskResource resource, ProcessRecovery before, ResourceLease lease) {
    }

    /** Binds original task authority before registering privacy or creating a browser. / 登记隐私或创建浏览器前绑定原任务授权。
     * @param database actual platform engine / 实际平台引擎
     * @param tasks central runtime / 中央运行时
     * @param task authentic original task / 真实原任务
     * @param owner exact host-bound owner / 精确宿主绑定所有者
     * @param version actual owner release / 实际所有者发布版本
     * @param observeLock native privacy registration / 原生隐私登记
     * @throws ApiException if original task admission is unavailable / 原任务准入不可用时 */
    public TaskBrowserSession(PlatformDatabase database, PlatformTaskRuntime tasks, TaskContext task, String owner,
            String version, Function<Consumer<Boolean>, AutoCloseable> observeLock) throws ApiException {
        this.database = Objects.requireNonNull(database);
        this.tasks = Objects.requireNonNull(tasks);
        this.task = Objects.requireNonNull(task);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        tasks.requireExecuting(task, owner, version);
        browser = new PlaywrightTerminalSession(observeLock, new Launch());
    }

    /** Creates durable ownership on the browser's serial owner thread. / 在浏览器串行所有者线程创建持久归属。 */
    private final class Launch implements BrowserLaunchObserver {
        /** Claim for this exact physical launch. / 此精确物理启动的申请。 */
        private Claim current;

        /** Persists STARTING before Playwright or Chromium allocation. / 分配 Playwright 或 Chromium 前持久化 STARTING。
         * @throws ApiException if admission or registration fails / 准入或登记失败时 */
        @Override
        public void preparing() throws ApiException {
            tasks.requireExecuting(task, owner, version);
            var before = new ProcessRecovery(database.bootId(), UUID.randomUUID(), OptionalLong.empty(),
                    Optional.empty());
            var resource = new TaskResource("PROCESS", PREFIX + before.process(), owner, false);
            current = new Claim(resource, before, tasks.retain(task, resource, before.encode("browser.v1"), false));
            synchronized (claims) {
                claims.add(current);
            }
        }

        /** Confirms the exact observed PID before terminal access. / 访问终端前确认精确已观察 PID。
         * @param process actual original browser / 实际原浏览器
         * @throws Exception if original identity cannot be committed / 无法提交原身份时 */
        @Override
        public void started(BrowserProcess process) throws Exception {
            try {
                new TaskLedger(database).checkpointBrowser(task.id(), current.resource(), current.before(),
                        new ProcessRecovery(database.bootId(), current.before().process(),
                                OptionalLong.of(process.pid()), Optional.of(process.started())));
            } catch (Exception failure) {
                tasks.reportUncertainEffect(task);
                throw failure;
            }
            tasks.requireExecuting(task, owner, version);
        }
    }

    /** Rejects work after original admission, cancellation or callback authority ends. / 原准入、取消或回调授权结束后拒绝工作。 */
    private void requireExecuting() {
        try {
            tasks.requireExecuting(task, owner, version);
        } catch (ApiException failure) {
            throw new BrowserRecoveryException(BrowserRecoveryException.Reason.OPERATION_FAILED, failure);
        }
    }

    /** Opens only within the original executing task. / 仅在原执行任务内打开。 */
    @Override
    public void open() {
        requireExecuting();
        browser.open();
    }

    /** Checks availability without reading login contents. / 不读取登录内容地检查可用性。
     * @return actual browser availability / 实际浏览器可用性 */
    @Override
    public boolean available() {
        requireExecuting();
        return browser.available();
    }

    /** Enumerates only within original task authority. / 仅在原任务授权内枚举。
     * @return current terminal candidates / 当前终端候选 */
    @Override
    public List<TerminalTarget> targets() {
        requireExecuting();
        return browser.targets();
    }

    /** Binds one explicitly selected terminal. / 绑定一个明确选定的终端。
     * @param id original candidate identity / 原候选身份
     * @return original binding generation / 原绑定代次 */
    @Override
    public long bind(String id) {
        requireExecuting();
        return browser.bind(id);
    }

    /** Observes only the original selected terminal. / 仅观测原选定终端。
     * @param generation exact binding generation / 精确绑定代次
     * @return transient terminal evidence / 临时终端证据 */
    @Override
    public TerminalObservation observe(long generation) {
        requireExecuting();
        return browser.observe(generation);
    }

    /** Submits one independently approved command while the original task can execute. / 原任务可执行时提交一条独立批准命令。
     * @param generation exact binding generation / 精确绑定代次
     * @param command independently approved command / 独立批准命令 */
    @Override
    public void submit(long generation, String command) {
        requireExecuting();
        browser.submit(generation, command);
    }

    /** Verifies the binding within the original task. / 在原任务内核验绑定。
     * @param generation original generation / 原代次
     * @return whether the binding remains valid / 绑定是否仍有效 */
    @Override
    public boolean valid(long generation) {
        requireExecuting();
        return browser.valid(generation);
    }

    /** Revokes browser bindings even when cancellation has sealed new work. / 即使取消已封闭新工作也撤销浏览器绑定。 */
    @Override
    public void invalidate() {
        browser.invalidate();
    }

    /** Releases claims only after the original browser, worker and privacy registration are closed. / 原浏览器、工作线程及隐私登记关闭后才释放申请。
     * @throws BrowserRecoveryException if physical or durable cleanup remains incomplete / 物理或持久清理仍未完成时 */
    @Override
    public synchronized void close() {
        browser.close();
        synchronized (claims) {
            var remaining = claims.iterator();
            while (remaining.hasNext()) {
                try {
                    var claim = remaining.next();
                    new TaskLedger(database).confirmBrowserClosed(task.id(), claim.resource(), claim.before());
                    claim.lease().close();
                    remaining.remove();
                } catch (Exception failure) {
                    try {
                        tasks.reportUncertainEffect(task);
                    } catch (ApiException accounting) {
                        failure.addSuppressed(accounting);
                    }
                    throw new BrowserRecoveryException(BrowserRecoveryException.Reason.CLEANUP_FAILED, failure);
                }
            }
        }
    }

}
