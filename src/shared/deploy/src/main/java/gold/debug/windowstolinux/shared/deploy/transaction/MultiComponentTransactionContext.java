package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.result.deployment.ComponentDeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.deployment.ComponentTransactionState;
import gold.debug.windowstolinux.shared.deploy.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.result.deployment.MultiComponentDeploymentResult;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Holds the mutable state of one component inside a reviewed transaction. / 持有一次经审阅事务中单个组件的可变状态。 */
final class MultiComponentTransactionContext {
    final ReviewedComponentDeployment component;
    final List<DeploymentEvent> events = new ArrayList<>();
    ComponentTransactionState state = ComponentTransactionState.PRECONDITION_REJECTED;
    RemoteWorkspace workspace;
    DeploymentBuildResult build;
    DeploymentInputManifest inputs;
    ReleaseSnapshot snapshot;
    String releaseIdentity;
    boolean stopped;
    boolean published;
    boolean stopAttempted;
    boolean publishAttempted;
    LifecycleObservation observation;

    MultiComponentTransactionContext(ReviewedComponentDeployment component) {
        this.component = component;
    }

    void event(String step, boolean succeeded, String evidence) {
        events.add(new DeploymentEvent(step, succeeded, evidence));
    }

    ComponentDeploymentResult componentResult() {
        return new ComponentDeploymentResult(component.componentId(), state, events, Optional.ofNullable(observation));
    }

    static MultiComponentDeploymentResult result(DeploymentStatus status, List<DeploymentEvent> applicationEvents,
                                                  Map<String, MultiComponentTransactionContext> contexts,
                                                  Optional<String> identity) {
        List<ComponentDeploymentResult> results = contexts.values().stream()
                .map(MultiComponentTransactionContext::componentResult).toList();
        return new MultiComponentDeploymentResult(status, applicationEvents, results, identity);
    }
}
