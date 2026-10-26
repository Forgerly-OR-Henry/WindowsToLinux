package gold.debug.windowstolinux.appapi.ui.intervention;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Creates a fresh form on the EDT for each explicit opening. / 每次明确打开时在 EDT 创建新表单。
 * @param <T> accepted decision / 已接受决定 */
@FunctionalInterface
public interface UiInterventionFormFactory<T> {
    /** Builds fresh form state without retaining secret values from earlier openings. / 创建新表单状态，不保留先前打开时的秘密值。
     * @return one disposable form / 单次可释放表单
     * @throws ApiException if the original input flow is unavailable / 原输入流程不可用时 */
    UiInterventionForm<T> create() throws ApiException;
}
