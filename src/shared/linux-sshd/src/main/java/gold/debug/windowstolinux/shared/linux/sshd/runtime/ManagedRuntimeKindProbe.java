package gold.debug.windowstolinux.shared.linux.sshd.runtime;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedReleaseProtocolExecutor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reads the root-owned current-release markers through the fixed helper protocol. / 通过固定 helper 协议读取 root 所有的当前发布标记。 */
public final class ManagedRuntimeKindProbe {
    private final SshCommandExecutor commands;
    private final ManagedReleaseProtocolExecutor protocol;

    /** Creates a managed runtime kind probe. / 创建受管运行时类型探测器。 */
    public ManagedRuntimeKindProbe(SshCommandExecutor commands, ManagedReleaseProtocolExecutor protocol) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    /** Identifies one current release only after helper ownership verification. / 仅在 helper 验证归属后识别当前发布。 */
    ManagedRuntimeIdentity inspect(ManagedApplication application) throws LinuxOperationException {
        var result = commands.execProtocol(protocol.command("inspect-runtime", application.id(),
                application.ownershipManifestSha256()), Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.runtimeObservationFailed",
                    "Controlled helper could not identify the managed runtime: " + result.failureEvidence());
        }
        Map<String, String> values = SshCommandExecutor.lines(result.output());
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
