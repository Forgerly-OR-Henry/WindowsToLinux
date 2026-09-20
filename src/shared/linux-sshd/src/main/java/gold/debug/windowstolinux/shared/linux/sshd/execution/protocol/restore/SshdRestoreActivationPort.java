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
        var result = step("restore-preflight", List.of(applicationId, Long.toString(requiredBytes)),
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
                List.of("Managed restore capacity and protocol-specific listeners were collected through the versioned helper"),
                parsePorts(values.getOrDefault("OCCUPIED_UDP_PORTS", "")));
    }

    private static Set<Integer> parsePorts(String value) {
        if (value.isEmpty()) return Set.of();
        return java.util.Arrays.stream(value.split(",")).map(Integer::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public StepEvidence startRestoreActivation(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        Objects.requireNonNull(request, "request");
        for (RemoteRestoreActivationComponent component : request.components()) {
            step("restore-prepare", prepareArguments(request, component),
                    LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        if (request.mode() == RemoteRestoreActivationMode.ISOLATED_STOPPED) {
            for (var component : request.components().reversed()) componentStep("restore-snapshot",request,component,LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        if (request.mode() != RemoteRestoreActivationMode.SHORT_STOP) {
            for (RemoteRestoreActivationComponent component : request.components()) {
                componentStep("restore-start-candidate", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
        }
        return new StepEvidence(true, List.of(request.mode() == RemoteRestoreActivationMode.PARALLEL_LOOPBACK
                ? "Every component started with loopback-only candidate ports"
                : "Every component was prepared without changing the running graph"));
    }

    @Override
    public StepEvidence prepareRestoreCommit(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        if (request.mode() != RemoteRestoreActivationMode.SHORT_STOP) {
            for (RemoteRestoreActivationComponent component : request.components().reversed()) {
                componentStep("restore-stop-candidate", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
        }
        for (RemoteRestoreActivationComponent component : request.components().reversed()) {
            componentStep("restore-snapshot", request, component,
                    LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        List<String> arguments = new ArrayList<>(List.of(request.candidateId(), request.candidateToken(),
                Integer.toString(request.components().size())));
        request.components().forEach(component -> arguments.add(component.componentId()));
        step("restore-mark-quiesced", arguments, LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        return new StepEvidence(true, List.of("Old and candidate processes stopped",
                "Application-wide stopped-write marker recorded by managed helper"));
    }

    @Override
    public StepEvidence startRestoreFormal(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        for (RemoteRestoreActivationComponent component : request.components()) {
            componentStep("restore-start-formal", request, component,
                    LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        return new StepEvidence(true, List.of("Restored graph started on formal ports in dependency order"));
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
        boolean componentsHealthy = formalComponentsHealthy(request);
        boolean applicationHealthy = componentsHealthy && formalApplicationHealthy(request);
        if (componentsHealthy && applicationHealthy) releaseAdmission(request);
        return new CommitEvidence(componentsHealthy && applicationHealthy, true,
                componentsHealthy, applicationHealthy, request.candidateId(),
                List.of("Formal component health=" + componentsHealthy,
                        "Formal whole-application health=" + applicationHealthy,
                        "Previous release snapshots remain retained for rollback"));
    }

    @Override
    public StepEvidence quiesceRestoreRecovery(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        for (RemoteRestoreActivationComponent component : request.components().reversed()) {
            componentStep("restore-quiesce-recovery", request, component,
                    LinuxOperationFailureType.RESTORE_RECOVERY_FAILED);
        }
        return new StepEvidence(true, List.of("Candidate and restored formal processes stopped before rollback"));
    }

    @Override
    public RecoveryEvidence recoverRestoreActivation(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        boolean previousVerified = true;
        List<String> evidence = new ArrayList<>();
        for (RemoteRestoreActivationComponent component : request.components().reversed()) {
            var result = componentStep("restore-recover", request, component,
                    LinuxOperationFailureType.RESTORE_RECOVERY_FAILED);
            Map<String, String> values = SshCommandExecutor.lines(result.output());
            boolean recovered = "1".equals(values.get("RECOVERED"));
            boolean previous = "1".equals(values.get("PREVIOUS"));
            boolean runtimeVerified = !previous || formalHealth(component, component.runtime().healthCheck()).healthy();
            previousVerified &= recovered && runtimeVerified;
            evidence.add(component.componentId() + " recovery=" + recovered
                    + ", previous-runtime-health=" + runtimeVerified);
        }
        if (previousVerified) releaseAdmission(request);
        return new RecoveryEvidence(true, previousVerified, evidence.isEmpty()
                ? List.of("No candidate component mutation was present") : evidence);
    }

    private void releaseAdmission(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        for (var component : request.components()) step("application-maintenance", List.of("end", component.managedApplicationId(),
                component.ownershipManifestSha256(), "restore-" + request.candidateToken()), LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
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
        var result = step("restore-application-health", List.of(request.candidateId(), request.candidateToken(),
                component.componentId(), gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ApplicationWorkloadArguments.healthPayload(candidate)),
                LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        return new HealthCheckResult("1".equals(SshCommandExecutor.lines(result.output()).get("HEALTHY")), "Isolated candidate application validation");
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

    private static HealthCheck candidateHealth(RemoteRestoreActivationComponent component, HealthCheck check) {
        if (check.portNumber().isEmpty()) return check;
        int official = port(check);
        int candidate = component.ports().isEmpty() ? official : component.ports().stream().filter(value -> value.officialPort() == official && value.protocol().equals(check instanceof HealthCheck.Udp ? "udp" : "tcp"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("health port is absent from the exact candidate mapping")).candidatePort();
        if (check instanceof HealthCheck.Tcp tcp) {
            return new HealthCheck.Tcp(candidate, tcp.timeoutSeconds(), tcp.stabilitySeconds());
        }
        if (check instanceof HealthCheck.Udp udp) return new HealthCheck.Udp(candidate, udp.requestHex(), udp.responseHex(), udp.probe(), udp.timeoutSeconds());
        HealthCheck.Http http = (HealthCheck.Http) check;
        try {
            URI source = http.endpoint();
            return new HealthCheck.Http(new URI(source.getScheme(), source.getUserInfo(), "127.0.0.1", candidate,
                    source.getPath(), source.getQuery(), source.getFragment()), http.expectedStatus(), http.timeoutSeconds());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("candidate health endpoint could not be rebuilt", exception);
        }
    }

    private static int port(HealthCheck check) {
        return check.portNumber().orElseThrow();
    }

    private SshCommandExecutor.CommandResult componentStep(
            String verb, RemoteRestoreActivationRequest request, RemoteRestoreActivationComponent component,
            LinuxOperationFailureType failure) throws LinuxOperationException {
        return step(verb, List.of(request.candidateId(), request.candidateToken(), component.componentId()), failure);
    }

    private SshCommandExecutor.CommandResult step(
            String verb, List<String> arguments, LinuxOperationFailureType failure)
            throws LinuxOperationException {
        var result = commands.execProtocol(command(verb, arguments), verb.equals("restore-prepare") ? Duration.ofMinutes(121) : STEP_TIMEOUT, true);
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
            values.add(binding.protocol());
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
