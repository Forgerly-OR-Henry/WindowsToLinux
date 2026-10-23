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

    public String configurationRevision = "fixture-route";

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
            if (m.getName().equals("configurationRevision")) return configurationRevision;
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

    private final gold.debug.windowstolinux.appapi.task.TaskService tasks = approvalCheckpoints(task);

    /** Keeps only the author policy and empty legacy layout; it grants no execution capability. */
    public static gold.debug.windowstolinux.appapi.task.TaskService approvalCheckpoints(TaskContext task) {
        var checkpoints = new HashMap<String, gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint>();
        return (gold.debug.windowstolinux.appapi.task.TaskService) Proxy.newProxyInstance(
            gold.debug.windowstolinux.appapi.task.TaskService.class.getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (p, m, a) -> {
                if (m.getName().equals("findCheckpoint") && a[0] == task
                    && "deployment.config.remote-layout.v1".equals(a[1])) return Optional.empty();
                if (a[0] == task && gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.KEY.equals(a[1])) {
                    if (m.getName().equals("findCheckpoint")) return Optional.ofNullable(checkpoints.get(a[1]));
                    if (m.getName().equals("beginCheckpoint")) {
                        if (checkpoints.containsKey(a[1]))
                            throw new IllegalStateException("Original checkpoint already exists");
                        var checkpoint = new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint() {
                            private Snapshot state = new Snapshot(0, (String) a[2], false);

                            public Snapshot snapshot() {
                                return state;
                            }

                            public Snapshot advance(Snapshot expected, String next) {
                                if (!state.equals(expected) || state.released())
                                    throw new IllegalStateException("Original checkpoint changed");
                                return state = new Snapshot(state.revision() + 1, next, false);
                            }

                            public void close() {
                                state = new Snapshot(state.revision() + 1, state.payload(), true);
                            }
                        };
                        checkpoints.put((String) a[1], checkpoint);
                        return checkpoint;
                    }
                }
                throw new AssertionError("Unexpected task operation in legacy native fixture: " + m.getName());
            });
    }

    public final ModContext context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
        (p, m, a) -> switch (m.getName()) {
            case "ai" -> ai;
            case "logs" -> logs;
            case "tasks" -> tasks;
            case "identity" ->
                new gold.debug.windowstolinux.appapi.mod.model.ModIdentity("fixture", "1.0.0", Optional.of("1"));
            default -> throw new AssertionError("No execution capability in native protocol fixture: " + m.getName());
        });

    public void nativeReply(AiToolCall... calls) {
        replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "fixture-model", "tool_calls",
            new AiUsage(Optional.of(10L), Optional.of(2L), Optional.empty(), Optional.empty(), Optional.empty()), List.of(calls),
            Optional.of(new AiContinuation(UUID.randomUUID(), 1)))), Optional.empty()));
    }
}
