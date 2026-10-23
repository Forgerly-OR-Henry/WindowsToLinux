package gold.debug.windowstolinux.appapi.ui.extension;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;

/** Owned contribution and observable cleanup state. / 所属扩展及可观测清理状态。 */
public interface UiExtensionRegistration extends AutoCloseable {
    /** Returns the owner-local identity. / 返回所属者内身份。
     * @return original identity / 原身份 */
    String id();

    /** Observes current state without starting work. / 观察当前状态，不启动工作。
     * @return current state / 当前状态
     * @throws ApiException if EDT dispatch fails / EDT 调度失败时 */
    UiExtensionState state() throws ApiException;

    /** Returns a safe diagnostic without author input. / 返回不含作者输入的安全诊断。
     * @return optional current diagnostic / 可选当前诊断
     * @throws ApiException if EDT dispatch fails / EDT 调度失败时 */
    Optional<Problem> problem() throws ApiException;

    /** Revokes callbacks and releases every actual mount. / 撤销回调并释放全部实际挂载。
     * @throws ApiException if actual cleanup remains pending / 实际清理仍待完成时 */
    @Override
    void close() throws ApiException;
}
