package gold.debug.windowstolinux.appapi.ui.intervention;

import javax.swing.JComponent;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** One EDT-owned form mounted in the host review window. / 挂载于宿主核验窗口、由 EDT 持有的单次表单。
 * @param <T> decision returned only after explicit acceptance / 仅明确接受后返回的决定 */
public interface UiInterventionForm<T> extends AutoCloseable {
    /** Returns the component owned by this one form opening. / 返回本次表单打开持有的组件。
     * @return current author component / 当前作者组件 */
    JComponent component();

    /** Validates current input and returns the decision; performs no deployment or other business work.
     * / 校验当前输入并返回决定，不执行部署等业务操作。
     * @return accepted decision; caller owns any returned secret buffer / 已接受决定，调用方持有返回的秘密缓冲区
     * @throws ApiException if current input is invalid / 当前输入无效时 */
    T submit() throws ApiException;

    /** Clears sensitive input on every acceptance, deferral, cancellation and invalidation. / 每次接受、延期、取消和失效时清除秘密输入。 */
    @Override
    void close();
}
