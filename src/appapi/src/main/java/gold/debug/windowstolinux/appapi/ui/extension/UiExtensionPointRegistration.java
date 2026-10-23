package gold.debug.windowstolinux.appapi.ui.extension;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Bound provider declaration with independently closable mounts. / 绑定提供方的位置声明，挂载可独立关闭。
 * @param <C> exact shared context / 精确共享上下文 */
public interface UiExtensionPointRegistration<C> extends AutoCloseable {
    /** Returns the host-bound destination. / 返回宿主绑定的目的地。
     * @return exact point / 精确位置 */
    UiExtensionPoint<C> point();

    /** Mounts into an author-owned anchor on the EDT. / 在 EDT 挂到作者所属位置。
     * @param host provider presentation adapter / 提供方展示适配器
     * @param context initial optional selection / 初始可选选择
     * @return original mount handle / 原挂载句柄
     * @throws ApiException if types or lifetime differ / 类型或寿命不符时 */
    UiExtensionMount<C> mount(UiExtensionHost host, Optional<C> context) throws ApiException;

    /** Withdraws the point and cleans all mounts; contributions may wait for a new point. / 撤销位置并清理全部挂载，扩展可等待新的位置。
     * @throws ApiException if actual cleanup remains pending / 实际清理仍待完成时 */
    @Override
    void close() throws ApiException;
}
