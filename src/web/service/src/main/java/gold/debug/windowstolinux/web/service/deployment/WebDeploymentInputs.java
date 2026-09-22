package gold.debug.windowstolinux.web.service.deployment;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.shared.config.input.DeploymentConfigurationParser;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.standard.analyze.component.*;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.standard.deploy.input.*;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.interaction.WebTaskInteractionService;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import tools.jackson.databind.JsonNode;

/**
 * Resolves the deterministic runtime inputs shared with desktop automatic deployment.
 * <p>解析与桌面自动部署共享的确定性运行输入。
 */
public final class WebDeploymentInputs {
    /**
     * KEYS.
     * <p>键集合。
     */
    private static final Set<String> KEYS = Set.of("applicationDeclaration", "executionMode", "exposure", "requestHex",
            "responseHex", "type", "primary", "secondary", "version", "port", "healthMode", "healthEndpoint",
            "expectedStatus", "timeout", "stability", "accessUrl", "jvmArguments", "arguments", "ports", "volumes",
            "containerEngine", "jvmTarget", "nodeBuild", "configuration", "secrets", "databaseMode", "databaseDetails",
            "dependencies");

    /**
     * Bound automatic runtime resolver collaborator for resolver.
     * <p>处理解析器的自动运行时解析器协作对象。
     */
    private final AutomaticRuntimeResolver resolver = new AutomaticRuntimeResolver();

    /**
     * Bound gold debug windowstolinux web service ai web ai service collaborator for the supplied web ai service.
     * <p>处理所提供的WebAI服务的golddebugwindowstolinuxWeb服务AIWebAI服务协作对象。
     */
    private final gold.debug.windowstolinux.web.service.ai.WebAiService ai;

    /**
     * Facts and dependencies scoped to the current operation.
     * <p>限定于当前操作的事实及依赖。
     */
    private final WebRequestContext context;
    /**
     * Binds the supplied dependencies and state for web deployment inputs.
     * <p>为Web部署输入集合绑定传入的依赖及状态。
     *
     * @param ai the supplied web ai service / 所提供的WebAI服务
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     */
    public WebDeploymentInputs(gold.debug.windowstolinux.web.service.ai.WebAiService ai, WebRequestContext context) {
        this.ai = ai;
        this.context = context;
    }

    /**
     * Checks overrides syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查覆盖项集合语法及边界。
     *
     * @param node node / 节点
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public static Map<String, String> overrides(JsonNode node) {
        if (node.isMissingNode())
            return Map.of();
        if (!node.isObject() || node.size() > 1024)
            throw new IllegalArgumentException("Invalid advanced inputs");
        Map<String, String> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            String key = entry.getKey(), leaf = key.substring(key.lastIndexOf('/') + 1);
            if (!key.matches("[a-zA-Z0-9-]+(?:/[a-zA-Z0-9]+)?") || !KEYS.contains(leaf) || !entry.getValue().isTextual()
                    || entry.getValue().asText()
                            .length() > (entry.getKey().equals("applicationDeclaration") ? 65536 : 4096)
                    || entry.getValue().asText().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid advanced input");
            if (!entry.getValue().asText().isBlank())
                result.put(key, entry.getValue().asText());
        });
        return Map.copyOf(result);
    }

    /**
     * Combines deterministic component facts and explicit overrides, then asks only for unresolved reviewed runtime fields.
     * <p>组合确定性组件事实及显式覆盖项，随后仅请求未解决的已审阅运行字段。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param overrides overrides / 覆盖项集合
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public Map<String, Map<String, String>> resolve(Path root, List<DiscoveredProjectComponent> components,
            Map<String, String> overrides, String host, TaskInteraction interaction) throws Exception {
        if (components.isEmpty() || components.size() > 64)
            throw new IllegalArgumentException("No bounded deployable components found");
        for (String key : overrides.keySet())
            if (key.contains("/") && components.stream().noneMatch(c -> key.startsWith(c.id() + "/")))
                throw new IllegalArgumentException("Unknown component input");
        var choices = new ArrayList<DeploymentInputField>();
        var selected = new LinkedHashMap<String, String>();
        for (var component : components) {
            String type = overrides.getOrDefault(component.id() + "/type", overrides.getOrDefault("type", ""));
            if (type.isEmpty() && component.types().size() == 1)
                type = component.types().getFirst().name();
            if (type.isEmpty())
                choices.add(AutomaticRuntimeResolver.field(component.id(), "type", "",
                        component.types().stream().map(Enum::name).toList()));
            else
                selected.put(component.id() + "/type", type);
        }
        selected.putAll(ai.inputs(context, choices, interaction));
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        choices.clear();
        for (var component : components) {
            var type = DeploymentProjectType.valueOf(selected.get(component.id() + "/type"));
            if (!component.types().contains(type))
                throw new IllegalArgumentException("Type is not supported by the source");
            var assessment = new DeploymentAnalysisCoordinator()
                    .analyzeForDatabaseReview(root.resolve(component.relativeRoot()), type);
            var facts = assessment.facts().orElseThrow(() -> new IllegalArgumentException("Source analysis failed"));
            if (!facts.conflicts().isEmpty() || facts.missingInformation().stream()
                    .anyMatch(message -> !message.key().equals("analysis.db.reviewRequired")))
                throw new IllegalArgumentException("Source facts are incomplete");
            var values = resolver.defaults(type, assessment.runtimeSuggestion().orElse(null),
                    root.resolve(component.relativeRoot()));
            overrides.forEach((key, value) -> {
                if (!key.contains("/"))
                    values.put(key, value);
            });
            overrides.forEach((key, value) -> {
                if (key.startsWith(component.id() + "/"))
                    values.put(key.split("/", 2)[1], value);
            });
            values.put("type", type.name());
            result.put(component.id(), values);
            choices.addAll(resolver.missing(component.id(), type, values));
            if (components.size() > 1 && !values.containsKey("dependencies"))
                choices.add(AutomaticRuntimeResolver.field(component.id(), "dependencies", "", List.of()));
        }
        ai.inputs(context, choices, interaction)
                .forEach((key, value) -> result.get(key.split("/", 2)[0]).put(key.split("/", 2)[1], value));
        for (var entry : result.entrySet()) {
            var values = entry.getValue();
            for (int attempt = 0;; attempt++) {
                try {
                    resolver.runtime(DeploymentProjectType.valueOf(values.get("type")), values);
                    resolver.access(values, host);
                    DeploymentConfigurationParser.parse(values.getOrDefault("configuration", ""));
                    DeploymentConfigurationParser.secrets(values.getOrDefault("secrets", ""));
                    DeploymentRuntimeParser.databaseBindings(
                            DatabaseReviewMode.valueOf(values.getOrDefault("databaseMode", "NONE")),
                            values.getOrDefault("databaseDetails", ""));
                    break;
                } catch (IllegalArgumentException invalid) {
                    if (attempt >= 4)
                        throw invalid;
                    interaction.progress("INPUT_CORRECTION_REQUIRED",
                            WebJsonCodec.object().put("component", entry.getKey()));
                    WebTaskInteractionService.inputs(interaction, resolver.corrections(entry.getKey(), values))
                            .forEach((key, value) -> values.put(key.split("/", 2)[1], value));
                    for (String key : List.of("healthEndpoint", "accessUrl", "jvmArguments", "arguments", "volumes",
                            "databaseDetails", "configuration", "secrets"))
                        if (values.getOrDefault(key, "").isBlank())
                            values.remove(key);
                }
            }
        }
        return result;
    }
}
