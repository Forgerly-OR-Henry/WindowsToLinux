package gold.debug.windowstolinux.shared.linux.sshd.runtime.dispatch;

import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Objects;
import java.util.Optional;

/** Identifies the sealed runtime kind without persisting a duplicate runtime specification. / 在不持久化重复运行时规格的情况下识别已封存运行时类型。 */
public record ManagedRuntimeIdentity(Kind kind, Optional<DeploymentRuntimeSpecification.ContainerEngine> containerEngine) {
    /** Creates an instance of this type. / 创建此类型的实例。 */
    public ManagedRuntimeIdentity {
        kind = Objects.requireNonNull(kind, "kind");
        containerEngine = Objects.requireNonNull(containerEngine, "containerEngine");
        if ((kind == Kind.CONTAINER) != containerEngine.isPresent()) {
            throw new IllegalArgumentException("only a container runtime may declare an engine");
        }
    }

    enum Kind { /** Represents the {@code ORDINARY} value. / 表示 {@code ORDINARY} 值。 */
    ORDINARY, /** Represents the {@code DEPLOYMENT} value. / 表示 {@code DEPLOYMENT} 值。 */
    DEPLOYMENT, /** Represents the {@code CONTAINER} value. / 表示 {@code CONTAINER} 值。 */
    CONTAINER }
}
