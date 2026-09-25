package gold.debug.windowstolinux.app.linux.ssh;

import java.io.*;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;

import gold.debug.windowstolinux.api.linux.CommandRequest;
import gold.debug.windowstolinux.api.task.*;

/** Sends script bytes separately from the command's unchanged binary input. / 将脚本字节与命令的原始二进制输入分开传送。 */
final class SshCommandFrame implements AutoCloseable {
    /** Maximum script and input size, applied independently before dispatch. / 发送前分别应用的脚本及输入容量上限。 */
    static final int MAX_INPUT = 16 * 1024 * 1024;

    /** Maximum combined retained stdout and stderr. / 标准输出及错误输出合计保留上限。 */
    static final int MAX_OUTPUT = 8 * 1024 * 1024;

    /** Fixed Bash bootstrap; only a small descriptor path becomes a script argument. / 固定 Bash 引导；只有短描述符路径作为脚本参数。 */
    private static final String BOOTSTRAP = """
            IFS= read -r length || exit 125
            case "$length" in ''|*[!0-9]*) exit 125;; esac
            [ "$length" -le 16777216 ] || exit 125
            IFS= read -r -N "$length" script || exit 125
            exec /bin/bash --noprofile --norc /dev/fd/3 3< <(builtin printf '%s' "$script")
            """;

    /** Constant SSH exec request, containing no author script or secret input. / 不含作者脚本或秘密输入的固定 SSH exec 请求。 */
    static final String COMMAND = "exec /usr/bin/env BASH_ENV=/dev/null ENV=/dev/null LC_ALL=C /bin/bash --noprofile --norc -c '"
            + BOOTSTRAP.replace("'", "'\"'\"'") + "'";

    /** Owned UTF-8 script bytes, wiped after actual sender termination. / 发送线程实际终止后清零的所属 UTF-8 脚本字节。 */
    private final byte[] script;

    /** Owned unmodified command input, wiped after actual sender termination. / 发送线程实际终止后清零的所属原始命令输入。 */
    private final byte[] input;

    /**
     * Validates and copies the exact request before any network dispatch. / 在任何网络发送前校验并复制精确请求。
     * @param request immutable reviewed request / 不可变的已审核请求
     * @throws ApiException if a limit or encoding is invalid / 限额或编码无效时
     */
    SshCommandFrame(CommandRequest request) throws ApiException {
        Objects.requireNonNull(request);
        byte[] payload = request.stdin();
        try {
            if (payload.length > MAX_INPUT || request.outputLimitBytes() > MAX_OUTPUT
                    || request.script().length() > MAX_INPUT || request.script().indexOf('\0') >= 0)
                throw new IllegalArgumentException();
            if (request.timeout().toNanos() <= 0)
                throw new IllegalArgumentException();
            var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(request.script()));
            try {
                if (encoded.remaining() > MAX_INPUT)
                    throw new IllegalArgumentException();
                script = new byte[encoded.remaining()];
                encoded.get(script);
            } finally {
                if (encoded.hasArray())
                    Arrays.fill(encoded.array(), (byte) 0);
            }
            input = payload;
        } catch (IllegalArgumentException | ArithmeticException | CharacterCodingException failure) {
            Arrays.fill(payload, (byte) 0);
            throw SshConnector.failure(ProblemCode.INVALID_INPUT, "SSH_COMMAND_INPUT",
                    "SSH script, input, output limit or timeout violates the request boundary");
        }
    }

    /**
     * Writes the length, script and exact binary input without a combined large buffer. / 写入长度、脚本及精确二进制输入，不创建合并的大缓冲区。
     * @param target channel input stream / 通道输入流
     * @throws IOException if the transport cannot accept bytes / 传输无法接收字节时
     */
    void writeTo(OutputStream target) throws IOException {
        target.write((script.length + "\n").getBytes(StandardCharsets.US_ASCII));
        target.write(script);
        target.write(input);
        target.flush();
    }

    /** Wipes owned copies only after no sender can access them. / 仅在发送线程不再访问时清零所属副本。 */
    @Override
    public void close() {
        Arrays.fill(script, (byte) 0);
        Arrays.fill(input, (byte) 0);
    }
}
