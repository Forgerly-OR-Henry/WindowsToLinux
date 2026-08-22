package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.restore;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationComponent;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationMode;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationRequest;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.ContainerRuntimeExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdHealthProbe;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Apache SSHD restore activation backed only by fixed helper verbs and layered health checks. / 仅由固定 helper 动词及分层健康检查支持的 Apache SSHD 恢复激活。 */
public final class SshdRestoreActivationPort implements RemoteRestoreActivationPort {
    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(30);
    private final SshCommandExecutor commands;
    private final SystemdHealthProbe systemdHealth;
    private final ContainerRuntimeExecutor containerHealth;

    /** Creates the activation capability for one authenticated session. / 为一个已认证会话创建激活能力。 */
    public SshdRestoreActivationPort(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.systemdHealth = new SystemdHealthProbe(commands);
        this.containerHealth = new ContainerRuntimeExecutor(commands);
    }

    @Override
    public PreflightEvidence inspectRestoreActivation(String applicationId, long requiredBytes)
            throws LinuxOperationException {
        var result = step("restore-preflight", List.of(applicationId, Long.toString(requiredBytes)), false,
                LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        Map<String, String> values = SshCommandExecutor.lines(result.output());
        long available;
        Set<Integer> occupied = new HashSet<>();
        try {
            available = Long.parseLong(required(values, "AVAILABLE_BYTES"));
            String ports = values.getOrDefault("OCCUPIED_PORTS", "");
            if (!ports.isEmpty()) for (String port : ports.split(",")) occupied.add(Integer.parseInt(port));
        } catch (RuntimeException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED,
                    "restore preflight returned malformed bounded evidence", exception);
        }
        return new PreflightEvidence("1".equals(values.get("MANAGED_ROOT_WRITABLE")),
                "1".equals(values.get("FOREIGN_CONFLICT")), available, occupied,
                List.of("Managed restore root capacity and live TCP listeners were collected through helper v5"));
    }

    @Override
    public StepEvidence startRestoreActivation(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        Objects.requireNonNull(request, "request");
        for (RemoteRestoreActivationComponent component : request.components()) {
            step("restore-prepare", prepareArguments(request, component), true,
                    LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        if (request.mode() == RemoteRestoreActivationMode.PARALLEL_LOOPBACK) {
            for (RemoteRestoreActivationComponent component : request.components()) {
                componentStep("restore-start-candidate", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
        } else {
            for (RemoteRestoreActivationComponent component : request.components().reversed()) {
                componentStep("restore-snapshot", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
            for (RemoteRestoreActivationComponent component : request.components()) {
                componentStep("restore-start-formal", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
        }
        return new StepEvidence(true, List.of(request.mode() == RemoteRestoreActivationMode.PARALLEL_LOOPBACK
                ? "Every component started with loopback-only candidate ports"
                : "The previous graph was snapshotted and the tentative graph started on formal ports"));
    }

    @Override
    public StepEvidence verifyRestoreComponents(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        List<String> evidence = new ArrayList<>();
        for (RemoteRestoreActivationComponent component : request.components()) {
            HealthCheckResult result = health(request, component, component.runtime().healthCheck());
            evidence.add(component.componentId() + " candidate health=" + result.healthy());
            if (!result.healthy()) return new StepEvidence(false, evidence);
        }
        return new StepEvidence(true, evidence);
    }

    @Override
    public StepEvidence verifyRestoreApplication(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        RemoteRestoreActivationComponent owner = request.components().stream().filter(component ->
                component.componentId().equals(request.applicationHealthComponentId())).findFirst().orElseThrow();
        HealthCheckResult result = health(request, owner, request.applicationHealthCheck());
        return new StepEvidence(result.healthy(), List.of("candidate whole-application health=" + result.healthy()));
    }

    @Override
    public CommitEvidence commitRestoreActivation(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        if (request.mode() == RemoteRestoreActivationMode.PARALLEL_LOOPBACK) {
            for (RemoteRestoreActivationComponent component : request.components().reversed()) {
                componentStep("restore-stop-candidate", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
            for (RemoteRestoreActivationComponent component : request.components().reversed()) {
                componentStep("restore-snapshot", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
            for (RemoteRestoreActivationComponent component : request.components()) {
                componentStep("restore-start-formal", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
        }
        boolean componentsHealthy = formalComponentsHealthy(request);
        boolean applicationHealthy = componentsHealthy && formalApplicationHealthy(request);
        return new CommitEvidence(componentsHealthy && applicationHealthy, true,
                componentsHealthy, applicationHealthy, request.candidateId(),
                List.of("Formal component health=" + componentsHealthy,
                        "Formal whole-application health=" + applicationHealthy,
                        "Previous release snapshots remain retained for rollback"));
    }

    @Override
    public RecoveryEvidence recoverRestoreActivation(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        boolean previousVerified = true;
        List<String> evidence = new ArrayList<>();
        for (RemoteRestoreActivationComponent component : request.components().reversed()) {
            var result = componentStep("restore-recover", request, component,
                    LinuxOperationFailureType.RESTORE_RECOVERY_FAILED);
            boolean recovered = "1".equals(SshCommandExecutor.lines(result.output()).get("RECOVERED"));
            previousVerified &= recovered;
            evidence.add(component.componentId() + " recovery=" + recovered);
        }
        return new RecoveryEvidence(true, previousVerified, evidence.isEmpty()
                ? List.of("No candidate component mutation was present") : evidence);
    }

    private boolean formalComponentsHealthy(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        for (RemoteRestoreActivationComponent component : request.components()) {
            if (!formalHealth(component, component.runtime().healthCheck()).healthy()) return false;
        }
        return true;
    }

    private boolean formalApplicationHealthy(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        RemoteRestoreActivationComponent owner = request.components().stream().filter(component ->
                component.componentId().equals(request.applicationHealthComponentId())).findFirst().orElseThrow();
        return formalHealth(owner, request.applicationHealthCheck()).healthy();
    }

    private HealthCheckResult health(RemoteRestoreActivationRequest request,
                                     RemoteRestoreActivationComponent component, HealthCheck check)
            throws LinuxOperationException {
        if (request.mode() == RemoteRestoreActivationMode.SHORT_STOP) return formalHealth(component, check);
        HealthCheck candidate = candidateHealth(component, check);
        if (component.runtime() instanceof DeploymentRuntimeSpecification.Container container) {
            return candidateContainerHealth(request, component, container, candidate);
        }
        return systemdHealth.checkUnit("windowstolinux-restore-" + request.candidateToken() + "-"
                + component.componentId() + ".service", candidate);
    }

    private HealthCheckResult formalHealth(RemoteRestoreActivationComponent component, HealthCheck check)
            throws LinuxOperationException {
        ManagedApplication application = ManagedApplication.forManaged(component.managedApplicationId(),
                new ServerIdentity("restore", "localhost", 22, "SHA256:restore-target"),
                component.ownershipManifestSha256());
        if (component.runtime() instanceof DeploymentRuntimeSpecification.Container container) {
            return containerHealth.checkHealth(application, container, check);
        }
        return systemdHealth.check(application, check);
    }

    private HealthCheckResult candidateContainerHealth(
            RemoteRestoreActivationRequest request,
            RemoteRestoreActivationComponent component,
            DeploymentRuntimeSpecification.Container runtime,
            HealthCheck check
    ) throws LinuxOperationException {
        String engine = runtime.engine().name().toLowerCase(java.util.Locale.ROOT);
        String name = "windowstolinux-restore-" + request.candidateToken() + "-" + component.componentId();
        String probe = check instanceof HealthCheck.Http http
                ? "curl --fail --silent --max-time 3 --output /dev/null --write-out '%{http_code}' "
                + SshCommandExecutor.quote(http.endpoint().toASCIIString()) + " | grep -qx "
                + SshCommandExecutor.quote(Integer.toString(http.expectedStatus()))
                : "timeout 3 /bin/bash -c '</dev/tcp/127.0.0.1/" + ((HealthCheck.Tcp) check).port() + "'";
        String script = "set -euo pipefail; " + SshCommandExecutor.quote(engine)
                + " inspect --format '{{.State.Running}} {{ index .Config.Labels \"io.windowstolinux.restore\" }}' "
                + SshCommandExecutor.quote(name) + " | grep -qx " + SshCommandExecutor.quote("true " + request.candidateToken())
                + "; " + probe + "; printf 'HEALTHY=1\\n'";
        var result = commands.exec("/bin/bash -lc " + SshCommandExecutor.quote(script),
                Duration.ofSeconds(check.timeoutSeconds() + 15L), true);
        return new HealthCheckResult(result.succeeded()
                && "1".equals(SshCommandExecutor.lines(result.output()).get("HEALTHY")),
                result.succeeded() ? "Restore candidate container health completed" : result.failureEvidence());
    }

    private static HealthCheck candidateHealth(RemoteRestoreActivationComponent component, HealthCheck check) {
        int official = port(check);
        int candidate = component.ports().stream().filter(value -> value.officialPort() == official)
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "health port is absent from the exact candidate mapping")).candidatePort();
        if (check instanceof HealthCheck.Tcp tcp) {
            return new HealthCheck.Tcp(candidate, tcp.timeoutSeconds(), tcp.stabilitySeconds());
        }
        HealthCheck.Http http = (HealthCheck.Http) check;
        try {
            URI source = http.endpoint();
            return new HealthCheck.Http(new URI(source.getScheme(), source.getUserInfo(), source.getHost(), candidate,
                    source.getPath(), source.getQuery(), source.getFragment()), http.expectedStatus(), http.timeoutSeconds());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("candidate health endpoint could not be rebuilt", exception);
        }
    }

    private static int port(HealthCheck check) {
        if (check instanceof HealthCheck.Tcp tcp) return tcp.port();
        HealthCheck.Http http = (HealthCheck.Http) check;
        return http.endpoint().getPort() >= 1 ? http.endpoint().getPort()
                : http.endpoint().getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private SshCommandExecutor.CommandResult componentStep(
            String verb, RemoteRestoreActivationRequest request, RemoteRestoreActivationComponent component,
            LinuxOperationFailureType failure) throws LinuxOperationException {
        return step(verb, List.of(request.candidateId(), request.candidateToken(), component.componentId()), true, failure);
    }

    private SshCommandExecutor.CommandResult step(
            String verb, List<String> arguments, boolean mutation, LinuxOperationFailureType failure)
            throws LinuxOperationException {
        var result = commands.execProtocol(command(verb, arguments), STEP_TIMEOUT, mutation);
        if (!result.succeeded()) throw LinuxOperationException.create(failure,
                "controlled restore helper step failed: " + result.failureEvidence());
        return result;
    }

    private static List<String> prepareArguments(
            RemoteRestoreActivationRequest request, RemoteRestoreActivationComponent component) {
        List<String> values = new ArrayList<>(List.of(request.candidateId(), request.candidateToken(),
                component.componentId(), component.managedApplicationId(), component.ownershipManifestSha256(),
                component.releaseSha256(), component.releaseArchivePath(),
                Integer.toString(component.persistentArchivePaths().size())));
        values.addAll(component.persistentArchivePaths());
        values.add(component.ociArchivePath().orElse("-"));
        values.add(Integer.toString(component.ports().size()));
        component.ports().forEach(binding -> {
            values.add(Integer.toString(binding.officialPort()));
            values.add(Integer.toString(binding.candidatePort()));
        });
        return List.copyOf(values);
    }

    private static String command(String verb, List<String> values) {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(SshCommandExecutor.quote(ManagedHelperBundle.PATH)).append(' ')
                .append(SshCommandExecutor.quote(verb));
        values.forEach(value -> command.append(' ').append(SshCommandExecutor.quote(value)));
        return command.toString();
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is missing");
        return value;
    }
}
