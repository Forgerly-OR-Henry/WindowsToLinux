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
        Objects.requireNonNull(request, "request");
        Set<Integer> unavailable = new HashSet<>(Objects.requireNonNull(unavailablePorts, "unavailablePorts"));
        if (unavailable.stream().anyMatch(port -> port == null || port < 1 || port > 65535)) {
            throw new IllegalArgumentException("unavailablePorts contains an invalid port");
        }
        boolean parallel = request.components().stream().allMatch(this::supportsTypedOverride);
        LinkedHashMap<String, List<CandidatePortBinding>> bindings = new LinkedHashMap<>();
        if (!parallel) {
            request.components().forEach(component -> bindings.put(component.componentId(), List.of()));
            return new CandidatePortPlan(CandidatePortMode.SHORT_STOP, bindings);
        }
        Set<Integer> official = new HashSet<>();
        request.components().forEach(component -> official.addAll(officialPorts(component)));
        unavailable.addAll(official);
        int candidate = FIRST_CANDIDATE_PORT;
        for (RestoreDeploymentComponent component : request.components()) {
            List<CandidatePortBinding> selected = new ArrayList<>();
            for (int officialPort : officialPorts(component)) {
                while (candidate <= LAST_CANDIDATE_PORT && unavailable.contains(candidate)) candidate++;
                if (candidate > LAST_CANDIDATE_PORT) {
                    throw new IllegalStateException("no dynamic candidate port remains in the reviewed range");
                }
                selected.add(new CandidatePortBinding(officialPort, candidate));
                unavailable.add(candidate++);
            }
            bindings.put(component.componentId(), List.copyOf(selected));
        }
        return new CandidatePortPlan(CandidatePortMode.PARALLEL_LOOPBACK, bindings);
    }

    private boolean supportsTypedOverride(RestoreDeploymentComponent component) {
        return switch (component.runtime()) {
            case DeploymentRuntimeSpecification.Container ignored -> true;
            case DeploymentRuntimeSpecification.StaticSite ignored -> true;
            case DeploymentRuntimeSpecification.PhpService ignored -> true;
            case DeploymentRuntimeSpecification.RubyService ignored -> true;
            default -> false;
        };
    }

    private List<Integer> officialPorts(RestoreDeploymentComponent component) {
        return switch (component.runtime()) {
            case DeploymentRuntimeSpecification.Container container -> container.publishedPorts().keySet().stream()
                    .sorted().toList();
            case DeploymentRuntimeSpecification.StaticSite site -> List.of(healthPort(site.healthCheck()));
            case DeploymentRuntimeSpecification.PhpService service -> List.of(service.servicePort());
            case DeploymentRuntimeSpecification.RubyService service -> List.of(service.servicePort());
            default -> List.of();
        };
    }

    private static int healthPort(HealthCheck health) {
        if (health instanceof HealthCheck.Tcp tcp) return tcp.port();
        HealthCheck.Http http = (HealthCheck.Http) health;
        if (http.endpoint().getPort() >= 1) return http.endpoint().getPort();
        return http.endpoint().getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }
}
