package gold.debug.windowstolinux.shared.linux.command;

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
public record RemoteCommandResult(boolean succeeded, boolean timedOut, String output, String evidenceOutput,
        String error, Integer exitStatus) {
    /** Distinguishes a confirmed exit from a timeout or lost result. / 区分已确认退出与超时或结果丢失。
     * @return whether the remote outcome is known / 远端结果是否已知
     */
    public boolean known() {
        return !timedOut && exitStatus != null;
    }

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
            return "exitCode=" + (exitStatus == null ? "unknown" : exitStatus) + ", error=" + error + ", output="
                    + evidenceOutput;
        }
        String detail = error.isBlank() ? evidenceOutput : error;
        if (!detail.isBlank()) {
            return "exitCode=" + (exitStatus == null ? "unknown" : exitStatus) + ", output=" + detail;
        }
        return "exitCode=" + (exitStatus == null ? "unknown" : exitStatus);
    }
}
