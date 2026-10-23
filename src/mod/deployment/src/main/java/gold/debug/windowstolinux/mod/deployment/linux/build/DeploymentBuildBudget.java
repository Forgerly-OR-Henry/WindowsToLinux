package gold.debug.windowstolinux.mod.deployment.linux.build;

/** Counts only actual remote command execution against one reviewed build budget; human review pauses it. / 仅将实际远端命令执行计入同一已审阅构建预算；人工审核期间暂停。 */
public final class DeploymentBuildBudget {
    public record Snapshot(long limitNanos, long usedNanos) {
        public Snapshot {
            if (limitNanos < java.time.Duration.ofSeconds(60).toNanos()
                || limitNanos > java.time.Duration.ofSeconds(7200).toNanos() || usedNanos < 0 || usedNanos > limitNanos)
                throw new IllegalArgumentException("Invalid retained build budget");
        }
    }

    /** Original cumulative execution limit. / 原累计执行上限。 */
    private final long limitNanos;

    /** Time spent inside actual public remote execute calls. / 已在公开远端执行调用内耗费的时间。 */
    private long usedNanos;

    /** Starts one bounded build budget. / 开始一个有界构建预算。
     * @param seconds reviewed total seconds / 已审阅总秒数 */
    public DeploymentBuildBudget(int seconds) {
        if (seconds < 60 || seconds > 7200)
            throw new IllegalArgumentException("Invalid total build budget");
        limitNanos = java.time.Duration.ofSeconds(seconds).toNanos();
    }

    public DeploymentBuildBudget(Snapshot retained) {
        limitNanos = retained.limitNanos();
        usedNanos = retained.usedNanos();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(limitNanos, usedNanos);
    }

    /** Returns whole remaining seconds, without resetting between candidates. / 返回剩余整秒，不在候选之间重置。
     * @return remaining whole seconds / 剩余整秒 */
    public synchronized long remainingSeconds() {
        return Math.max(0, (limitNanos - usedNanos) / 1_000_000_000L);
    }

    /** Charges only the public remote execution interval after its return or exception. / 仅在公开远端执行返回或异常后计入该区间。
     * @param elapsedNanos observed local monotonic duration / 本地单调计时所得时长 */
    public synchronized void charge(long elapsedNanos) {
        usedNanos += Math.min(limitNanos - usedNanos, Math.max(0, elapsedNanos));
    }
}
