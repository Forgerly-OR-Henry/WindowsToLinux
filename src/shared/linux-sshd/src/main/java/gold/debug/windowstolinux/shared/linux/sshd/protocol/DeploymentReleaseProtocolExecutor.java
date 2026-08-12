package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Uses the root-owned helper for reviewed non-container release snapshots, publication, and rollback.
 *
 * <p>使用 root 所有的辅助程序处理经审阅非容器发布的快照、发布和回滚。
 */
public final class DeploymentReleaseProtocolExecutor {
    private final SshCommandExecutor commands;

    /** Creates the release protocol executor. / 创建发布协议执行器。 */
    public DeploymentReleaseProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** Captures the current release only after helper ownership verification. / 仅在辅助程序验证归属后捕获当前发布。 */
    public ReleaseSnapshot snapshot(ManagedApplication application, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException {
        var result = commands.execProtocol(command("snapshot-deployment", application, runtime), Duration.ofSeconds(30), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.snapshotIdentityUnverified",
                    "Controlled helper could not verify the existing reviewed release: " + result.failureEvidence());
        }
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        if (!"1".equals(values.get("PREVIOUS"))) {
            return ReleaseSnapshot.firstDeployment("No previous reviewed release exists");
        }
        String token = values.get("SNAPSHOT_TOKEN");
        if (token == null || !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw LinuxOperationException.localized("linux.error.snapshotTokenMissing",
                    "Controlled helper did not return a verifiable rollback snapshot token");
        }
        return ReleaseSnapshot.withPreviousRelease(token, "1".equals(values.get("PREVIOUS_RUNNING")),
                "Controlled helper saved the previous reviewed release and runtime state");
    }

    /** Publishes the sealed candidate through the helper. / 通过辅助程序发布已封存候选版本。 */
    public RemoteStepResult publish(ManagedApplication application, RemoteWorkspace workspace, DeploymentBuildResult build,
                                    String releaseIdentity, DeploymentRuntimeSpecification runtime, ReleaseSnapshot snapshot)
            throws LinuxOperationException {
        if (!build.succeeded()) {
            throw LinuxOperationException.localized("linux.error.unverifiedBuildPublish",
                    "An unverified build result cannot be published");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> values = new ArrayList<>(List.of(application.id(), workspace.candidateId(), releaseIdentity,
                application.ownershipManifestSha256()));
        values.addAll(DeploymentRuntimeArguments.from(runtime));
        var result = commands.exec(helperCommand("publish-deployment", values), Duration.ofSeconds(120), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper sealed and started the reviewed candidate release"
                : "Controlled helper could not publish the reviewed candidate: " + result.failureEvidence());
    }

    /** Rolls back a reviewed candidate. / 回滚经审阅的候选版本。 */
    public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot,
                                     DeploymentBuildResult build, String releaseIdentity,
                                     DeploymentRuntimeSpecification runtime) throws LinuxOperationException {
        if (!build.succeeded()) {
            throw LinuxOperationException.localized("linux.error.unverifiedBuildRollback",
                    "An unverified build result cannot be rolled back");
        }
        List<String> values = new ArrayList<>(List.of(application.id(), releaseIdentity,
                application.ownershipManifestSha256()));
        if (snapshot.hasPreviousRelease()) {
            values.add(snapshot.rollbackToken().orElseThrow());
            values.addAll(DeploymentRuntimeArguments.from(runtime));
            return step("rollback-deployment", values, "Controlled helper restored the reviewed previous release");
        }
        values.addAll(DeploymentRuntimeArguments.from(runtime));
        return step("rollback-deployment-first", values, "Controlled helper removed the failed first reviewed release");
    }

    /** Executes a non-container lifecycle action. / 执行非容器生命周期动作。 */
    public RemoteStepResult lifecycle(ManagedApplication application, String action, DeploymentRuntimeSpecification runtime)
            throws LinuxOperationException {
        List<String> values = new ArrayList<>(List.of(application.id(), action, application.ownershipManifestSha256()));
        values.addAll(DeploymentRuntimeArguments.from(runtime));
        return step("lifecycle-deployment", values, "Controlled helper executed the reviewed lifecycle action");
    }

    /** Observes the currently sealed non-container runtime using its saved release parameters. / 使用保存的发布参数观察当前已封存的非容器运行时。 */
    public LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException {
        List<String> values = List.of(application.id(), application.ownershipManifestSha256());
        var result = commands.execProtocol(helperCommand("observe-deployment", values), Duration.ofSeconds(20), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.runtimeObservationFailed",
                    "Controlled helper could not verify the managed deployment runtime: " + result.failureEvidence());
        }
        Map<String, String> valuesByName = SshCommandExecutor.lines(result.output());
        boolean ownership = "1".equals(valuesByName.get("OWNER"));
        RuntimeState state = ownership && "1".equals(valuesByName.get("RUNNING")) ? RuntimeState.RUNNING
                : ownership ? RuntimeState.STOPPED : RuntimeState.UNKNOWN;
        String enabled = valuesByName.getOrDefault("ENABLED", "");
        AutostartState autostart = ownership && "enabled".equals(enabled) ? AutostartState.ENABLED
                : ownership ? AutostartState.DISABLED : AutostartState.UNKNOWN;
        return new LifecycleObservation(application, state, autostart, ownership, Instant.now(), ownership
                ? "Controlled helper verified the managed runtime ownership and state"
                : "Controlled helper could not verify managed runtime ownership");
    }

    private RemoteStepResult step(String verb, List<String> values, String successEvidence) throws LinuxOperationException {
        var result = commands.exec(helperCommand(verb, values), Duration.ofSeconds(90), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(),
                result.succeeded() ? successEvidence : result.failureEvidence());
    }

    private String command(String verb, ManagedApplication application, DeploymentRuntimeSpecification runtime) {
        List<String> values = new ArrayList<>(List.of(application.id(), application.ownershipManifestSha256()));
        values.addAll(DeploymentRuntimeArguments.from(runtime));
        return helperCommand(verb, values);
    }

    private static String helperCommand(String verb, List<String> values) {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(SshCommandExecutor.quote(ManagedPrivilegeHelper.PATH)).append(' ')
                .append(SshCommandExecutor.quote(verb));
        for (String value : values) {
            command.append(' ').append(SshCommandExecutor.quote(Objects.requireNonNull(value, "helper argument")));
        }
        return command.toString();
    }
}
