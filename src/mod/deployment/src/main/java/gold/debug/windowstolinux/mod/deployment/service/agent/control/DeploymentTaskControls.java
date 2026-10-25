package gold.debug.windowstolinux.mod.deployment.service.agent.control;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Mod-owned safe boundaries; never suspends a physical operation. / Mod 所属安全边界，绝不挂起物理操作。 */
public final class DeploymentTaskControls implements
    gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow.TaskBoundary {
    private final gold.debug.windowstolinux.appapi.mod.ModContext context;
    private final Map<UUID, DeploymentAgentControl> live = new ConcurrentHashMap<>();
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();

    public DeploymentTaskControls(gold.debug.windowstolinux.appapi.mod.ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    @Override public AutoCloseable begin(TaskContext task) {
        var control = new DeploymentAgentControl();
        if (live.putIfAbsent(task.id(), control) != null) throw new IllegalStateException("Original task already active");
        return () -> { waiting.remove(task.id()); live.remove(task.id(), control); control.finish(); };
    }

    public boolean waiting(UUID id) { return waiting.contains(id); }

    public void waiting(UUID id, boolean value) {
        if (value && live.containsKey(id)) waiting.add(id); else waiting.remove(id);
    }

    public Optional<DeploymentAgentControl.State> state(UUID id) {
        return Optional.ofNullable(live.get(id)).map(DeploymentAgentControl::state);
    }

    public void request(UUID id, DeploymentAgentControl.CommandAction command) {
        var control = live.get(id);
        if (control == null) throw new IllegalStateException("Original standard worker is unavailable");
        control.request(command);
    }

    /** Called only between completed workflow stages, with no in-flight remote command. */
    @Override public void checkpoint(TaskContext task) throws Exception {
        var control = live.get(task.id());
        if (control == null) return;
        control.checkpoint(task, new DeploymentAgentControl.Boundary() {
            public void paused() {
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.taskControl(context, task, true);
            }
            public void resumed() {
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.taskControl(context, task, false);
            }
            public boolean refresh() { throw new IllegalStateException("Standard task does not replace models"); }
        });
    }
}
