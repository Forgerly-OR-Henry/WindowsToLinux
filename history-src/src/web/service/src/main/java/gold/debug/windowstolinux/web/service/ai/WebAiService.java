package gold.debug.windowstolinux.web.service.ai;

import java.net.URI;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.*;
import gold.debug.windowstolinux.shared.ai.collaboration.role.*;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.contract.validation.WebRequestValidator;
import gold.debug.windowstolinux.web.service.interaction.WebTaskInteractionService;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import tools.jackson.databind.JsonNode;

/**
 * Verifies selected models and obtains ordered validated AI advice; AI does not authorize remote actions.
 * <p>验证所选模型并按顺序取得经校验 AI 建议；AI 不授权远端动作。
 */
public final class WebAiService {
    /**
     * Bound web resource repository collaborator for persistence boundary for the owned records.
     * <p>处理所属记录的持久化边界的Web资源仓库协作对象。
     */
    private final WebResourceRepository repository;

    /**
     * Bound web credential store collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的Web凭据存储协作对象。
     */
    private final WebCredentialStore secrets;

    /**
     * Client.
     * <p>客户端。
     */
    private final OpenAiCompatibleRoleClient client;
    /**
     * Initializes web ai service through its shared constructor contract.
     * <p>通过共享构造契约初始化WebAI服务。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     */
    public WebAiService(WebResourceRepository repository, WebCredentialStore secrets) {
        this(repository, secrets, new OpenAiCompatibleRoleClient());
    }

    /**
     * Binds the supplied dependencies and state for web ai service.
     * <p>为WebAI服务绑定传入的依赖及状态。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param client client / 客户端
     */
    public WebAiService(WebResourceRepository repository, WebCredentialStore secrets,
            OpenAiCompatibleRoleClient client) {
        this.repository = repository;
        this.secrets = secrets;
        this.client = client;
    }

    /**
     * Lists json node.
     * <p>列出JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public JsonNode list(WebRequestContext context) throws Exception {
        return WebJsonCodec.tree(ordered(context).stream().map(WebAiService::view).toList());
    }

    /**
     * Prepares a model configuration change that verifies the selected provider before persisting its metadata and encrypted credential reference.
     * <p>准备模型配置变更，在持久化元数据及加密凭据引用前验证所选提供者。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved prepared web operation / 构造或解析得到的已准备Web操作
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public PreparedWebOperation save(WebRequestContext context, String id, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "name", "endpoint", "model", "apiKey", "version");
        String name = WebRequestValidator.text(input, "name", 120),
                model = new ProviderEndpointPolicy().requireModel(WebRequestValidator.text(input, "model", 128));
        URI endpoint = new ProviderEndpointPolicy()
                .validateEndpoint(URI.create(WebRequestValidator.text(input, "endpoint", 2048)));
        var old = id == null ? Optional.<StoredResource>empty() : Optional.of(require(context, id));
        if (old.map(StoredResource::version).orElse(0L) != input.path("version").asLong(0))
            throw new IllegalStateException("Model changed");
        String supplied = input.path("apiKey").asText("");
        String secret;
        if (supplied.isEmpty()) {
            var saved = old.orElseThrow(() -> new IllegalArgumentException("API key required"));
            if (!WebJsonCodec.read(saved.document()).path("endpoint").asText().equals(endpoint.toString()))
                throw new IllegalArgumentException("Endpoint changes require a new key");
            secret = saved.attributes().get("secret_id").toString();
        } else {
            if (supplied.length() > 65536)
                throw new IllegalArgumentException("API key too long");
            secret = secrets.save(scope(context), "ai", supplied.toCharArray());
        }
        String profileId = id == null ? "ai-" + UUID.randomUUID() : id;
        var safe = WebJsonCodec.object().put("profileId", profileId).put("name", name)
                .put("endpoint", endpoint.toString()).put("model", model);
        return new PreparedWebOperation("AI_SAVE", safe, List.of(), List.of("ai:" + context.workspaceId()), true, null,
                null, null, interaction -> {
                    interaction.progress("AI_TESTING", WebJsonCodec.object().put("profileId", profileId));
                    var test = new ProjectAnalysisRoleContext("connection-test", "JAVA_MAVEN_SPRING_BOOT",
                            "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of());
                    var result = secrets.use(scope(context), secret, "ai", key -> client
                            .invoke(new AiRoleBinding(test.role(), profileId, endpoint, model), key, test));
                    interaction.checkCancelled();
                    if (result.evidence().status() != AiInvocationStatus.VALIDATED) {
                        interaction.completion(OperationCompletionState.FAILED);
                        return WebJsonCodec.object().put("status", "AI_TEST_FAILED");
                    }
                    var document = WebJsonCodec.object().put("endpoint", endpoint.toString()).put("model", model)
                            .put("verifiedAt", Instant.now().toString());
                    int priority = old.map(row -> ((Number) row.attributes().get("priority")).intValue())
                            .orElse(ordered(context).size());
                    int enabled = old.map(row -> ((Number) row.attributes().get("enabled")).intValue()).orElse(1);
                    return view(repository.save(scope(context), ResourceType.AI_PROFILE, profileId, name,
                            Map.of("priority", priority, "enabled", enabled, "secret_id", secret, "secret_version", 1),
                            WebJsonCodec.write(document), old.map(StoredResource::version).orElse(0L)));
                });
    }

    /**
     * Validates and persists the model's enabled flag using optimistic resource versioning.
     * <p>校验模型启用标记，并使用乐观资源版本控制持久化。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public JsonNode enabled(WebRequestContext context, String id, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "enabled", "version");
        if (!input.path("enabled").isBoolean())
            throw new IllegalArgumentException("Invalid enabled flag");
        var old = require(context, id);
        var values = new LinkedHashMap<>(old.attributes());
        values.put("enabled", input.path("enabled").asBoolean() ? 1 : 0);
        return view(repository.save(scope(context), ResourceType.AI_PROFILE, id, old.name(), values, old.document(),
                input.path("version").asLong(-1)));
    }

    /**
     * Reorders json node.
     * <p>重新排序JSON节点。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public JsonNode reorder(WebRequestContext context, JsonNode input) throws Exception {
        WebRequestValidator.fields(input, "ids");
        if (!input.path("ids").isArray())
            throw new IllegalArgumentException("Invalid model order");
        var ids = new ArrayList<String>();
        for (var id : input.path("ids")) {
            if (!id.isTextual())
                throw new IllegalArgumentException("Invalid model id");
            ids.add(id.asText());
        }
        repository.reorderAi(scope(context), ids);
        return list(context);
    }

    /**
     * Deletes web ai.
     * <p>删除WebAI。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public void delete(WebRequestContext context, String id, long version) throws Exception {
        repository.delete(scope(context), ResourceType.AI_PROFILE, id, version);
    }

    /**
     * Invokes optional.
     * <p>调用可选。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param role role / 角色
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public Optional<AiRoleInvocationResult> invoke(WebRequestContext context, AiRoleContext role,
            TaskInteraction interaction) throws Exception {
        var snapshot = ordered(context).stream()
                .filter(row -> ((Number) row.attributes().get("enabled")).intValue() == 1).toList();
        for (var row : snapshot) {
            interaction.checkCancelled();
            var document = WebJsonCodec.read(row.document());
            AiRoleInvocationResult result = null;
            try {
                result = secrets.use(scope(context), row.attributes().get("secret_id").toString(), "ai",
                        key -> client.invoke(new AiRoleBinding(role.role(), row.id(),
                                URI.create(document.path("endpoint").asText()), document.path("model").asText()), key,
                                role));
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                interaction.progress("AI_PROVIDER_UNAVAILABLE", WebJsonCodec.object().put("profileId", row.id()));
            }
            interaction.checkCancelled();
            if (result != null) {
                interaction.progress("AI_ATTEMPT", WebJsonCodec.object().put("profileId", row.id()).put("status",
                        result.evidence().status().name()));
                if (result.evidence().status() == AiInvocationStatus.VALIDATED)
                    return Optional.of(result);
            }
        }
        return Optional.empty();
    }

    /**
     * Uses model advice only for evidenced input candidates and requests explicit task interaction for remaining fields.
     * <p>仅对有证据的输入候选使用模型建议，并为剩余字段请求显式任务交互。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    public Map<String, String> inputs(WebRequestContext context, List<DeploymentInputField> fields,
            TaskInteraction interaction) throws Exception {
        if (fields.isEmpty())
            return Map.of();
        var supplied = new LinkedHashMap<String, String>();
        var result = invoke(context, new DeploymentInputRoleContext(fields.stream().limit(64).toList(),
                "Select only a uniquely evidenced supplied candidate; leave unknown free text unresolved.", List.of()),
                interaction);
        result.flatMap(value -> value.evidence().output()).filter(value -> value.decision() == AiAdviceDecision.CLEAR)
                .ifPresent(advice -> {
                    for (String finding : advice.findings()) {
                        String[] pair = finding.split("=", 2);
                        if (pair.length == 2)
                            fields.stream()
                                    .filter(field -> field.id().equals(pair[0]) && field.choices().size() == 1
                                            && field.choices().contains(pair[1]))
                                    .findFirst().ifPresent(field -> supplied.put(field.id(), pair[1]));
                    }
                });
        supplied.putAll(WebTaskInteractionService.inputs(interaction,
                fields.stream().filter(field -> !supplied.containsKey(field.id())).toList()));
        return Map.copyOf(supplied);
    }

    /**
     * Returns scoped model profiles ordered by priority and then identifier.
     * <p>返回作用域内模型配置，先按优先级、再按标识排序。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return scoped model profiles ordered by priority and then identifier / 作用域内模型配置，先按优先级、再按标识排序
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private List<StoredResource> ordered(WebRequestContext context) throws Exception {
        return repository.list(scope(context), ResourceType.AI_PROFILE).stream()
                .sorted(Comparator
                        .comparingInt((StoredResource row) -> ((Number) row.attributes().get("priority")).intValue())
                        .thenComparing(StoredResource::id))
                .toList();
    }

    /**
     * Validates and returns stored resource.
     * <p>校验并返回已存储资源。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @return constructed or resolved stored resource / 构造或解析得到的已存储资源
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    private StoredResource require(WebRequestContext context, String id) throws Exception {
        return repository.find(scope(context), ResourceType.AI_PROFILE, id).orElseThrow(NoSuchElementException::new);
    }

    /**
     * Projects stored non-secret resource metadata into its Web response fields.
     * <p>将持久化的非秘密资源元数据投影为 Web 响应字段。
     *
     * @param row row / 数据行
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     */
    private static JsonNode view(StoredResource row) {
        var document = WebJsonCodec.read(row.document());
        return WebJsonCodec.object().put("id", row.id()).put("name", row.name()).put("version", row.version())
                .put("endpoint", document.path("endpoint").asText()).put("model", document.path("model").asText())
                .put("verifiedAt", document.path("verifiedAt").asText())
                .put("enabled", ((Number) row.attributes().get("enabled")).intValue() == 1)
                .put("priority", ((Number) row.attributes().get("priority")).intValue())
                .put("credentialConfigured", true);
    }

    /**
     * Resolves the ownership scope supplied by the trusted caller.
     * <p>解析可信调用方提供的归属作用域。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return the ownership scope supplied by the trusted caller / 可信调用方提供的归属作用域
     */
    private static ResourceScope scope(WebRequestContext context) {
        return new ResourceScope(context.workspaceId(), context.userId());
    }
}
