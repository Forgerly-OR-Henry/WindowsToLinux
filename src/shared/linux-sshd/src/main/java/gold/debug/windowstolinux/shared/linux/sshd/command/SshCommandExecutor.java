package gold.debug.windowstolinux.shared.linux.sshd.command;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Executes only implementation-owned, pre-rendered commands over an authenticated SSHD session.
 *
 * <p>仅通过已认证的 SSHD 会话执行由实现持有且预先渲染的命令。
 */
public final class SshCommandExecutor {
    /**
     * Exposes the {@code MAX_EVIDENCE_CHARS} constant.
     *
     * <p>公开 {@code MAX_EVIDENCE_CHARS} 常量。
     */
    public static final int MAX_EVIDENCE_CHARS = 4096;

    private final ClientSession session;

    /**
     * Creates a {@code SshCommandExecutor} instance.
     *
     * <p>创建 {@code SshCommandExecutor} 实例。
     *
     * @param session the {@code session} value / {@code session} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SshCommandExecutor(ClientSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Performs the {@code exec} operation.
     *
     * <p>执行 {@code exec} 操作。
     *
     * @param script the {@code script} value / {@code script} 值
     * @param timeout the {@code timeout} value / {@code timeout} 值
     * @param preserveOutput the {@code preserveOutput} value / {@code preserveOutput} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public CommandResult exec(String script, Duration timeout, boolean preserveOutput)
            throws LinuxOperationException {
        return execute(script, timeout, preserveOutput, false, null);
    }

    /** Streams an implementation-owned script without Linux's per-argument size limit. / 流式传输实现自有脚本，避免 Linux 单参数长度限制。 */
    public CommandResult execScript(String script, Duration timeout, boolean preserveOutput)
            throws LinuxOperationException {
        Objects.requireNonNull(script, "script");
        // Parse the complete group before commands run; installers must not consume the script stream. / 先解析整个命令组，再执行；安装器不能读取剩余的脚本输入。
        byte[] input = ("{\n" + script + "\n} </dev/null\n").getBytes(StandardCharsets.UTF_8);
        return execute("exec /bin/bash -ls", timeout, preserveOutput, false, input);
    }

    /**
     * Keeps short-lived helper protocol values available only to the implementation parser.
     *
     * <p>确保短生命周期辅助协议值只对实现解析器可用。
     *
     * @param script the {@code script} value / {@code script} 值
     * @param timeout the {@code timeout} value / {@code timeout} 值
     * @param preserveOutput the {@code preserveOutput} value / {@code preserveOutput} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public CommandResult execProtocol(String script, Duration timeout, boolean preserveOutput)
            throws LinuxOperationException {
        return execute(script, timeout, preserveOutput, true, null);
    }

    /**
     * Streams a bounded sensitive payload directly to a controlled helper without placing it in a command or file.
     *
     * <p>将有界敏感载荷直接流式传给受控辅助程序，不把它放入命令或文件。
     */
    public CommandResult execProtocolWithInput(String script, byte[] input, Duration timeout)
            throws LinuxOperationException {
        Objects.requireNonNull(input, "input");
        return execute(script, timeout, true, true, input);
    }

    /** Streams a controlled build script with the reviewed response limit. / 按经审阅响应上限流式发送受控构建脚本。 */
    public CommandResult execProtocolWithInput(String script, byte[] input, Duration timeout, long maxBytes)
            throws LinuxOperationException {
        Objects.requireNonNull(input, "input");
        if (input.length > 1048576) throw new IllegalArgumentException("Controlled build script exceeds 1 MiB");
        return execute(script, timeout, true, true, input, maxBytes);
    }

    private CommandResult execute(String script, Duration timeout, boolean preserveOutput,
                                  boolean preserveProtocolOutput, byte[] input) throws LinuxOperationException {
        return execute(script, timeout, preserveOutput, preserveProtocolOutput, input, 1024 * 1024);
    }

    /** Executes a build with its reviewed output budget. / 按经审阅输出预算执行构建。 */
    public CommandResult execWithOutputLimit(String script, Duration timeout, long maxBytes)
            throws LinuxOperationException {
        return execute(script, timeout, true, false, null, maxBytes);
    }

    private CommandResult execute(String script, Duration timeout, boolean preserveOutput,
                                  boolean preserveProtocolOutput, byte[] input, long maxBytes) throws LinuxOperationException {
        String command = "/bin/bash -lc " + quote(script);
        try (ClientChannel channel = session.createExecChannel(command)) {
            CommandOutputCapture capture = new CommandOutputCapture(maxBytes, preserveOutput, () -> channel.close(true));
            if (input != null) {
                channel.setIn(new ByteArrayInputStream(input));
            }
            channel.setOut(capture.stdout());
            channel.setErr(capture.stderr());
            channel.open().verify(timeout);
            var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
            boolean timedOut = !events.contains(ClientChannelEvent.CLOSED);
            if (timedOut) {
                channel.close(true);
            }
            Integer exit = channel.getExitStatus();
            boolean succeeded = !timedOut && !capture.exceeded() && exit != null && exit == 0;
            String rawOutput = capture.output();
            String evidenceOutput = sanitize(rawOutput);
            String text = preserveProtocolOutput ? rawOutput : evidenceOutput;
            String errorText = capture.exceeded() ? "SSH output exceeded the confirmed byte limit"
                    : preserveOutput ? sanitize(capture.error()) : "";
            return new CommandResult(succeeded, timedOut, text, evidenceOutput, errorText, exit);
        } catch (IOException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED,
                    "Controlled SSH command could not be executed (" + safeException(exception) + ")", exception);
        }
    }

    /**
     * Performs the {@code lines} operation.
     *
     * <p>执行 {@code lines} 操作。
     *
     * @param text the {@code text} value / {@code text} 值
     * @return the operation result collection / 操作结果集合
     */
    public static Map<String, String> lines(String text) {
        return text.lines()
                .map(String::trim)
                .filter(line -> line.contains("="))
                .map(line -> line.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        parts -> parts[0], parts -> parts[1], (first, ignored) -> first));
    }

    /**
     * Performs the {@code firstLine} operation.
     *
     * <p>执行 {@code firstLine} 操作。
     *
     * @param text the {@code text} value / {@code text} 值
     * @return the operation result / 操作结果
     */
    public static String firstLine(String text) {
        return text.lines().findFirst().map(String::trim).orElse("");
    }

    /**
     * Performs the {@code parseLong} operation.
     *
     * <p>执行 {@code parseLong} 操作。
     *
     * @param value the {@code value} value / {@code value} 值
     * @return the operation result / 操作结果
     */
    public static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            // Invalid bounded numeric evidence is treated as unavailable, not propagated to users. / 无效的有界数字证据视为不可用，不向用户传播。
            return 0;
        }
    }

    /**
     * Performs the {@code quote} operation.
     *
     * <p>执行 {@code quote} 操作。
     *
     * @param value the {@code value} value / {@code value} 值
     * @return the operation result / 操作结果
     */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * Identifies a transport timeout that can be retried only by a caller whose command is known to be read-only.
     *
     * <p>识别仅可由已知命令为只读的调用方重试的传输超时。
     *
     * @param failure the controlled SSH failure / 受控 SSH 失败
     * @return whether the causal chain contains a transport timeout / 因果链是否包含传输超时
     */
    public static boolean isTransientTransportFailure(LinuxOperationException failure) {
        for (Throwable current = Objects.requireNonNull(failure, "failure"); current != null;
             current = current.getCause()) {
            if (current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String sanitize(String text) {
        String redacted = text
                .replaceAll("(?i)(password|secret|token|api[_-]?key)\\s*[:=]\\s*\\S+", "$1=<redacted>")
                .replaceAll("(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@", "$1<redacted>@")
                .replaceAll("-----BEGIN [A-Z ]+-----[\\s\\S]*?-----END [A-Z ]+-----", "<redacted-key>");
        if (redacted.length() <= MAX_EVIDENCE_CHARS) return redacted;
        String omitted = "\n[... output omitted ...]\n";
        int head = (MAX_EVIDENCE_CHARS - omitted.length()) / 2;
        int tail = MAX_EVIDENCE_CHARS - omitted.length() - head;
        return redacted.substring(0, head) + omitted + redacted.substring(redacted.length() - tail);
    }

    private static String safeException(IOException exception) {
        return exception.getClass().getSimpleName();
    }

    /** Streams large controlled protocol input and output without buffering it as diagnostic evidence. / 在不把大型受控协议输入输出缓冲为诊断证据的情况下进行流式传输。 */
    public CommandResult execProtocolStreaming(
            String script, InputStream input, OutputStream output, Duration timeout) throws LinuxOperationException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(timeout, "timeout");
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String command = "/bin/bash -lc " + quote(script);
        try (ClientChannel channel = session.createExecChannel(command)) {
            channel.setIn(input);
            channel.setOut(output);
            channel.setErr(error);
            channel.open().verify(timeout);
            var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
            boolean timedOut = !events.contains(ClientChannelEvent.CLOSED);
            if (timedOut) channel.close(true);
            Integer exit = channel.getExitStatus();
            boolean succeeded = !timedOut && exit != null && exit == 0;
            return new CommandResult(succeeded, timedOut, "", "", sanitize(error.toString(StandardCharsets.UTF_8)), exit);
        } catch (IOException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED,
                    "Controlled SSH stream could not be executed (" + safeException(exception) + ")", exception);
        }
    }

    /**
     * Represents an immutable {@code CommandResult} value.
     *
     * <p>表示不可变的 {@code CommandResult} 值。
     *
     * @param succeeded the {@code succeeded} value / {@code succeeded} 值
     * @param timedOut the {@code timedOut} value / {@code timedOut} 值
     * @param output the {@code output} value / {@code output} 值
     * @param evidenceOutput the {@code evidenceOutput} value / {@code evidenceOutput} 值
     * @param error the {@code error} value / {@code error} 值
     * @param exitStatus the {@code exitStatus} value / {@code exitStatus} 值
     */
    public record CommandResult(
            boolean succeeded,
            boolean timedOut,
            String output,
            String evidenceOutput,
            String error,
            Integer exitStatus
    ) {
        /**
         * Performs the {@code failureEvidence} operation.
         *
         * <p>执行 {@code failureEvidence} 操作。
         *
         * @return the operation result / 操作结果
         */
        public String failureEvidence() {
            if (timedOut) {
                return "Remote command timed out";
            }
            if (!error.isBlank() && !evidenceOutput.isBlank()) {
                return "exitCode=" + (exitStatus == null ? "unknown" : exitStatus)
                        + ", error=" + error + ", output=" + evidenceOutput;
            }
            String detail = error.isBlank() ? evidenceOutput : error;
            if (!detail.isBlank()) {
                return "exitCode=" + (exitStatus == null ? "unknown" : exitStatus) + ", output=" + detail;
            }
            return "exitCode=" + (exitStatus == null ? "unknown" : exitStatus);
        }
    }
}
