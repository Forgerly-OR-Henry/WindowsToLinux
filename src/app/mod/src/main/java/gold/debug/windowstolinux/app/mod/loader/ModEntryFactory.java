package gold.debug.windowstolinux.app.mod.loader;

import java.lang.reflect.Modifier;

import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;

/** Constructs an entry only during activation, after inspecting its public shape and origin. / 仅在启用期间检查公开形状与来源后构造入口。 */
public final class ModEntryFactory {
    /** Prevents creation of this stateless activation helper. / 禁止创建此无状态启用辅助类实例。 */
    private ModEntryFactory() {
    }

    /**
     * Validates the entry without initialization, then invokes its public zero-argument constructor. / 不初始化地验证入口，再调用其公开无参构造器。
     * @param loader prepared owner loader after dependency and data checks / 完成依赖和数据检查后准备的所有者加载器
     * @return newly constructed public entry contract / 新构造的公开入口契约
     * @throws ReflectiveOperationException if entry shape, origin or construction fails / 入口形状、来源或构造失败时
     */
    public static ModEntrypoint create(ModClassLoader loader) throws ReflectiveOperationException {
        try {
            loader.inspectCode();
        } catch (java.io.IOException invalid) {
            throw new ReflectiveOperationException("Mod code admission failed", invalid);
        }
        var type = Class.forName(loader.manifest().entryClass(), false, loader);
        int modifiers = type.getModifiers();
        if (!loader.ownsMainClass(type) || !Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers)
                || type.isInterface() || !ModEntrypoint.class.isAssignableFrom(type))
            throw new ReflectiveOperationException("Main source must define a public concrete ModEntrypoint");
        var constructor = type.getConstructor();
        return ModEntrypoint.class.cast(constructor.newInstance());
    }
}
