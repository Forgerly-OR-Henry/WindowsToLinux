package gold.debug.windowstolinux.app.linux.ssh.recover.browser;

import java.util.*;
import java.util.function.*;

import gold.debug.windowstolinux.app.linux.ssh.recover.model.TerminalObservation;
import gold.debug.windowstolinux.app.linux.ssh.recover.model.TerminalTarget;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Keeps an isolated browser in its original central task until physical and durable cleanup finish. / 将隔离浏览器保留在原中央任务，直到物理及持久清理完成。 */
public final class TaskBrowserSession implements BrowserTerminalSession {
    /** Original task authority and durable browser receipts. / 原任务授权及持久浏览器凭据。 */
    private final Host host;

    /** Authentic original task, never replaced for cleanup. / 真实原任务，不为清理替换任务。 */
    private final TaskContext task;

    /** Actual task owner and release. / 实际任务所有者及发布版本。 */
    private final String owner, version;

    /** Original browser, retained when close fails. / 关闭失败时保留的原浏览器。 */
    private final PlaywrightTerminalSession browser;

    /** Claims for all actual launches, guarded independently of blocking cleanup. / 全部实际启动的申请，独立于阻塞清理加锁。 */
    private final List<Claim> claims = new ArrayList<>();

    /** Platform operations needed for this original browser. / 原浏览器需要的平台操作。 */
    public interface Host {
        /** Requires original executing authority. / 要求原执行授权。
         * @param task original task / 原任务
         * @param owner original owner / 原所有者
         * @param version original version / 原版本
         * @throws ApiException if authority ended / 授权已结束时 */
        void requireExecuting(TaskContext task, String owner, String version) throws ApiException;

        /** Retains a durable pre-creation receipt. / 保留持久创建前凭据。
         * @param task original task / 原任务
         * @param owner original owner / 原所有者
         * @return retained claim / 保留申请
         * @throws ApiException if admission fails / 准入失败时 */
        Claim retain(TaskContext task, String owner) throws ApiException;

        /** Preserves uncertain effects. / 保留不确定副作用。
         * @param task original task / 原任务
         * @throws ApiException if accounting fails / 记账失败时 */
        void uncertain(TaskContext task) throws ApiException;
    }

    /** Exact original browser receipt, independent of the physical close. / 精确原浏览器凭据，独立于物理关闭。 */
    public interface Claim extends AutoCloseable {
        /** Commits the observed process before terminal use. / 在终端使用前提交已观察进程。
         * @param process original physical browser / 原物理浏览器
         * @throws Exception if the receipt cannot commit / 凭据无法提交时 */
        void started(BrowserProcess process) throws Exception;

        /** Confirms physical closure and releases the same claim. / 确认物理关闭并释放同一申请。
         * @throws Exception if release cannot commit / 释放无法提交时 */
        @Override
        void close() throws Exception;
    }

    /** Binds original task authority before registering privacy or creating a browser. / 登记隐私或创建浏览器前绑定原任务授权。
     * @param host original authority and receipts / 原授权及凭据
     * @param task authentic original task / 真实原任务
     * @param owner exact host-bound owner / 精确宿主绑定所有者
     * @param version actual owner release / 实际所有者发布版本
     * @param observeLock native privacy registration / 原生隐私登记
     * @throws ApiException if original task admission is unavailable / 原任务准入不可用时 */
    public TaskBrowserSession(Host host, TaskContext task, String owner,
                              String version, Function<Consumer<Boolean>, AutoCloseable> observeLock) throws ApiException {
        this.host = Objects.requireNonNull(host);
        this.task = Objects.requireNonNull(task);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
        host.requireExecuting(task, owner, version);
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
            host.requireExecuting(task, owner, version);
            current = host.retain(task, owner);
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
                current.started(process);
            } catch (Exception failure) {
                host.uncertain(task);
                throw failure;
            }
            host.requireExecuting(task, owner, version);
        }
    }

    /** Rejects work after original admission, cancellation or callback authority ends. / 原准入、取消或回调授权结束后拒绝工作。 */
    private void requireExecuting() {
        try {
            host.requireExecuting(task, owner, version);
        } catch (ApiException failure) {
            throw new BrowserRecoveryException(BrowserRecoveryException.ReasonType.OPERATION_FAILED, failure);
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
                    claim.close();
                    remaining.remove();
                } catch (Exception failure) {
                    try {
                        host.uncertain(task);
                    } catch (ApiException accounting) {
                        failure.addSuppressed(accounting);
                    }
                    throw new BrowserRecoveryException(BrowserRecoveryException.ReasonType.CLEANUP_FAILED, failure);
                }
            }
        }
    }

}
