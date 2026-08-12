package gold.debug.windowstolinux.shared.linux.sshd.protocol;

import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Provides the {@code ManagedReleaseProtocolExecutor} implementation.
 *
 * <p>提供 {@code ManagedReleaseProtocolExecutor} 实现。
 */
public final class ManagedReleaseProtocolExecutor {
    private final SshCommandExecutor commands;

    /**
     * Creates a {@code ManagedReleaseProtocolExecutor} instance.
     *
     * <p>创建 {@code ManagedReleaseProtocolExecutor} 实例。
     *
     * @param commands the {@code commands} value / {@code commands} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ManagedReleaseProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Creates a value through {@code createCandidate}.
     *
     * <p>通过 {@code createCandidate} 创建值。
     *
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public RemoteStepResult createCandidate(RemoteWorkspace workspace) throws LinuxOperationException {
        var result = commands.exec(helperCommand(
                "candidate-create", workspace.applicationId(), workspace.candidateId()),
                Duration.ofSeconds(20), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper created the managed candidate directory"
                : "Controlled helper could not create the managed candidate directory: " + result.failureEvidence());
    }

    /**
     * Performs the {@code cleanupCandidate} operation.
     *
     * <p>执行 {@code cleanupCandidate} 操作。
     *
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public RemoteStepResult cleanupCandidate(RemoteWorkspace workspace) throws LinuxOperationException {
        Objects.requireNonNull(workspace, "workspace");
        var result = commands.exec(helperCommand(
                "candidate-cleanup", workspace.applicationId(), workspace.candidateId()),
                Duration.ofSeconds(30), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper cleaned the current candidate directory"
                : "Controlled helper could not clean the current candidate directory: " + result.failureEvidence());
    }

    /**
     * Performs the {@code snapshot} operation.
     *
     * <p>执行 {@code snapshot} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public ReleaseSnapshot snapshot(ManagedApplication application) throws LinuxOperationException {
        var result = commands.execProtocol(helperCommand(
                "snapshot", application.id(), application.ownershipManifestSha256()),
                Duration.ofSeconds(30), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.localized("linux.error.snapshotIdentityUnverified",
                    "Controlled helper could not verify the existing release or unit identity: "
                            + result.failureEvidence());
        }
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        if (!"1".equals(values.get("PREVIOUS"))) {
            return ReleaseSnapshot.firstDeployment("No previous release exists; only the current candidate may be cleaned");
        }
        String token = values.get("SNAPSHOT_TOKEN");
        if (token == null || !token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw LinuxOperationException.localized("linux.error.snapshotTokenMissing",
                    "Controlled helper did not return a verifiable rollback snapshot token");
        }
        return ReleaseSnapshot.withPreviousRelease(token, "1".equals(values.get("PREVIOUS_RUNNING")),
                "Root-owned controlled helper saved the previous release, unit, autostart, and runtime state");
    }

    /**
     * Performs the {@code publish} operation.
     *
     * <p>执行 {@code publish} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @param build the {@code build} value / {@code build} 值
     * @param snapshot the {@code snapshot} value / {@code snapshot} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public RemoteStepResult publish(ManagedApplication application, RemoteWorkspace workspace,
                                    RemoteBuildResult build, ReleaseSnapshot snapshot)
            throws LinuxOperationException {
        if (!build.succeeded()) {
            throw LinuxOperationException.localized("linux.error.unverifiedBuildPublish",
                    "An unverified build result cannot be published");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        build.executableJarPath().orElseThrow();
        String artifactSha256 = build.executableJarSha256().orElseThrow();
        var result = commands.exec(helperCommand("publish", application.id(), workspace.candidateId(),
                artifactSha256, application.ownershipManifestSha256()), Duration.ofSeconds(90), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper stopped the old service, switched managed current, generated the canonical unit, "
                        + "and started the candidate release"
                : "Controlled helper candidate publish step failed; deployment success was not declared: "
                        + result.failureEvidence());
    }

    /**
     * Performs the {@code retainRecentSuccessfulReleases} operation.
     *
     * <p>执行 {@code retainRecentSuccessfulReleases} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application)
            throws LinuxOperationException {
        var result = commands.exec(helperCommand(
                "retain", application.id(), application.ownershipManifestSha256()),
                Duration.ofSeconds(60), true);
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper verified managed identity and retained the three most recent successful releases "
                        + "without deleting current"
                : "Publish completed, but controlled helper did not finish old-release cleanup; no directory lacking "
                        + "verified managed identity was deleted: "
                + result.failureEvidence());
    }

    /**
     * Performs the {@code rollback} operation.
     *
     * <p>执行 {@code rollback} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param snapshot the {@code snapshot} value / {@code snapshot} 值
     * @param build the {@code build} value / {@code build} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot,
                                     RemoteBuildResult build) throws LinuxOperationException {
        if (!build.succeeded() || build.executableJarSha256().isEmpty()) {
            throw LinuxOperationException.localized("linux.error.rollbackDigestMissing",
                    "A publish without a controlled candidate JAR digest cannot be rolled back");
        }
        String candidateDigest = build.executableJarSha256().orElseThrow();
        SshCommandExecutor.CommandResult result;
        if (snapshot.hasPreviousRelease()) {
            result = commands.exec(helperCommand("rollback-previous", application.id(), candidateDigest,
                    application.ownershipManifestSha256(), snapshot.rollbackToken().orElseThrow()),
                    Duration.ofSeconds(90), true);
        } else {
            result = commands.exec(helperCommand("rollback-first", application.id(), candidateDigest,
                    application.ownershipManifestSha256()), Duration.ofSeconds(90), true);
        }
        return new RemoteStepResult(result.succeeded(), result.timedOut(), result.succeeded()
                ? "Controlled helper restored the verified previous release or cleaned the first-release candidate"
                : "Controlled helper could not complete rollback; old and new directories plus diagnostic state were "
                        + "preserved for manual handling: " + result.failureEvidence());
    }

    /**
     * Performs the {@code command} operation.
     *
     * <p>执行 {@code command} 操作。
     *
     * @param verb the {@code verb} value / {@code verb} 值
     * @param arguments the {@code arguments} value / {@code arguments} 值
     * @return the operation result / 操作结果
     */
    public String command(String verb, String... arguments) {
        return helperCommand(verb, arguments);
    }

    private static String helperCommand(String verb, String... arguments) {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(SshCommandExecutor.quote(ManagedPrivilegeHelper.PATH))
                .append(' ').append(SshCommandExecutor.quote(Objects.requireNonNull(verb, "verb")));
        for (String argument : arguments) {
            command.append(' ').append(SshCommandExecutor.quote(
                    Objects.requireNonNull(argument, "helper argument")));
        }
        return command.toString();
    }
}
