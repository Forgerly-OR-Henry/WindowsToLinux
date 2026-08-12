package gold.debug.windowstolinux.shared.git.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs bounded, non-interactive Git commands with hooks and LFS materialization disabled. / 以禁用 Hook 和 LFS 物化的方式运行有界非交互 Git 命令。 */
final class GitCommandRunner {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;

    String run(Path directory, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        Process process = builder.start();
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Git command timed out");
        }
        byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
        if (output.length > MAX_OUTPUT_BYTES) {
            throw new IOException("Git command output exceeds the safe diagnostic bound");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Git command returned a non-zero exit status");
        }
        return new String(output, StandardCharsets.UTF_8);
    }
}
