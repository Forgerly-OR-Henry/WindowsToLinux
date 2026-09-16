package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import gold.debug.windowstolinux.shared.linux.distro.SelinuxEnvironmentPreparer;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationPlan;
import gold.debug.windowstolinux.shared.model.server.security.SelinuxPreparationState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Executes only fixed, reviewed SELinux preparation steps. / 仅执行固定且经审阅的 SELinux 准备步骤。 */
public final class SelinuxPreparationExecutor implements SelinuxEnvironmentPreparer {
    private final SshCommandExecutor commands;
    private final String serverId;

    public SelinuxPreparationExecutor(SshCommandExecutor commands, String serverId) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.serverId = Objects.requireNonNull(serverId, "serverId");
    }

    @Override public java.util.Optional<SelinuxPreparationPlan> inspect() throws LinuxOperationException {
        Map<String, String> values = SshCommandExecutor.lines(execute("inspect", null));
        if ("false".equals(values.get("APPLICABLE"))) return java.util.Optional.empty();
        try {
            if (!"true".equals(values.get("APPLICABLE"))) throw new IllegalArgumentException("Missing applicability");
            return java.util.Optional.of(new SelinuxPreparationPlan(serverId, values.get("BOOT_ID"), values.get("CONFIG_SHA256"),
                    LinuxSecurityState.valueOf(values.get("SECURITY_STATE")),
                    SelinuxPreparationState.valueOf(values.get("PREPARATION_STATE"))));
        } catch (RuntimeException failure) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "Invalid SELinux preparation observation", failure);
        }
    }

    @Override public void prepareReboot(SelinuxPreparationPlan approved) throws LinuxOperationException {
        execute("prepare", approved);
    }

    @Override public void enableEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException {
        execute("enforce", approved);
    }

    @Override public void commitEnforcement(SelinuxPreparationPlan approved) throws LinuxOperationException {
        execute("commit", approved);
    }

    private String execute(String operation, SelinuxPreparationPlan approved) throws LinuxOperationException {
        if (approved != null && !serverId.equals(approved.serverId())) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "System preparation approval targets another server");
        }
        String arguments = "'" + operation + "'";
        if (approved != null) {
            arguments += " '" + approved.bootId() + "' '" + approved.configurationSha256()
                    + "' '" + approved.state().name() + "' '" + approved.securityState().name() + "'";
        }
        String script;
        try (var input = SelinuxPreparationExecutor.class.getResourceAsStream("selinux-preparation.sh")) {
            if (input == null) throw new IOException("Missing SELinux preparation resource");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "Cannot load SELinux preparation resource", failure);
        }
        var result = commands.execScript(script + "\nselinux_preparation " + arguments + "\n",
                operation.equals("inspect") ? Duration.ofSeconds(30) : Duration.ofMinutes(3), true);
        if (!result.succeeded()) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_PREPARATION_FAILED,
                    "SELinux preparation " + operation + " failed: " + result.failureEvidence());
        }
        return result.output();
    }
}
