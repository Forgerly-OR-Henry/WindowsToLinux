package gold.debug.windowstolinux.app.main.start;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Callable;

import gold.debug.windowstolinux.app.ui.window.PlatformFrame;

/** Owns one guarded fresh-process restart and its one-use startup acknowledgement. / 持有一次受门禁保护的新进程重启及一次性启动确认。 */
final class ApplicationRestart implements PlatformFrame.RestartActions {
    /** Revalidated launch evidence, never old process arguments. / 重验启动证据，从不采用旧进程参数。 */
    private final Callable<ApplicationRestartLaunch> launch;

    /** Exact runtime close gate, also checked immediately before process creation. / 精确运行时关闭门禁，创建进程前再次检查。 */
    private final AutoCloseable shutdown;

    /** Owned temporary parent. / 所属临时父目录。 */
    private final Path temporary;

    /** Exact process creation seam used by deterministic tests. / 确定性测试使用的精确进程创建接缝。 */
    private final Starter starter;

    /** Current prepared launch. / 当前已准备启动。 */
    private ApplicationRestartLaunch prepared;

    /** Current original acknowledgement ownership. / 当前原确认归属。 */
    private ApplicationRestartReceipt receipt;

    /** Original child, never looked up by a copied PID. / 原子进程，从不通过复制 PID 查找。 */
    private Process child;

    /** Current observed readiness. / 当前已观察就绪状态。 */
    private PlatformFrame.RestartStatus state = PlatformFrame.RestartStatus.WAITING;

    /** Captures actual platform ownership without doing I/O on the EDT. / 捕获实际平台归属，不在 EDT 执行 I/O。
     * @param runtime original runtime / 原运行时 */
    ApplicationRestart(PlatformRuntime runtime) {
        this(() -> ApplicationRestartLaunch.capture(runtime.startup().layout()), runtime,
            Path.of(System.getProperty("java.io.tmpdir")), ProcessBuilder::start);
    }

    /** Binds explicit testable collaborators without weakening the production capture. / 绑定可测试的明确协作对象，不削弱生产捕获。
     * @param launch actual entry supplier / 实际入口提供方
     * @param shutdown original close gate / 原关闭门禁
     * @param temporary temporary root / 临时根
     * @param starter original process creator / 原进程创建器 */
    ApplicationRestart(Callable<ApplicationRestartLaunch> launch, AutoCloseable shutdown, Path temporary,
                       Starter starter) {
        this.launch = Objects.requireNonNull(launch);
        this.shutdown = Objects.requireNonNull(shutdown);
        this.temporary = Objects.requireNonNull(temporary);
        this.starter = Objects.requireNonNull(starter);
    }

    /** Validates the exact entry before any shutdown and prepares owned temporary inputs. / 关闭前验证精确入口并准备所属临时输入。
     * @throws Exception if a child is active or launch inputs are invalid / 子进程活动或启动输入无效时 */
    @Override
    public synchronized void prepare() throws Exception {
        if (child != null && child.isAlive()) throw new IOException("A restart process is still active");
        close();
        child = null;
        prepared = launch.call();
        receipt = ApplicationRestartReceipt.create(temporary);
        try {
            if (prepared.javaLauncher()) {
                var arguments = new ArrayList<>(prepared.arguments());
                arguments.addAll(receipt.arguments());
                var text = new StringBuilder();
                arguments.forEach(value -> text.append(ApplicationRestartLaunch.quote(value)).append('\n'));
                Files.writeString(receipt.argumentFile(), text.toString(), StandardOpenOption.CREATE_NEW);
            }
            state = PlatformFrame.RestartStatus.WAITING;
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    /** Starts only after the exact runtime has really closed. / 仅在精确运行时确实关闭后启动。
     * @throws Exception if closing or creating this process fails / 关闭或创建此进程失败时 */
    @Override
    public synchronized void start() throws Exception {
        if (child != null) throw new IOException("Prepare a new restart after the original process ends");
        if (receipt == null || prepared == null) throw new IOException("Restart inputs have not been prepared");
        shutdown.close();
        var command = new ArrayList<String>();
        command.add(prepared.executable().toString());
        if (prepared.javaLauncher()) {
            command.add("@" + receipt.argumentFile());
        } else {
            command.addAll(prepared.arguments());
            command.addAll(receipt.arguments());
        }
        var process = new ProcessBuilder(command).directory(prepared.directory().toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        for (String key : new ArrayList<>(process.environment().keySet()))
            if (java.util.List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").stream()
                .anyMatch(key::equalsIgnoreCase)) process.environment().remove(key);
        child = starter.start(process);
        state = PlatformFrame.RestartStatus.WAITING;
    }

    /** Observes the original child and verified terminal receipt without relaunching. / 观察原子进程及已验证终态回执，不重新启动。
     * @return current readiness / 当前就绪状态
     * @throws Exception if the original acknowledgement is invalid / 原确认无效时 */
    @Override
    public synchronized PlatformFrame.RestartStatus status() throws Exception {
        if (state != PlatformFrame.RestartStatus.WAITING) return state;
        if (child == null || receipt == null) return state;
        var result = receipt.result();
        if (result.isPresent()) {
            state = child.isAlive() ? PlatformFrame.RestartStatus.valueOf(result.get()) : PlatformFrame.RestartStatus.FAILED;
            receipt.cleanup();
            receipt = null;
        } else if (!child.isAlive()) {
            state = PlatformFrame.RestartStatus.FAILED;
            receipt.cleanup();
            receipt = null;
        }
        return state;
    }

    /** Allows a retry only when no original child can still become ready. / 仅在原子进程不可能继续就绪时允许重试。
     * @return whether process creation may be retried / 是否可重试创建进程 */
    @Override
    public synchronized boolean retryable() {
        return child == null || !child.isAlive();
    }

    /** Cleans inactive or consumed inputs without killing a still-starting application. / 清理非活动或已消费输入，不终止仍在启动的应用。
     * @throws Exception if original cleanup cannot be confirmed / 无法确认原清理时 */
    @Override
    public synchronized void close() throws Exception {
        if (child != null && child.isAlive() && state == PlatformFrame.RestartStatus.WAITING) return;
        if (receipt != null) receipt.cleanup();
        receipt = null;
        prepared = null;
    }

    /** Creates exactly one detached application process. / 精确创建一个独立应用进程。 */
    @FunctionalInterface
    interface Starter {
        /** Starts the supplied literal process request. / 启动给定字面进程请求。
         * @param builder exact request / 精确请求
         * @return original process / 原进程
         * @throws IOException if process creation fails / 创建进程失败时 */
        Process start(ProcessBuilder builder) throws IOException;
    }
}
