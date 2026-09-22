package gold.debug.windowstolinux.shared.linux.command;

/** Mandatory review and durable dispatch hooks for an active AI task. / AI 活跃任务的强制审核及持久化派发钩子。 */
public interface RemoteCommandAudit {
    /** Reviews and journals intent before transport effects. / 在传输影响前审核并记录意图。
     * @param request exact command / 精确命令
     */
    void before(RemoteCommandRequest request);

    /** Persists intent only after the final evidence check. / 仅在最后证据检查后持久化执行意图。
     * @param request approved unchanged request / 已审批且未变化的请求
     */
    void dispatching(RemoteCommandRequest request);

    /** Records the actual result; unknown outcomes must block continuation. / 记录实际结果，未知结果必须阻断后续执行。
     * @param request dispatched command / 已派发命令
     * @param result actual result / 实际结果
     */
    void after(RemoteCommandRequest request, RemoteCommandResult result);

    /** Records an uncertain dispatch without permitting replay. / 记录不确定派发，不允许重放。
     * @param request dispatched command / 已派发命令
     */
    void unknown(RemoteCommandRequest request);
}
