package gold.debug.windowstolinux.appapi.task.control;

/** Immutable Mod observation; never authorizes an action. / 不可变 Mod 观察，不作为动作授权。
 * @param state current Mod-local boundary / 当前 Mod 内部边界
 * @param pause pause is supported now / 当前支持暂停
 * @param resume same-worker continuation is supported now / 当前支持原工作继续
 * @param intervene an original handling flow is available / 原处理流程可用
 * @param reason bounded explanation when unavailable / 不可用时的有界原因 */
public record TaskControlSnapshot(State state, boolean pause, boolean resume, boolean intervene, String reason) {
    public enum State { RUNNING, PAUSE_REQUESTED, PAUSED, WAITING_USER, UNAVAILABLE }

    public TaskControlSnapshot {
        java.util.Objects.requireNonNull(state);
        if (reason == null || reason.length() > 1024 || pause && resume
            || pause && state != State.RUNNING || resume && state != State.PAUSED)
            throw new IllegalArgumentException("Invalid task control observation");
    }

    public static TaskControlSnapshot unavailable(String reason) {
        return new TaskControlSnapshot(State.UNAVAILABLE, false, false, false, reason);
    }

    public boolean allows(TaskCommand command) {
        return switch (command) { case PAUSE -> pause; case RESUME -> resume; case INTERVENE -> intervene; };
    }
}
