package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentEndpoint;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;

/** Checks explicit Agent network intent without inferring component roles or changing the plan. / 校验明确的 Agent 网络意图，不推断组件角色或改写计划。 */
public final class DeploymentAgentPlanNetworkConstraints {
    private DeploymentAgentPlanNetworkConstraints() {
    }

    /** Rejects unsupported process mappings and missing requested public exposure before resource resolution. / 资源解析前拒绝无实现的进程端口映射及缺失的指定外露端口。
     * @param plan original model declaration / 原模型声明
     * @param requirements original nonsecret user intent / 原非秘密用户意图
     * @throws IllegalArgumentException if a process mapping or explicit public URL is inconsistent / 进程映射或明确公开网址不一致时 */
    public static void require(DeploymentAgentPlan plan, Map<String, String> requirements) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(requirements);
        for (var component : plan.components()) {
            if (!(component.runtime() instanceof DeploymentRuntimeDefinition.ManagedProcess)) continue;
            for (var endpoint : component.runtime().workload().endpoints())
                if (endpoint.hostPort() != endpoint.targetPort())
                    throw new IllegalArgumentException("AGENT_PLAN_NETWORK_CONSTRAINT: PROCESS endpoint " + endpoint.id()
                        + " in component " + component.id() + " declares host port " + endpoint.hostPort()
                        + " and target port " + endpoint.targetPort()
                        + "; PROCESS creates no port forwarding. Correct the plan and actual listener before sealing; "
                        + "host and target must name the same process listener port.");
        }
        if (!requirements.containsKey("publicUrl")) return;
        int port = publicPort(requirements.get("publicUrl"));
        boolean exposed = plan.components().stream().flatMap(component -> component.runtime().workload().endpoints().stream())
            .anyMatch(endpoint -> endpoint.exposure() == DeploymentEndpoint.ExposureType.EXTERNAL && endpoint.hostPort() == port);
        if (!exposed)
            throw new IllegalArgumentException("AGENT_PLAN_NETWORK_CONSTRAINT: requirements.publicUrl requires an EXTERNAL "
                + "endpoint on host port " + port + "; correct the source-backed plan before sealing. "
                + "An INTERNAL endpoint or a repository default port does not satisfy that user requirement.");
    }

    private static int publicPort(String value) {
        URI uri;
        try {
            if (value == null) throw new IllegalArgumentException();
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw invalidPublicUrl();
        }
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getRawUserInfo() != null)
            throw invalidPublicUrl();
        int port = uri.getPort();
        if (port == -1) return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        if (port < 1 || port > 65535) throw invalidPublicUrl();
        return port;
    }

    private static IllegalArgumentException invalidPublicUrl() {
        return new IllegalArgumentException("AGENT_PLAN_NETWORK_CONSTRAINT: requirements.publicUrl must be a valid "
            + "HTTP/HTTPS URL with a host, no user-info, and an explicit port in 1..65535 or the scheme default.");
    }
}
