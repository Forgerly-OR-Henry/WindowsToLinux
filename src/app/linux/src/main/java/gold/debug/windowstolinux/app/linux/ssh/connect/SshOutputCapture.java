package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.CommandResult;

/** Drains both SSH streams while retaining only a shared bounded prefix. / 持续排空两条 SSH 流，同时只保留共享限额内的前缀。 */
final class SshOutputCapture {
    /** Combined byte retention budget. / 合计字节保留预算。 */
    private final long limit;

    /** Retained stdout bytes. / 保留的标准输出字节。 */
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    /** Retained stderr bytes. / 保留的标准错误字节。 */
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    /** Bytes already retained across both streams. / 两条流已经合计保留的字节数。 */
    private long retained;

    /** Whether any received bytes were omitted. / 是否省略了收到的字节。 */
    private boolean truncated;

    /**
     * Binds the already validated capture limit. / 绑定已经校验的捕获限额。
     * @param limit combined maximum byte count / 合计最大字节数
     */
    SshOutputCapture(long limit) {
        this.limit = limit;
    }

    /** Returns a bounded stdout sink. / 返回有界标准输出接收器。
     * @return draining stdout stream / 持续排空的标准输出流 */
    OutputStream stdout() {
        return stream(stdout);
    }

    /** Returns a bounded stderr sink. / 返回有界标准错误接收器。
     * @return draining stderr stream / 持续排空的标准错误流 */
    OutputStream stderr() {
        return stream(stderr);
    }

    /**
     * Creates a sink that never aborts remote work merely because capture is full. / 创建不会仅因捕获已满而终止远端工作的接收器。
     * @param target retained stream buffer / 保留流缓冲区
     * @return bounded draining sink / 有界排空接收器
     */
    private OutputStream stream(ByteArrayOutputStream target) {
        return new OutputStream() {
            /** Appends one byte within the shared limit. / 在共享限额内追加一个字节。
             * @param value received byte / 收到的字节 */
            @Override
            public void write(int value) {
                write(new byte[]{(byte) value}, 0, 1);
            }

            /** Appends only bytes within the limit and discards the remainder. / 仅追加限额内字节并丢弃其余字节。
             * @param bytes received buffer / 收到的缓冲区
             * @param offset starting position / 起始位置
             * @param count received byte count / 收到的字节数 */
            @Override
            public void write(byte[] bytes, int offset, int count) {
                java.util.Objects.checkFromIndexSize(offset, count, bytes.length);
                synchronized (SshOutputCapture.this) {
                    int accepted = (int) Math.min(count, limit - retained);
                    target.write(bytes, offset, accepted);
                    retained += accepted;
                    truncated |= accepted < count;
                }
            }
        };
    }

    /**
     * Copies the observed output with an optional actually reported exit status. / 复制观测输出并附带可选的实际报告退出状态。
     * @param exit observed exit code, absent when unknown / 已观测退出码，未知时为空
     * @return immutable bounded command result / 不可变有界命令结果
     */
    synchronized CommandResult snapshot(Integer exit) {
        return new CommandResult(Optional.ofNullable(exit), stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8), truncated);
    }
}
