package gold.debug.windowstolinux.appapi.ui.extension;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Owner-bound declarative extension registration. / 绑定所有者的声明式界面扩展登记。 */
public interface UiExtensionService {
    /** Declares a point under the actual bound provider. / 在真实绑定提供方下声明位置。
     * @param <C> shared immutable context / 共享不可变上下文
     * @param id provider-local position / 提供方内位置
     * @param contextType exact shared type / 精确共享类型
     * @param kind accepted form / 接受形式
     * @return owned point registration / 所属位置登记
     * @throws ApiException if identity, type or lifetime is invalid / 身份、类型或寿命无效时 */
    <C> UiExtensionPointRegistration<C> provide(String id, Class<C> contextType, UiExtensionKind kind) throws ApiException;

    /** Registers an action without opening the target page. / 登记动作，不打开目标页面。
     * @param <C> shared context / 共享上下文
     * @param point exact target / 精确目标
     * @param spec owned identity and ordering / 所属身份及排序
     * @param action lightweight callbacks / 轻量回调
     * @return owned contribution / 所属扩展
     * @throws ApiException if dependency, type or lifetime is invalid / 依赖、类型或寿命无效时 */
    <C> UiExtensionRegistration registerAction(UiExtensionPoint<C> point, UiExtensionSpec spec, UiAction<C> action) throws ApiException;

    /** Registers a lazy component factory without opening the target page. / 登记惰性组件工厂，不打开目标页面。
     * @param <C> shared context / 共享上下文
     * @param point exact target / 精确目标
     * @param spec owned identity and ordering / 所属身份及排序
     * @param factory fresh instance factory / 新实例工厂
     * @return owned contribution / 所属扩展
     * @throws ApiException if dependency, type or lifetime is invalid / 依赖、类型或寿命无效时 */
    <C> UiExtensionRegistration registerComponent(UiExtensionPoint<C> point, UiExtensionSpec spec, UiComponentFactory<C> factory) throws ApiException;
}
