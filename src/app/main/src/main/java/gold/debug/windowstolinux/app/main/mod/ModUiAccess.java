package gold.debug.windowstolinux.app.main.mod;

import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.mod.service.ServiceValueCopier;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.ui.mod.UiExtensionAccess;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionPoint;

/** Actual-loader policy for public UI contexts and declared dependencies. / 公开界面上下文及显式依赖的实际加载器策略。 */
final class ModUiAccess implements UiExtensionAccess {
    /** Original activation loader. / 原启用加载器。 */
    private final ModClassLoader loader;

    /** Captures the actual original loader. / 捕获实际原加载器。
     * @param loader original activation / 原启用 */
    ModUiAccess(ModClassLoader loader) {
        this.loader = Objects.requireNonNull(loader);
    }

    /** Returns original release identity. / 返回原发布身份。
     * @return actual release / 实际版本 */
    @Override
    public String version() {
        return loader.manifest().version().toString();
    }

    /** Checks original physical availability. / 核对原物理可用性。
     * @return whether the original loader remains open / 原加载器是否仍打开 */
    @Override
    public boolean active() {
        return !loader.isClosed();
    }

    /** Checks shared public type identity and provider ownership when declaring. / 核对共享公开类型身份及声明时的提供方归属。
     * @param type context type / 上下文类型
     * @param owned whether declaring a provider position / 是否声明提供方位置 */
    @Override
    public void checkContextType(Class<?> type, boolean owned) {
        if (!publicType(type) || owned && type.getClassLoader() instanceof ModClassLoader provider && provider != loader)
            throw new IllegalArgumentException("Context type is not owned or public");
    }

    /** Validates direct declaration, version and exact exported type. / 校验直接声明、版本及精确导出类型。
     * @param target original destination / 原目的地
     * @param version actual release if published / 已发布时的实际版本 */
    @Override
    public void checkTarget(UiExtensionPoint<?> target, Optional<String> version) {
        if (!target.providerId().equals("platform") && !target.providerId().equals(loader.manifest().id())) {
            var dependency = loader.manifest().dependencies().stream()
                .filter(value -> value.id().equals(target.providerId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("UI provider is not declared"));
            if (version.isPresent() && !dependency.versionRange().contains(SemanticVersion.parse(version.orElseThrow())))
                throw new IllegalArgumentException("UI provider release differs");
        }
        if (!publicType(target.contextType()))
            throw new IllegalArgumentException("UI context Class identity differs");
        if (target.contextType().getClassLoader() instanceof ModClassLoader provider
            && !provider.manifest().id().equals(target.providerId()))
            throw new IllegalArgumentException("UI context is not exported by its provider");
    }

    /** Copies values through the existing detached public-value mechanism. / 经现有分离公开值机制复制数据。
     * @param value original value / 原值
     * @return detached value / 分离值
     * @throws Exception if copying fails / 复制失败时 */
    @Override
    public Object copy(Object value) throws Exception {
        return ServiceValueCopier.copy(value, this::publicType);
    }

    /** Checks exact JDK, API or declared exported Class identity. / 核对精确 JDK、API 或显式导出 Class 身份。
     * @param type actual type / 实际类型
     * @return whether shared and public / 是否共享且公开 */
    private boolean publicType(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) return false;
        if (type.getClassLoader() == null) return true;
        try {
            if (loader.loadClass(type.getName()) != type) return false;
            if (type.getName().startsWith("gold.debug.windowstolinux.appapi."))
                return type.getClassLoader() == UiExtensionPoint.class.getClassLoader();
            if (!(type.getClassLoader() instanceof ModClassLoader provider) || provider.isClosed()) return false;
            boolean exported = provider.manifest().exports().stream()
                .anyMatch(name -> type.getPackageName().equals(name) || type.getPackageName().startsWith(name + "."));
            return exported && (provider == loader || loader.manifest().dependencies().stream()
                .anyMatch(dependency -> dependency.id().equals(provider.manifest().id())
                    && dependency.versionRange().contains(provider.manifest().version())));
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }
}
