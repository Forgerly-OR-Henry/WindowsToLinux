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
        URI requested = publicUrl(requirements.get("publicUrl"));
        int port = requested.getPort() == -1 ? "https".equalsIgnoreCase(requested.getScheme()) ? 443 : 80 : requested.getPort();
        boolean exposed = plan.components().stream().flatMap(component -> component.runtime().workload().endpoints().stream())
            .anyMatch(endpoint -> endpoint.exposure() == DeploymentEndpoint.ExposureType.EXTERNAL && endpoint.hostPort() == port
                && endpoint.protocol().name().equalsIgnoreCase(requested.getScheme())
                && !endpoint.accessUrl().isEmpty() && requested.equals(URI.create(endpoint.accessUrl())));
        if (!exposed)
            throw new IllegalArgumentException("AGENT_PLAN_NETWORK_CONSTRAINT: requirements.publicUrl requires an EXTERNAL "
                + "endpoint on host port " + port + " with protocol " + requested.getScheme().toUpperCase(java.util.Locale.ROOT)
                + " and an explicit accessUrl equal to publicUrl; correct the source-backed declaration. "
                + "TCP reachability, INTERNAL exposure, an absent URL or a different URL does not satisfy that user requirement.");
    }

    private static URI publicUrl(String value) {
        URI uri;
        try {
            if (value == null) throw new IllegalArgumentException();
            uri = URI.create(value);
            DeploymentEndpoint.requireAccessUrl(uri);
        } catch (IllegalArgumentException invalid) {
            throw invalidPublicUrl();
        }
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null)
            throw invalidPublicUrl();
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) throw invalidPublicUrl();
        return uri;
    }

    private static IllegalArgumentException invalidPublicUrl() {
        return new IllegalArgumentException("AGENT_PLAN_NETWORK_CONSTRAINT: requirements.publicUrl must be a valid "
            + "HTTP/HTTPS URL with a host, no user-info, and an explicit port in 1..65535 or the scheme default.");
    }
}
