package gold.debug.windowstolinux.shared.linux.sshd.connection;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

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
        return execute(script, timeout, preserveOutput, false);
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
        return execute(script, timeout, preserveOutput, true);
    }

    private CommandResult execute(String script, Duration timeout, boolean preserveOutput,
                                  boolean preserveProtocolOutput) throws LinuxOperationException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String command = "/bin/bash -lc " + quote(script);
        try (ClientChannel channel = session.createExecChannel(command)) {
            channel.setOut(output);
            channel.setErr(error);
            channel.open().verify(timeout);
            var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
            boolean timedOut = !events.contains(ClientChannelEvent.CLOSED);
            if (timedOut) {
                channel.close(true);
            }
            Integer exit = channel.getExitStatus();
            boolean succeeded = !timedOut && exit != null && exit == 0;
            String rawOutput = preserveOutput ? output.toString(StandardCharsets.UTF_8) : "";
            String evidenceOutput = sanitize(rawOutput);
            String text = preserveProtocolOutput ? rawOutput : evidenceOutput;
            String errorText = preserveOutput ? sanitize(error.toString(StandardCharsets.UTF_8)) : "";
            return new CommandResult(succeeded, timedOut, text, evidenceOutput, errorText, exit);
        } catch (IOException exception) {
            throw LinuxOperationException.localized("linux.error.sshCommandFailed",
                    "Controlled SSH command could not be executed", exception);
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

    private static String sanitize(String text) {
        String bounded = text.length() > MAX_EVIDENCE_CHARS ? text.substring(0, MAX_EVIDENCE_CHARS) : text;
        return bounded
                .replaceAll("(?i)(password|secret|token|api[_-]?key)\\s*[:=]\\s*\\S+", "$1=<redacted>")
                .replaceAll("(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@", "$1<redacted>@")
                .replaceAll("-----BEGIN [A-Z ]+-----[\\s\\S]*?-----END [A-Z ]+-----", "<redacted-key>");
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
            String detail = !error.isBlank() ? error : evidenceOutput;
            if (!detail.isBlank()) {
                return "exitCode=" + (exitStatus == null ? "unknown" : exitStatus) + ", output=" + detail;
            }
            return "exitCode=" + (exitStatus == null ? "unknown" : exitStatus);
        }
    }
}
