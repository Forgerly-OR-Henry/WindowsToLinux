package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortBinding;
import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortMode;
import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortPlan;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentComponent;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentRequest;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Selects the fail-closed application-wide restore mode and unique dynamic loopback ports. / 选择故障关闭的整应用恢复模式及唯一动态回环端口。 */
public final class RestoreCandidatePortPlanner {
    public static final int FIRST_CANDIDATE_PORT = 49152;
    public static final int LAST_CANDIDATE_PORT = 65535;

    /** Plans without treating a health endpoint as an application binding override. / 制定计划且不把健康端点当作应用监听覆盖。 */
    public CandidatePortPlan plan(RestoreDeploymentRequest request, Set<Integer> unavailablePorts) {
        return plan(request, unavailablePorts, false);
    }

    /** Forces stopped-write mode when a database must be activated before process health. / 数据库需先激活再检查进程健康时强制停写模式。 */
    public CandidatePortPlan plan(
            RestoreDeploymentRequest request, Set<Integer> unavailablePorts, boolean databaseActivationRequired) {
        return plan(request, unavailablePorts, Set.of(), databaseActivationRequired);
    }

    public CandidatePortPlan plan(RestoreDeploymentRequest request, Set<Integer> tcp, Set<Integer> udp, boolean databaseActivationRequired) {
        Objects.requireNonNull(request, "request");
        Set<String> unavailable = new HashSet<>();
        tcp.forEach(port -> unavailable.add("tcp:" + port)); udp.forEach(port -> unavailable.add("udp:" + port));
        boolean parallel = (!databaseActivationRequired || request.isolatedDatabase())
                && request.components().stream().allMatch(this::supportsTypedOverride);
        LinkedHashMap<String, List<CandidatePortBinding>> bindings = new LinkedHashMap<>();
        if (!parallel) {
            request.components().forEach(component -> bindings.put(component.componentId(), List.of()));
            return new CandidatePortPlan(request.isolatedDatabase() ? CandidatePortMode.ISOLATED_STOPPED : CandidatePortMode.SHORT_STOP, bindings);
        }
        request.components().forEach(component -> officialPorts(component).forEach(port -> unavailable.add(port.protocol() + ":" + port.officialPort())));
        for (RestoreDeploymentComponent component : request.components()) {
            List<CandidatePortBinding> selected = new ArrayList<>();
            for (var port : officialPorts(component)) {
                int candidate = FIRST_CANDIDATE_PORT;
                while (candidate <= LAST_CANDIDATE_PORT && unavailable.contains(port.protocol() + ":" + candidate)) candidate++;
                if (candidate > LAST_CANDIDATE_PORT) throw new IllegalStateException("no candidate port remains");
                selected.add(new CandidatePortBinding(port.officialPort(), candidate, port.protocol()));
                unavailable.add(port.protocol() + ":" + candidate);
            }
            bindings.put(component.componentId(), List.copyOf(selected));
        }
        return new CandidatePortPlan(CandidatePortMode.PARALLEL_LOOPBACK, bindings);
    }

    private boolean supportsTypedOverride(RestoreDeploymentComponent component) {
        if (component.runtime().healthCheck().portNumber().isEmpty() && component.runtime().workload().endpoints().isEmpty()) return true;
        return switch (component.runtime()) {
            case DeploymentRuntimeSpecification.Container ignored -> true;
            case DeploymentRuntimeSpecification.StaticSite ignored -> true;
            case DeploymentRuntimeSpecification.PhpService ignored -> true;
            case DeploymentRuntimeSpecification.RubyService ignored -> true;
            default -> false;
        };
    }

    private record OfficialPort(int officialPort, String protocol) { }

    private List<OfficialPort> officialPorts(RestoreDeploymentComponent component) {
        var runtime = component.runtime();
        if (!runtime.workload().endpoints().isEmpty()) return runtime.workload().endpoints().stream()
                .map(endpoint -> new OfficialPort(endpoint.hostPort(), endpoint.protocol().transport())).toList();
        if (runtime instanceof DeploymentRuntimeSpecification.Container container) return container.publishedPorts().keySet().stream().sorted()
                .map(port -> new OfficialPort(port, "tcp")).toList();
        return runtime.healthCheck().portNumber().stream().mapToObj(port -> new OfficialPort(port,
                runtime.healthCheck() instanceof HealthCheck.Udp ? "udp" : "tcp")).toList();
    }
}
