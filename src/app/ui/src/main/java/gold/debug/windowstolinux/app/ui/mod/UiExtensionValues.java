package gold.debug.windowstolinux.app.ui.mod;

import java.lang.reflect.Modifier;
import java.util.Optional;

import gold.debug.windowstolinux.app.mod.service.ServiceValueCopier;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionPoint;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;

/** Detached UI values and host-only type policy. / 分离的界面值及仅宿主类型策略。 */
final class UiExtensionValues {
    /** Host policy used before concrete Mod assembly and by APP points. / 具体 Mod 装配前及 APP 位置使用的宿主策略。 */
    static final UiExtensionAccess PLATFORM = new UiExtensionAccess() {
        /** Returns the current development release. / 返回当前开发版本。
         * @return actual release / 实际版本 */
        public String version() {
            return "1.0.0";
        }

        /** Reports the host as available. / 报告宿主可用。
         * @return true / 为真 */
        public boolean active() {
            return true;
        }

        /** Checks only JDK and platform public types. / 仅核对 JDK 和平台公开类型。
         * @param type requested type / 请求类型
         * @param owned whether declaring a provider position / 是否声明提供方位置 */
        public void checkContextType(Class<?> type, boolean owned) {
            if (!publicType(type)) throw new IllegalArgumentException("Private UI context type");
        }

        /** Checks the exact shared type. / 核对精确共享类型。
         * @param target requested destination / 请求目的地
         * @param version current release / 当前版本 */
        public void checkTarget(UiExtensionPoint<?> target, Optional<String> version) {
            checkContextType(target.contextType(), false);
        }

        /** Copies permitted host values. / 复制允许的宿主值。
         * @param value original value / 原值
         * @return detached value / 分离值
         * @throws Exception if copying fails / 复制失败时 */
        public Object copy(Object value) throws Exception {
            return ServiceValueCopier.copy(value, UiExtensionValues::publicType);
        }
    };

    /** Prevents allocation. / 禁止实例化。 */
    private UiExtensionValues() {
    }

    /** Checks JDK or exact platform API identity. / 核对 JDK 或精确平台 API 身份。
     * @param type actual type / 实际类型
     * @return whether public / 是否公开 */
    private static boolean publicType(Class<?> type) {
        return Modifier.isPublic(type.getModifiers()) && (type.getClassLoader() == null
            || type.getName().startsWith("gold.debug.windowstolinux.appapi.")
            && type.getClassLoader() == UiExtensionPoint.class.getClassLoader());
    }

    /** Copies one typed optional context. / 复制一个类型明确的可选上下文。
     * @param <C> actual context / 实际上下文
     * @param type expected type / 预期类型
     * @param value optional value / 可选值
     * @param access bound policy / 绑定策略
     * @return detached context / 分离上下文
     * @throws Exception if type or value differs / 类型或值不符时 */
    static <C> Optional<C> copy(Class<C> type, Optional<C> value, UiExtensionAccess access) throws Exception {
        if (value.isEmpty()) return Optional.empty();
        return Optional.of(type.cast(access.copy(value.orElseThrow())));
    }

    /** Copies a transient request before author navigation. / 作者导航前复制临时请求。
     * @param <C> payload type / 载荷类型
     * @param request original request / 原请求
     * @param access original owner policy / 原所属者策略
     * @return detached request / 分离请求
     * @throws Exception if copying fails / 复制失败时 */
    static <C> UiNavigationRequest<C> navigation(UiNavigationRequest<C> request, UiExtensionAccess access) throws Exception {
        access.checkContextType(request.contextType(), false);
        return new UiNavigationRequest<>(request.destination(), request.contextType(),
            copy(request.contextType(), request.context(), access));
    }
}
