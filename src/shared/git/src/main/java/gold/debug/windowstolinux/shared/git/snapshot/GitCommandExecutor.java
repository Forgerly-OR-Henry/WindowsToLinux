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

/** Runs bounded, non-interactive Git commands with hooks and LFS materialization disabled. / 以禁用 Hook 和 LFS 物化的方式运行有界非交互 Git 命令。 */
final class GitCommandExecutor {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final int MAX_INDEX_OUTPUT_BYTES = 32 * 1024 * 1024;
    private final Duration commandTimeout;

    GitCommandExecutor() {
        this(COMMAND_TIMEOUT);
    }

    GitCommandExecutor(Duration commandTimeout) {
        this.commandTimeout = java.util.Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
    }

    String run(Path directory, List<String> command) throws GitSnapshotException, InterruptedException {
        return run(directory, command, MAX_OUTPUT_BYTES);
    }

    String readIndex(Path directory) throws GitSnapshotException, InterruptedException {
        return run(directory, List.of("git", "ls-files", "--stage"), MAX_INDEX_OUTPUT_BYTES);
    }

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

    static List<String> commandForPlatform(List<String> command, String operatingSystem) {
        ArrayList<String> configured = new ArrayList<>(command);
        if (operatingSystem.toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                && !configured.isEmpty() && "git".equals(configured.getFirst())) {
            configured.add(1, "-c");
            configured.add(2, "http.sslBackend=openssl");
        }
        return configured;
    }

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

    private static void finishReader(Thread reader) throws IOException, InterruptedException {
        reader.join(10_000);
        if (reader.isAlive()) {
            throw new IOException("Git command output reader did not terminate");
        }
    }

    private static final class BoundedOutput implements Runnable {
        private final InputStream input;
        private final int maximumBytes;
        private final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        private final AtomicReference<IOException> failure = new AtomicReference<>();
        private volatile boolean exceeded;

        private BoundedOutput(InputStream input, int maximumBytes) {
            this.input = input;
            this.maximumBytes = maximumBytes;
        }

        /** Performs the {@code run} operation. / 执行 {@code run} 操作。 */
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

        private byte[] bytes() {
            return captured.toByteArray();
        }

        private boolean exceeded() {
            return exceeded;
        }

        private java.util.Optional<IOException> failure() {
            return java.util.Optional.ofNullable(failure.get());
        }
    }
}
