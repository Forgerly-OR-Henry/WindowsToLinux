package gold.debug.windowstolinux.app.ui.mod;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionPoint;

/** Assembly-supplied exact owner and shared-type policy. / 装配提供的精确所属者及共享类型策略。 */
public interface UiExtensionAccess {
    /** Returns the actual bound release. / 返回实际绑定版本。
     * @return actual release / 实际版本 */
    String version();

    /** Checks that the original loader remains available. / 核对原加载器仍可用。
     * @return whether author callbacks remain available / 作者回调是否仍可用 */
    boolean active();

    /** Checks a shared context and optional provider ownership. / 核对共享上下文及可选提供方归属。
     * @param type original shared type / 原共享类型
     * @param owned whether declaring a provider position / 是否声明提供方位置
     * @throws Exception if private or unavailable / 私有或不可用时 */
    void checkContextType(Class<?> type, boolean owned) throws Exception;

    /** Checks a declared target dependency and its actual release. / 核对已声明目标依赖及实际版本。
     * @param target original typed destination / 原类型明确目的地
     * @param version actual published version, absent before publication / 实际发布版本，发布前为空
     * @throws Exception if dependency or type identity differs / 依赖或类型身份不符时 */
    void checkTarget(UiExtensionPoint<?> target, Optional<String> version) throws Exception;

    /** Copies public values without live resources. / 复制公开值，不携带活动资源。
     * @param value original context / 原上下文
     * @return detached copy / 分离副本
     * @throws Exception if the value is not permitted / 值不允许时 */
    Object copy(Object value) throws Exception;
}
