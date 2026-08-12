package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;
import java.util.Optional;

/** Identifies the sealed runtime kind without persisting a duplicate runtime specification. / 在不持久化重复运行时规格的情况下识别已封存运行时类型。 */
record ManagedRuntimeIdentity(Kind kind, Optional<DeploymentRuntimeSpecification.ContainerEngine> containerEngine) {
    ManagedRuntimeIdentity {
        kind = Objects.requireNonNull(kind, "kind");
        containerEngine = Objects.requireNonNull(containerEngine, "containerEngine");
        if ((kind == Kind.CONTAINER) != containerEngine.isPresent()) {
            throw new IllegalArgumentException("only a container runtime may declare an engine");
        }
    }

    enum Kind { ORDINARY, DEPLOYMENT, CONTAINER }
}
