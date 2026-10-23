package gold.debug.windowstolinux.appapi.ui.extension;

/** Creates a fresh instance for each actual mount. / 每次实际挂载创建新实例。
 * @param <C> detached point context / 分离的位置上下文 */
@FunctionalInterface
public interface UiComponentFactory<C> {
    /** Allocates one independent lifecycle owner on the EDT. / 在 EDT 创建独立生命周期所有者。
     * @return fresh component owner / 新组件所有者
     * @throws Exception if allocation fails / 创建失败时 */
    UiComponent<C> create() throws Exception;
}
