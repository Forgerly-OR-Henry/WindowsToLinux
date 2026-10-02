package gold.debug.windowstolinux.app.linux.sftp;

import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.TransferProgress;

/** Counts only completed file bytes, excluding human waiting from the no-progress clock. / 仅计算已完成文件字节，从无进展计时中排除人工等待。 */
final class SftpProgress {
    /** Original transfer identity. / 原传输身份。 */
    private final UUID id;

    /** Current observed stage. / 当前已观察阶段。 */
    private TransferProgress.Stage stage = TransferProgress.Stage.PREPARING;

    /** Completed bytes of this stage. / 本阶段已完成字节数。 */
    private long completed;

    /** Known stage extent or minus one. / 已知阶段范围或负一。 */
    private long total = -1;

    /** Monotonic time of the last effective byte progress. / 最后有效字节进展的单调时间。 */
    private long lastAdvance = System.nanoTime();

    /** Monotonic human-wait start, or zero. / 人工等待开始单调时间，未等待时为零。 */
    private long paused;

    /** Binds one original transfer without starting a worker. / 绑定一个原传输，不启动工作线程。
     * @param id original identity / 原身份 */
    SftpProgress(UUID id) {
        this.id = id;
    }

    /** Changes the visible stage without counting that change as byte progress. / 改变可见阶段，不将阶段切换计作字节进展。
     * @param stage next physical stage / 下一物理阶段
     * @param total known extent or minus one / 已知范围或负一
     * @param completed already verified stage prefix / 已核验阶段前缀 */
    synchronized void begin(TransferProgress.Stage stage, long total, long completed) {
        new TransferProgress(id, stage, completed, total);
        this.stage = stage;
        this.total = total;
        this.completed = completed;
    }

    /** Records actual completed read or acknowledged write bytes. / 记录实际已完成读取或已确认写入字节。
     * @param count positive effective byte count / 正有效字节数 */
    synchronized void advance(long count) {
        if (count <= 0 || total >= 0 && count > total - completed)
            throw new IllegalArgumentException("Invalid effective byte progress");
        completed += count;
        lastAdvance = System.nanoTime();
    }

    /** Suspends or resumes only for a real human choice. / 仅为实际人工选择暂停或恢复计时。
     * @param waiting whether the worker is waiting on a person / 工作线程是否等待人工 */
    synchronized void human(boolean waiting) {
        long now = System.nanoTime();
        if (waiting)
            paused = now;
        else if (paused != 0) {
            lastAdvance += now - paused;
            paused = 0;
        }
    }

    /** Reads the remaining no-progress allowance, with no whole-operation deadline. / 读取剩余无进展容限，不设整项操作期限。
     * @param timeout positive no-progress interval / 正无进展时长
     * @return remaining monotonic nanoseconds / 剩余单调纳秒数 */
    synchronized long remaining(long timeout) {
        return paused == 0 ? timeout - (System.nanoTime() - lastAdvance) : timeout;
    }

    /** Returns current immutable progress. / 返回当前不可变进度。
     * @return current stage observation / 当前阶段观测 */
    synchronized TransferProgress snapshot() {
        return new TransferProgress(id, stage, completed, total);
    }
}
