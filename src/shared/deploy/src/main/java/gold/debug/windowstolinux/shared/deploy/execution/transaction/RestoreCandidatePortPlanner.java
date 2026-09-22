package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortBinding;
import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortMode;
import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortPlan;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentComponent;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentRequest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/**
 * Selects the fail-closed application-wide restore mode and unique dynamic loopback ports. / 选择故障关闭的整应用恢复模式及唯一动态回环端口。
 */
public final class RestoreCandidatePortPlanner {
    /**
     * FIRST CANDIDATE PORT.
     * <p>首次候选端口。
     */
    public static final int FIRST_CANDIDATE_PORT = 49152;

    /**
     * LAST CANDIDATE PORT.
     * <p>上次候选端口。
     */
    public static final int LAST_CANDIDATE_PORT = 65535;

    /**
     * Allocates isolated candidate ports without colliding with official endpoints or already observed listeners.
     * <p>分配隔离候选端口，避免与正式端点或已观测监听器冲突。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param unavailablePorts unavailable ports / 不可用端口集合
     * @return constructed or resolved candidate port plan / 构造或解析得到的候选端口计划
     */
    public CandidatePortPlan plan(RestoreDeploymentRequest request, Set<Integer> unavailablePorts) {
        return plan(request, unavailablePorts, false);
    }

    /**
     * Allocates isolated candidate ports without colliding with official endpoints or already observed listeners.
     * <p>分配隔离候选端口，避免与正式端点或已观测监听器冲突。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param unavailablePorts unavailable ports / 不可用端口集合
     * @param databaseActivationRequired database activation required / 数据库激活必需
     * @return constructed or resolved candidate port plan / 构造或解析得到的候选端口计划
     */
    public CandidatePortPlan plan(RestoreDeploymentRequest request, Set<Integer> unavailablePorts,
            boolean databaseActivationRequired) {
        return plan(request, unavailablePorts, Set.of(), databaseActivationRequired);
    }

    /**
     * Allocates isolated candidate ports without colliding with official endpoints or already observed listeners.
     * <p>分配隔离候选端口，避免与正式端点或已观测监听器冲突。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param tcp tcp / tcp 对应的输入或状态
     * @param udp udp / udp 对应的输入或状态
     * @param databaseActivationRequired database activation required / 数据库激活必需
     * @return constructed or resolved candidate port plan / 构造或解析得到的候选端口计划
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public CandidatePortPlan plan(RestoreDeploymentRequest request, Set<Integer> tcp, Set<Integer> udp,
            boolean databaseActivationRequired) {
        Objects.requireNonNull(request, "request");
        Set<String> unavailable = new HashSet<>();
        tcp.forEach(port -> unavailable.add("tcp:" + port));
        udp.forEach(port -> unavailable.add("udp:" + port));
        boolean parallel = (!databaseActivationRequired || request.isolatedDatabase())
                && request.components().stream().allMatch(this::supportsTypedOverride);
        LinkedHashMap<String, List<CandidatePortBinding>> bindings = new LinkedHashMap<>();
        if (!parallel) {
            request.components().forEach(component -> bindings.put(component.componentId(), List.of()));
            return new CandidatePortPlan(
                    request.isolatedDatabase() ? CandidatePortMode.ISOLATED_STOPPED : CandidatePortMode.SHORT_STOP,
                    bindings);
        }
        request.components().forEach(component -> officialPorts(component)
                .forEach(port -> unavailable.add(port.protocol() + ":" + port.officialPort())));
        for (RestoreDeploymentComponent component : request.components()) {
            List<CandidatePortBinding> selected = new ArrayList<>();
            for (var port : officialPorts(component)) {
                int candidate = FIRST_CANDIDATE_PORT;
                while (candidate <= LAST_CANDIDATE_PORT && unavailable.contains(port.protocol() + ":" + candidate))
                    candidate++;
                if (candidate > LAST_CANDIDATE_PORT)
                    throw new IllegalStateException("no candidate port remains");
                selected.add(new CandidatePortBinding(port.officialPort(), candidate, port.protocol()));
                unavailable.add(port.protocol() + ":" + candidate);
            }
            bindings.put(component.componentId(), List.copyOf(selected));
        }
        return new CandidatePortPlan(CandidatePortMode.PARALLEL_LOOPBACK, bindings);
    }

    /**
     * Reports whether the typed override condition holds for this contract.
     * <p>判断当前契约是否满足类型化覆盖项条件。
     *
     * @param component component / 组件
     * @return true when typed override condition holds for this contract, false otherwise / 当前契约是否满足类型化覆盖项条件时为 true，否则为 false
     */
    private boolean supportsTypedOverride(RestoreDeploymentComponent component) {
        if (component.runtime().healthCheck().portNumber().isEmpty()
                && component.runtime().workload().endpoints().isEmpty())
            return true;
        return switch (component.runtime()) {
            case DeploymentRuntimeSpecification.Container ignored -> true;
            case DeploymentRuntimeSpecification.StaticSite ignored -> true;
            case DeploymentRuntimeSpecification.PhpService ignored -> true;
            case DeploymentRuntimeSpecification.RubyService ignored -> true;
            default -> false;
        };
    }

    /**
     * Associates a published component port with its restore candidate allocation.
     * <p>将已发布组件端口与其恢复候选分配关联。
     *
     * @param officialPort official port / 正式端口
     * @param protocol protocol / 协议
     */
    private record OfficialPort(int officialPort, String protocol) {
    }

    /**
     * Resolves the component's official host ports and transport protocols from its reviewed runtime.
     * <p>根据已审阅运行规格解析组件正式主机端口及传输协议。
     *
     * @param component component / 组件
     * @return the component's official host ports and transport protocols from its reviewed runtime / 根据已审阅运行规格解析组件正式主机端口及传输协议
     */
    private List<OfficialPort> officialPorts(RestoreDeploymentComponent component) {
        var runtime = component.runtime();
        if (!runtime.workload().endpoints().isEmpty())
            return runtime.workload().endpoints().stream()
                    .map(endpoint -> new OfficialPort(endpoint.hostPort(), endpoint.protocol().transport())).toList();
        if (runtime instanceof DeploymentRuntimeSpecification.Container container)
            return container.publishedPorts().keySet().stream().sorted().map(port -> new OfficialPort(port, "tcp"))
                    .toList();
        return runtime.healthCheck().portNumber().stream().mapToObj(
                port -> new OfficialPort(port, runtime.healthCheck() instanceof HealthCheck.Udp ? "udp" : "tcp"))
                .toList();
    }
}
