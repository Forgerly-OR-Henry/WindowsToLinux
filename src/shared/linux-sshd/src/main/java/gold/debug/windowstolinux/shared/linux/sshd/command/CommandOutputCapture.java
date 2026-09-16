package gold.debug.windowstolinux.shared.linux.sshd.command;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Shares one byte budget across stdout and stderr, closing the producer on overflow. / 标准输出与错误共享字节预算，超限关闭生产通道。 */
final class CommandOutputCapture {
    private final long limit;
    private final boolean retain;
    private final Runnable cancel;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private long received;
    private volatile boolean exceeded;

    CommandOutputCapture(long limit, boolean retain, Runnable cancel) {
        if (limit < 1 || limit > 128L * 1024 * 1024 + 65536) throw new IllegalArgumentException("Invalid SSH byte budget");
        this.limit = limit;
        this.retain = retain;
        this.cancel = Objects.requireNonNull(cancel, "cancel");
    }

    OutputStream stdout() { return stream(stdout); }
    OutputStream stderr() { return stream(stderr); }
    boolean exceeded() { return exceeded; }
    synchronized String output() { return stdout.toString(StandardCharsets.UTF_8); }
    synchronized String error() { return stderr.toString(StandardCharsets.UTF_8); }

    private OutputStream stream(ByteArrayOutputStream target) {
        return new OutputStream() {
            /** Accepts one byte within the shared budget. / 在共享预算内接收一个字节。 */
            @Override public void write(int value) { write(new byte[]{(byte) value}, 0, 1); }

            /** Bounds a chunk before copying it. / 复制数据块前应用上限。 */
            @Override public void write(byte[] bytes, int offset, int length) {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                boolean overflow;
                synchronized (CommandOutputCapture.this) {
                    if (exceeded) return;
                    int accepted = (int) Math.min(length, limit - received);
                    if (retain) target.write(bytes, offset, accepted);
                    received += accepted;
                    overflow = accepted < length;
                    exceeded = overflow;
                }
                if (overflow) cancel.run();
            }
        };
    }
}
