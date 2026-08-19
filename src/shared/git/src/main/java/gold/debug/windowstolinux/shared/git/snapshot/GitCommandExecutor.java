package gold.debug.windowstolinux.shared.git.snapshot;

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

    String run(Path directory, List<String> command) throws IOException, InterruptedException {
        return run(directory, command, MAX_OUTPUT_BYTES);
    }

    String readIndex(Path directory) throws IOException, InterruptedException {
        return run(directory, List.of("git", "ls-files", "--stage"), MAX_INDEX_OUTPUT_BYTES);
    }

    private String run(Path directory, List<String> command, int maximumOutputBytes)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(commandForPlatform(command, System.getProperty("os.name", "")));
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        Process process = builder.start();
        BoundedOutput output = new BoundedOutput(process.getInputStream(), maximumOutputBytes);
        Thread reader = Thread.ofVirtual().name("windowstolinux-git-output").start(output);
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            terminateTree(process);
            finishReader(process, reader);
            throw new IOException("Git command timed out");
        }
        finishReader(process, reader);
        IOException outputFailure = output.failure().orElse(null);
        if (outputFailure != null) {
            throw outputFailure;
        }
        if (output.exceeded()) {
            throw new IOException("Git command output exceeds the safe diagnostic bound");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Git command returned a non-zero exit status");
        }
        return new String(output.bytes(), StandardCharsets.UTF_8);
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

    private static void terminateTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroyForcibly();
        }
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
        for (ProcessHandle descendant : descendants) {
            try {
                descendant.onExit().get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
                // The bounded caller still fails; a later workspace cleanup verifies that no handle remains. / 有界调用方仍会失败；后续工作区清理会验证没有句柄残留。
            }
        }
    }

    private static void finishReader(Process process, Thread reader) throws IOException, InterruptedException {
        reader.join(10_000);
        if (reader.isAlive()) {
            process.getInputStream().close();
            reader.join(10_000);
        }
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
