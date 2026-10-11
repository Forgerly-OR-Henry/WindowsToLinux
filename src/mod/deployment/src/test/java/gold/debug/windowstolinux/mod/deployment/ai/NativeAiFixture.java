package gold.debug.windowstolinux.mod.deployment.ai;

import java.lang.reflect.Proxy;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;

public final class NativeAiFixture {
    public final List<AiRequest> requests = new ArrayList<>();

    public final Deque<OperationResult<AiResponse>> replies = new ArrayDeque<>();

    public final List<LogEvent> events = new ArrayList<>();

    public Optional<ApiException> refusal = Optional.empty();

    private final UUID id = UUID.randomUUID();

    public final TaskContext task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class},
        (p, m, a) -> switch (m.getName()) {
            case "id" -> id;
            case "cancelToken" -> (CancelToken) () -> false;
            case "progress" -> null;
            default -> throw new AssertionError(m.getName());
        });

    private final AiService ai = (AiService) Proxy.newProxyInstance(AiService.class.getClassLoader(), new Class<?>[]{AiService.class},
        (p, m, a) -> {
            if (m.getName().equals("configurationRevision")) return "fixture-route";
            if (m.getName().equals("call")) {
                requests.add((AiRequest) a[0]);
                if (refusal.isPresent()) throw refusal.orElseThrow();
                return replies.removeFirst();
            }
            throw new AssertionError(m.getName());
        });

    private final LogService logs = (LogService) Proxy.newProxyInstance(LogService.class.getClassLoader(), new Class<?>[]{LogService.class},
        (p, m, a) -> {
            if (m.getName().equals("register")) return new LogTarget(UUID.randomUUID());
            if (m.getName().equals("write")) events.add((LogEvent) a[1]);
            return null;
        });

    private final gold.debug.windowstolinux.appapi.task.TaskService tasks =
        (gold.debug.windowstolinux.appapi.task.TaskService) Proxy.newProxyInstance(
            gold.debug.windowstolinux.appapi.task.TaskService.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (p, m, a) -> {
                if (m.getName().equals("findCheckpoint") && a[0] == task
                    && "deployment.config.remote-layout.v1".equals(a[1])) return Optional.empty();
                throw new AssertionError("Unexpected task operation in legacy native fixture: " + m.getName());
            });

    public final ModContext context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
        (p, m, a) -> switch (m.getName()) {
            case "ai" -> ai;
            case "logs" -> logs;
            case "tasks" -> tasks;
            default -> throw new AssertionError("No execution capability in native protocol fixture: " + m.getName());
        });

    public void nativeReply(AiToolCall... calls) {
        replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "fixture-model", "tool_calls",
            new AiUsage(Optional.of(10L), Optional.of(2L), Optional.empty(), Optional.empty(), Optional.empty()), List.of(calls),
            Optional.of(new AiContinuation(UUID.randomUUID(), 1)))), Optional.empty()));
    }
}
