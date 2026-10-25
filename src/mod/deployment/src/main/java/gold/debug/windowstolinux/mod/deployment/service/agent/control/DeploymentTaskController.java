package gold.debug.windowstolinux.mod.deployment.service.agent.control;

import java.util.*;
import java.util.function.*;
import javax.swing.SwingUtilities;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;

/** Adapts original Mod controllers; the host never learns deployment recovery rules. */
public final class DeploymentTaskController implements TaskController {
    private final ModContext context;
    private final Function<UUID, Optional<DeploymentAgentControl.State>> state;
    private final BiConsumer<UUID, DeploymentAgentControl.CommandAction> request;
    private final Predicate<UUID> waiting;

    public DeploymentTaskController(ModContext context, Function<UUID, Optional<DeploymentAgentControl.State>> state,
                                    BiConsumer<UUID, DeploymentAgentControl.CommandAction> request) {
        this(context, state, request, id -> false);
    }

    public DeploymentTaskController(ModContext context, Function<UUID, Optional<DeploymentAgentControl.State>> state,
                                    BiConsumer<UUID, DeploymentAgentControl.CommandAction> request, Predicate<UUID> waiting) {
        this.context = context; this.state = state; this.request = request; this.waiting = waiting;
    }

    @Override public TaskControlSnapshot snapshot(UUID id) {
        var observed = state.apply(id);
        if (observed.isEmpty() || observed.get() == DeploymentAgentControl.State.FINISHED)
            return TaskControlSnapshot.unavailable("TASK_CONTROL_UNAVAILABLE");
        if (waiting.test(id)) return new TaskControlSnapshot(TaskControlSnapshot.State.WAITING_USER,
            false, false, context.launchMode() == ModLaunchMode.NORMAL, "");
        var boundary = switch (observed.get()) {
            case RUNNING -> TaskControlSnapshot.State.RUNNING;
            case PAUSE_REQUESTED -> TaskControlSnapshot.State.PAUSE_REQUESTED;
            case PAUSED -> TaskControlSnapshot.State.PAUSED;
            case FINISHED -> TaskControlSnapshot.State.UNAVAILABLE;
        };
        return new TaskControlSnapshot(boundary, boundary == TaskControlSnapshot.State.RUNNING,
            boundary == TaskControlSnapshot.State.PAUSED, context.launchMode() == ModLaunchMode.NORMAL, "");
    }

    @Override public void request(UUID id, TaskCommand command) throws ApiException {
        if (!snapshot(id).allows(command)) throw unavailable();
        try {
            if (command != TaskCommand.INTERVENE) {
                request.accept(id, command == TaskCommand.PAUSE ? DeploymentAgentControl.CommandAction.PAUSE
                    : DeploymentAgentControl.CommandAction.RESUME);
                return;
            }
            var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
            Runnable open = () -> {
                try { context.ui().open("workspace", new UiNavigationRequest<>("intervene-task", UUID.class, Optional.of(id))); }
                catch (Exception rejected) { failure.set(rejected); }
            };
            if (SwingUtilities.isEventDispatchThread()) open.run(); else SwingUtilities.invokeAndWait(open);
            if (failure.get() != null) throw failure.get();
        } catch (Exception rejected) {
            if (rejected instanceof InterruptedException) Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(new Problem(ProblemCode.UNAVAILABLE, "DEPLOYMENT_TASK_CONTROL_UNAVAILABLE",
            "The original deployment task control is unavailable", Map.of(), Optional.empty()));
    }

    @Override public gold.debug.windowstolinux.appapi.task.log.LogReadResult readLogs(UUID id) throws ApiException {
        return new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context).read(id);
    }
}
