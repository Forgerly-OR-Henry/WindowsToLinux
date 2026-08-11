package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.deploy.adapter.PhaseTwoDeploymentAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.container.ContainerAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.javajar.JavaJarAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.node.NodeServiceAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.python.PythonServiceAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.springboot.GradleSpringBootAdapter;
import gold.debug.windowstolinux.shared.deploy.adapter.staticweb.StaticSiteAdapter;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Routes a reviewed request to the only adapter that can plan its selected single-component project type.
 *
 * <p>将经审阅的请求路由到唯一能够计划其选定单组件项目类型的适配器。
 */
public final class PhaseTwoDeploymentPlanner {
    private final Map<PhaseTwoProjectType, PhaseTwoDeploymentAdapter> adapters;

    /**
     * Creates a planner with every Phase Two adapter.
     *
     * <p>使用每个二期适配器创建计划器。
     */
    public PhaseTwoDeploymentPlanner() {
        this(List.of(new GradleSpringBootAdapter(), new JavaJarAdapter(), new NodeServiceAdapter(),
                new PythonServiceAdapter(), new StaticSiteAdapter(), new ContainerAdapter()));
    }

    PhaseTwoDeploymentPlanner(List<PhaseTwoDeploymentAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        EnumMap<PhaseTwoProjectType, PhaseTwoDeploymentAdapter> indexed = new EnumMap<>(PhaseTwoProjectType.class);
        for (PhaseTwoDeploymentAdapter adapter : adapters) {
            PhaseTwoDeploymentAdapter previous = indexed.put(adapter.projectType(), Objects.requireNonNull(adapter, "adapter"));
            if (previous != null) {
                throw new IllegalArgumentException("each Phase Two project type must have exactly one adapter");
            }
        }
        if (indexed.size() != PhaseTwoProjectType.values().length) {
            throw new IllegalArgumentException("every Phase Two project type requires an adapter");
        }
        this.adapters = Map.copyOf(indexed);
    }

    /**
     * Produces the type-specific deterministic plan.
     *
     * <p>生成类型专属确定性计划。
     *
     * @param request the reviewed request / 经审阅的请求
     * @return the deployment plan / 部署计划
     */
    public PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request) {
        request = Objects.requireNonNull(request, "request");
        return adapters.get(request.facts().projectType()).plan(request);
    }
}
