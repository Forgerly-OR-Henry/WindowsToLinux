package gold.debug.windowstolinux.app.linux.recovery;

import java.time.Instant;
import java.util.Objects;

/** Identifies one actual browser process without granting authority to signal it. / 标识一个实际浏览器进程，不授予向其发信号的权限。
 * @param pid original native process identifier / 原原生进程标识
 * @param started original operating-system start time / 原操作系统启动时间 */
public record BrowserProcess(long pid, Instant started) {
    /** Validates durable non-secret process facts. / 校验可持久化的非秘密进程事实。
     * @param pid original native process identifier / 原原生进程标识
     * @param started original operating-system start time / 原操作系统启动时间 */
    public BrowserProcess {
        if (pid <= 0)
            throw new IllegalArgumentException("Invalid browser process identity");
        Objects.requireNonNull(started);
    }

    /** Captures a proven descendant of the current host process. / 捕获经证实属于当前宿主的后代进程。
     * @param pid browser-reported process identifier / 浏览器报告的进程标识
     * @return exact original identity / 精确原身份 */
    static BrowserProcess capture(long pid) {
        var process = ProcessHandle.of(pid).orElseThrow();
        boolean owned = false;
        for (var parent = process.parent(); parent.isPresent(); parent = parent.get().parent())
            if (parent.get().pid() == ProcessHandle.current().pid()) {
                owned = true;
                break;
            }
        if (!owned)
            throw new IllegalStateException("Browser is not owned by the current host");
        return new BrowserProcess(pid, process.info().startInstant().orElseThrow());
    }

    /** Proves exit or PID reuse; inaccessible start time cannot prove release. / 证明退出或 PID 复用，无法读取开始时间不能证明释放。
     * @return whether this original process may still be alive / 原进程是否可能仍存活 */
    public boolean alive() {
        var current = ProcessHandle.of(pid);
        return current.isPresent() && current.get().isAlive()
                && current.get().info().startInstant().map(started::equals).orElse(true);
    }
}
