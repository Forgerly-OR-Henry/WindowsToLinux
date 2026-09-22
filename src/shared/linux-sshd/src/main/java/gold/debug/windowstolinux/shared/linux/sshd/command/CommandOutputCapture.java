package gold.debug.windowstolinux.shared.linux.sshd.command;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Shares one byte budget across stdout and stderr, closing the producer on overflow. / 标准输出与错误共享字节预算，超限关闭生产通道。
 */
final class CommandOutputCapture {
    /**
     * Limit.
     * <p>限制。
     */
    private final long limit;

    /**
     * Retain.
     * <p>保留。
     */
    private final boolean retain;

    /**
     * Cancel.
     * <p>取消。
     */
    private final Runnable cancel;

    /**
     * Stdout.
     * <p>标准输出。
     */
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    /**
     * Stderr.
     * <p>标准错误输出。
     */
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    /**
     * Bytes received so far against the output bound.
     * <p>当前已计入输出边界的接收字节数。
     */
    private long received;

    /**
     * Exceeded.
     * <p>已超限。
     */
    private volatile boolean exceeded;

    /**
     * Validates and binds the inputs required by command output capture.
     * <p>校验并绑定命令输出捕获所需输入。
     *
     * @param limit limit / 限制
     * @param retain retain / 保留
     * @param cancel cancel / 取消
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    CommandOutputCapture(long limit, boolean retain, Runnable cancel) {
        if (limit < 1 || limit > 128L * 1024 * 1024 + 65536)
            throw new IllegalArgumentException("Invalid SSH byte budget");
        this.limit = limit;
        this.retain = retain;
        this.cancel = Objects.requireNonNull(cancel, "cancel");
    }

    /**
     * Returns stdout.
     * <p>返回标准输出。
     *
     * @return stdout / 标准输出
     */
    OutputStream stdout() {
        return stream(stdout);
    }

    /**
     * Returns stderr.
     * <p>返回标准错误输出。
     *
     * @return stderr / 标准错误输出
     */
    OutputStream stderr() {
        return stream(stderr);
    }

    /**
     * Returns exceeded.
     * <p>返回已超限。
     *
     * @return true when returns exceeded, false otherwise / 返回已超限时为 true，否则为 false
     */
    boolean exceeded() {
        return exceeded;
    }

    /**
     * Returns destination receiving the produced content.
     * <p>返回接收所生成内容的目标。
     *
     * @return destination receiving the produced content / 接收所生成内容的目标
     */
    synchronized String output() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    /**
     * Returns error.
     * <p>返回错误。
     *
     * @return error / 错误
     */
    synchronized String error() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    /**
     * Builds output stream from the supplied stream inputs.
     * <p>根据所提供流输入构建输出流。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @return output stream from the supplied stream inputs / 根据所提供流输入构建输出流
     */
    private OutputStream stream(ByteArrayOutputStream target) {
        return new OutputStream() {
            /**
             * Accepts one byte within the shared budget. / 在共享预算内接收一个字节。
             *
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             */
            @Override
            public void write(int value) {
                write(new byte[]{(byte) value}, 0, 1);
            }

            /**
             * Bounds a chunk before copying it. / 复制数据块前应用上限。
             *
             * @param bytes content buffer processed by the current codec or stream / 当前编解码器或流处理的内容缓冲区
             * @param offset offset / 偏移量
             * @param length length / 长度
             */
            @Override
            public void write(byte[] bytes, int offset, int length) {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                boolean overflow;
                synchronized (CommandOutputCapture.this) {
                    if (exceeded)
                        return;
                    int accepted = (int) Math.min(length, limit - received);
                    if (retain)
                        target.write(bytes, offset, accepted);
                    received += accepted;
                    overflow = accepted < length;
                    exceeded = overflow;
                }
                if (overflow)
                    cancel.run();
            }
        };
    }
}
