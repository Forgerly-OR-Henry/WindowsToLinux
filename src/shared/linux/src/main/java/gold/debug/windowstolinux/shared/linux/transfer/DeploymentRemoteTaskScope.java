package gold.debug.windowstolinux.shared.linux.transfer;
import java.util.*;
import java.util.function.Consumer;
/** Associates existing transaction candidates with one calling task; no executor is exposed. / 将既有事务候选项关联至调用任务，不暴露执行器。 */
public final class DeploymentRemoteTaskScope implements AutoCloseable {
    /** Worker-local task. / 工作线程任务。 */
    private static final ThreadLocal<DeploymentRemoteTaskScope> CURRENT=new ThreadLocal<>();
    /** Exact task identity. / 精确任务身份。 */
    private final String task;
    /** Durable intent sink. / 持久化意图端口。 */
    private final Consumer<RemoteWorkspace> intent;
    /** Creates the calling worker's task boundary. / 创建调用工作线程的任务边界。
     * @param task task identity / 任务身份
     * @param intent callback before any candidate mutation / 候选项变更前回调
     */
    public DeploymentRemoteTaskScope(String task,Consumer<RemoteWorkspace> intent){
        requireTask(task);if(CURRENT.get()!=null)throw new IllegalStateException("remote task already active");
        this.task=task;this.intent=Objects.requireNonNull(intent);CURRENT.set(this);
    }
    /** Validates a transport-safe opaque identity. / 校验可安全传输的不透明身份。
     * @param task task identity / 任务身份
     */
    public static void requireTask(String task){if(task==null||!task.matches("[a-zA-Z0-9-]{1,80}"))throw new IllegalArgumentException("invalid remote task identity");}
    /** Captures the scope on this worker. / 获取当前工作线程作用域。
     * @return optional scope / 可选作用域
     */
    public static Optional<DeploymentRemoteTaskScope> current(){return Optional.ofNullable(CURRENT.get());}
    /** Journals the exact candidate before creating it remotely. / 远端创建前记录精确候选项。
     * @param workspace bounded candidate / 有界候选项
     * @return task identity / 任务身份
     */
    public String preparing(RemoteWorkspace workspace){intent.accept(workspace);return task;}
    /** Clears only the owning worker's scope. / 仅清理所属工作线程作用域。 */
    @Override public void close(){if(CURRENT.get()!=this)throw new IllegalStateException("wrong remote task owner");CURRENT.remove();}
}
