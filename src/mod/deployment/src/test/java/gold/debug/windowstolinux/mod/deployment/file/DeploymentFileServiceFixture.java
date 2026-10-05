package gold.debug.windowstolinux.mod.deployment.file;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;

import gold.debug.windowstolinux.appapi.file.FileService;
import gold.debug.windowstolinux.appapi.file.action.FileActionService;
import gold.debug.windowstolinux.appapi.file.query.FileQueryService;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshotService;
import gold.debug.windowstolinux.appapi.file.source.FileSourceService;
import gold.debug.windowstolinux.appapi.file.work.FileWorkspaceService;
import gold.debug.windowstolinux.appapi.file.material.FileMaterialService;

/** Intercepts actual responsibility calls without intercepting entry accessors. / 拦截实际职责调用，不将入口访问器作为操作。 */
public final class DeploymentFileServiceFixture {
    private DeploymentFileServiceFixture() {
    }

    /** Creates a grouped file fixture sharing one original observer. / 创建共用一个原观察器的分组文件夹具。
     * @param loader original test loader / 原测试加载器
     * @param handler original operation observer / 原操作观察器
     * @return grouped entry / 分组入口 */
    public static FileService create(ClassLoader loader, InvocationHandler handler) {
        var views = new HashMap<Class<?>, Object>();
        for (var type : new Class<?>[]{FileQueryService.class, FileActionService.class, FileSourceService.class,
            FileSnapshotService.class, FileWorkspaceService.class, FileMaterialService.class})
            views.put(type, Proxy.newProxyInstance(loader, new Class<?>[]{type}, handler));
        return (FileService) Proxy.newProxyInstance(loader, new Class<?>[]{FileService.class},
            (proxy, method, arguments) -> views.containsKey(method.getReturnType())
                ? views.get(method.getReturnType()) : handler.invoke(proxy, method, arguments));
    }

    /** Invokes a method on its actual responsibility view. / 在实际职责视图上调用方法。
     * @param method current contract method / 当前合同方法
     * @param original original entry / 原入口
     * @param arguments original arguments / 原参数
     * @return observed result / 已观察结果
     * @throws ReflectiveOperationException if the original invocation fails / 原调用失败时 */
    public static Object invoke(Method method, FileService original, Object[] arguments) throws ReflectiveOperationException {
        return method.invoke(view(method, original), arguments);
    }

    /** Selects the original method owner for an observer. / 为观察器选取原方法所有者。
     * @param method current contract method / 当前合同方法
     * @param original original entry / 原入口
     * @return actual responsibility view / 实际职责视图 */
    public static Object view(Method method, FileService original) {
        return switch (method.getDeclaringClass().getSimpleName()) {
            case "FileQueryService" -> original.queries();
            case "FileActionService" -> original.actions();
            case "FileSourceService" -> original.sources();
            case "FileSnapshotService" -> original.snapshots();
            case "FileWorkspaceService" -> original.workspaces();
            case "FileMaterialService" -> original.materials();
            default -> original;
        };
    }
}
