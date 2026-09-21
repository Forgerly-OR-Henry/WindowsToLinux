package gold.debug.windowstolinux.shared.git.snapshot;

import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSnapshotFailureType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Runs bounded, non-interactive Git commands with hooks and LFS materialization disabled. / 以禁用 Hook 和 LFS 物化的方式运行有界非交互 Git 命令。
 */
final class GitCommandExecutor {
    /**
     * COMMAND TIMEOUT.
     * <p>命令超时。
     */
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    /**
     * MAX OUTPUT BYTES.
     * <p>最大输出字节。
     */
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    /**
     * MAX INDEX OUTPUT BYTES.
     * <p>最大索引输出字节。
     */
    private static final int MAX_INDEX_OUTPUT_BYTES = 32 * 1024 * 1024;
    /**
     * Command timeout.
     * <p>命令超时。
     */
    private final Duration commandTimeout;

    /**
     * Initializes git command executor through its shared constructor contract.
     * <p>通过共享构造契约初始化Git命令执行器。
     */
    GitCommandExecutor() {
        this(COMMAND_TIMEOUT);
    }

    /**
     * Validates and binds the inputs required by git command executor.
     * <p>校验并绑定Git命令执行器所需输入。
     *
     * @param commandTimeout command timeout / 命令超时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    GitCommandExecutor(Duration commandTimeout) {
        this.commandTimeout = java.util.Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
    }

    /**
     * Runs the fixed Git argument vector with bounded output, deadline and process-tree cleanup, returning output only after a successful exit.
     * <p>使用有界输出、期限及进程树清理运行固定 Git 参数向量，并仅在成功退出后返回输出。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @return run text / 运行文本
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    String run(Path directory, List<String> command) throws GitSnapshotException, InterruptedException {
        return run(directory, command, MAX_OUTPUT_BYTES);
    }

    /**
     * Reads index.
     * <p>读取索引。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @return index / 索引
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    String readIndex(Path directory) throws GitSnapshotException, InterruptedException {
        return run(directory, List.of("git", "ls-files", "--stage"), MAX_INDEX_OUTPUT_BYTES);
    }

    /**
     * Runs the fixed Git argument vector with bounded output, deadline and process-tree cleanup, returning output only after a successful exit.
     * <p>使用有界输出、期限及进程树清理运行固定 Git 参数向量，并仅在成功退出后返回输出。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @param maximumOutputBytes maximum output bytes / 最大输出字节
     * @return run text / 运行文本
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    private String run(Path directory, List<String> command, int maximumOutputBytes)
            throws GitSnapshotException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(commandForPlatform(command, System.getProperty("os.name", "")));
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw GitSnapshotException.create(GitSnapshotFailureType.TOOL_UNAVAILABLE,
                    "Git could not be started for controlled snapshot preparation", exception);
        }
        BoundedOutput output = new BoundedOutput(process.getInputStream(), maximumOutputBytes);
        Thread reader = Thread.ofVirtual().name("windowstolinux-git-output").start(output);
        java.util.Set<ProcessHandle> children = new java.util.LinkedHashSet<>();
        Throwable primaryFailure = null;
        try {
            long deadline = System.nanoTime() + commandTimeout.toNanos();
            while (process.isAlive()) {
                children.addAll(process.descendants().toList());
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw GitSnapshotException.create(GitSnapshotFailureType.TIMEOUT,
                        "Git command exceeded its configured execution limit");
                process.waitFor(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS);
            }
            try {
                finishReader(reader);
            } catch (IOException exception) {
                throw GitSnapshotException.create(GitSnapshotFailureType.COMMAND_FAILED,
                        "Git command output reader did not terminate safely", exception);
            }
            IOException outputFailure = output.failure().orElse(null);
            if (outputFailure != null) {
                throw GitSnapshotException.create(GitSnapshotFailureType.COMMAND_FAILED,
                        "Git command output could not be read safely", outputFailure);
            }
            if (output.exceeded()) {
                throw GitSnapshotException.create(GitSnapshotFailureType.COMMAND_FAILED,
                        "Git command output exceeded the safe diagnostic bound");
            }
            if (process.exitValue() != 0) {
                GitSnapshotFailureType type = transientNetworkFailure(output.bytes())
                        ? GitSnapshotFailureType.TRANSIENT_NETWORK_FAILURE
                        : command.contains("fetch")
                        ? GitSnapshotFailureType.REFERENCE_UNAVAILABLE
                        : GitSnapshotFailureType.COMMAND_FAILED;
                throw GitSnapshotException.create(type,
                        "Git command returned a controlled non-success result without exposing remote output");
            }
            return new String(output.bytes(), StandardCharsets.UTF_8);
        } catch (GitSnapshotException | InterruptedException | RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                cleanup(process, children, reader);
            } catch (IOException cleanupFailure) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw GitSnapshotException.create(GitSnapshotFailureType.COMMAND_FAILED,
                        "Git process cleanup could not be verified", cleanupFailure);
            }
            if (primaryFailure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /**
     * Tests the transient network failure predicate against the supplied evidence.
     * <p>根据所提供证据检查暂时网络失败条件。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return true when transient network failure predicate against the supplied evidence, false otherwise / 根据所提供证据检查暂时网络失败条件时为 true，否则为 false
     */
    private static boolean transientNetworkFailure(byte[] output) {
        String text = new String(output, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        return text.contains("could not resolve host")
                || text.contains("failed to connect")
                || text.contains("connection reset")
                || text.contains("network is unreachable")
                || text.contains("remote end hung up")
                || text.contains("connection timed out")
                || text.contains("operation timed out")
                || text.contains("temporary failure in name resolution");
    }

    /**
     * Copies the Git argument vector and applies the Windows-specific process configuration when needed.
     * <p>复制 Git 参数向量，并在需要时应用 Windows 专用进程配置。
     *
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @param operatingSystem operating system / 操作系统
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    static List<String> commandForPlatform(List<String> command, String operatingSystem) {
        ArrayList<String> configured = new ArrayList<>(command);
        if (operatingSystem.toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                && !configured.isEmpty() && "git".equals(configured.getFirst())) {
            configured.add(1, "-c");
            configured.add(2, "http.sslBackend=openssl");
        }
        return configured;
    }

    /**
     * Terminates the owned process tree and joins its output reader while preserving the caller's interruption state.
     * <p>终止自有进程树并等待输出读取线程，同时保留调用方中断状态。
     *
     * @param process process / 进程
     * @param children children / 子项
     * @param reader reader / 读取器
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void cleanup(Process process, java.util.Set<ProcessHandle> children, Thread reader)
            throws IOException {
        boolean interrupted = Thread.interrupted();
        children.addAll(process.descendants().toList());
        children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
            while (System.nanoTime() < deadline
                    && (process.isAlive() || reader.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))) {
                try { Thread.sleep(20); } catch (InterruptedException failure) { interrupted = true; }
            }
            if (process.isAlive() || reader.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive))
                throw new IOException("Task-owned Git process or reader remains after cleanup deadline");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Finishes reader.
     * <p>完成读取器。
     *
     * @param reader reader / 读取器
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    private static void finishReader(Thread reader) throws IOException, InterruptedException {
        reader.join(10_000);
        if (reader.isAlive()) {
            throw new IOException("Git command output reader did not terminate");
        }
    }

    /**
     * Limits captured subprocess output before storing it in memory.
     * <p>在存入内存前限制捕获的子进程输出。
     */
    private static final class BoundedOutput implements Runnable {
        /**
         * Source content consumed by this operation.
         * <p>当前操作消费的源内容。
         */
        private final InputStream input;
        /**
         * Maximum bytes.
         * <p>最大字节。
         */
        private final int maximumBytes;
        /**
         * Captured.
         * <p>已捕获。
         */
        private final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        /**
         * Returns the first capture failure when asynchronous output collection has failed.
         * <p>异步输出采集失败时返回首个采集失败。
         */
        private final AtomicReference<IOException> failure = new AtomicReference<>();
        /**
         * Exceeded.
         * <p>已超限。
         */
        private volatile boolean exceeded;

        /**
         * Binds the supplied dependencies and state for bounded output.
         * <p>为有界输出绑定传入的依赖及状态。
         *
         * @param input source content consumed by this operation / 当前操作消费的源内容
         * @param maximumBytes maximum bytes / 最大字节
         */
        private BoundedOutput(InputStream input, int maximumBytes) {
            this.input = input;
            this.maximumBytes = maximumBytes;
        }

        /**
         * Runs bounded output.
         * <p>运行有界输出。
         */
        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try (input) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    int remaining = maximumBytes - captured.size();
                    if (remaining > 0) {
                        captured.write(buffer, 0, Math.min(count, remaining));
                    }
                    if (count > remaining) {
                        exceeded = true;
                    }
                }
            } catch (IOException exception) {
                failure.set(exception);
            }
        }

        /**
         * Returns content buffer processed by the current codec or stream.
         * <p>返回当前编解码器或流处理的内容缓冲区。
         *
         * @return content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
         */
        private byte[] bytes() {
            return captured.toByteArray();
        }

        /**
         * Returns exceeded.
         * <p>返回已超限。
         *
         * @return true when returns exceeded, false otherwise / 返回已超限时为 true，否则为 false
         */
        private boolean exceeded() {
            return exceeded;
        }

        /**
         * Returns the first capture failure when asynchronous output collection has failed.
         * <p>异步输出采集失败时返回首个采集失败。
         *
         * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
         */
        private java.util.Optional<IOException> failure() {
            return java.util.Optional.ofNullable(failure.get());
        }
    }
}
