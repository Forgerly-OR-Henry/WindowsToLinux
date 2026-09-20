package gold.debug.windowstolinux.web.service.contract;

import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.MultiComponentDeploymentPlanner;
import java.util.*;

/** Workspace-owned application topology, independent of HTTP or local filesystem paths. */
public record ManagedWebGraph(String applicationId, String healthOwner, List<ManagedWebComponent> components) {
    public ManagedWebGraph {
        components = List.copyOf(components);
        if (components.isEmpty() || components.size() > 64 || components.stream().noneMatch(c -> c.id().equals(healthOwner)))
            throw new IllegalArgumentException("Invalid managed application graph");
    }
    public MultiComponentDeploymentPlan plan() {
        var namespaces = new LinkedHashMap<String, String>(); var dependencies = new LinkedHashMap<String, List<String>>();
        for (var component : components) {
            if (namespaces.put(component.id(), component.application().id()) != null) throw new IllegalArgumentException("Duplicate component");
            dependencies.put(component.id(), component.dependencies());
        }
        return new MultiComponentDeploymentPlanner().restore(applicationId, namespaces, dependencies);
    }
}
