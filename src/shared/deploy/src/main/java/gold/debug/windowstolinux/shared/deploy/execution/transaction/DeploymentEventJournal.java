package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Retains transaction evidence and publishes each actual event as it occurs. / 保留事务证据，并在每个实际事件发生时发布。 */
final class DeploymentEventJournal extends ArrayList<DeploymentEvent> {
    private final Consumer<DeploymentEvent> progress;
    DeploymentEventJournal(Consumer<DeploymentEvent> progress) { this.progress = java.util.Objects.requireNonNull(progress); }
    @Override public boolean add(DeploymentEvent event) {
        boolean added = super.add(event);
        progress.accept(event);
        return added;
    }
}
