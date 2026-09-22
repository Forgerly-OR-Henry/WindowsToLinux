package gold.debug.windowstolinux.shared.linux.sshd.command;

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

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

/**
 * Executes only implementation-owned, pre-rendered commands over an authenticated SSHD session.
 *
 *  <p>仅通过已认证的 SSHD 会话执行由实现持有且预先渲染的命令。
 */
public final class SshCommandExecutor implements gold.debug.windowstolinux.shared.linux.command.RemoteCommandExecutor {
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
        this(session, null);
    }
    /** Exact endpoint for task review. / 任务审核使用的精确端点。 */
    private final String target;

    /** Exact executable stdin for the current synchronous call. / 当前同步调用的精确可执行标准输入。 */
    private String executableInput = "";
    /** Binds the authenticated endpoint. / 绑定已认证端点。
     * @param session authenticated session / 已认证会话
     * @param endpoint endpoint, absent only for transport tests / 端点，仅传输测试可为空
     */
    public SshCommandExecutor(ClientSession session,
            gold.debug.windowstolinux.shared.linux.connection.SshEndpoint endpoint) {
        this.session = Objects.requireNonNull(session, "session");
        this.target = endpoint == null
                ? "unscoped"
                : endpoint.serverId() + "|" + endpoint.username() + "@" + endpoint.host() + ":" + endpoint.port();
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
    public gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult exec(String script, Duration timeout,
            boolean preserveOutput) throws LinuxOperationException {
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
    public gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult execScript(String script,
            Duration timeout, boolean preserveOutput) throws LinuxOperationException {
        Objects.requireNonNull(script, "script");
        // Parse the complete group before commands run; installers must not consume the script stream. / 先解析整个命令组，再执行；安装器不能读取剩余的脚本输入。
        byte[] input = ("{\n" + script + "\n} </dev/null\n").getBytes(StandardCharsets.UTF_8);
        executableInput = new String(input, StandardCharsets.UTF_8);
        try {
            return execute("exec /bin/bash -ls", timeout, preserveOutput, false, input);
        } finally {
            executableInput = "";
        }
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
    public gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult execProtocol(String script,
            Duration timeout, boolean preserveOutput) throws LinuxOperationException {
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
    public gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult execProtocolWithInput(String script,
            byte[] input, Duration timeout) throws LinuxOperationException {
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
    public gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult execProtocolWithInput(String script,
            byte[] input, Duration timeout, long maxBytes) throws LinuxOperationException {
        Objects.requireNonNull(input, "input");
        if (input.length > 1048576)
            throw new IllegalArgumentException("Controlled build script exceeds 1 MiB");
        executableInput = new String(input, StandardCharsets.UTF_8);
        try {
            return execute(script, timeout, true, true, input, maxBytes);
        } finally {
            executableInput = "";
        }
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
    private gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult execute(String script, Duration timeout,
            boolean preserveOutput, boolean preserveProtocolOutput, byte[] input) throws LinuxOperationException {
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
    public gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult execWithOutputLimit(String script,
            Duration timeout, long maxBytes) throws LinuxOperationException {
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
    private gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult execute(String script, Duration timeout,
            boolean preserveOutput, boolean preserveProtocolOutput, byte[] input, long maxBytes)
            throws LinuxOperationException {
        return executeRemoteCommand(
                "/bin/bash -lc " + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(script), timeout,
                preserveOutput, preserveProtocolOutput, input, maxBytes);
    }

    /**
     * Proves read-only execution without requiring bash, systemd or deployment support. / 验证只读执行，不依赖 bash、systemd 或部署支持。
     *
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    public void verifyConnection() throws LinuxOperationException {
        gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult result = executeRemoteCommand(
                "printf 'WTL_SSH_READY\\n'", Duration.ofSeconds(10), true, false, null, 128);
        if (!result.succeeded() || !result.output().trim().equals("WTL_SSH_READY"))
            throw LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED,
                    "SSH read-only verification failed");
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
    private gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult executeRemoteCommand(String command,
            Duration timeout, boolean preserveOutput, boolean preserveProtocolOutput, byte[] input, long maxBytes)
            throws LinuxOperationException {
        var dispatch = gold.debug.windowstolinux.shared.linux.command.CommandExecutionScope.before(target, command,
                executableInput, input, timeout, maxBytes);
        try (ClientChannel channel = session.createExecChannel(command)) {
            CommandOutputCapture capture = new CommandOutputCapture(maxBytes, preserveOutput,
                    () -> channel.close(true));
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
            String errorText = capture.exceeded()
                    ? "SSH output exceeded the confirmed byte limit"
                    : preserveOutput ? sanitize(capture.error()) : "";
            var result = new gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult(succeeded, timedOut,
                    text, evidenceOutput, errorText, capture.exceeded() ? null : exit);
            dispatch.ifPresent(receipt -> receipt.completed(result));
            return result;
        } catch (IOException exception) {
            dispatch.ifPresent(gold.debug.windowstolinux.shared.linux.command.CommandExecutionScope.Dispatch::unknown);
            throw LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED,
                    "Controlled SSH command could not be executed (" + safeException(exception) + ")", exception);
        }
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
        for (Throwable current = Objects.requireNonNull(failure, "failure"); current != null; current = current
                .getCause()) {
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
        String redacted = text.replaceAll("(?i)(password|secret|token|api[_-]?key)\\s*[:=]\\s*\\S+", "$1=<redacted>")
                .replaceAll("(?i)(https?://)[^\\s/@:]+:[^\\s/@]+@", "$1<redacted>@")
                .replaceAll("-----BEGIN [A-Z ]+-----[\\s\\S]*?-----END [A-Z ]+-----", "<redacted-key>");
        if (redacted.length() <= MAX_EVIDENCE_CHARS)
            return redacted;
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
     * @param inputDigest approved data digest / 已批准数据摘要
     * @param inputBytes approved data size / 已批准数据大小
     * @param outputLimit approved output limit / 已批准输出限制
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult execProtocolStreaming(String script,
            InputStream input, OutputStream output, Duration timeout, String inputDigest, long inputBytes,
            long outputLimit) throws LinuxOperationException {
        Objects.requireNonNull(input);
        Objects.requireNonNull(output);
        Objects.requireNonNull(timeout);
        if (!inputDigest.matches("[0-9a-f]{64}") || inputBytes < 0 || inputBytes > 137438953472L || outputLimit < 1
                || outputLimit > 137438953472L)
            throw new IllegalArgumentException("invalid stream identity or bounds");
        String command = "/bin/bash -lc " + gold.debug.windowstolinux.shared.linux.command.CommandText.quote(script);
        var dispatch = gold.debug.windowstolinux.shared.linux.command.CommandExecutionScope.beforeDigest(target,
                command, "", inputDigest, timeout, outputLimit);
        try (ClientChannel channel = session.createExecChannel(command)) {
            var inputHash = java.security.MessageDigest.getInstance("SHA-256");
            var counted = new CountingStream(input, inputHash, inputBytes);
            var bounded = new BoundedStream(output, outputLimit, () -> channel.close(true));
            var errors = new CommandOutputCapture(65536, true, () -> channel.close(true));
            channel.setIn(counted);
            channel.setOut(bounded);
            channel.setErr(errors.stderr());
            channel.open().verify(timeout);
            var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), timeout);
            boolean timedOut = !events.contains(ClientChannelEvent.CLOSED);
            if (timedOut)
                channel.close(true);
            Integer exit = channel.getExitStatus();
            boolean intact = counted.count == inputBytes
                    && java.util.HexFormat.of().formatHex(inputHash.digest()).equals(inputDigest) && !bounded.exceeded
                    && !errors.exceeded();
            var result = new gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult(
                    !timedOut && intact && exit != null && exit == 0, timedOut, "", "", sanitize(errors.error()),
                    intact ? exit : null);
            dispatch.ifPresent(receipt -> receipt.completed(result));
            if (!intact)
                throw new IOException("stream differs from reviewed bounds or digest");
            return result;
        } catch (IOException failure) {
            dispatch.ifPresent(gold.debug.windowstolinux.shared.linux.command.CommandExecutionScope.Dispatch::unknown);
            throw LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED,
                    "Controlled SSH stream failed (" + safeException(failure) + ")", failure);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Measures exact approved stream bytes without retaining data. / 测量精确已批准流字节，不保留数据。 */
    private static final class CountingStream extends InputStream {
        /** Caller-owned input. / 调用方持有的输入。 */
        private final InputStream input;

        /** Exact content digest. / 精确内容摘要。 */
        private final java.security.MessageDigest digest;

        /** Reviewed byte count. / 已审阅字节数。 */
        private final long limit;

        /** Observed byte count. / 已观察字节数。 */
        private long count;
        /** Binds input integrity. / 绑定输入完整性。
         * @param input data stream / 数据流
         * @param digest running digest / 运行摘要
         * @param limit approved bytes / 已批准字节数
         */
        private CountingStream(InputStream input, java.security.MessageDigest digest, long limit) {
            this.input = input;
            this.digest = digest;
            this.limit = limit;
        }

        /** Reads one bounded byte. / 读取一个有界字节。
         * @return byte or EOF / 字节或结束标记
         * @throws IOException when the input exceeds its approval / 输入超出批准时
         */
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 255;
        }

        /** Reads bounded exact data. / 读取有界精确数据。
         * @param bytes target buffer / 目标缓冲区
         * @param offset buffer offset / 缓冲区偏移
         * @param length requested bytes / 请求字节数
         * @return bytes read / 已读字节数
         * @throws IOException when the content exceeds its declaration / 内容超出声明时
         */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int n = input.read(bytes, offset, length);
            if (n > 0) {
                count += n;
                if (count > limit)
                    throw new IOException("stream input limit");
                digest.update(bytes, offset, n);
            }
            return n;
        }
    }

    /** Stops the actual channel when streamed output exceeds approval. / 流输出超出批准时停止实际通道。 */
    private static final class BoundedStream extends OutputStream {
        /** Caller-owned output. / 调用方持有的输出。 */
        private final OutputStream output;

        /** Approved output bytes. / 已批准输出字节数。 */
        private final long limit;

        /** Channel stop action. / 通道停止动作。 */
        private final Runnable stop;

        /** Observed count. / 已观察计数。 */
        private long count;

        /** Whether output integrity failed. / 输出完整性是否失败。 */
        private boolean exceeded;
        /** Binds bounded output. / 绑定有界输出。
         * @param output destination / 目的地
         * @param limit maximum bytes / 最大字节数
         * @param stop actual channel stop / 实际通道停止
         */
        private BoundedStream(OutputStream output, long limit, Runnable stop) {
            this.output = output;
            this.limit = limit;
            this.stop = stop;
        }

        /** Writes one bounded byte. / 写入一个有界字节。
         * @param value byte / 字节
         * @throws IOException on exceeded output / 输出超限时
         */
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        /** Writes only within the approved byte count. / 仅在批准字节数内写入。
         * @param bytes source / 来源
         * @param offset source offset / 来源偏移
         * @param length byte count / 字节数
         * @throws IOException on exceeded output / 输出超限时
         */
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            count += length;
            if (count > limit) {
                exceeded = true;
                stop.run();
                throw new IOException("stream output limit");
            }
            output.write(bytes, offset, length);
        }
    }
}
