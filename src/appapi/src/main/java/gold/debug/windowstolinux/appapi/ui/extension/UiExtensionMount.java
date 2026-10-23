package gold.debug.windowstolinux.appapi.ui.extension;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** One provider page mount and its transient selection. / 单个提供方页面挂载及临时选择。
 * @param <C> exact context type / 精确上下文类型 */
public interface UiExtensionMount<C> extends AutoCloseable {
    /** Updates selection and provider availability without rebuilding children. / 更新选择及提供方可用状态，不重建子项。
     * @param context current optional selection / 当前可选选择
     * @param enabled whether the provider is ready for interaction / 提供方是否可交互
     * @throws ApiException if context or dispatch is invalid / 上下文或调度无效时 */
    void update(Optional<C> context, boolean enabled) throws ApiException;

    /** Releases children and withdraws this physical mount. / 释放子项并撤销本次物理挂载。
     * @throws ApiException if child or host cleanup remains pending / 子项或宿主清理仍待完成时 */
    @Override
    void close() throws ApiException;
}
