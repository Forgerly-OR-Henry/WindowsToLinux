package gold.debug.windowstolinux.shared.agent.execution.protocol;

import static gold.debug.windowstolinux.shared.ai.execution.protocol.StrictJson.*;

import java.net.URI;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient;
import gold.debug.windowstolinux.shared.ai.client.StructuredAiClient.Reply;
import gold.debug.windowstolinux.shared.deploy.delivery.ManagedDelivery;
import gold.debug.windowstolinux.shared.linux.workspace.RemoteSourcePatch;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.project.application.*;

/** Strict autonomous deployment schema and business validation. / 严格自主部署模式及业务校验。 */
public final class AutonomousAiProtocolClient {
    /** Versioned autonomous semantics. / 版本化自主语义。 */
    public static final String SKILL = "autonomous-deployment-v2";

    /** Generic provider transport. / 通用提供者传输。 */
    private final StructuredAiClient client;
    /** Binds transport without starting a model session. / 绑定传输，不启动模型会话。
     * @param client structured transport / 结构化传输
     */
    public AutonomousAiProtocolClient(StructuredAiClient client) {
        this.client = Objects.requireNonNull(client);
    }

    /** Obtains one bounded strict tool request. / 获取一个有界严格工具请求。
     * @param endpoint provider endpoint / 提供者端点
     * @param model selected model / 所选模型
     * @param key temporary credential / 临时凭据
     * @param context verified current state / 已验证当前状态
     * @param history actual recent observations / 实际最近观察
     * @param remaining global decision budget / 全局决策预算
     * @return strict proposal and reported usage / 严格提议及返回用量
     * @throws Exception on invalid or unavailable responses / 响应无效或不可用时
     */
    public Reply<AgentProposal> decide(URI endpoint, String model, char[] key, Map<String, Object> context,
            List<Map<String, String>> history, int remaining) throws Exception {
        var response = client.request(endpoint, model, key, AutonomousAiProtocolClient.class,
                "/gold/debug/windowstolinux/shared/agent/protocol/autonomous.md",
                Map.of("state", context, "observations", history, "remaining", remaining));
        return new Reply<>(parse(response.value()), response.tokens());
    }

    /** Parses only the documented tool union; unknown fields are rejected. / 仅解析已记录工具联合，拒绝未知字段。
     * @param node strict JSON response / 严格 JSON 响应
     * @return checked proposal / 已校验提议
     */
    public static AgentProposal parse(JsonNode node) {
        exact(node, Set.of("tool", "arguments"));
        JsonNode a = node.get("arguments");
        return switch (text(node, "tool")) {
            case "list" -> {
                exact(a, Set.of("path", "offset", "limit"));
                yield new AgentProposal.ListSource(text(a, "path"), number(a, "offset"), number(a, "limit"));
            }
            case "read" -> {
                exact(a, Set.of("path", "offset", "limit"));
                yield new AgentProposal.ReadSource(text(a, "path"), number(a, "offset"), number(a, "limit"));
            }
            case "search" -> {
                exact(a, Set.of("query", "offset", "limit"));
                yield new AgentProposal.SearchSource(text(a, "query"), number(a, "offset"), number(a, "limit"));
            }
            case "inspect" -> {
                exact(a, Set.of());
                yield new AgentProposal.InspectServer();
            }
            case "plan" -> {
                exact(a, Set.of("components", "explanation", "applicationHealth"));
                var gate = a.get("applicationHealth");
                exact(gate, Set.of("component", "health"));
                var graph = delivery(a.get("components"),
                        new gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate(
                                text(gate, "component"), health(gate.get("health"))));
                yield new AgentProposal.Plan(graph, text(a, "explanation"));
            }
            case "command" -> {
                exact(a, Set.of("component", "script", "seconds"));
                yield new AgentProposal.Command(text(a, "component"), longText(a, "script", 24000),
                        number(a, "seconds"));
            }
            case "remote_read" -> {
                exact(a, Set.of("component", "path", "offset", "limit"));
                yield new AgentProposal.ReadRemote(text(a, "component"), text(a, "path"), number(a, "offset"),
                        number(a, "limit"));
            }
            case "patch" -> {
                exact(a, Set.of("component", "path", "beforeDigest", "sourceRevision", "edits"));
                var edits = new ArrayList<RemoteSourcePatch.Edit>();
                for (var edit : array(a.get("edits"), 64)) {
                    exact(edit, Set.of("line", "removed", "added"));
                    edits.add(new RemoteSourcePatch.Edit(number(edit, "line"), strings(edit.get("removed"), 200),
                            strings(edit.get("added"), 200)));
                }
                yield new AgentProposal.Patch(text(a, "component"), new RemoteSourcePatch(text(a, "path"),
                        text(a, "beforeDigest"), text(a, "sourceRevision"), edits));
            }
            case "seal" -> {
                exact(a, Set.of("component"));
                yield new AgentProposal.Seal(text(a, "component"));
            }
            case "deliver" -> {
                exact(a, Set.of());
                yield new AgentProposal.Deliver();
            }
            case "input" -> {
                exact(a, Set.of("question"));
                yield new AgentProposal.NeedInput(text(a, "question"));
            }
            case "unable" -> {
                exact(a, Set.of("reason"));
                yield new AgentProposal.Unable(text(a, "reason"));
            }
            default -> throw new IllegalArgumentException("unknown autonomous tool");
        };
    }

    /** Converts an explicit delivery graph without consulting static recognition. / 转换显式交付图，不查询静态识别。
     * @param nodes component declarations / 组件声明
     * @param applicationHealth explicit whole-application probe / 显式整应用探针
     * @return checked managed graph / 已校验受管图
     */
    private static ManagedDelivery delivery(JsonNode nodes,
            gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate applicationHealth) {
        var components = new ArrayList<ManagedDelivery.Component>();
        for (var node : array(nodes, 16)) {
            exact(node, Set.of("id", "dependencies", "evidence", "backend", "mode", "entrypoint", "arguments",
                    "workingDirectory", "health", "ports", "resources", "configuration"));
            var health = health(node.get("health"));
            var command = new ApplicationCommand(text(node, "entrypoint"), strings(node.get("arguments"), 64));
            var mode = ApplicationWorkload.ExecutionMode.valueOf(text(node, "mode"));
            var endpoints = new ArrayList<ApplicationEndpoint>();
            var published = new LinkedHashMap<Integer, Integer>();
            for (var port : array(node.get("ports"), 32)) {
                exact(port, Set.of("id", "host", "target", "exposure"));
                var exposure = ApplicationEndpoint.ExposureType.valueOf(text(port, "exposure"));
                endpoints.add(new ApplicationEndpoint(text(port, "id"), ApplicationEndpoint.ProtocolType.TCP,
                        exposure == ApplicationEndpoint.ExposureType.EXTERNAL ? "0.0.0.0" : "127.0.0.1",
                        number(port, "host"), number(port, "target"), exposure, ""));
                if (published.put(number(port, "host"), number(port, "target")) != null)
                    throw new IllegalArgumentException("duplicate published port");
            }
            Optional<ApplicationCommand> verification = health instanceof HealthCheck.Command h
                    ? Optional.of(h.command())
                    : Optional.empty();
            String expected = health instanceof HealthCheck.Command h ? h.expectedOutput() : "";
            var workload = new ApplicationWorkload(mode, true, command, text(node, "workingDirectory"), endpoints,
                    verification, expected, Optional.empty(), List.of(), List.of(), "", List.of());
            DeploymentRuntimeSpecification runtime = switch (text(node, "backend")) {
                case "PROCESS" -> new DeploymentRuntimeSpecification.ManagedProcess(health,
                        RuntimeIdentityMode.SYSTEMD_STATIC, workload);
                case "DOCKER",
                        "PODMAN" ->
                    new DeploymentRuntimeSpecification.Container(
                            DeploymentRuntimeSpecification.ContainerEngineType.valueOf(text(node, "backend")),
                            published, List.of(), health).withWorkload(workload);
                default -> throw new IllegalArgumentException("unknown managed backend");
            };
            components.add(new ManagedDelivery.Component(text(node, "id"), strings(node.get("dependencies"), 15),
                    runtime, strings(node.get("evidence"), 32), AgentDeliveryInputs.resources(node.get("resources")),
                    AgentDeliveryInputs.configuration(node.get("configuration"))));
        }
        return new ManagedDelivery(components, applicationHealth);
    }

    /** Parses explicit healthy-state evidence. / 解析显式健康状态证据。
     * @param node health declaration / 健康声明
     * @return typed health condition / 类型化健康条件
     */
    private static HealthCheck health(JsonNode node) {
        return switch (text(node, "type")) {
            case "PROCESS" -> {
                exact(node, Set.of("type", "timeout", "stability"));
                yield new HealthCheck.Process(number(node, "timeout"), number(node, "stability"));
            }
            case "TCP" -> {
                exact(node, Set.of("type", "port", "timeout", "stability"));
                yield new HealthCheck.Tcp(number(node, "port"), number(node, "timeout"), number(node, "stability"));
            }
            case "HTTP" -> {
                exact(node, Set.of("type", "url", "status", "timeout"));
                yield new HealthCheck.Http(URI.create(text(node, "url")), number(node, "status"),
                        number(node, "timeout"));
            }
            case "COMMAND" -> {
                exact(node, Set.of("type", "entrypoint", "arguments", "expected", "timeout"));
                yield new HealthCheck.Command(
                        new ApplicationCommand(text(node, "entrypoint"), strings(node.get("arguments"), 64)),
                        text(node, "expected"), number(node, "timeout"));
            }
            default -> throw new IllegalArgumentException("unknown health condition");
        };
    }

    /** Requires an integer without coercing strings or fractions. / 要求整数，不强制转换字符串或小数。
     * @param node parent object / 父对象
     * @param field field name / 字段名
     * @return exact integer / 精确整数
     */
    private static int number(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException("integer required");
        return value.intValue();
    }

    /** Reads a bounded array. / 读取有界数组。
     * @param node input array / 输入数组
     * @param limit size limit / 大小限制
     * @return copied nodes / 复制节点
     */
    private static List<JsonNode> array(JsonNode node, int limit) {
        if (node == null || !node.isArray() || node.size() > limit)
            throw new IllegalArgumentException("bounded array required");
        var values = new ArrayList<JsonNode>();
        node.forEach(values::add);
        return List.copyOf(values);
    }

    /** Reads bounded literal strings. / 读取有界字面字符串。
     * @param node string array / 字符串数组
     * @param limit item limit / 条目限制
     * @return copied strings / 复制字符串
     */
    private static List<String> strings(JsonNode node, int limit) {
        return array(node, limit).stream().map(value -> {
            if (!value.isTextual() || value.textValue().length() > 4000)
                throw new IllegalArgumentException("bounded text required");
            return value.textValue();
        }).toList();
    }

    /** Allows bounded scripts larger than ordinary labels. / 允许比普通标签更长的有界脚本。
     * @param node parent object / 父对象
     * @param field field name / 字段名
     * @param max maximum characters / 最大字符数
     * @return exact text / 精确文本
     */
    private static String longText(JsonNode node, String field, int max) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().length() > max)
            throw new IllegalArgumentException("bounded text required");
        return value.textValue();
    }
}
