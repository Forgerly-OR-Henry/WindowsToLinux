package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCommand;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentEndpoint;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentIdentityMode;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload;

/** Converts a strict autonomous graph into existing typed deployment values. / 将严格自主图转换为既有类型化部署值。 */
public final class DeploymentAgentPlanProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** No construction. / 禁止构造。 */
    private DeploymentAgentPlanProtocol() {
    }

    /** Parses complete graph without consulting standard recognition. / 不查询标准识别而解析完整图。
     * @param node plan action arguments / 计划动作参数
     * @return typed graph / 类型化图 */
    public static DeploymentAgentPlan parse(JsonNode node) {
        JsonNode gate = node.get("applicationHealth");
        DeploymentJsonFields.exact(gate, Set.of("component", "health"));
        var components = new ArrayList<DeploymentAgentPlan.Part>();
        for (JsonNode item : array(node.get("components"), 16))
            components.add(component(item));
        return new DeploymentAgentPlan(DeploymentJsonFields.text(node, "applicationId", 63),
            DeploymentJsonFields.text(gate, "component", 63), health(gate.get("health")), components);
    }

    /** Encodes an accepted model declaration in the original native plan shape, without assessment or resolved tool values. / 按原生计划形状编码已接受模型声明，不含判断或工具解析值。
     * @param declaration validated pre-resolution model declaration / 解析资源前已校验的模型声明
     * @return fresh native plan arguments without execution authority / 不携带执行权限的新原生计划参数
     * @throws IllegalArgumentException if the declaration uses a runtime or health type outside the native plan contract / 声明使用原生计划合同外的运行或健康类型时
     * @throws NullPointerException if the declaration is absent / 声明缺省时 */
    public static ObjectNode encode(DeploymentAgentProposal.Plan declaration) {
        var plan = Objects.requireNonNull(declaration).plan();
        var output = JSON.createObjectNode().put("applicationId", plan.applicationId()).put("explanation", declaration.explanation());
        var components = output.putArray("components");
        for (var part : plan.components()) {
            var component = components.addObject().put("id", part.id()).put("sourceRoot", part.sourceRoot());
            part.dependencies().forEach(component.putArray("dependencies")::add);
            part.evidence().forEach(component.putArray("evidence")::add);
            String backend;
            if (part.runtime() instanceof DeploymentRuntimeDefinition.ManagedProcess) backend = "PROCESS";
            else if (part.runtime() instanceof DeploymentRuntimeDefinition.Container container)
                backend = container.engine().name();
            else throw new IllegalArgumentException("Not a native Agent plan runtime");
            var workload = part.runtime().workload();
            component.put("backend", backend).put("mode", workload.mode().name()).put("entrypoint", workload.command().entrypoint());
            workload.command().arguments().forEach(component.putArray("arguments")::add);
            component.put("workingDirectory", workload.workingDirectory());
            component.set("health", encodeHealth(part.runtime().healthCheck()));
            var ports = component.putArray("ports");
            for (var port : workload.endpoints())
                ports.addObject().put("id", port.id()).put("protocol", port.protocol().name()).put("host", port.hostPort())
                    .put("target", port.targetPort()).put("exposure", port.exposure().name()).put("accessUrl", port.accessUrl());
            var resources = component.putArray("resources");
            for (var resource : part.resources().fileBindings()) {
                var path = resource.dataPath();
                resources.addObject().put("id", resource.bindingId()).put("path", path.path()).put("access", path.access().name())
                    .put("schema", path.schemaId()).put("reversible", path.reversible()).put("type", resource.resourceType().name())
                    .put("seedFile", resource.seedFile()).put("digest", resource.contentSha256());
            }
            var configuration = component.putArray("configuration");
            for (var entry : part.configuration()) {
                var value = configuration.addObject().put("key", entry.key());
                switch (entry.value()) {
                    case DeploymentConfigValue.Text text -> value.put("value", text.value());
                    case DeploymentConfigValue.Number number -> value.put("value", number.value());
                    case DeploymentConfigValue.Flag flag -> value.put("value", flag.value());
                }
            }
            if (part.databaseIds().isEmpty()) component.putNull("databases");
            else part.databaseIds().orElseThrow().forEach(component.putArray("databases")::add);
            part.workspaceRequest().ifPresent(request -> component.set("workspace", JSON.valueToTree(request)));
            var references = component.putArray("secretReferences");
            for (var ref : part.secretReferences())
                references.addObject().put("identifier", ref.identifier()).put("revision", ref.revision());
        }
        output.putObject("applicationHealth").put("component", plan.healthComponentId()).set("health", encodeHealth(plan.health()));
        return output;
    }

    /** Encodes only the health shapes accepted by the native parser. / 仅编码原生解析器接受的健康形状。
     * @param health declared health contract / 已声明健康合同
     * @return native health object / 原生健康对象
     * @throws IllegalArgumentException if the health type is not supported by the native parser / 原生解析器不支持健康类型时 */
    private static ObjectNode encodeHealth(DeploymentHealthCheck health) {
        return switch (health) {
            case DeploymentHealthCheck.Process process -> JSON.createObjectNode().put("type", "PROCESS")
                .put("timeout", process.timeoutSeconds()).put("stability", process.stabilitySeconds());
            case DeploymentHealthCheck.Tcp tcp -> JSON.createObjectNode().put("type", "TCP").put("port", tcp.port())
                .put("timeout", tcp.timeoutSeconds()).put("stability", tcp.stabilitySeconds());
            case DeploymentHealthCheck.Http http ->
                JSON.createObjectNode().put("type", "HTTP").put("url", http.endpoint().toString())
                    .put("status", http.expectedStatus()).put("timeout", http.timeoutSeconds());
            case DeploymentHealthCheck.Command command -> {
                var node = JSON.createObjectNode().put("type", "COMMAND").put("entrypoint", command.command().entrypoint())
                    .put("expected", command.expectedOutput()).put("timeout", command.timeoutSeconds());
                command.command().arguments().forEach(node.putArray("arguments")::add);
                yield node;
            }
            default -> throw new IllegalArgumentException("Not a native Agent plan health check");
        };
    }

    /** Parses one declared component and its exact runtime. / 解析一个声明组件及其精确运行定义。
     * @param node component object / 组件对象
     * @return typed component / 类型化组件 */
    private static DeploymentAgentPlan.Part component(JsonNode node) {
        DeploymentJsonFields.exact(node, Set.of("id", "dependencies", "evidence", "backend", "mode", "entrypoint",
            "arguments", "workingDirectory", "health", "ports", "resources", "configuration", "sourceRoot", "databases", "secretReferences", "workspace"));
        String id = DeploymentJsonFields.text(node, "id", 63);
        var dependencies = strings(node.get("dependencies"), 15, 63);
        var evidence = strings(node.get("evidence"), 32, 500);
        var health = health(node.get("health"));
        var command = new DeploymentCommand(DeploymentJsonFields.text(node, "entrypoint", 512),
            strings(node.get("arguments"), 64, 4000));
        var mode = DeploymentWorkload.ExecutionMode.valueOf(DeploymentJsonFields.text(node, "mode", 32));
        var endpoints = new ArrayList<DeploymentEndpoint>();
        var published = new LinkedHashMap<Integer, Integer>();
        for (JsonNode port : array(node.get("ports"), 32)) {
            DeploymentJsonFields.exact(port, Set.of("id", "protocol", "host", "target", "exposure", "accessUrl"));
            var exposure = DeploymentEndpoint.ExposureType.valueOf(DeploymentJsonFields.text(port, "exposure", 16));
            var protocol = DeploymentEndpoint.ProtocolType.valueOf(DeploymentJsonFields.text(port, "protocol", 16));
            int host = DeploymentJsonFields.number(port, "host", 1, 65535);
            int target = DeploymentJsonFields.number(port, "target", 1, 65535);
            String accessUrl = boundedText(port, "accessUrl", 4096);
            if (!accessUrl.isEmpty()) {
                URI address = DeploymentEndpoint.requireAccessUrl(URI.create(accessUrl));
                int accessPort = address.getPort() == -1 ? "https".equalsIgnoreCase(address.getScheme()) ? 443 : 80 : address.getPort();
                if (!protocol.name().equalsIgnoreCase(address.getScheme()) || accessPort != host)
                    throw new IllegalArgumentException("Agent endpoint accessUrl must match its explicit protocol and host port");
            }
            endpoints.add(
                new DeploymentEndpoint(DeploymentJsonFields.text(port, "id", 63), protocol,
                    exposure == DeploymentEndpoint.ExposureType.EXTERNAL ? "0.0.0.0" : "127.0.0.1", host,
                    target, exposure, accessUrl));
            if (published.put(host, target) != null)
                throw new IllegalArgumentException("Duplicate agent host port");
        }
        Optional<DeploymentCommand> verification = health instanceof DeploymentHealthCheck.Command probe
            ? Optional.of(probe.command())
            : Optional.empty();
        String expected = health instanceof DeploymentHealthCheck.Command probe ? probe.expectedOutput() : "";
        var workload = new DeploymentWorkload(mode, true, command, boundedText(node, "workingDirectory", 512),
            endpoints, verification, expected, Optional.empty(), List.of(), List.of());
        DeploymentRuntimeDefinition runtime = switch (DeploymentJsonFields.text(node, "backend", 16)) {
            case "PROCESS" ->
                new DeploymentRuntimeDefinition.ManagedProcess(health, DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            case "DOCKER",
                 "PODMAN" -> new DeploymentRuntimeDefinition.Container(
                DeploymentRuntimeDefinition.ContainerEngineType
                    .valueOf(DeploymentJsonFields.text(node, "backend", 16)),
                published, List.of(), health, DeploymentIdentityMode.CONTAINER_NON_ROOT, workload);
            default -> throw new IllegalArgumentException("Unsupported agent backend");
        };
        var refs = new ArrayList<gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef>();
        for (var item : array(node.get("secretReferences"), 64)) {
            DeploymentJsonFields.exact(item, Set.of("identifier", "revision"));
            refs.add(new gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef(
                DeploymentJsonFields.text(item, "identifier", 128), DeploymentJsonFields.number(item, "revision", 1, Integer.MAX_VALUE)));
        }
        var databases = node.get("databases");
        return new DeploymentAgentPlan.Part(id, DeploymentJsonFields.text(node, "sourceRoot", 500), dependencies,
            evidence, runtime, resources(node.get("resources")), configuration(node.get("configuration")),
            databases != null && databases.isNull() ? Optional.empty() : Optional.of(strings(databases, 32, 63)), refs, Optional.of(workspace(node.get("workspace"))), Optional.empty());
    }

    private static gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request workspace(JsonNode node) {
        DeploymentJsonFields.exact(node, Set.of("mode", "minimumBuildBytes", "preferredBuildBytes", "explicitBytes"));
        for (String key : List.of("minimumBuildBytes", "preferredBuildBytes", "explicitBytes"))
            if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToLong())
                throw new IllegalArgumentException("WORKSPACE_DEMAND_INVALID: integral byte budgets required");
        return new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request(
            gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode.valueOf(DeploymentJsonFields.text(node, "mode", 16)),
            node.path("minimumBuildBytes").longValue(), node.path("preferredBuildBytes").longValue(), node.path("explicitBytes").longValue());
    }

    /** Parses only supported explicit health checks. / 仅解析受支持的明确健康检查。
     * @param node health object / 健康对象
     * @return typed check / 类型化检查 */
    private static DeploymentHealthCheck health(JsonNode node) {
        String type = DeploymentJsonFields.text(node, "type", 16);
        return switch (type) {
            case "PROCESS" -> {
                DeploymentJsonFields.exact(node, Set.of("type", "timeout", "stability"));
                yield new DeploymentHealthCheck.Process(DeploymentJsonFields.number(node, "timeout", 1, 3600),
                    DeploymentJsonFields.number(node, "stability", 1, 3600));
            }
            case "TCP" -> {
                DeploymentJsonFields.exact(node, Set.of("type", "port", "timeout", "stability"));
                yield new DeploymentHealthCheck.Tcp(DeploymentJsonFields.number(node, "port", 1, 65535),
                    DeploymentJsonFields.number(node, "timeout", 1, 3600),
                    DeploymentJsonFields.number(node, "stability", 1, 3600));
            }
            case "HTTP" -> {
                DeploymentJsonFields.exact(node, Set.of("type", "url", "status", "timeout"));
                yield new DeploymentHealthCheck.Http(URI.create(DeploymentJsonFields.text(node, "url", 2048)),
                    DeploymentJsonFields.number(node, "status", 200, 399),
                    DeploymentJsonFields.number(node, "timeout", 1, 3600));
            }
            case "COMMAND" -> {
                DeploymentJsonFields.exact(node, Set.of("type", "entrypoint", "arguments", "expected", "timeout"));
                yield new DeploymentHealthCheck.Command(
                    new DeploymentCommand(DeploymentJsonFields.text(node, "entrypoint", 512),
                        strings(node.get("arguments"), 64, 4000)),
                    DeploymentJsonFields.text(node, "expected", 4096),
                    DeploymentJsonFields.number(node, "timeout", 1, 3600));
            }
            default -> throw new IllegalArgumentException("Unsupported agent health check");
        };
    }

    /** Parses persistent FILE and immutable CONFIGURATION bindings; this agent protocol excludes databases. / 解析持久 FILE 与不可变 CONFIGURATION 绑定；此自主协议不接纳数据库。
     * @param node resource array / 资源数组
     * @return checked resource bindings / 已检查资源绑定 */
    private static DeploymentResourceBindings resources(JsonNode node) {
        var files = new ArrayList<DeploymentFileBinding>();
        for (JsonNode item : array(node, 32)) {
            DeploymentJsonFields.exact(item,
                Set.of("id", "path", "access", "schema", "reversible", "type", "seedFile", "digest"));
            JsonNode reversible = item.get("reversible");
            if (!reversible.isBoolean())
                throw new IllegalArgumentException("Agent resource reversibility must be boolean");
            var path = new DeploymentStoragePath(DeploymentJsonFields.text(item, "path", 500),
                DeploymentStoragePath.AccessMode.valueOf(DeploymentJsonFields.text(item, "access", 16)),
                DeploymentJsonFields.text(item, "schema", 64), reversible.booleanValue());
            files.add(new DeploymentFileBinding(DeploymentJsonFields.text(item, "id", 63), path,
                DeploymentStorageLocation.defaults(),
                DeploymentStorageLocation.StorageResourceType.valueOf(DeploymentJsonFields.text(item, "type", 32)),
                boundedText(item, "seedFile", 500), boundedText(item, "digest", 64)));
        }
        return new DeploymentResourceBindings(files, Optional.of(List.of()));
    }

    /** Parses nonsecret typed runtime configuration. / 解析不含秘密的类型化运行配置。
     * @param node configuration array / 配置数组
     * @return checked entries / 已检查条目 */
    private static List<DeploymentConfigEntry> configuration(JsonNode node) {
        var entries = new ArrayList<DeploymentConfigEntry>();
        for (JsonNode item : array(node, 64)) {
            DeploymentJsonFields.exact(item, Set.of("key", "value"));
            String key = DeploymentJsonFields.text(item, "key", 64);
            if (key.matches("(?i).*(password|passwd|secret|token|api_?key|private_?key).*"))
                throw new IllegalArgumentException("Agent credentials require a separate user secret workflow");
            JsonNode value = item.get("value");
            DeploymentConfigValue typed;
            if (value.isTextual())
                typed = new DeploymentConfigValue.Text(value.textValue());
            else if (value.isIntegralNumber() && value.canConvertToLong())
                typed = new DeploymentConfigValue.Number(value.longValue());
            else if (value.isBoolean())
                typed = new DeploymentConfigValue.Flag(value.booleanValue());
            else
                throw new IllegalArgumentException("Agent configuration must be literal");
            entries.add(new DeploymentConfigEntry(key, DeploymentConfigScopeType.RUNTIME, typed));
        }
        return List.copyOf(entries);
    }

    /** Reads a bounded array without coercion. / 不强制转换而读取有界数组。
     * @param node array input / 数组输入
     * @param limit maximum entries / 最大项数
     * @return copied nodes / 复制节点 */
    private static List<JsonNode> array(JsonNode node, int limit) {
        if (node == null || !node.isArray() || node.size() > limit)
            throw new IllegalArgumentException("Bounded agent array required");
        var values = new ArrayList<JsonNode>();
        node.forEach(values::add);
        return List.copyOf(values);
    }

    /** Reads bounded literal strings. / 读取有界字面字符串。
     * @param node string array / 字符串数组
     * @param limit item limit / 项数上限
     * @param characters character limit / 字符上限
     * @return copied values / 复制值 */
    private static List<String> strings(JsonNode node, int limit, int characters) {
        return array(node, limit).stream().map(item -> {
            if (!item.isTextual() || item.textValue().length() > characters)
                throw new IllegalArgumentException("Bounded agent text required");
            return item.textValue();
        }).toList();
    }

    /** Reads an exact bounded string that may intentionally denote the root. / 读取可有意表示根的精确有界字符串。
     * @param node exact object / 精确对象
     * @param key original indexed checkpoint key / 原索引检查点键
     * @param limit maximum characters / 最大字符数
     * @return literal value / 字面值 */
    private static String boundedText(JsonNode node, String key, int limit) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().length() > limit
            || value.textValue().indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid agent string: " + key);
        return value.textValue();
    }
}
