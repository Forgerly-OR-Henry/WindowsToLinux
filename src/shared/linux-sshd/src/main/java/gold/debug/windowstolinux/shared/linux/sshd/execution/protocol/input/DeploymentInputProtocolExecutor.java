package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.input;

import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

import gold.debug.windowstolinux.shared.linux.protocol.RemoteRuntimeConfiguration;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteDeploymentInputs;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteSecretPayload;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Streams and seals runtime inputs through the root-owned helper before publication. / 在发布前通过 root 所有的辅助程序流式传输并封存运行时输入。 */
public final class DeploymentInputProtocolExecutor {
    private final SshCommandExecutor commands;

    /** Creates the controlled input protocol. / 创建受控输入协议。 */
    public DeploymentInputProtocolExecutor(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /** Seals one immutable configuration and exact secret-revision set. / 封存一个不可变配置与精确秘密修订集合。 */
    public RemoteDeploymentInputs stage(ManagedApplication application, RemoteRuntimeConfiguration configuration,
                                         List<RemoteSecretPayload> secrets) throws LinuxOperationException {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(configuration, "configuration");
        secrets = List.copyOf(Objects.requireNonNull(secrets, "secrets"));
        if (!application.id().equals(configuration.applicationId())) {
            throw new IllegalArgumentException("runtime configuration must match the managed application");
        }
        var manifest = new RemoteDeploymentInputs(configuration.sha256(), secrets.stream().map(RemoteSecretPayload::digest).toList());
        stageConfiguration(application.id(), configuration.sha256(), "systemd",
                DeploymentConfigurationRenderer.systemd(configuration));
        stageConfiguration(application.id(), configuration.sha256(), "container",
                DeploymentConfigurationRenderer.container(configuration));
        for (RemoteSecretPayload secret : secrets.stream()
                .sorted(Comparator.comparing((RemoteSecretPayload value) -> value.digest().identifier())
                        .thenComparingLong(value -> value.digest().revision())).toList()) {
            stageSecret(application.id(), secret);
        }
        return manifest;
    }

    private void stageConfiguration(String applicationId, String configurationSha256, String format, byte[] payload)
            throws LinuxOperationException {
        try {
            execute("stage-config", List.of(applicationId, configurationSha256, format, sha256(payload),
                    Integer.toString(payload.length)), payload);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private void stageSecret(String applicationId, RemoteSecretPayload secret) throws LinuxOperationException {
        byte[] payload = secret.copyValue();
        try {
            execute("stage-secret", List.of(applicationId, secret.digest().identifier(),
                    Long.toString(secret.digest().revision()), secret.digest().sha256(),
                    Integer.toString(secret.digest().byteCount())), payload);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private void execute(String verb, List<String> arguments, byte[] payload) throws LinuxOperationException {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(SshCommandExecutor.quote(ManagedHelperBundle.PATH)).append(' ')
                .append(SshCommandExecutor.quote(verb));
        arguments.forEach(value -> command.append(' ').append(SshCommandExecutor.quote(value)));
        var result = commands.execProtocolWithInput(command.toString(), payload, Duration.ofSeconds(30));
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.DEPLOYMENT_INPUT_STAGING_FAILED,
                    "Controlled helper could not seal reviewed deployment inputs: " + result.failureEvidence());
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }
}
