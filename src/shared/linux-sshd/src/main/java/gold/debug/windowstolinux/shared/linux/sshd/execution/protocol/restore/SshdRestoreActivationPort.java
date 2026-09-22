package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.restore;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

/**
 * Apache SSHD restore activation backed only by fixed helper verbs and layered health checks. / 仅由固定 helper 动词及分层健康检查支持的 Apache SSHD 恢复激活。
 */
public final class SshdRestoreActivationPort implements RemoteRestoreActivationPort {
    /**
     * STEP TIMEOUT.
     * <p>步骤超时。
     */
    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(30);

    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;

    /**
     * Systemd health.
     * <p>systemd健康。
     */
    private final SystemdHealthProbe systemdHealth;

    /**
     * Bound container runtime executor collaborator for container health.
     * <p>处理容器健康的容器运行时执行器协作对象。
     */
    private final ContainerRuntimeExecutor containerHealth;

    /**
     * Creates the activation capability for one authenticated session. / 为一个已认证会话创建激活能力。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshdRestoreActivationPort(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.systemdHealth = new SystemdHealthProbe(commands);
        this.containerHealth = new ContainerRuntimeExecutor(commands);
    }

    /**
     * Inspects restore activation.
     * <p>检查恢复激活。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param requiredBytes required bytes / 必需字节
     * @return constructed or resolved preflight evidence / 构造或解析得到的预检证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public PreflightEvidence inspectRestoreActivation(String applicationId, long requiredBytes)
            throws LinuxOperationException {
        var result = step("restore-preflight", List.of(applicationId, Long.toString(requiredBytes)),
                LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        Map<String, String> values = gold.debug.windowstolinux.shared.linux.command.CommandText.lines(result.output());
        long available;
        Set<Integer> occupied = new HashSet<>();
        try {
            available = Long.parseLong(required(values, "AVAILABLE_BYTES"));
            String ports = values.getOrDefault("OCCUPIED_PORTS", "");
            if (!ports.isEmpty())
                for (String port : ports.split(","))
                    occupied.add(Integer.parseInt(port));
        } catch (RuntimeException exception) {
            throw LinuxOperationException.create(LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED,
                    "restore preflight returned malformed bounded evidence", exception);
        }
        return new PreflightEvidence("1".equals(values.get("MANAGED_ROOT_WRITABLE")),
                "1".equals(values.get("FOREIGN_CONFLICT")), available, occupied,
                List.of("Managed restore capacity and protocol-specific listeners were collected through the versioned helper"),
                parsePorts(values.getOrDefault("OCCUPIED_UDP_PORTS", "")));
    }

    /**
     * Parses bound host ports.
     * <p>解析绑定的宿主机端口。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return bound host ports / 绑定的宿主机端口
     */
    private static Set<Integer> parsePorts(String value) {
        if (value.isEmpty())
            return Set.of();
        return java.util.Arrays.stream(value.split(",")).map(Integer::valueOf)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Starts restore activation.
     * <p>启动恢复激活。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    @Override
    public StepEvidence startRestoreActivation(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        Objects.requireNonNull(request, "request");
        for (RemoteRestoreActivationComponent component : request.components()) {
            step("restore-prepare", prepareArguments(request, component),
                    LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        if (request.mode() == RemoteRestoreActivationMode.ISOLATED_STOPPED) {
            for (var component : request.components().reversed())
                componentStep("restore-snapshot", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        if (request.mode() != RemoteRestoreActivationMode.SHORT_STOP) {
            for (RemoteRestoreActivationComponent component : request.components()) {
                componentStep("restore-start-candidate", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
        }
        return new StepEvidence(true,
                List.of(request.mode() == RemoteRestoreActivationMode.PARALLEL_LOOPBACK
                        ? "Every component started with loopback-only candidate ports"
                        : "Every component was prepared without changing the running graph"));
    }

    /**
     * Prepares restore commit.
     * <p>准备恢复提交。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public StepEvidence prepareRestoreCommit(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        if (request.mode() != RemoteRestoreActivationMode.SHORT_STOP) {
            for (RemoteRestoreActivationComponent component : request.components().reversed()) {
                componentStep("restore-stop-candidate", request, component,
                        LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
            }
        }
        for (RemoteRestoreActivationComponent component : request.components().reversed()) {
            componentStep("restore-snapshot", request, component, LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        List<String> arguments = new ArrayList<>(List.of(request.candidateId(), request.candidateToken(),
                Integer.toString(request.components().size())));
        request.components().forEach(component -> arguments.add(component.componentId()));
        step("restore-mark-quiesced", arguments, LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        return new StepEvidence(true, List.of("Old and candidate processes stopped",
                "Application-wide stopped-write marker recorded by managed helper"));
    }

    /**
     * Starts restore formal.
     * <p>启动恢复正式。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public StepEvidence startRestoreFormal(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        for (RemoteRestoreActivationComponent component : request.components()) {
            componentStep("restore-start-formal", request, component,
                    LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        }
        return new StepEvidence(true, List.of("Restored graph started on formal ports in dependency order"));
    }

    /**
     * Verifies restore components.
     * <p>验证恢复组件集合。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public StepEvidence verifyRestoreComponents(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        List<String> evidence = new ArrayList<>();
        for (RemoteRestoreActivationComponent component : request.components()) {
            HealthCheckResult result = health(request, component, component.runtime().healthCheck());
            evidence.add(component.componentId() + " candidate health=" + result.healthy());
            if (!result.healthy())
                return new StepEvidence(false, evidence);
        }
        return new StepEvidence(true, evidence);
    }

    /**
     * Verifies restore application.
     * <p>验证恢复应用。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved step evidence / 构造或解析得到的步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public StepEvidence verifyRestoreApplication(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        RemoteRestoreActivationComponent owner = request.components().stream()
                .filter(component -> component.componentId().equals(request.applicationHealthComponentId())).findFirst()
                .orElseThrow();
        HealthCheckResult result = health(request, owner, request.applicationHealthCheck());
        return new StepEvidence(result.healthy(), List.of("candidate whole-application health=" + result.healthy()));
    }

    /**
     * Commits restore activation.
     * <p>提交恢复激活。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved commit evidence / 构造或解析得到的提交证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public CommitEvidence commitRestoreActivation(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        boolean componentsHealthy = formalComponentsHealthy(request);
        boolean applicationHealthy = componentsHealthy && formalApplicationHealthy(request);
        if (componentsHealthy && applicationHealthy)
            releaseAdmission(request);
        return new CommitEvidence(componentsHealthy && applicationHealthy, true, componentsHealthy, applicationHealthy,
                request.candidateId(),
                List.of("Formal component health=" + componentsHealthy,
                        "Formal whole-application health=" + applicationHealthy,
                        "Previous release snapshots remain retained for rollback"));
    }

    /**
     * Builds step evidence from the supplied quiesce restore recovery inputs.
     * <p>根据所提供停写恢复恢复输入构建步骤证据。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return step evidence from the supplied quiesce restore recovery inputs / 根据所提供停写恢复恢复输入构建步骤证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public StepEvidence quiesceRestoreRecovery(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        for (RemoteRestoreActivationComponent component : request.components().reversed()) {
            componentStep("restore-quiesce-recovery", request, component,
                    LinuxOperationFailureType.RESTORE_RECOVERY_FAILED);
        }
        return new StepEvidence(true, List.of("Candidate and restored formal processes stopped before rollback"));
    }

    /**
     * Recovers restore activation.
     * <p>恢复恢复激活。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public RecoveryEvidence recoverRestoreActivation(RemoteRestoreActivationRequest request)
            throws LinuxOperationException {
        boolean previousVerified = true;
        List<String> evidence = new ArrayList<>();
        for (RemoteRestoreActivationComponent component : request.components().reversed()) {
            var result = componentStep("restore-recover", request, component,
                    LinuxOperationFailureType.RESTORE_RECOVERY_FAILED);
            Map<String, String> values = gold.debug.windowstolinux.shared.linux.command.CommandText
                    .lines(result.output());
            boolean recovered = "1".equals(values.get("RECOVERED"));
            boolean previous = "1".equals(values.get("PREVIOUS"));
            boolean runtimeVerified = !previous || formalHealth(component, component.runtime().healthCheck()).healthy();
            previousVerified &= recovered && runtimeVerified;
            evidence.add(component.componentId() + " recovery=" + recovered + ", previous-runtime-health="
                    + runtimeVerified);
        }
        if (previousVerified)
            releaseAdmission(request);
        return new RecoveryEvidence(true, previousVerified,
                evidence.isEmpty() ? List.of("No candidate component mutation was present") : evidence);
    }

    /**
     * Releases the deterministic admission status.
     * <p>释放确定性准入状态。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private void releaseAdmission(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        for (var component : request.components())
            step("application-maintenance",
                    List.of("end", component.managedApplicationId(), component.ownershipManifestSha256(),
                            "restore-" + request.candidateToken()),
                    LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
    }

    /**
     * Tests the formal components healthy predicate against the supplied evidence.
     * <p>根据所提供证据检查正式组件集合健康条件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return true when formal components healthy predicate against the supplied evidence, false otherwise / 根据所提供证据检查正式组件集合健康条件时为 true，否则为 false
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private boolean formalComponentsHealthy(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        for (RemoteRestoreActivationComponent component : request.components()) {
            if (!formalHealth(component, component.runtime().healthCheck()).healthy())
                return false;
        }
        return true;
    }

    /**
     * Tests the formal application healthy predicate against the supplied evidence.
     * <p>根据所提供证据检查正式应用健康条件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return true when formal application healthy predicate against the supplied evidence, false otherwise / 根据所提供证据检查正式应用健康条件时为 true，否则为 false
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private boolean formalApplicationHealthy(RemoteRestoreActivationRequest request) throws LinuxOperationException {
        RemoteRestoreActivationComponent owner = request.components().stream()
                .filter(component -> component.componentId().equals(request.applicationHealthComponentId())).findFirst()
                .orElseThrow();
        return formalHealth(owner, request.applicationHealthCheck()).healthy();
    }

    /**
     * Builds health check result from the supplied health inputs.
     * <p>根据所提供健康输入构建健康检查结果。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param component component / 组件
     * @param check check / 检查
     * @return health check result from the supplied health inputs / 根据所提供健康输入构建健康检查结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private HealthCheckResult health(RemoteRestoreActivationRequest request, RemoteRestoreActivationComponent component,
            HealthCheck check) throws LinuxOperationException {
        if (request.mode() == RemoteRestoreActivationMode.SHORT_STOP)
            return formalHealth(component, check);
        HealthCheck candidate = candidateHealth(component, check);
        var result = step("restore-application-health", List.of(request.candidateId(), request.candidateToken(),
                component.componentId(),
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.runtime.ApplicationWorkloadArguments
                        .healthPayload(candidate)),
                LinuxOperationFailureType.RESTORE_ACTIVATION_FAILED);
        return new HealthCheckResult("1".equals(
                gold.debug.windowstolinux.shared.linux.command.CommandText.lines(result.output()).get("HEALTHY")),
                "Isolated candidate application validation");
    }

    /**
     * Checks the restored component's formal runtime health using its managed ownership evidence.
     * <p>使用受管归属证据检查恢复组件的正式运行健康状态。
     *
     * @param component component / 组件
     * @param check check / 检查
     * @return constructed or resolved health check result / 构造或解析得到的健康检查结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
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

    /**
     * Builds health check from the supplied candidate health inputs.
     * <p>根据所提供候选健康输入构建健康检查。
     *
     * @param component component / 组件
     * @param check check / 检查
     * @return health check from the supplied candidate health inputs / 根据所提供候选健康输入构建健康检查
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static HealthCheck candidateHealth(RemoteRestoreActivationComponent component, HealthCheck check) {
        if (check.portNumber().isEmpty())
            return check;
        int official = port(check);
        int candidate = component.ports().isEmpty()
                ? official
                : component.ports().stream()
                        .filter(value -> value.officialPort() == official
                                && value.protocol().equals(check instanceof HealthCheck.Udp ? "udp" : "tcp"))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "health port is absent from the exact candidate mapping"))
                        .candidatePort();
        if (check instanceof HealthCheck.Tcp tcp) {
            return new HealthCheck.Tcp(candidate, tcp.timeoutSeconds(), tcp.stabilitySeconds());
        }
        if (check instanceof HealthCheck.Udp udp)
            return new HealthCheck.Udp(candidate, udp.requestHex(), udp.responseHex(), udp.probe(),
                    udp.timeoutSeconds());
        HealthCheck.Http http = (HealthCheck.Http) check;
        try {
            URI source = http.endpoint();
            return new HealthCheck.Http(new URI(source.getScheme(), source.getUserInfo(), "127.0.0.1", candidate,
                    source.getPath(), source.getQuery(), source.getFragment()), http.expectedStatus(),
                    http.timeoutSeconds());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("candidate health endpoint could not be rebuilt", exception);
        }
    }

    /**
     * Requires and returns the health check's declared port number.
     * <p>要求健康检查声明端口号并返回该端口。
     *
     * @param check check / 检查
     * @return port as a numeric result / 端口的数值结果
     */
    private static int port(HealthCheck check) {
        return check.portNumber().orElseThrow();
    }

    /**
     * Runs a restore helper step bound to the candidate identifier, token and component.
     * <p>执行绑定候选标识、令牌及组件的恢复 helper 步骤。
     *
     * @param verb verb / 操作动词
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param component component / 组件
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult componentStep(String verb,
            RemoteRestoreActivationRequest request, RemoteRestoreActivationComponent component,
            LinuxOperationFailureType failure) throws LinuxOperationException {
        return step(verb, List.of(request.candidateId(), request.candidateToken(), component.componentId()), failure);
    }

    /**
     * Executes one restore protocol verb with its bounded timeout and classifies a failed exit under the supplied failure type.
     * <p>使用有界超时执行一个恢复协议动词，并按提供的失败类型分类失败退出。
     *
     * @param verb verb / 操作动词
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved command result / 构造或解析得到的命令结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult step(String verb, List<String> arguments,
            LinuxOperationFailureType failure) throws LinuxOperationException {
        var result = commands.execProtocol(command(verb, arguments),
                verb.equals("restore-prepare") ? Duration.ofMinutes(121) : STEP_TIMEOUT, true);
        if (!result.succeeded())
            throw LinuxOperationException.create(failure,
                    "controlled restore helper step failed: " + result.failureEvidence());
        return result;
    }

    /**
     * Prepares literal arguments passed to the fixed command or message template.
     * <p>准备传给固定命令或消息模板的字面参数。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param component component / 组件
     * @return constructed or resolved list / 构造或解析得到的列表
     */
    private static List<String> prepareArguments(RemoteRestoreActivationRequest request,
            RemoteRestoreActivationComponent component) {
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

    /**
     * Renders a fixed helper invocation with individually quoted reviewed arguments; does not execute it.
     * <p>使用逐项引用的已审阅参数渲染固定 helper 调用，不执行该调用。
     *
     * @param verb verb / 操作动词
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return command text / 命令文本
     */
    private static String command(String verb, List<String> values) {
        StringBuilder command = new StringBuilder("sudo -n ")
                .append(gold.debug.windowstolinux.shared.linux.command.CommandText.quote(ManagedHelperBundle.PATH))
                .append(' ').append(gold.debug.windowstolinux.shared.linux.command.CommandText.quote(verb));
        values.forEach(value -> command.append(' ')
                .append(gold.debug.windowstolinux.shared.linux.command.CommandText.quote(value)));
        return command.toString();
    }

    /**
     * Requires the named input to be present and valid before continuing.
     * <p>继续前要求具名输入存在且有效。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return required text / 必需文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " is missing");
        return value;
    }
}
