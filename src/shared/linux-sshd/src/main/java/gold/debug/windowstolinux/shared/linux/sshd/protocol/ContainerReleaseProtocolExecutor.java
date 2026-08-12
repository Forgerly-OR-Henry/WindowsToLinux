package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies the distinct Docker restart-policy and Podman Quadlet release protocol.
 *
 * <p>应用彼此独立的 Docker 重启策略与 Podman Quadlet 发布协议。
 */
public final class ContainerReleaseProtocolExecutor {
    private final SshCommandExecutor commands;

    /** Creates the container release protocol executor. / 创建容器发布协议执行器。 */
    public ContainerReleaseProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** Captures a container rollback snapshot. / 捕获容器回滚快照。 */
    public ReleaseSnapshot snapshot(ManagedApplication application, DeploymentRuntimeSpecification.Container runtime)
            throws LinuxOperationException {
        var result = commands.execProtocol(command("snapshot-container", application, runtime), Duration.ofSeconds(30), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.snapshotIdentityUnverified",
                    "Controlled helper could not verify the existing managed container: " + result.failureEvidence());
        }
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        if (!"1".equals(values.get("PREVIOUS"))) {
            return ReleaseSnapshot.firstDeployment("No previous managed container release exists");
        }
        String token = values.get("SNAPSHOT_TOKEN");
        if (token == null || !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw LinuxOperationException.localized("linux.error.snapshotTokenMissing",
                    "Controlled helper did not return a verifiable container rollback token");
        }
        return ReleaseSnapshot.withPreviousRelease(token, "1".equals(values.get("PREVIOUS_RUNNING")),
                "Controlled helper saved the previous managed container state");
    }

    /** Publishes exactly one reviewed container. / 发布恰好一个经审阅的容器。 */
    public RemoteStepResult publish(ManagedApplication application, RemoteWorkspace workspace, DeploymentBuildResult build,
                                    String releaseIdentity, DeploymentRuntimeSpecification.Container runtime,
                                    DeploymentInputManifest inputs, ReleaseSnapshot snapshot) throws LinuxOperationException {
        if (!build.succeeded()) {
            throw LinuxOperationException.localized("linux.error.unverifiedBuildPublish",
                    "An unverified container build cannot be published");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> values = new ArrayList<>(List.of(application.id(), workspace.candidateId(), releaseIdentity,
                application.ownershipManifestSha256()));
        values.addAll(DeploymentInputArguments.from(inputs));
        values.addAll(ContainerRuntimeArguments.from(runtime));
        return step("publish-container", values, "Controlled helper started the reviewed managed container");
    }

    /** Rolls back a managed container. / 回滚受管容器。 */
    public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot,
                                     DeploymentBuildResult build, String releaseIdentity,
                                     DeploymentRuntimeSpecification.Container runtime, DeploymentInputManifest inputs)
            throws LinuxOperationException {
        if (!build.succeeded()) {
            throw LinuxOperationException.localized("linux.error.unverifiedBuildRollback",
                    "An unverified container build cannot be rolled back");
        }
        List<String> values = new ArrayList<>(List.of(application.id(), releaseIdentity,
                application.ownershipManifestSha256()));
        if (snapshot.hasPreviousRelease()) {
            values.add(snapshot.rollbackToken().orElseThrow());
            return step("rollback-container", values, "Controlled helper restored the previous managed container");
        }
        return step("rollback-container-first", values, "Controlled helper removed the failed first container release");
    }

    /** Executes a container lifecycle action. / 执行容器生命周期动作。 */
    public RemoteStepResult lifecycle(ManagedApplication application, String action) throws LinuxOperationException {
        List<String> values = new ArrayList<>(List.of(application.id(), action, application.ownershipManifestSha256()));
        return step("lifecycle-container", values, "Controlled helper executed the managed container lifecycle action");
    }

    private String command(String verb, ManagedApplication application, DeploymentRuntimeSpecification.Container runtime) {
        return helperCommand(verb, List.of(application.id(), application.ownershipManifestSha256()));
    }

    private RemoteStepResult step(String verb, List<String> values, String successEvidence) throws LinuxOperationException {
        var result = commands.exec(helperCommand(verb, values), Duration.ofSeconds(120), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(),
                result.succeeded() ? successEvidence : result.failureEvidence());
    }

    private static String helperCommand(String verb, List<String> values) {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(SshCommandExecutor.quote(ManagedHelperBundle.PATH)).append(' ')
                .append(SshCommandExecutor.quote(verb));
        for (String value : values) {
            command.append(' ').append(SshCommandExecutor.quote(Objects.requireNonNull(value, "helper argument")));
        }
        return command.toString();
    }
}
