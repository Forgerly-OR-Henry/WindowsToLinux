package gold.debug.windowstolinux.appapi.ui.intervention;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** In-memory decision lifetime, independent of durable task waiting. / 与持久任务等待独立的内存决定寿命。
 * @param <T> accepted decision / 已接受决定 */
public interface UiInterventionSession<T> {
    /** Returns this transient request identity. / 返回本次临时请求标识。
     * @return request identifier, distinct from task identity / 请求标识，与任务标识不同 */
    UUID id();

    /** Observes acceptance or explicit withdrawal; deferral leaves it pending. / 观察接受或明确撤回，延期时仍待决定。
     * @return read-only original decision stage / 只读原决定阶段 */
    CompletionStage<T> completion();

    /** Explicitly withdraws this request; closing the host window merely defers it. / 明确撤回请求，关闭宿主窗口仅延期。 */
    void cancel();
}
