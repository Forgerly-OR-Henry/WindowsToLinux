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
 *  <p>仅通过已认证的 SSHD 会话执行由实现持有且预先渲染的命令。
 */
public final class SshCommandExecutor {
    /**
     * Exposes the {@code MAX_EVIDENCE_CHARS} constant.
     *
     *  <p>公开 {@code MAX_EVIDENCE_CHARS} 常量。
     */
    public static final int MAX_EVIDENCE_CHARS = 4096;

    /**
     * Session used for the current scoped operation.
     * <p>当前限定作用域操作使用的会话。
     */
    private final ClientSession session;

    /**
     * Validates and binds the inputs required by ssh command executor.
     * <p>校验并绑定SSH命令执行器所需输入。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshCommandExecutor(ClientSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /**
     * Executes a bounded SSH script with the requested output-retention policy and no protocol input stream.
     * <p>按请求的输出保留策略执行有界 SSH 脚本，不提供协议输入流。
     *
     * @param script build script path / 构建脚本路径
     * @param timeout timeout / 超时
     * @param preserveOutput preserve output / 保留输出
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public CommandResult exec(String script, Duration timeout, boolean preserveOutput)
            throws LinuxOperationException {
        return execute(script, timeout, preserveOutput, false, null);
    }

    /**
     * Streams an implementation-owned script without Linux's per-argument size limit. / 流式传输实现自有脚本，避免 Linux 单参数长度限制。
     *
     * @param script build script path / 构建脚本路径
     * @param timeout timeout / 超时
     * @param preserveOutput preserve output / 保留输出
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
     *  <p>确保短生命周期辅助协议值只对实现解析器可用。
     *
     * @param script build script path / 构建脚本路径
     * @param timeout timeout / 超时
     * @param preserveOutput preserve output / 保留输出
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public CommandResult execProtocol(String script, Duration timeout, boolean preserveOutput)
            throws LinuxOperationException {
        return execute(script, timeout, preserveOutput, true, null);
    }

    /**
     * Streams a bounded sensitive payload directly to a controlled helper without placing it in a command or file.
     *
     *  <p>将有界敏感载荷直接流式传给受控辅助程序，不把它放入命令或文件。
     *
     * @param script build script path / 构建脚本路径
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param timeout timeout / 超时
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public CommandResult execProtocolWithInput(String script, byte[] input, Duration timeout)
            throws LinuxOperationException {
        Objects.requireNonNull(input, "input");
        return execute(script, timeout, true, true, input);
    }

    /**
     * Streams a controlled build script with the reviewed response limit. / 按经审阅响应上限流式发送受控构建脚本。
     *
     * @param script build script path / 构建脚本路径
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param timeout timeout / 超时
     * @param maxBytes max bytes / 最大字节
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public CommandResult execProtocolWithInput(String script, byte[] input, Duration timeout, long maxBytes)
            throws LinuxOperationException {
        Objects.requireNonNull(input, "input");
        if (input.length > 1048576) throw new IllegalArgumentException("Controlled build script exceeds 1 MiB");
        return execute(script, timeout, true, true, input, maxBytes);
    }

    /**
     * Executes command result.
     * <p>执行命令结果。
     *
     * @param script build script path / 构建脚本路径
     * @param timeout timeout / 超时
     * @param preserveOutput preserve output / 保留输出
     * @param preserveProtocolOutput preserve protocol output / 保留协议输出
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private CommandResult execute(String script, Duration timeout, boolean preserveOutput,
                                  boolean preserveProtocolOutput, byte[] input) throws LinuxOperationException {
        return execute(script, timeout, preserveOutput, preserveProtocolOutput, input, 1024 * 1024);
    }

    /**
     * Executes a build with its reviewed output budget. / 按经审阅输出预算执行构建。
     *
     * @param script build script path / 构建脚本路径
     * @param timeout timeout / 超时
     * @param maxBytes max bytes / 最大字节
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public CommandResult execWithOutputLimit(String script, Duration timeout, long maxBytes)
            throws LinuxOperationException {
        return execute(script, timeout, true, false, null, maxBytes);
    }

    /**
     * Executes command result.
     * <p>执行命令结果。
     *
     * @param script build script path / 构建脚本路径
     * @param timeout timeout / 超时
     * @param preserveOutput preserve output / 保留输出
     * @param preserveProtocolOutput preserve protocol output / 保留协议输出
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param maxBytes max bytes / 最大字节
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private CommandResult execute(String script, Duration timeout, boolean preserveOutput,
                                  boolean preserveProtocolOutput, byte[] input, long maxBytes) throws LinuxOperationException {
        return executeRemoteCommand("/bin/bash -lc " + quote(script), timeout, preserveOutput, preserveProtocolOutput, input, maxBytes);
    }

    /**
     * Proves read-only execution without requiring bash, systemd or deployment support. / 验证只读执行，不依赖 bash、systemd 或部署支持。
     *
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public void verifyConnection() throws LinuxOperationException {
        CommandResult result = executeRemoteCommand("printf 'WTL_SSH_READY\\n'", Duration.ofSeconds(10), true, false, null, 128);
        if (!result.succeeded() || !result.output().trim().equals("WTL_SSH_READY"))
            throw LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED, "SSH read-only verification failed");
    }

    /**
     * Runs a bounded SSH command with concurrent output capture, verifies completion and closes owned command resources on every exit path.
     * <p>运行有界 SSH 命令并并发采集输出，验证完成状态，并在所有退出路径关闭自有命令资源。
     *
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @param timeout timeout / 超时
     * @param preserveOutput preserve output / 保留输出
     * @param preserveProtocolOutput preserve protocol output / 保留协议输出
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param maxBytes max bytes / 最大字节
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private CommandResult executeRemoteCommand(String command, Duration timeout, boolean preserveOutput,
            boolean preserveProtocolOutput, byte[] input, long maxBytes) throws LinuxOperationException {
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
     * Parses trimmed key-value lines at the first equals sign, retaining the first value for duplicate keys.
     * <p>按首个等号解析去除首尾空白的键值行，并为重复键保留首个值。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
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
     * Returns the trimmed first line, or an empty string for empty input.
     * <p>返回去除首尾空白的首行；输入为空时返回空字符串。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return the operation result / 操作结果
     */
    public static String firstLine(String text) {
        return text.lines().findFirst().map(String::trim).orElse("");
    }

    /**
     * Parses long.
     * <p>解析长整型。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
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
     * Quotes a literal argument for the fixed command-rendering boundary.
     * <p>为固定命令渲染边界引用字面参数。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return the operation result / 操作结果
     */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /**
     * Identifies a transport timeout that can be retried only by a caller whose command is known to be read-only.
     *
     *  <p>识别仅可由已知命令为只读的调用方重试的传输超时。
     *
     * @param failure the controlled SSH failure / 受控 SSH 失败
     * @return whether the causal chain contains a transport timeout / 因果链是否包含传输超时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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

    /**
     * Renders fixed sanitize protocol text from the reviewed inputs.
     * <p>根据已审阅输入渲染固定清洗协议文本。
     *
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return sanitize text / 清洗文本
     */
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

    /**
     * Validates and produces safe exception for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的安全异常。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return safe exception text / 安全异常文本
     */
    private static String safeException(IOException exception) {
        return exception.getClass().getSimpleName();
    }

    /**
     * Streams large controlled protocol input and output without buffering it as diagnostic evidence. / 在不把大型受控协议输入输出缓冲为诊断证据的情况下进行流式传输。
     *
     * @param script build script path / 构建脚本路径
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param timeout timeout / 超时
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
     *  <p>表示不可变的 {@code CommandResult} 值。
     *
     * @param succeeded whether the build and artifact verification succeeded / 构建和产物验证是否成功
     * @param timedOut timed out / 超时输出
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @param evidenceOutput evidence output / 证据输出
     * @param error error / 错误
     * @param exitStatus exit status / 退出状态
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
         * Returns failure evidence.
         * <p>返回失败证据。
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
