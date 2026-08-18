package gold.debug.windowstolinux.shared.linux.sshd.runtime.dispatch;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.runtime.ManagedRuntimeController;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reads the root-owned current-release markers through the fixed helper protocol. / 通过固定 helper 协议读取 root 所有的当前发布标记。 */
public final class ManagedRuntimeKindProbe {
    private final ManagedRuntimeController runtimes;

    /** Creates a managed runtime kind probe. / 创建受管运行时类型探测器。 */
    public ManagedRuntimeKindProbe(ManagedRuntimeController runtimes) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
    }

    /** Identifies one current release only after helper ownership verification. / 仅在 helper 验证归属后识别当前发布。 */
    ManagedRuntimeIdentity inspect(ManagedApplication application) throws LinuxOperationException {
        Map<String, String> values = runtimes.inspect(application);
        return switch (values.getOrDefault("KIND", "")) {
            case "ordinary" -> new ManagedRuntimeIdentity(ManagedRuntimeIdentity.Kind.ORDINARY, Optional.empty());
            case "deployment" -> new ManagedRuntimeIdentity(ManagedRuntimeIdentity.Kind.DEPLOYMENT, Optional.empty());
            case "container" -> new ManagedRuntimeIdentity(ManagedRuntimeIdentity.Kind.CONTAINER,
                    Optional.of(parseEngine(values.get("ENGINE"))));
            default -> throw LinuxOperationException.localized("linux.error.runtimeObservationFailed",
                    "Controlled helper returned an unsupported managed runtime kind");
        };
    }

    private static DeploymentRuntimeSpecification.ContainerEngine parseEngine(String value)
            throws LinuxOperationException {
        try {
            return DeploymentRuntimeSpecification.ContainerEngine.valueOf(
                    Objects.requireNonNull(value, "container engine").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw LinuxOperationException.localized("linux.error.runtimeObservationFailed",
                    "Controlled helper returned an unsupported managed container engine", exception);
        }
    }
}
