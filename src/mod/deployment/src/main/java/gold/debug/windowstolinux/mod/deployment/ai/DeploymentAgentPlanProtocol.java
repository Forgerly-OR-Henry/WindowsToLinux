package gold.debug.windowstolinux.mod.deployment.ai;

import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;

import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;

/** Converts a strict autonomous graph into existing typed deployment values. / 将严格自主图转换为既有类型化部署值。 */
public final class DeploymentAgentPlanProtocol {
    /** No construction. / 禁止构造。 */
    private DeploymentAgentPlanProtocol() {
    }

    /** Parses complete graph without consulting standard recognition. / 不查询标准识别而解析完整图。
     * @param node plan action arguments / 计划动作参数
     * @return typed graph / 类型化图 */
    public static DeploymentAgentPlan parse(JsonNode node) {
        JsonNode gate = node.get("applicationHealth");
        DeploymentAgentProtocol.exact(gate, Set.of("component", "health"));
        var components = new ArrayList<DeploymentAgentPlan.Component>();
        for (JsonNode item : array(node.get("components"), 16))
            components.add(component(item));
        return new DeploymentAgentPlan(DeploymentAiJson.text(node, "applicationId", 63),
                DeploymentAiJson.text(gate, "component", 63), health(gate.get("health")), components);
    }

    /** Parses one declared component and its exact runtime. / 解析一个声明组件及其精确运行定义。
     * @param node component object / 组件对象
     * @return typed component / 类型化组件 */
    private static DeploymentAgentPlan.Component component(JsonNode node) {
        DeploymentAgentProtocol.exact(node, Set.of("id", "dependencies", "evidence", "backend", "mode",
                "entrypoint", "arguments", "workingDirectory", "health", "ports", "resources", "configuration"));
        String id = DeploymentAiJson.text(node, "id", 63);
        var dependencies = strings(node.get("dependencies"), 15, 63);
        var evidence = strings(node.get("evidence"), 32, 500);
        var health = health(node.get("health"));
        var command = new DeploymentCommand(DeploymentAiJson.text(node, "entrypoint", 512),
                strings(node.get("arguments"), 64, 4000));
        var mode = DeploymentWorkload.ExecutionMode.valueOf(DeploymentAiJson.text(node, "mode", 32));
        var endpoints = new ArrayList<DeploymentEndpoint>();
        var published = new LinkedHashMap<Integer, Integer>();
        for (JsonNode port : array(node.get("ports"), 32)) {
            DeploymentAgentProtocol.exact(port, Set.of("id", "host", "target", "exposure"));
            var exposure = DeploymentEndpoint.ExposureType.valueOf(DeploymentAiJson.text(port, "exposure", 16));
            int host = DeploymentAgentProtocol.number(port, "host", 1, 65535);
            int target = DeploymentAgentProtocol.number(port, "target", 1, 65535);
            endpoints.add(new DeploymentEndpoint(DeploymentAiJson.text(port, "id", 63),
                    DeploymentEndpoint.ProtocolType.TCP,
                    exposure == DeploymentEndpoint.ExposureType.EXTERNAL ? "0.0.0.0" : "127.0.0.1", host, target,
                    exposure, ""));
            if (published.put(host, target) != null)
                throw new IllegalArgumentException("Duplicate agent host port");
        }
        Optional<DeploymentCommand> verification = health instanceof DeploymentHealthCheck.Command probe
                ? Optional.of(probe.command()) : Optional.empty();
        String expected = health instanceof DeploymentHealthCheck.Command probe ? probe.expectedOutput() : "";
        var workload = new DeploymentWorkload(mode, true, command,
                boundedText(node, "workingDirectory", 512), endpoints, verification, expected,
                Optional.empty(), List.of(), List.of());
        DeploymentRuntimeDefinition runtime = switch (DeploymentAiJson.text(node, "backend", 16)) {
            case "PROCESS" -> new DeploymentRuntimeDefinition.ManagedProcess(health,
                    DeploymentIdentityMode.SYSTEMD_STATIC, workload);
            case "DOCKER", "PODMAN" -> new DeploymentRuntimeDefinition.Container(
                    DeploymentRuntimeDefinition.ContainerEngineType
                            .valueOf(DeploymentAiJson.text(node, "backend", 16)),
                    published, List.of(), health, DeploymentIdentityMode.CONTAINER_NON_ROOT, workload);
            default -> throw new IllegalArgumentException("Unsupported agent backend");
        };
        return new DeploymentAgentPlan.Component(id, dependencies, evidence, runtime, resources(node.get("resources")),
                configuration(node.get("configuration")));
    }

    /** Parses only supported explicit health checks. / 仅解析受支持的明确健康检查。
     * @param node health object / 健康对象
     * @return typed check / 类型化检查 */
    private static DeploymentHealthCheck health(JsonNode node) {
        String type = DeploymentAiJson.text(node, "type", 16);
        return switch (type) {
            case "PROCESS" -> {
                DeploymentAgentProtocol.exact(node, Set.of("type", "timeout", "stability"));
                yield new DeploymentHealthCheck.Process(
                        DeploymentAgentProtocol.number(node, "timeout", 1, 3600),
                        DeploymentAgentProtocol.number(node, "stability", 1, 3600));
            }
            case "TCP" -> {
                DeploymentAgentProtocol.exact(node, Set.of("type", "port", "timeout", "stability"));
                yield new DeploymentHealthCheck.Tcp(DeploymentAgentProtocol.number(node, "port", 1, 65535),
                        DeploymentAgentProtocol.number(node, "timeout", 1, 3600),
                        DeploymentAgentProtocol.number(node, "stability", 1, 3600));
            }
            case "HTTP" -> {
                DeploymentAgentProtocol.exact(node, Set.of("type", "url", "status", "timeout"));
                yield new DeploymentHealthCheck.Http(URI.create(DeploymentAiJson.text(node, "url", 2048)),
                        DeploymentAgentProtocol.number(node, "status", 200, 399),
                        DeploymentAgentProtocol.number(node, "timeout", 1, 3600));
            }
            case "COMMAND" -> {
                DeploymentAgentProtocol.exact(node, Set.of("type", "entrypoint", "arguments", "expected", "timeout"));
                yield new DeploymentHealthCheck.Command(
                        new DeploymentCommand(DeploymentAiJson.text(node, "entrypoint", 512),
                                strings(node.get("arguments"), 64, 4000)),
                        DeploymentAiJson.text(node, "expected", 4096),
                        DeploymentAgentProtocol.number(node, "timeout", 1, 3600));
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
            DeploymentAgentProtocol.exact(item,
                    Set.of("id", "path", "access", "schema", "reversible", "type", "seedFile", "digest"));
            JsonNode reversible = item.get("reversible");
            if (!reversible.isBoolean())
                throw new IllegalArgumentException("Agent resource reversibility must be boolean");
            var path = new DeploymentDataPath(DeploymentAiJson.text(item, "path", 500),
                    DeploymentDataPath.AccessMode.valueOf(DeploymentAiJson.text(item, "access", 16)),
                    DeploymentAiJson.text(item, "schema", 64), reversible.booleanValue());
            files.add(new DeploymentFileBinding(DeploymentAiJson.text(item, "id", 63), path,
                    DeploymentStorageLocation.defaults(),
                    DeploymentStorageLocation.StorageResourceType.valueOf(DeploymentAiJson.text(item, "type", 32)),
                    boundedText(item, "seedFile", 500), boundedText(item, "digest", 64)));
        }
        return new DeploymentResourceBindings(files, Optional.of(List.of()));
    }

    /** Parses nonsecret typed runtime configuration. / 解析不含秘密的类型化运行配置。
     * @param node configuration array / 配置数组
     * @return checked entries / 已检查条目 */
    private static List<DeploymentConfigurationEntry> configuration(JsonNode node) {
        var entries = new ArrayList<DeploymentConfigurationEntry>();
        for (JsonNode item : array(node, 64)) {
            DeploymentAgentProtocol.exact(item, Set.of("key", "value"));
            String key = DeploymentAiJson.text(item, "key", 64);
            if (key.matches("(?i).*(password|passwd|secret|token|api_?key|private_?key).*"))
                throw new IllegalArgumentException("Agent credentials require a separate user secret workflow");
            JsonNode value = item.get("value");
            DeploymentConfigurationValue typed;
            if (value.isTextual())
                typed = new DeploymentConfigurationValue.Text(value.textValue());
            else if (value.isIntegralNumber() && value.canConvertToLong())
                typed = new DeploymentConfigurationValue.Number(value.longValue());
            else if (value.isBoolean())
                typed = new DeploymentConfigurationValue.Flag(value.booleanValue());
            else
                throw new IllegalArgumentException("Agent configuration must be literal");
            entries.add(new DeploymentConfigurationEntry(key, DeploymentConfigurationScopeType.RUNTIME, typed));
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
     * @param key property / 属性
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
