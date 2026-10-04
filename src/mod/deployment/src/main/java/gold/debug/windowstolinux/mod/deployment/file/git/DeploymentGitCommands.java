package gold.debug.windowstolinux.mod.deployment.file.git;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Runs fixed Git argv inside the worker and waits for the original process, observed descendants and pipes. / 在 worker 内运行固定 Git 参数并等待原进程、已观察后代及管道退出。 */
final class DeploymentGitCommands {
    /** Exact native Git executable. / 精确原生 Git 可执行文件。 */
    private final Path executable;

    /** Owned checkout directory. / 所属检出目录。 */
    private final Path checkout;

    /** Isolated configuration and temporary directory. / 隔离配置及临时目录。 */
    private final Path control;

    /** Cooperative stop signal. / 协作停止信号。 */
    private final BooleanSupplier cancelled;

    /** Whether local transport was explicitly accepted. / 是否明确接纳本地传输。 */
    private final boolean local;

    /** Binds exact local tools and owned directories. / 绑定精确本机工具及所属目录。
     * @param executable native Git / 原生 Git
     * @param checkout fresh checkout / 新检出目录
     * @param control private control files / 私有控制文件
     * @param cancelled cooperative cancellation / 协作取消
     * @param local explicit local permission / 明确本地许可 */
    DeploymentGitCommands(Path executable, Path checkout, Path control, BooleanSupplier cancelled, boolean local) {
        this.executable = executable;
        this.checkout = checkout;
        this.control = control;
        this.cancelled = cancelled;
        this.local = local;
    }

    /** Executes one fixed command with bounded capture and a two-minute observation deadline. / 使用有界捕获及两分钟观察期限执行一个固定命令。
     * @param arguments literal native arguments / 字面原生参数
     * @param maximum maximum captured bytes / 最大捕获字节
     * @return exact successful output / 精确成功输出
     * @throws ApiException if cancelled, unavailable, oversized or unsuccessful / 取消、不可用、超限或不成功时 */
    byte[] run(List<String> arguments, int maximum) throws ApiException {
        if (cancelled.getAsBoolean())
            throw problem(ProblemCode.CANCELLED, "DEPLOYMENT_GIT_CANCELLED");
        var command = configured(arguments);
        Process process;
        try {
            var builder = new ProcessBuilder(command).directory(checkout.toFile()).redirectErrorStream(true);
            environment(builder.environment());
            process = builder.start();
        } catch (IOException failure) {
            throw problem(ProblemCode.UNAVAILABLE, "DEPLOYMENT_GIT_TOOL");
        }
        var output = new Capture(process.getInputStream(), maximum);
        var reader = Thread.ofVirtual().name("deployment-git-output").start(output);
        var observed = observe(process, reader);
        if (process.exitValue() < 0)
            throw problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GIT_NATIVE_CRASH");
        if (cancelled.getAsBoolean() || observed.interrupted())
            throw problem(ProblemCode.CANCELLED, "DEPLOYMENT_GIT_CANCELLED");
        if (observed.timedOut())
            throw problem(ProblemCode.TIMEOUT, "DEPLOYMENT_GIT_TIMEOUT");
        if (observed.inputCloseFailed() || output.failure != null || output.exceeded)
            throw problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_GIT_OUTPUT");
        byte[] bytes = output.bytes.toByteArray();
        if (process.exitValue() != 0)
            throw problem(ProblemCode.IO_FAILURE,
                transientFailure(bytes) ? "DEPLOYMENT_GIT_TRANSIENT" : "DEPLOYMENT_GIT_COMMAND");
        return bytes;
    }

    /** Drains the exact child, observed descendants and all pipes before returning. / 返回前排空精确子进程、已观察后代及全部管道。
     * @param process original native process / 原原生进程
     * @param reader original pipe reader / 原管道读取线程
     * @return physical observation flags / 物理观察标志
     * @throws ApiException if pipe closure cannot be proved / 无法证明管道关闭时 */
    private static Observation observe(Process process, Thread reader) throws ApiException {
        var children = new LinkedHashSet<ProcessHandle>();
        boolean timedOut = false, interrupted = false, inputCloseFailed = false;
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        try {
            try {
                process.getOutputStream().close();
            } catch (IOException failure) {
                inputCloseFailed = true;
            }
            while (process.isAlive()) {
                children.addAll(process.descendants().toList());
                timedOut |= System.nanoTime() >= deadline;
                try {
                    process.waitFor(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException signal) {
                    interrupted = true;
                }
            }
        } finally {
            while (process.isAlive()) {
                children.addAll(process.descendants().toList());
                try {
                    process.waitFor(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException signal) {
                    interrupted = true;
                }
            }
            children.addAll(process.descendants().toList());
            for (var child : children)
                while (child.isAlive()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException signal) {
                        interrupted = true;
                    }
                }
            while (reader.isAlive()) {
                try {
                    reader.join(100);
                } catch (InterruptedException signal) {
                    interrupted = true;
                }
            }
            try {
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            } catch (IOException failure) {
                throw problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GIT_PIPE_CLOSE");
            } finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }
        return new Observation(timedOut, interrupted, inputCloseFailed);
    }

    /** Physical observation independent of a command's business exit code. / 独立于命令业务退出码的物理观察。
     * @param timedOut observation deadline crossed / 已跨过观察期限
     * @param interrupted wait was interrupted / 等待被中断
     * @param inputCloseFailed stdin closure failed / 标准输入关闭失败 */
    private record Observation(boolean timedOut, boolean interrupted, boolean inputCloseFailed) {
    }

    /** Disables ambient helpers, hooks, automatic maintenance and unreviewed transports. / 禁用环境助手、Hook、自动维护及未审阅传输。
     * @param arguments exact operation arguments / 精确操作参数
     * @return literal native argv / 字面原生参数 */
    private List<String> configured(List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(executable.toString());
        for (String setting : List.of("core.hooksPath=" + control.resolve("hooks"), "core.autocrlf=false",
            "core.longpaths=true", "core.symlinks=false", "credential.helper=", "credential.interactive=false",
            "core.askPass=", "protocol.allow=never", "protocol.https.allow=always", "protocol.ssh.allow=never",
            "protocol.file.allow=" + (local ? "always" : "never"), "http.followRedirects=false",
            "http.sslBackend=openssl", "http.lowSpeedLimit=1", "http.lowSpeedTime=120", "gc.auto=0",
            "maintenance.auto=false", "submodule.recurse=false", "filter.lfs.smudge=", "filter.lfs.process=",
            "filter.lfs.required=false")) {
            command.add("-c");
            command.add(setting);
        }
        command.addAll(arguments);
        return List.copyOf(command);
    }

    /** Supplies only controlled local environment and noninteractive Git policy. / 仅提供受控本地环境及非交互 Git 策略。
     * @param environment native mutable environment / 原生可变环境 */
    private void environment(Map<String, String> environment) {
        environment.clear();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot != null)
            environment.put("SystemRoot", systemRoot);
        environment.put("PATH", executable.getParent().toString());
        environment.put("HOME", control.toString());
        environment.put("USERPROFILE", control.toString());
        environment.put("TEMP", control.toString());
        environment.put("TMP", control.toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_ATTR_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", control.resolve("empty-config").toString());
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
    }

    /** Classifies only the original known transient diagnostic patterns without publishing output. / 仅按原已知暂时诊断模式分类，不公开输出。
     * @param output bounded native diagnostic / 有界原生诊断
     * @return whether a read-only fetch may retry / 只读抓取是否可重试 */
    private static boolean transientFailure(byte[] output) {
        String text = new String(output, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return text.contains("could not resolve host") || text.contains("failed to connect")
            || text.contains("connection reset") || text.contains("network is unreachable")
            || text.contains("remote end hung up") || text.contains("connection timed out")
            || text.contains("operation timed out") || text.contains("temporary failure in name resolution");
    }

    /** Creates a stable safe failure without command output or paths. / 创建不含命令输出或路径的稳定安全失败。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return public failure / 公开失败 */
    static ApiException problem(ProblemCode code, String event) {
        return new ApiException(
            new Problem(code, event, "Git source operation did not complete", Map.of(), Optional.empty()));
    }

    /** Continuously drains native output while retaining only the bounded prefix. / 持续排空原生输出，仅保留有界前缀。 */
    private static final class Capture implements Runnable {
        /** Original pipe. / 原管道。 */
        private final InputStream input;

        /** Maximum retained bytes. / 最大保留字节。 */
        private final int maximum;

        /** Bounded retained prefix. / 有界保留前缀。 */
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /** Actual read failure. / 实际读取失败。 */
        private IOException failure;

        /** Whether output exceeded the accepted bound. / 输出是否超过接纳上限。 */
        private boolean exceeded;

        /** Binds the actual pipe and retention budget. / 绑定实际管道及保留预算。
         * @param input original pipe / 原管道
         * @param maximum retention ceiling / 保留上限 */
        private Capture(InputStream input, int maximum) {
            this.input = input;
            this.maximum = maximum;
        }

        /** Reads to physical EOF even after the retained prefix is full. / 即使保留前缀已满仍读取到物理 EOF。 */
        @Override
        public void run() {
            try (input) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    int keep = Math.min(count, maximum - bytes.size());
                    bytes.write(buffer, 0, keep);
                    exceeded |= keep < count;
                }
            } catch (IOException error) {
                failure = error;
            }
        }
    }
}
