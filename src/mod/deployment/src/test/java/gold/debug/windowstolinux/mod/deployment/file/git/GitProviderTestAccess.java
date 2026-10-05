package gold.debug.windowstolinux.mod.deployment.file.git;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Function;
import java.util.Map;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.process.ProcessService;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Observes the actual isolated provider in host fixtures without adding a production hook. / 宿主夹具观察实际隔离提供方，不增加生产钩子。 */
final class GitProviderTestAccess {
    private static final String OWNER = "gold.debug.windowstolinux.mod.git";

    private GitProviderTestAccess() {
    }

    static void observeProcesses(PlatformRuntime runtime, ModContext consumer, TaskContext task,
                                 Function<ModContext, ProcessService> observer) throws Exception {
        var loader = GitRuntimeTestBridge.loader(runtime, OWNER);
        var registry = runtime.services();
        synchronized (registry) {
            var bindingsField = registry.getClass().getDeclaredField("bindings");
            bindingsField.setAccessible(true);
            var bindings = (Map<?, ?>) bindingsField.get(registry);
            for (var binding : bindings.values()) {
                var ownedLoader = binding.getClass().getDeclaredField("loader");
                ownedLoader.setAccessible(true);
                if (ownedLoader.get(binding) != loader) continue;
                var registrationsField = binding.getClass().getDeclaredField("registrations");
                registrationsField.setAccessible(true);
                var registrations = (Map<?, ?>) registrationsField.get(binding);
                var registration = registrations.get(loader.loadClass(OWNER + ".api.GitSourceService"));
                var implementationField = registration.getClass().getDeclaredField("implementation");
                implementationField.setAccessible(true);
                var actual = implementationField.get(registration);
                var field = actual.getClass().getDeclaredField("context");
                field.setAccessible(true);
                var original = (ModContext) field.get(actual);
                var processes = observer.apply(original);
                var intercepted = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                    new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("processes")) return processes;
                        try {
                            return method.invoke(original, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
                field.set(actual, intercepted);
                return;
            }
            throw new AssertionError("Actual Git service registration is missing");
        }
    }
}
