package gold.debug.windowstolinux.mod.deployment.file.git;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

import gold.debug.windowstolinux.app.db.task.DependencyRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.file.material.MaterialRef;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;

/** Runs existing typed assertions against the actual isolated deployment code and shared Git exports. / 让既有类型断言运行于实际隔离部署代码及共享 Git 导出。 */
public final class GitRuntimeTestBridge {
    private static final String ROOT = "gold.debug.windowstolinux.mod.deployment.";

    private GitRuntimeTestBridge() {
    }

    static Map<DependencyRecovery, List<UUID>> retainedCalls(PlatformRuntime runtime, UUID task) throws Exception {
        var calls = new TaskLedger(runtime.persistence().database()).queries().dependencies(task);
        org.junit.jupiter.api.Assertions.assertFalse(calls.isEmpty());
        for (var identity : calls.keySet()) {
            org.junit.jupiter.api.Assertions.assertEquals("service.v2", identity.encode().protocol());
            org.junit.jupiter.api.Assertions.assertEquals("gold.debug.windowstolinux.mod.git", identity.provider());
            org.junit.jupiter.api.Assertions.assertEquals("1.0.0-dev.1", identity.version());
            org.junit.jupiter.api.Assertions.assertEquals("gold.debug.windowstolinux.mod.deployment", identity.caller());
            org.junit.jupiter.api.Assertions.assertEquals("1.1.0-dev.2", identity.callerVersion());
        }
        return calls;
    }

    static void onlyOriginalCallsRemain(PlatformRuntime runtime, UUID task,
                                        Map<DependencyRecovery, List<UUID>> original) throws Exception {
        org.junit.jupiter.api.Assertions.assertNotNull(original);
        var observed = retainedCalls(runtime, task);
        org.junit.jupiter.api.Assertions.assertEquals(original, observed);
        var expected = new HashSet<UUID>();
        original.values().forEach(expected::addAll);
        var actual = new HashSet<UUID>();
        try (var connection = runtime.persistence().database().openConnection();
             var query = connection.prepareStatement(
                 "SELECT lease_id,resource_kind,recovery_json FROM platform_task_resource WHERE task_id=? AND released_at IS NULL")) {
            query.setObject(1, task);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var recovery = rows.getString(3);
                    if (recovery.equals("{\"release\":\"TASK_END\"}")) continue;
                    org.junit.jupiter.api.Assertions.assertTrue(Set.of("MOD", "SCHEMA").contains(rows.getString(2)),
                        "An original physical resource or author checkpoint remains");
                    org.junit.jupiter.api.Assertions.assertEquals("service.v2", ResourceRecovery.decode(recovery).protocol());
                    actual.add(rows.getObject(1, UUID.class));
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    static ClassLoader loader(PlatformRuntime runtime, String owner) throws ReflectiveOperationException {
        var activation = runtime.modContexts().get(owner);
        var field = activation.getClass().getDeclaredField("loader");
        field.setAccessible(true);
        return (ClassLoader) field.get(activation);
    }

    public static Preparation preparation(PlatformRuntime runtime, ModContext context, Path executable) throws Exception {
        var loader = loader(runtime, context.identity().id());
        var type = loader.loadClass(DeploymentGitSetup.class.getName());
        return new Preparation(loader, type.getConstructor(ModContext.class, Path.class).newInstance(context, executable));
    }

    public static Flow flow(PlatformRuntime runtime, ModContext context) throws Exception {
        var loader = loader(runtime, context.identity().id());
        var type = loader.loadClass(ROOT + "service.core.DeploymentGitSourceFlow");
        return new Flow(loader, type.getConstructor(ModContext.class).newInstance(context));
    }

    public static StaticWorkflow staticWorkflow(PlatformRuntime runtime, ModContext context) throws Exception {
        var loader = loader(runtime, context.identity().id());
        var referenceType = loader.loadClass(ROOT + "service.core.DeploymentRefFlow");
        var references = referenceType.getConstructor(ModContext.class).newInstance(context);
        var type = loader.loadClass(DeploymentStaticWorkflow.class.getName());
        return new StaticWorkflow(loader, type.getConstructor(ModContext.class, referenceType).newInstance(context, references));
    }

    public static OperationResult<MaterialRef> reconcileOriginal(PlatformRuntime runtime, ModContext context,
                                                                 DeploymentGitSetup.Planned plan, TaskContext task) throws Exception {
        var loader = loader(runtime, context.identity().id());
        var type = loader.loadClass(DeploymentGitSetup.class.getName());
        try {
            var result = type.getMethod("reconcileOriginal", ModContext.class,
                    loader.loadClass(DeploymentGitSetup.Planned.class.getName()), TaskContext.class)
                .invoke(null, context, copy(plan, loader), task);
            @SuppressWarnings("unchecked") var actual = (OperationResult<MaterialRef>) result;
            if (actual.outcome() == gold.debug.windowstolinux.appapi.task.result.TaskResult.UNKNOWN)
                System.err.println("Original Git service reconciliation: " + actual.problem());
            return actual;
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof ApiException api) {
                System.err.println("Original Git reconciliation admission: " + api.problem());
                throw api;
            }
            throw new AssertionError(failure.getCause());
        }
    }

    private static Object invoke(Object receiver, String name, Object... arguments) throws ApiException {
        try {
            var method = Arrays.stream(receiver.getClass().getMethods())
                .filter(candidate -> candidate.getName().equals(name) && candidate.getParameterCount() == arguments.length)
                .findFirst().orElseThrow();
            method.setAccessible(true);
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof ApiException api) throw api;
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object copy(Object value, ClassLoader destination) {
        if (value == null) return null;
        if (value instanceof Optional<?> optional) return optional.map(item -> copy(item, destination));
        if (value instanceof List<?> list) return list.stream().map(item -> copy(item, destination)).toList();
        if (value instanceof Set<?> set) {
            var copied = new HashSet<>();
            set.forEach(item -> copied.add(copy(item, destination)));
            return Set.copyOf(copied);
        }
        if (value instanceof Map<?, ?> map) {
            var copied = new HashMap<>();
            map.forEach((key, item) -> copied.put(copy(key, destination), copy(item, destination)));
            return Map.copyOf(copied);
        }
        if (value instanceof OperationResult<?> result) {
            return new OperationResult<>(result.outcome(), result.value().map(item -> copy(item, destination)), result.problem());
        }
        if (!value.getClass().getName().startsWith(ROOT)) return value;
        try {
            var target = destination.loadClass(value.getClass().getName());
            if (value instanceof Enum<?> enumeration)
                return Arrays.stream(target.getEnumConstants()).filter(item -> ((Enum<?>) item).name().equals(enumeration.name()))
                    .findFirst().orElseThrow();
            if (!value.getClass().isRecord()) throw new AssertionError("Fixture copies only immutable author records");
            var parts = value.getClass().getRecordComponents();
            var types = new Class<?>[parts.length];
            var arguments = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType().getName().startsWith(ROOT)
                    ? destination.loadClass(parts[i].getType().getName()) : parts[i].getType();
                arguments[i] = copy(parts[i].getAccessor().invoke(value), destination);
            }
            return target.getConstructor(types).newInstance(arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T> T host(Object value) {
        @SuppressWarnings("unchecked") T result = (T) copy(value, GitRuntimeTestBridge.class.getClassLoader());
        return result;
    }

    public static final class Preparation {
        private final ClassLoader loader;

        private final Object actual;

        private Preparation(ClassLoader loader, Object actual) {
            this.loader = loader;
            this.actual = actual;
        }

        public DeploymentGitMaterial prepare(DeploymentGitRequest request, long bytes, int entries, TaskContext task,
                                             DeploymentGitSetup.Observer observer) throws ApiException {
            try {
                Object original = observer == null ? null : Proxy.newProxyInstance(loader,
                    new Class<?>[]{loader.loadClass(DeploymentGitSetup.Observer.class.getName())}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "planned" -> copy(observer.planned(), loader);
                            case "beforeRequest" -> {
                                observer.beforeRequest();
                                yield null;
                            }
                            case "receipt" -> {
                                observer.receipt(host(args[0]));
                                yield null;
                            }
                            default -> throw new AssertionError("Unexpected original observer method");
                        };
                    });
                return host(invoke(actual, "prepare", copy(request, loader), bytes, entries, task, original));
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(failure);
            }
        }

        public void closeTaskResources(TaskContext task) throws ApiException {
            invoke(actual, "closeTaskResources", task);
        }
    }

    public static final class Flow {
        private final ClassLoader loader;

        private final Object actual;

        private Flow(ClassLoader loader, Object actual) {
            this.loader = loader;
            this.actual = actual;
        }

        public Published prepareForPublication(DeploymentGitRequest request, Path executable, String name,
                                               long bytes, int entries, TaskContext task) throws ApiException {
            return new Published(invoke(actual, "prepareForPublication", copy(request, loader), executable, name, bytes, entries, task));
        }

        public TaskHandle<DeploymentProjectRecord> submit(DeploymentGitRequest request, Path executable, UUID project,
                                                          String name, Optional<DeploymentProjectRecord> expected,
                                                          Instant when, long bytes, int entries) throws ApiException {
            var original = (TaskHandle<?>) invoke(actual, "submit", copy(request, loader), executable, project, name,
                copy(expected, loader), when, bytes, entries);
            return new TaskHandle<>() {
                public UUID id() {
                    return original.id();
                }

                public TaskSnapshot snapshot() {
                    return original.snapshot();
                }

                public CompletionStage<OperationResult<DeploymentProjectRecord>> completion() {
                    return original.completion().thenApply(result -> new OperationResult<>(result.outcome(),
                        result.value().map(GitRuntimeTestBridge::<DeploymentProjectRecord>host), result.problem()));
                }

                public void requestCancel() throws ApiException {
                    original.requestCancel();
                }
            };
        }
    }

    public static final class Published implements AutoCloseable {
        private final Object actual;

        private Published(Object actual) {
            this.actual = actual;
        }

        public DeploymentGitMaterial material() throws ApiException {
            return host(invoke(actual, "material"));
        }

        public DeploymentProjectRecord save() throws ApiException {
            return host(invoke(actual, "save"));
        }

        public void close() throws ApiException {
            invoke(actual, "close");
        }
    }

    public static final class StaticWorkflow {
        private final ClassLoader loader;

        private final Object actual;

        private StaticWorkflow(ClassLoader loader, Object actual) {
            this.loader = loader;
            this.actual = actual;
        }

        public void validateReview(DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers answers) {
            try {
                invoke(actual, "validateReview", copy(prompt, loader), copy(answers, loader));
            } catch (ApiException failure) {
                throw new AssertionError(failure);
            }
        }

        public TaskHandle<Void> submit(ServerId server, DeploymentStaticWorkflow.Draft draft,
                                       Optional<DeploymentStaticWorkflow.GitSource> git,
                                       DeploymentStaticWorkflow.Reviewer reviewer) throws ApiException {
            try {
                var contract = loader.loadClass(DeploymentStaticWorkflow.Reviewer.class.getName());
                var observation = Proxy.newProxyInstance(loader, new Class<?>[]{contract}, (proxy, method, args) -> {
                    Object[] arguments = args == null ? new Object[0] : Arrays.stream(args)
                        .map(item -> copy(item, GitRuntimeTestBridge.class.getClassLoader())).toArray();
                    return copy(invoke(reviewer, method.getName(), arguments), loader);
                });
                @SuppressWarnings("unchecked") var handle = (TaskHandle<Void>) invoke(actual, "submit", server,
                    copy(draft, loader), copy(git, loader), observation);
                return handle;
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
